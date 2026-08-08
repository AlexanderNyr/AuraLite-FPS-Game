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
import com.lanfps.shared.MatchState

/**
 * Shown between connecting and spawning: who is on the server, what is being
 * played, and one big button to drop in.
 *
 * The server has already spawned us by the time this appears — the client keeps
 * sending zeroed input while the lobby is up, which both keeps the session alive
 * and means the player is not moved around by a stale stick reading when they
 * tap "Enter match".
 */
@SuppressLint("ViewConstructor")
class LobbyView(
    context: Context,
    private val state: ClientGameState,
    private val onEnterMatch: () -> Unit,
    private val onLeave: () -> Unit,
    private val onVote: (GameMode) -> Unit,
) : LinearLayout(context) {

    private val header: TextView
    private val subHeader: TextView
    private val playerList: LinearLayout
    private val hint: TextView
    private val voteDmButton: android.widget.Button
    private val voteTdmButton: android.widget.Button

    init {
        orientation = HORIZONTAL
        setBackgroundColor(Color.argb(232, 8, 11, 16))
        val pad = UiKit.dp(context, 18f)
        setPadding(pad, pad, pad, pad)
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true

        val left = UiKit.panel(context).apply {
            layoutParams = UiKit.lp(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        }
        header = UiKit.title(context, "CONNECTED", 24f, UiKit.GOOD)
        left.addView(header)
        subHeader = UiKit.mono(context, "", 11.5f, UiKit.TEXT_DIM)
        left.addView(subHeader)
        left.addView(UiKit.divider(context))

        hint = UiKit.label(
            context,
            "Controls:  left thumb = move  ·  right thumb = look  ·  FIRE / JUMP / CROUCH " +
                "on the right  ·  TAB (top right) = scoreboard  ·  II (top left) = pause menu.",
            11.5f,
        )
        left.addView(hint)

        left.addView(UiKit.spacer(context, 10f))
        // P3-4: lobby vote for the ruleset of the NEXT match. A strict majority
        // of the humans flips the server's configured mode for one match; with
        // no majority the operator's config wins. Tally arrives in LOBBY_STATE.
        left.addView(UiKit.label(context, "VOTE THE NEXT MODE (majority of players wins)", 10.5f))
        val voteRow = UiKit.row(context)
        voteDmButton = UiKit.button(context, "DM") { onVote(GameMode.DM) }.apply {
            layoutParams = UiKit.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        voteTdmButton = UiKit.button(context, "TDM") { onVote(GameMode.TDM) }.apply {
            layoutParams = UiKit.lp(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
                margins = intArrayOf(UiKit.dp(context, 8f), 0, 0, 0),
            )
        }
        voteRow.addView(voteDmButton)
        voteRow.addView(voteTdmButton)
        left.addView(voteRow)

        left.addView(UiKit.spacer(context, 14f))
        left.addView(
            UiKit.button(context, "Enter match", primary = true) { onEnterMatch() }.apply {
                layoutParams = UiKit.lp(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            },
        )
        left.addView(
            UiKit.button(context, "Disconnect") { onLeave() }.apply {
                layoutParams = UiKit.lp(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    margins = intArrayOf(0, UiKit.dp(context, 8f), 0, 0),
                )
            },
        )
        addView(left)

        val right = UiKit.panel(context).apply {
            layoutParams = UiKit.lp(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1.1f,
                margins = intArrayOf(UiKit.dp(context, 14f), 0, 0, 0),
            )
        }
        right.addView(UiKit.title(context, "PLAYERS", 14f))
        right.addView(UiKit.divider(context))
        val scroll = ScrollView(context).apply {
            layoutParams = UiKit.lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        playerList = LinearLayout(context).apply { orientation = VERTICAL }
        scroll.addView(playerList)
        right.addView(scroll)
        addView(right)
    }

    /** Called ~4 times a second by the activity while the lobby is visible. */
    fun refresh() {
        val phaseName = when (state.matchState) {
            MatchState.WARMUP -> "warm-up"
            MatchState.ACTIVE -> "match in progress"
            MatchState.ENDED -> "match over"
            else -> "?"
        }
        header.text = "CONNECTED AS ${state.nickname.uppercase()}"
        subHeader.text = buildString {
            append(state.serverName)
            append("  ·  ")
            append(state.serverIp).append(':').append(state.serverPort)
            append("  ·  ").append(state.mode.name) // P2-6: the mode, up front
            if (state.killLimit > 0) append(" (first to ").append(state.killLimit).append(')')
            append("  ·  map ").append(state.arena.name)
            append("  ·  ").append(phaseName)
            append("  ·  ping ").append(state.pingMs).append(" ms")
            if (state.mode == GameMode.TDM) {
                append("\nyour team: ").append(state.localTeam.name)
                append("   RED ").append(state.redScore)
                append("   BLUE ").append(state.blueScore)
            }
            if (state.arenaMismatch) {
                append("\nWARNING: the server's map differs from the one in this APK.")
            }
        }

        // P3-4: live tally next to the buttons; the configured mode is marked.
        voteDmButton.text = "DM  ${'\u00B7'}  ${state.votesDm}" +
            if (state.mode == GameMode.DM) "  (current)" else ""
        voteTdmButton.text = "TDM  ${'\u00B7'}  ${state.votesTdm}" +
            if (state.mode == GameMode.TDM) "  (current)" else ""

        playerList.removeAllViews()
        val rows = state.scoreboardRows()
        if (rows.isEmpty()) {
            playerList.addView(UiKit.label(context, "waiting for the first snapshot ...", 11.5f))
            return
        }
        for (e in rows) {
            val line = UiKit.row(context).apply {
                layoutParams = UiKit.lp(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    margins = intArrayOf(0, 0, 0, UiKit.dp(context, 5f)),
                )
            }
            val isMe = e.id == state.localPlayerId
            val name = (if (e.type == EntityType.BOT) "\u2699 " else "") +
                e.name + (if (isMe) "  (you)" else "")
            val colour = when {
                isMe -> UiKit.ACCENT
                state.mode == GameMode.TDM -> UiKit.teamColor(e.teamEnum)
                e.type == EntityType.BOT -> UiKit.TEXT_DIM
                else -> UiKit.TEXT
            }
            line.addView(
                UiKit.mono(context, name, 12.5f, colour).apply {
                    layoutParams = UiKit.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                },
            )
            line.addView(UiKit.mono(context, "${e.kills} / ${e.deaths}", 12.5f, UiKit.TEXT_DIM))
            playerList.addView(line)
        }
    }
}
