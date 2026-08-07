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
import com.lanfps.shared.Team

/**
 * End-of-match results.
 *
 * The server keeps running and starts the next match by itself after
 * `POST_MATCH_SEC`, so this screen offers "stay on the server" as the default
 * action; leaving is the deliberate one.
 */
@SuppressLint("ViewConstructor")
class EndMatchView(
    context: Context,
    private val state: ClientGameState,
    private val onStay: () -> Unit,
    private val onLeave: () -> Unit,
) : LinearLayout(context) {

    private val headline: TextView
    private val summary: TextView
    private val rows: LinearLayout

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.argb(238, 8, 11, 16))
        val pad = UiKit.dp(context, 20f)
        setPadding(pad, pad, pad, pad)
        isClickable = true

        headline = UiKit.title(context, "MATCH OVER", 28f, UiKit.ACCENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = UiKit.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        addView(headline)

        summary = UiKit.mono(context, "", 12f, UiKit.TEXT_DIM).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = UiKit.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        addView(summary)
        addView(UiKit.divider(context))

        val scroll = ScrollView(context).apply {
            layoutParams = UiKit.lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        rows = LinearLayout(context).apply { orientation = VERTICAL }
        scroll.addView(rows)
        addView(scroll)

        val buttons = UiKit.row(context).apply {
            gravity = Gravity.CENTER
            layoutParams = UiKit.lp(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                margins = intArrayOf(0, UiKit.dp(context, 10f), 0, 0),
            )
        }
        buttons.addView(
            UiKit.button(context, "Stay for next match", primary = true) { onStay() },
        )
        buttons.addView(
            UiKit.button(context, "Leave server") { onLeave() }.apply {
                layoutParams = UiKit.lp(margins = intArrayOf(UiKit.dp(context, 12f), 0, 0, 0))
            },
        )
        addView(buttons)
    }

    fun refresh() {
        val list = state.scoreboardRows()
        val teamGame = state.mode == GameMode.TDM

        if (teamGame) {
            val winner = when {
                state.redScore > state.blueScore -> Team.RED
                state.blueScore > state.redScore -> Team.BLUE
                else -> Team.NONE
            }
            headline.text = when (winner) {
                Team.NONE -> "DRAW"
                else -> "${winner.name} TEAM WINS"
            }
            headline.setTextColor(
                if (winner == Team.NONE) UiKit.TEXT else UiKit.teamColor(winner),
            )
            summary.text = "RED ${state.redScore}   ·   BLUE ${state.blueScore}"
        } else {
            val best = list.firstOrNull()
            if (best == null) {
                headline.text = "MATCH OVER"
                summary.text = ""
            } else {
                val isMe = best.id == state.localPlayerId
                headline.text = if (isMe) "YOU WIN" else "${best.name.uppercase()} WINS"
                headline.setTextColor(if (isMe) UiKit.GOOD else UiKit.ACCENT)
                summary.text = "${best.kills} kills   ·   your score: " +
                    "${state.kills} kills / ${state.deaths} deaths"
            }
        }

        rows.removeAllViews()
        for ((index, e) in list.withIndex()) {
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
                    margins = intArrayOf(0, 0, 0, UiKit.dp(context, 5f)),
                )
            }
            line.addView(
                UiKit.mono(context, "%2d.".format(index + 1), 12.5f, UiKit.TEXT_DIM).apply {
                    layoutParams = UiKit.lp(UiKit.dp(context, 30f), ViewGroup.LayoutParams.WRAP_CONTENT)
                },
            )
            val tag = if (e.type == EntityType.BOT) "\u2699 " else ""
            line.addView(
                UiKit.mono(context, tag + e.name + (if (isMe) "  (you)" else ""), 13f, colour).apply {
                    layoutParams = UiKit.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                },
            )
            line.addView(
                UiKit.mono(context, "${e.kills} kills", 12.5f, colour).apply {
                    layoutParams = UiKit.lp(UiKit.dp(context, 78f), ViewGroup.LayoutParams.WRAP_CONTENT)
                    gravity = Gravity.END
                },
            )
            line.addView(
                UiKit.mono(context, "${e.deaths} deaths", 12.5f, UiKit.TEXT_DIM).apply {
                    layoutParams = UiKit.lp(UiKit.dp(context, 90f), ViewGroup.LayoutParams.WRAP_CONTENT)
                    gravity = Gravity.END
                },
            )
            rows.addView(line)
        }
    }
}
