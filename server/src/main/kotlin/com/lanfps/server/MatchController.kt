package com.lanfps.server

import com.lanfps.shared.ArenaDef
import com.lanfps.shared.GameConstants
import com.lanfps.shared.GameMode
import com.lanfps.shared.MatchEventType
import com.lanfps.shared.MatchState
import com.lanfps.shared.Packets

/**
 * Drives the match lifecycle: WARMUP -> ACTIVE -> ENDED -> (reset) -> ACTIVE.
 *
 * A match ends when the time runs out or the kill limit is reached. After the
 * results screen has been shown for [GameConstants.POST_MATCH_SEC], scores reset
 * and the next match starts automatically — nobody has to touch the server.
 *
 * Between matches three optional hooks run (all wired up by GameServer):
 *  - [voteWinner]: P3-4 lobby votes (MODE_VOTE) can pick the next ruleset;
 *  - [nextArena]: P2-3 map rotation swaps the world onto the next arena;
 *  - team balancer (P2-7) re-deals TDM sides.
 */
class MatchController(
    private val world: World,
    private val config: ServerConfig,
    private val voteWinner: (() -> GameMode?)? = null,
    private val nextArena: (() -> ArenaDef?)? = null,
) {
    @JvmField var state: Int = MatchState.WARMUP
    @JvmField var timeRemaining: Float = config.matchTimeSeconds.toFloat()

    private var postMatchTimer: Float = 0f

    /** Drained by [GameServer] and broadcast as MATCH_EVENT packets. */
    @JvmField val pendingEvents: ArrayList<Packets.MatchEvent> = ArrayList()

    fun update(dt: Float) {
        when (state) {
            MatchState.WARMUP -> {
                // Start as soon as there is anyone to play against — including
                // bots, so the server is testable with zero clients connected.
                if (world.entities.isNotEmpty()) startMatch()
            }

            MatchState.ACTIVE -> {
                timeRemaining -= dt
                val outOfTime = timeRemaining <= 0f
                val limitHit = world.score.limitReached(
                    world.mode, world.entities.values, config.killLimit,
                )
                if (outOfTime || limitHit) {
                    endMatch(if (outOfTime) "time limit" else "score limit")
                }
            }

            MatchState.ENDED -> {
                postMatchTimer -= dt
                if (postMatchTimer <= 0f) resetMatch()
            }
        }

        // Convert queued kills into wire events.
        if (world.killFeed.isNotEmpty()) {
            for (k in world.killFeed) {
                pendingEvents.add(
                    Packets.MatchEvent().apply {
                        eventType = MatchEventType.KILL
                        killerId = k.killerId
                        victimId = k.victimId
                        killerName = k.killerName
                        victimName = k.victimName
                    },
                )
            }
            world.killFeed.clear()
        }
    }

    fun startMatch() {
        state = MatchState.ACTIVE
        timeRemaining = config.matchTimeSeconds.toFloat()
        world.resetForNewMatch()
        // extra carries the mode; killerName/victimName carry the arena identity
        // (name + FNV-1a hash) so clients can hot-load a rotated map (P2-3).
        // Their kill-feed meaning is unused on a MATCH_START event.
        val def = world.serverArena.def
        pendingEvents.add(
            Packets.MatchEvent().apply {
                eventType = MatchEventType.MATCH_START
                extra = world.mode.wire
                killerName = def.name
                victimName = "0x%08X".format(def.hash())
            },
        )
        Log.info(
            "MATCH START mode=${world.mode.name} arena=${def.name} " +
                "time=${config.matchTimeSeconds}s killLimit=${config.killLimit} " +
                "entities=${world.entities.size}",
        )
    }

    fun endMatch(reason: String) {
        state = MatchState.ENDED
        timeRemaining = 0f
        postMatchTimer = GameConstants.POST_MATCH_SEC

        val summary = world.score.describeWinner(world.mode, world.entities.values)
        pendingEvents.add(
            Packets.MatchEvent().apply {
                eventType = MatchEventType.MATCH_END
                extra = world.score.winningTeam(world.mode)
                killerName = summary
            },
        )
        Log.info("MATCH END ($reason): $summary")
        for (e in world.score.standings(world.entities.values)) {
            Log.info(
                "  ${e.name.padEnd(18)} ${e.team.name.padEnd(5)} " +
                    "K:${e.kills} D:${e.deaths}",
            )
        }
    }

    private fun resetMatch() {
        // P3-4: the lobby vote outranks the config for one match; with no
        // majority (or no votes) the operator's `mode=` stays in charge.
        val voted = voteWinner?.invoke()
        if (voted != null && voted != world.mode) {
            Log.info("lobby vote decides the next match: ${voted.name} (config: ${config.mode})")
        }
        world.applyMode(voted ?: config.mode)
        // P2-7: even out the teams before the next round.
        world.balanceTeams()
        // P2-3: rotate the map, if the operator listed more than one.
        val next = nextArena?.invoke()
        if (next != null) {
            world.setArena(next)
        }
        startMatch()
    }

    val isActive: Boolean get() = state == MatchState.ACTIVE
}
