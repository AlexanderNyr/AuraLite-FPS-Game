package com.lanfps.server

import com.lanfps.shared.GameConstants
import com.lanfps.shared.MatchEventType
import com.lanfps.shared.MatchState
import com.lanfps.shared.Packets

/**
 * Drives the match lifecycle: WARMUP -> ACTIVE -> ENDED -> (reset) -> ACTIVE.
 *
 * A match ends when the time runs out or the kill limit is reached. After the
 * results screen has been shown for [GameConstants.POST_MATCH_SEC], scores reset
 * and the next match starts automatically — nobody has to touch the server.
 */
class MatchController(
    private val world: World,
    private val config: ServerConfig,
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
        pendingEvents.add(
            Packets.MatchEvent().apply {
                eventType = MatchEventType.MATCH_START
                extra = world.mode.wire
            },
        )
        Log.info(
            "MATCH START mode=${world.mode.name} time=${config.matchTimeSeconds}s " +
                "killLimit=${config.killLimit} entities=${world.entities.size}",
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
        // Pick up a mode change requested via config between matches.
        world.applyMode(config.mode)
        startMatch()
    }

    val isActive: Boolean get() = state == MatchState.ACTIVE
}
