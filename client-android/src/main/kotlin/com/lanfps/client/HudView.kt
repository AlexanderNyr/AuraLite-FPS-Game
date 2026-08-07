package com.lanfps.client

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import com.lanfps.shared.EntityState
import com.lanfps.shared.GameConstants
import com.lanfps.shared.GameMode
import com.lanfps.shared.MatchState
import com.lanfps.shared.Team
import kotlin.math.max
import kotlin.math.min

/**
 * The 2D overlay: crosshair, health, score, timer, ping, kill feed, name plates
 * and the damage / hit / death feedback.
 *
 * Drawn with plain Canvas on top of the GL surface. That is far cheaper than
 * rendering text in OpenGL (no font atlas, no extra draw calls, crisp at any
 * density) and it keeps the renderer focused on geometry.
 *
 * Name plates are projected with the *exact* view-projection matrix the GL
 * thread used for the current frame, published through [ClientGameState], so a
 * plate can never drift away from the body it belongs to.
 */
class HudView(context: Context, private val state: ClientGameState) : View(context) {

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val rect = RectF()

    private val vp = FloatArray(16)
    private val hudEntities = ArrayList<EntityState>(24)
    private val hudPool = ArrayList<EntityState>(24)
    private val feed = ArrayList<KillFeedEntry>(8)

    /** Toggled by the TAB button; the scoreboard View handles the big table. */
    var showDebug: Boolean = false

    init {
        // Purely decorative: never intercept touches, they belong to the controls.
        isClickable = false
        isFocusable = false
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val now = System.currentTimeMillis()
        val playing = state.phase == Phase.PLAYING || state.phase == Phase.ENDED

        if (!playing) return

        drawNamePlates(canvas, now)
        drawDamageFlash(canvas, w, h, now)
        drawCrosshair(canvas, w, h, now)
        drawHealthAndAmmo(canvas, w, h)
        drawTopBar(canvas, w, h)
        drawKillFeed(canvas, w, now)
        drawDeathOverlay(canvas, w, h)
        if (showDebug) drawDebug(canvas)
    }

    // ---------------------------------------------------------- name plates

    private fun drawNamePlates(canvas: Canvas, now: Long) {
        if (!state.copyViewProj(vp)) return
        if (!state.snapshots.sampleInto(hudEntities, hudPool, now)) return

        val w = width.toFloat()
        val h = height.toFloat()
        val localId = state.localPlayerId
        val teamGame = state.mode == GameMode.TDM

        text.textAlign = Paint.Align.CENTER
        text.textSize = dp(11.5f)

        for (i in hudEntities.indices) {
            val e = hudEntities[i]
            if (e.id == localId || !e.alive) continue

            // Top of the head, plus a little headroom.
            val wx = e.x
            val wy = e.y + (if (e.crouching) {
                GameConstants.PLAYER_CROUCH_HEIGHT
            } else {
                GameConstants.PLAYER_HEIGHT
            }) + 0.32f
            val wz = e.z

            val cw = vp[3] * wx + vp[7] * wy + vp[11] * wz + vp[15]
            if (cw <= 0.05f) continue
            val cx = vp[0] * wx + vp[4] * wy + vp[8] * wz + vp[12]
            val cy = vp[1] * wx + vp[5] * wy + vp[9] * wz + vp[13]

            val sx = (cx / cw * 0.5f + 0.5f) * w
            val sy = (1f - (cy / cw * 0.5f + 0.5f)) * h
            if (sx < -dp(60f) || sx > w + dp(60f) || sy < -dp(40f) || sy > h + dp(40f)) continue

            // Distance fade: nearby plates solid, far ones ghosted.
            val dx = e.x - state.eyeX
            val dy = e.y - state.eyeY
            val dz = e.z - state.eyeZ
            val dist = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
            if (dist > 70f) continue
            val alpha = (255 * (1f - (dist / 70f) * 0.65f)).toInt().coerceIn(60, 255)

            val friendly = teamGame && e.teamEnum == state.localTeam
            val colour = when {
                friendly -> Color.argb(alpha, 110, 220, 150)
                teamGame && e.teamEnum == Team.RED -> Color.argb(alpha, 236, 110, 96)
                teamGame && e.teamEnum == Team.BLUE -> Color.argb(alpha, 110, 165, 245)
                else -> Color.argb(alpha, 240, 176, 96)
            }

            // Health pip bar.
            val barW = dp(38f)
            val barH = dp(3.5f)
            rect.set(sx - barW / 2f, sy, sx + barW / 2f, sy + barH)
            paint.style = Paint.Style.FILL
            paint.color = Color.argb((alpha * 0.45f).toInt(), 8, 12, 16)
            canvas.drawRect(rect, paint)
            val frac = (e.health / GameConstants.MAX_HEALTH.toFloat()).coerceIn(0f, 1f)
            rect.set(sx - barW / 2f, sy, sx - barW / 2f + barW * frac, sy + barH)
            paint.color = colour
            canvas.drawRect(rect, paint)

            text.color = colour
            canvas.drawText(e.name, sx, sy - dp(4f), text)
        }
    }

    // ------------------------------------------------------------- crosshair

    private fun drawCrosshair(canvas: Canvas, w: Float, h: Float, now: Long) {
        if (!state.alive) return
        val cx = w / 2f
        val cy = h / 2f

        // The gap grows with movement speed and recoil: honest feedback that you
        // are less accurate on the move.
        val moveT = min(state.localSpeed / GameConstants.MOVE_SPEED, 1f)
        val gap = dp(5f) + dp(7f) * moveT + dp(2.2f) * state.recoilPitch
        val len = dp(7f)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1.8f)
        paint.color = Color.argb(40, 0, 0, 0)
        crossLines(canvas, cx, cy, gap + dp(0.8f), len)
        paint.color = Color.argb(225, 240, 246, 252)
        crossLines(canvas, cx, cy, gap, len)

        paint.style = Paint.Style.FILL
        canvas.drawCircle(cx, cy, dp(1.1f), paint)

        // Hit marker.
        if (now < state.hitMarkerUntilMs) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(2.4f)
            paint.color = Color.argb(240, 255, 214, 92)
            val a = dp(7f)
            val b = dp(15f)
            canvas.drawLine(cx - b, cy - b, cx - a, cy - a, paint)
            canvas.drawLine(cx + a, cy - a, cx + b, cy - b, paint)
            canvas.drawLine(cx - b, cy + b, cx - a, cy + a, paint)
            canvas.drawLine(cx + a, cy + a, cx + b, cy + b, paint)
        }
    }

    private fun crossLines(canvas: Canvas, cx: Float, cy: Float, gap: Float, len: Float) {
        canvas.drawLine(cx - gap - len, cy, cx - gap, cy, paint)
        canvas.drawLine(cx + gap, cy, cx + gap + len, cy, paint)
        canvas.drawLine(cx, cy - gap - len, cx, cy - gap, paint)
        canvas.drawLine(cx, cy + gap, cx, cy + gap + len, paint)
    }

    // ------------------------------------------------------------- feedback

    private fun drawDamageFlash(canvas: Canvas, w: Float, h: Float, now: Long) {
        if (now >= state.damageFlashUntilMs) return
        val remain = (state.damageFlashUntilMs - now) / 260f
        val a = (110 * remain).toInt().coerceIn(0, 140)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(46f)
        paint.color = Color.argb(a, 200, 40, 40)
        canvas.drawRect(-dp(20f), -dp(20f), w + dp(20f), h + dp(20f), paint)
    }

    private fun drawDeathOverlay(canvas: Canvas, w: Float, h: Float) {
        if (state.alive || state.phase != Phase.PLAYING) return
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(120, 24, 6, 6)
        canvas.drawRect(0f, 0f, w, h, paint)

        text.textAlign = Paint.Align.CENTER
        text.textSize = dp(30f)
        text.color = Color.argb(240, 240, 120, 108)
        canvas.drawText("YOU DIED", w / 2f, h / 2f - dp(14f), text)

        text.textSize = dp(15f)
        text.color = Color.argb(210, 226, 232, 240)
        val secs = max(state.respawnInSec, 0f)
        canvas.drawText("respawning in %.1f s".format(secs), w / 2f, h / 2f + dp(18f), text)
    }

    // ------------------------------------------------------------ status HUD

    private fun drawHealthAndAmmo(canvas: Canvas, w: Float, h: Float) {
        val left = dp(26f)
        val bottom = h - dp(26f)

        // Health bar.
        val barW = dp(190f)
        val barH = dp(16f)
        rect.set(left, bottom - barH, left + barW, bottom)
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(150, 10, 14, 20)
        canvas.drawRoundRect(rect, dp(3f), dp(3f), paint)

        val frac = (state.health / GameConstants.MAX_HEALTH.toFloat()).coerceIn(0f, 1f)
        val hc = when {
            frac > 0.6f -> Color.argb(235, 108, 208, 132)
            frac > 0.3f -> Color.argb(235, 232, 190, 84)
            else -> Color.argb(235, 224, 88, 76)
        }
        rect.set(left, bottom - barH, left + barW * frac, bottom)
        paint.color = hc
        canvas.drawRoundRect(rect, dp(3f), dp(3f), paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1.4f)
        paint.color = Color.argb(120, 226, 232, 240)
        rect.set(left, bottom - barH, left + barW, bottom)
        canvas.drawRoundRect(rect, dp(3f), dp(3f), paint)

        text.textAlign = Paint.Align.LEFT
        text.textSize = dp(20f)
        text.color = hc
        canvas.drawText("${max(state.health, 0)}", left, bottom - barH - dp(8f), text)

        text.textSize = dp(11f)
        text.color = Color.argb(170, 139, 148, 158)
        canvas.drawText("HP", left + dp(34f), bottom - barH - dp(8f), text)

        // Ammo (infinite in this build) and nickname.
        text.textAlign = Paint.Align.RIGHT
        text.textSize = dp(22f)
        text.color = Color.argb(230, 240, 246, 252)
        val ammo = if (GameConstants.WEAPON_INFINITE_AMMO) "\u221E" else "30"
        canvas.drawText(ammo, w - dp(26f), bottom - dp(74f), text)
        text.textSize = dp(11f)
        text.color = Color.argb(170, 139, 148, 158)
        canvas.drawText("RIFLE", w - dp(26f), bottom - dp(58f), text)

        text.textAlign = Paint.Align.LEFT
        text.textSize = dp(12f)
        text.color = when (state.localTeam) {
            Team.RED -> Color.argb(220, 236, 110, 96)
            Team.BLUE -> Color.argb(220, 110, 165, 245)
            else -> Color.argb(200, 226, 232, 240)
        }
        canvas.drawText(state.nickname, left, bottom + dp(14f), text)
    }

    private fun drawTopBar(canvas: Canvas, w: Float, h: Float) {
        val cx = w / 2f
        val top = dp(24f)

        // Timer.
        val t = max(state.matchTimeRemaining, 0f).toInt()
        val timer = "%d:%02d".format(t / 60, t % 60)
        text.textAlign = Paint.Align.CENTER
        text.textSize = dp(22f)
        text.color = if (t <= 30) {
            Color.argb(240, 236, 130, 116)
        } else {
            Color.argb(235, 240, 246, 252)
        }
        canvas.drawText(timer, cx, top + dp(18f), text)

        text.textSize = dp(10.5f)
        text.color = Color.argb(160, 139, 148, 158)
        val phase = when (state.matchState) {
            MatchState.WARMUP -> "WARMUP"
            MatchState.ACTIVE -> state.mode.name
            MatchState.ENDED -> "MATCH OVER"
            else -> ""
        }
        canvas.drawText(phase, cx, top + dp(32f), text)

        // Scores.
        text.textSize = dp(19f)
        if (state.mode == GameMode.TDM) {
            text.textAlign = Paint.Align.RIGHT
            text.color = Color.argb(235, 236, 110, 96)
            canvas.drawText("${state.redScore}", cx - dp(52f), top + dp(18f), text)
            text.textAlign = Paint.Align.LEFT
            text.color = Color.argb(235, 110, 165, 245)
            canvas.drawText("${state.blueScore}", cx + dp(52f), top + dp(18f), text)
        } else {
            text.textAlign = Paint.Align.RIGHT
            text.color = Color.argb(235, 240, 246, 252)
            canvas.drawText("${state.kills}", cx - dp(52f), top + dp(18f), text)
            text.textSize = dp(10.5f)
            text.color = Color.argb(160, 139, 148, 158)
            canvas.drawText("KILLS", cx - dp(52f), top + dp(32f), text)

            text.textAlign = Paint.Align.LEFT
            text.textSize = dp(19f)
            text.color = Color.argb(180, 200, 208, 216)
            canvas.drawText("${state.deaths}", cx + dp(52f), top + dp(18f), text)
            text.textSize = dp(10.5f)
            text.color = Color.argb(160, 139, 148, 158)
            canvas.drawText("DEATHS", cx + dp(52f), top + dp(32f), text)
        }

        // Ping, colour-coded. On a healthy LAN this stays in single digits.
        val ping = state.pingMs
        text.textAlign = Paint.Align.RIGHT
        text.textSize = dp(12f)
        text.color = when {
            ping <= 30 -> Color.argb(220, 108, 208, 132)
            ping <= 90 -> Color.argb(220, 232, 190, 84)
            else -> Color.argb(220, 224, 88, 76)
        }
        canvas.drawText("${ping} ms", w - dp(84f), dp(30f), text)
        text.textSize = dp(9.5f)
        text.color = Color.argb(140, 139, 148, 158)
        canvas.drawText("%.0f snap/s".format(state.snapshotsPerSec), w - dp(84f), dp(43f), text)

        if (state.arenaMismatch) {
            text.textAlign = Paint.Align.CENTER
            text.textSize = dp(11f)
            text.color = Color.argb(230, 236, 160, 70)
            canvas.drawText("! map mismatch with server !", cx, h - dp(12f), text)
        }
    }

    private fun drawKillFeed(canvas: Canvas, w: Float, now: Long) {
        state.killFeedSnapshot(now, KILL_FEED_TTL_MS, feed)
        if (feed.isEmpty()) return

        text.textAlign = Paint.Align.RIGHT
        text.textSize = dp(12f)
        var y = dp(66f)
        for (i in feed.indices) {
            val e = feed[i]
            val age = (now - e.bornMs).toFloat() / KILL_FEED_TTL_MS
            val a = ((1f - age) * 3f).coerceIn(0f, 1f)
            val alpha = (235 * a).toInt()
            if (alpha <= 4) {
                y += dp(17f)
                continue
            }
            val line = "${e.killer}  \u2620  ${e.victim}"
            text.color = when {
                e.killerIsLocal -> Color.argb(alpha, 255, 206, 96)
                e.victimIsLocal -> Color.argb(alpha, 236, 110, 96)
                else -> Color.argb((alpha * 0.8f).toInt(), 200, 208, 216)
            }
            canvas.drawText(line, w - dp(26f), y, text)
            y += dp(17f)
        }
    }

    private fun drawDebug(canvas: Canvas) {
        val pred = state.prediction
        val lines = listOf(
            "fps %.0f  snap/s %.1f  ping %d ms".format(state.fps, state.snapshotsPerSec, state.pingMs),
            "pos %.2f %.2f %.2f  yaw %.1f".format(state.eyeX, state.eyeY, state.eyeZ, state.viewYaw),
            "pkt in %d / out %d  bad %d".format(state.packetsIn, state.packetsOut, state.malformedIn),
            "pending %d  corrections %d  snaps %d  err %.3f m".format(
                pred?.pendingCount ?: 0,
                pred?.corrections ?: 0,
                pred?.hardSnaps ?: 0,
                pred?.lastErrorMeters ?: 0f,
            ),
            "acked seq %d  out-of-order %d".format(
                pred?.lastAckedSeq ?: -1,
                state.snapshots.outOfOrderCount,
            ),
        )
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(150, 6, 9, 13)
        canvas.drawRect(dp(80f), dp(20f), dp(80f) + dp(250f), dp(20f) + dp(16f) * lines.size + dp(8f), paint)

        text.textAlign = Paint.Align.LEFT
        text.textSize = dp(10.5f)
        text.color = Color.argb(220, 150, 220, 170)
        var y = dp(34f)
        for (l in lines) {
            canvas.drawText(l, dp(86f), y, text)
            y += dp(16f)
        }
    }

    companion object {
        private const val KILL_FEED_TTL_MS = 5000L
    }
}
