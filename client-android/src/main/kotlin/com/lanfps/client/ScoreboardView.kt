package com.lanfps.client

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.lanfps.shared.EntityType
import com.lanfps.shared.GameMode

/**
 * Hold-to-view scoreboard.
 *
 * Every number here comes straight out of the newest server snapshot, so what
 * two phones see is identical by construction — there is no client-side score
 * tracking to drift.
 */
@SuppressLint("ViewConstructor")
class ScoreboardView(
    context: Context,
    private val state: ClientGameState,
) : LinearLayout(context) {

    private val titleLabel: TextView
    private val subtitle: TextView
    private val rows: LinearLayout

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.argb(206, 8, 11, 16))
        val pad = UiKit.dp(context, 22f)
        setPadding(pad, pad, pad, pad)
        gravity = Gravity.CENTER_HORIZONTAL
        // Purely informational: the controls underneath keep working while held.
        isClickable = false

        val card = UiKit.panel(context).apply {
            layoutParams = UiKit.lp(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        titleLabel = UiKit.title(context, "SCOREBOARD", 20f)
        card.addView(titleLabel)
        subtitle = UiKit.mono(context, "", 11.5f, UiKit.TEXT_DIM)
        card.addView(subtitle)
        card.addView(UiKit.divider(context))
        card.addView(headerRow())

        val scroll = ScrollView(context).apply {
            layoutParams = UiKit.lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        rows = LinearLayout(context).apply { orientation = VERTICAL }
        scroll.addView(rows)
        card.addView(scroll)
        addView(card)
    }

    private fun headerRow(): LinearLayout = UiKit.row(context).apply {
        layoutParams = UiKit.lp(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            margins = intArrayOf(0, 0, 0, UiKit.dp(context, 6f)),
        )
        addView(
            UiKit.mono(context, "PLAYER", 11f, UiKit.TEXT_DIM).apply {
                layoutParams = UiKit.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        addView(cell("K"))
        addView(cell("D"))
        addView(cell("K/D"))
        addView(cell("HP"))
    }

    private fun cell(s: String, colour: Int = UiKit.TEXT_DIM): TextView =
        UiKit.mono(context, s, 11f, colour).apply {
            layoutParams = UiKit.lp(UiKit.dp(context, 52f), ViewGroup.LayoutParams.WRAP_CONTENT)
            gravity = Gravity.END
        }

    fun refresh() {
        val teamGame = state.mode == GameMode.TDM
        titleLabel.text = if (teamGame) "TEAM DEATHMATCH" else "DEATHMATCH"
        val t = state.matchTimeRemaining.toInt().coerceAtLeast(0)
        subtitle.text = buildString {
            append(state.serverName).append("  ·  ").append(state.arena.name)
            append("  ·  %d:%02d left".format(t / 60, t % 60))
            if (teamGame) {
                append("  ·  RED ").append(state.redScore)
                append("  BLUE ").append(state.blueScore)
            }
            append("  ·  ping ").append(state.pingMs).append(" ms")
        }

        rows.removeAllViews()
        val list = state.scoreboardRows()
        for (e in list) {
            val isMe = e.id == state.localPlayerId
            val colour = when {
                isMe -> UiKit.ACCENT
                teamGame -> UiKit.teamColor(e.teamEnum)
                e.type == EntityType.BOT -> UiKit.TEXT_DIM
                else -> UiKit.TEXT
            }
            val line = UiKit.row(context).apply {
                layoutParams = UiKit.lp(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    margins = intArrayOf(0, 0, 0, UiKit.dp(context, 4f)),
                )
                if (isMe) {
                    background = UiKit.rounded(0x22F2A33C, UiKit.dp(context, 4f))
                    setPadding(UiKit.dp(context, 4f), UiKit.dp(context, 2f), UiKit.dp(context, 4f), UiKit.dp(context, 2f))
                }
            }
            val tag = when {
                e.type == EntityType.BOT -> "\u2699 "
                else -> ""
            }
            line.addView(
                UiKit.mono(context, tag + e.name, 12.5f, colour).apply {
                    layoutParams = UiKit.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                },
            )
            line.addView(cell("${e.kills}", colour))
            line.addView(cell("${e.deaths}", UiKit.TEXT_DIM))
            val kd = if (e.deaths == 0) e.kills.toFloat() else e.kills.toFloat() / e.deaths
            line.addView(cell("%.2f".format(kd), UiKit.TEXT_DIM))
            line.addView(
                cell(
                    if (e.alive) "${e.health}" else "--",
                    if (e.alive) UiKit.TEXT_DIM else UiKit.BAD,
                ),
            )
            rows.addView(line)
        }
    }
}
