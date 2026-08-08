package com.lanfps.server

import com.lanfps.shared.EntityType
import com.lanfps.shared.InputCommand
import com.lanfps.shared.SpawnPoint
import com.lanfps.shared.Vec3

/** Finite-state-machine states a bot can be in. */
enum class BotState {
    /** No known enemy: walk the waypoint graph. */
    PATROL,

    /** Enemy seen recently but not visible now: move to the last known position. */
    SEEK_TARGET,

    /** Enemy visible: hold range, strafe and shoot. */
    ATTACK,

    /** Hurt: back away from the threat while still facing it. */
    EVADE,

    /** P4-6/7: no enemy around — head for a useful pickup. */
    GET_PICKUP,

    /** P4-7: seen a far enemy — circle to a side angle before closing in. */
    FLANK,

    /** Dead, counting down to respawn. */
    RESPAWN_WAIT,
}

/**
 * A server-controlled opponent.
 *
 * A bot is an ordinary [GameEntity] that fills in its own [InputCommand] each
 * tick instead of receiving one over the network. It is then simulated through
 * exactly the same physics as a human player, so bots collide, fall and shoot
 * under identical rules — and clients receive them as regular snapshot entities
 * without needing any bot-specific code.
 */
class BotEntity(id: Int, botName: String) : GameEntity(id) {

    override val entityType: Int get() = EntityType.BOT

    init {
        name = botName
    }

    /** Synthesised each tick by [BotAI]. */
    @JvmField val input: InputCommand = InputCommand()

    /**
     * P2-4: this bot's individual skill 0..1. The server config's
     * `botDifficulty` is the *mean*; each bot gets a fixed random-ish offset so
     * a match mixes sharp and sloppy opponents instead of clones.
     */
    @JvmField var skill: Float = 0.55f

    @JvmField var state: BotState = BotState.PATROL
    @JvmField var stateTimer: Float = 0f

    @JvmField var targetId: Int = 0
    @JvmField val lastKnownTargetPos: Vec3 = Vec3()
    @JvmField var hasLastKnown: Boolean = false

    /** Delay before reacting to a newly spotted enemy (human-like). */
    @JvmField var reactionTimer: Float = 0f

    @JvmField var goalWaypoint: Int = -1

    /** -1 or +1: which way the bot is currently side-stepping. */
    @JvmField var strafeDir: Float = 1f
    @JvmField var strafeTimer: Float = 0f

    /** Grows while the bot wants to move but is not actually moving. */
    @JvmField var stuckTimer: Float = 0f
    @JvmField val lastPosition: Vec3 = Vec3()

    /** P2-4: waypoint the bot is currently hiding behind while EVADE-ing. */
    @JvmField var coverWaypoint: Int = -1

    /** P4-7: active pickup slot the bot is travelling to (GET_PICKUP), -1 none. */
    @JvmField var pickupIndex: Int = -1

    /** P4-7: side-angle waypoint the bot is circling to (FLANK), -1 none. */
    @JvmField var flankWaypoint: Int = -1

    /** Slowly drifting aim error, refreshed by [aimErrorTimer]. */
    @JvmField var aimErrorYaw: Float = 0f
    @JvmField var aimErrorPitch: Float = 0f
    @JvmField var aimErrorTimer: Float = 0f

    override fun spawnAt(spawn: SpawnPoint) {
        super.spawnAt(spawn)
        state = BotState.PATROL
        stateTimer = 0f
        targetId = 0
        hasLastKnown = false
        goalWaypoint = -1
        stuckTimer = 0f
        reactionTimer = 0f
        coverWaypoint = -1
        pickupIndex = -1
        flankWaypoint = -1
        lastPosition.set(spawn.position)
        input.clear()
        input.yaw = spawn.yaw
    }

    fun setState(newState: BotState) {
        if (state != newState) {
            state = newState
            stateTimer = 0f
        }
    }
}
