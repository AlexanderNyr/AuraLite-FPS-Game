package com.lanfps.server

import com.lanfps.shared.GameMode
import com.lanfps.shared.Team

/**
 * Match scoring for both rulesets.
 *
 * Per-entity kills/deaths live on the entities themselves (they are replicated
 * in every snapshot, so clients can build a scoreboard with no extra packets).
 * This class owns the *team* totals and decides when a score limit is reached.
 */
class ScoreSystem {

    @JvmField var redScore: Int = 0
    @JvmField var blueScore: Int = 0

    fun reset() {
        redScore = 0
        blueScore = 0
    }

    fun registerKill(killer: GameEntity, mode: GameMode) {
        if (!mode.isTeamBased) return
        when (killer.team) {
            Team.RED -> redScore++
            Team.BLUE -> blueScore++
            else -> {}
        }
    }

    fun teamScore(team: Team): Int = when (team) {
        Team.RED -> redScore
        Team.BLUE -> blueScore
        else -> 0
    }

    /** True when someone has reached the configured limit. */
    fun limitReached(mode: GameMode, entities: Collection<GameEntity>, killLimit: Int): Boolean =
        if (mode.isTeamBased) {
            redScore >= killLimit || blueScore >= killLimit
        } else {
            entities.any { it.kills >= killLimit }
        }

    /** Human-readable winner, used in logs and the end-of-match packet. */
    fun describeWinner(mode: GameMode, entities: Collection<GameEntity>): String {
        if (mode.isTeamBased) {
            return when {
                redScore > blueScore -> "RED wins $redScore-$blueScore"
                blueScore > redScore -> "BLUE wins $blueScore-$redScore"
                else -> "Draw $redScore-$blueScore"
            }
        }
        val best = entities.maxByOrNull { it.kills } ?: return "No players"
        val tied = entities.count { it.kills == best.kills }
        return if (tied > 1) {
            "Draw at ${best.kills} kills"
        } else {
            "${best.name} wins with ${best.kills} kills"
        }
    }

    /** Winning team as a wire value, or 0 in DM / on a draw. */
    fun winningTeam(mode: GameMode): Int = when {
        !mode.isTeamBased -> Team.NONE.wire
        redScore > blueScore -> Team.RED.wire
        blueScore > redScore -> Team.BLUE.wire
        else -> Team.NONE.wire
    }

    /** Scoreboard rows sorted the way clients display them. */
    fun standings(entities: Collection<GameEntity>): List<GameEntity> =
        entities.sortedWith(
            compareByDescending<GameEntity> { it.kills }
                .thenBy { it.deaths }
                .thenBy { it.name },
        )
}
