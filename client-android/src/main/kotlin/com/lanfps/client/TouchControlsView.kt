package com.lanfps.client

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import com.lanfps.shared.Weapons
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * The touch layer: floating movement stick on the left, look-drag on the right,
 * plus fire / jump / crouch / scoreboard / menu buttons.
 *
 * Multi-touch is handled by assigning every pointer id a *role* the moment it
 * goes down and keeping that role until it lifts. Without that, sliding your
 * aiming thumb over the fire button (or vice versa) would steal the other
 * control — the single most common way home-made mobile FPS controls break.
 *
 * The movement stick floats: its centre snaps to wherever your left thumb lands,
 * so you never have to look down to find it.
 */
@SuppressLint("ClickableViewAccessibility")
class TouchControlsView(
    context: Context,
    private val input: InputController,
) : View(context) {

    /** Tapped the pause / menu button. */
    var onMenu: (() -> Unit)? = null

    /** Scoreboard button pressed (true) / released (false). */
    var onScoreboard: ((Boolean) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 235, 240, 246)
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    // ---- pointer roles -----------------------------------------------------
    private enum class Role { NONE, STICK, LOOK, FIRE, JUMP, CROUCH, SCORE, MENU, WEAPON, RELOAD }

    private val roles = HashMap<Int, Role>()

    // stick
    private var stickPointer = -1
    private var stickBaseX = 0f
    private var stickBaseY = 0f
    private var stickX = 0f
    private var stickY = 0f
    private var stickActive = false

    // look
    private var lookPointer = -1
    private var lookLastX = 0f
    private var lookLastY = 0f

    // buttons
    private var firePressed = false
    private var jumpPressed = false
    private var crouchLatched = false
    private var scorePressed = false

    // geometry (recomputed on layout)
    private var fireCx = 0f; private var fireCy = 0f; private var fireR = 0f
    private var jumpCx = 0f; private var jumpCy = 0f; private var jumpR = 0f
    private var crouchCx = 0f; private var crouchCy = 0f; private var crouchR = 0f
    private var weaponCx = 0f; private var weaponCy = 0f; private var weaponR = 0f
    private var reloadCx = 0f; private var reloadCy = 0f; private var reloadR = 0f
    private var scoreCx = 0f; private var scoreCy = 0f; private var scoreR = 0f
    private var menuCx = 0f; private var menuCy = 0f; private var menuR = 0f

    private val stickRadius = dp(86f)
    private val knobRadius = dp(34f)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val wf = w.toFloat()
        val hf = h.toFloat()

        fireR = dp(58f)
        fireCx = wf - dp(86f)
        fireCy = hf - dp(84f)

        jumpR = dp(40f)
        jumpCx = wf - dp(80f)
        jumpCy = hf - dp(206f)

        crouchR = dp(37f)
        crouchCx = wf - dp(196f)
        crouchCy = hf - dp(66f)

        // P2-1: weapon cycler, stacked above CROUCH (same thumb column).
        weaponR = dp(32f)
        weaponCx = wf - dp(196f)
        weaponCy = hf - dp(160f)

        // P2-2: reload, above JUMP and clear of the fire button.
        reloadR = dp(30f)
        reloadCx = wf - dp(86f)
        reloadCy = hf - dp(320f)

        scoreR = dp(26f)
        scoreCx = wf - dp(44f)
        scoreCy = dp(44f)

        menuR = dp(26f)
        menuCx = dp(44f)
        menuCy = dp(44f)

        stickBaseX = dp(150f)
        stickBaseY = hf - dp(130f)
        stickX = stickBaseX
        stickY = stickBaseY

        textPaint.textSize = dp(13f)
    }

    // -------------------------------------------------------------- touching

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = event.actionIndex
                assignRole(event.getPointerId(idx), event.getX(idx), event.getY(idx))
            }

            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val id = event.getPointerId(i)
                    when (roles[id]) {
                        Role.STICK -> updateStick(event.getX(i), event.getY(i))
                        Role.LOOK -> {
                            val x = event.getX(i)
                            val y = event.getY(i)
                            val dx = x - lookLastX
                            val dy = y - lookLastY
                            lookLastX = x
                            lookLastY = y
                            // Ignore the huge jump that a re-assigned pointer id can
                            // produce; a real thumb never crosses the screen in 16 ms.
                            if (abs(dx) < width * 0.5f && abs(dy) < height * 0.5f) {
                                input.addLook(dx / density, dy / density)
                            }
                        }
                        else -> Unit
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val idx = event.actionIndex
                releasePointer(event.getPointerId(idx))
            }

            MotionEvent.ACTION_CANCEL -> {
                for (id in roles.keys.toList()) releasePointer(id)
                input.releaseAll()
                stickActive = false
                firePressed = false
                jumpPressed = false
            }
        }
        invalidate()
        return true
    }

    private fun assignRole(id: Int, x: Float, y: Float) {
        val role = when {
            inside(x, y, menuCx, menuCy, menuR + dp(8f)) -> Role.MENU
            inside(x, y, scoreCx, scoreCy, scoreR + dp(8f)) -> Role.SCORE
            inside(x, y, fireCx, fireCy, fireR + dp(6f)) -> Role.FIRE
            inside(x, y, jumpCx, jumpCy, jumpR + dp(6f)) -> Role.JUMP
            inside(x, y, crouchCx, crouchCy, crouchR + dp(6f)) -> Role.CROUCH
            inside(x, y, weaponCx, weaponCy, weaponR + dp(6f)) -> Role.WEAPON
            inside(x, y, reloadCx, reloadCy, reloadR + dp(6f)) -> Role.RELOAD
            x < width * 0.46f -> Role.STICK
            else -> Role.LOOK
        }
        roles[id] = role

        when (role) {
            Role.STICK -> {
                if (stickPointer == -1) {
                    stickPointer = id
                    // Floating origin, kept fully on screen.
                    stickBaseX = x.coerceIn(stickRadius, width * 0.46f - dp(4f))
                    stickBaseY = y.coerceIn(stickRadius, height - stickRadius)
                    stickX = x
                    stickY = y
                    stickActive = true
                    updateStick(x, y)
                } else {
                    roles[id] = Role.NONE
                }
            }

            Role.LOOK -> {
                if (lookPointer == -1) {
                    lookPointer = id
                    lookLastX = x
                    lookLastY = y
                } else {
                    roles[id] = Role.NONE
                }
            }

            Role.FIRE -> {
                firePressed = true
                input.firing = true
                performHaptic()
            }

            Role.JUMP -> {
                jumpPressed = true
                input.jumpQueued = true
                performHaptic()
            }

            Role.CROUCH -> {
                crouchLatched = !crouchLatched
                input.crouching = crouchLatched
                performHaptic()
            }

            Role.SCORE -> {
                scorePressed = true
                onScoreboard?.invoke(true)
            }

            Role.WEAPON -> {
                // P2-1: one tap cycles rifle -> shotgun -> sniper. The InputController
                // latches the choice; the server adopts it next input packet.
                input.cycleWeapon()
                performHaptic()
            }

            Role.RELOAD -> {
                // P2-2: queued for exactly one network tick (like jump).
                input.reloadQueued = true
                performHaptic()
            }

            Role.MENU -> onMenu?.invoke()

            Role.NONE -> Unit
        }
    }

    private fun releasePointer(id: Int) {
        when (roles.remove(id)) {
            Role.STICK -> {
                if (stickPointer == id) {
                    stickPointer = -1
                    stickActive = false
                    input.moveForward = 0f
                    input.moveRight = 0f
                    stickX = stickBaseX
                    stickY = stickBaseY
                }
            }

            Role.LOOK -> if (lookPointer == id) lookPointer = -1

            Role.FIRE -> {
                firePressed = false
                input.firing = false
            }

            Role.JUMP -> jumpPressed = false

            Role.SCORE -> {
                scorePressed = false
                onScoreboard?.invoke(false)
            }

            else -> Unit
        }
    }

    private fun updateStick(x: Float, y: Float) {
        var dx = x - stickBaseX
        var dy = y - stickBaseY
        val len = hypot(dx, dy)
        if (len > stickRadius) {
            val k = stickRadius / len
            dx *= k
            dy *= k
        }
        stickX = stickBaseX + dx
        stickY = stickBaseY + dy

        // Small dead zone so resting a thumb does not creep the player forward.
        val dead = dp(9f)
        if (len < dead) {
            input.moveForward = 0f
            input.moveRight = 0f
            return
        }
        val usable = stickRadius - dead
        val scale = min((len - dead) / usable, 1f) / (len.coerceAtLeast(0.001f))
        input.moveRight = (dx * scale).coerceIn(-1f, 1f)
        // Screen +Y is down, world forward is stick-up.
        input.moveForward = (-dy * scale).coerceIn(-1f, 1f)
    }

    private fun inside(x: Float, y: Float, cx: Float, cy: Float, r: Float): Boolean =
        hypot(x - cx, y - cy) <= r

    private fun performHaptic() {
        try {
            performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
        } catch (_: Exception) {
        }
    }

    /** Called when the game is left, so nothing stays stuck down. */
    fun resetAll() {
        roles.clear()
        stickPointer = -1
        lookPointer = -1
        stickActive = false
        firePressed = false
        jumpPressed = false
        crouchLatched = false
        scorePressed = false
        input.releaseAll()
        invalidate()
    }

    // --------------------------------------------------------------- drawing

    override fun onDraw(canvas: Canvas) {
        // Movement stick.
        val baseAlpha = if (stickActive) 70 else 42
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2f)
        paint.color = Color.argb(baseAlpha + 40, 226, 232, 240)
        canvas.drawCircle(stickBaseX, stickBaseY, stickRadius, paint)

        paint.style = Paint.Style.FILL
        paint.color = Color.argb(baseAlpha, 226, 232, 240)
        canvas.drawCircle(stickX, stickY, knobRadius, paint)
        paint.style = Paint.Style.STROKE
        paint.color = Color.argb(150, 242, 163, 60)
        canvas.drawCircle(stickX, stickY, knobRadius, paint)

        // Fire.
        drawButton(canvas, fireCx, fireCy, fireR, firePressed, "FIRE", 242, 163, 60)
        // Jump.
        drawButton(canvas, jumpCx, jumpCy, jumpR, jumpPressed, "JUMP", 226, 232, 240)
        // Crouch.
        drawButton(canvas, crouchCx, crouchCy, crouchR, crouchLatched, "CROUCH", 226, 232, 240)
        // Weapon cycler shows the CURRENT pick (P2-1); reload is a tap (P2-2).
        drawButton(
            canvas, weaponCx, weaponCy, weaponR, false,
            Weapons.byId(input.currentWeapon).shortName, 242, 163, 60,
        )
        drawButton(canvas, reloadCx, reloadCy, reloadR, false, "RLD", 226, 232, 240)
        // Scoreboard / menu.
        drawButton(canvas, scoreCx, scoreCy, scoreR, scorePressed, "TAB", 226, 232, 240)
        drawButton(canvas, menuCx, menuCy, menuR, false, "II", 226, 232, 240)
    }

    private fun drawButton(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        r: Float,
        pressed: Boolean,
        label: String,
        cr: Int,
        cg: Int,
        cb: Int,
    ) {
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(if (pressed) 110 else 46, cr, cg, cb)
        canvas.drawCircle(cx, cy, r, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2f)
        paint.color = Color.argb(if (pressed) 235 else 140, cr, cg, cb)
        canvas.drawCircle(cx, cy, r, paint)

        textPaint.textSize = if (r > dp(50f)) dp(15f) else dp(11.5f)
        textPaint.color = Color.argb(if (pressed) 255 else 200, 240, 245, 250)
        canvas.drawText(label, cx, cy + textPaint.textSize * 0.36f, textPaint)
    }
}
