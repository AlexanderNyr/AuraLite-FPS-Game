package com.lanfps.server

import com.lanfps.shared.GameConstants
import com.lanfps.shared.InputButtons
import com.lanfps.shared.MathUtil
import com.lanfps.shared.Vec3
import java.util.Random
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Server-side bot brain.
 *
 * Produces an [com.lanfps.shared.InputCommand] per bot per tick — nothing more.
 * Because the bot's intent is expressed as ordinary input, the bot is then run
 * through the same [ServerPhysics] as a human, which is what keeps bots from
 * clipping through walls or outrunning players.
 *
 * Fairness rules the AI obeys:
 *  - it only acquires targets it can actually see (shared line-of-sight test),
 *    so it never shoots through geometry;
 *  - it never targets a teammate in TDM;
 *  - it has a reaction delay and a drifting aim error scaled by difficulty, so
 *    it is beatable.
 */
class BotAI(
    private val world: World,
    private val arena: ServerArena,
    private val difficulty: Float,
    seed: Long = 0xB07L,
) {
    private val rng = Random(seed)

    private val eye = Vec3()
    private val targetEye = Vec3()
    private val desiredDir = Vec3()
    private val fwd = Vec3()
    private val right = Vec3()
    private val tmp = Vec3()

    // Difficulty-scaled behaviour knobs.
    private val turnSpeedDeg = MathUtil.lerp(180f, 520f, difficulty)
    private val maxAimErrorDeg = MathUtil.lerp(9f, 1.6f, difficulty)
    private val reactionSeconds = MathUtil.lerp(0.55f, 0.12f, difficulty)
    private val viewRange = MathUtil.lerp(32f, 55f, difficulty)

    fun update(bot: BotEntity, dt: Float) {
        bot.stateTimer += dt

        if (!bot.alive) {
            bot.setState(BotState.RESPAWN_WAIT)
            bot.input.moveForward = 0f
            bot.input.moveRight = 0f
            bot.input.buttons = 0
            return
        }

        updateStuckDetection(bot, dt)
        updateAimError(bot, dt)

        val target = acquireTarget(bot)
        chooseState(bot, target, dt)

        // Reset per-tick intent; each state fills in what it needs.
        bot.input.buttons = 0
        bot.input.moveForward = 0f
        bot.input.moveRight = 0f

        when (bot.state) {
            BotState.ATTACK -> actAttack(bot, target, dt)
            BotState.EVADE -> actEvade(bot, target, dt)
            BotState.SEEK_TARGET -> actSeek(bot, dt)
            BotState.PATROL -> actPatrol(bot, dt)
            BotState.RESPAWN_WAIT -> { /* nothing */ }
        }

        // Free the bot if it has been grinding against geometry.
        if (bot.stuckTimer > STUCK_THRESHOLD) {
            bot.input.moveRight = bot.strafeDir
            bot.input.moveForward = 0.4f
            if (bot.body.onGround && rng.nextFloat() < 0.25f) {
                bot.input.buttons = bot.input.buttons or InputButtons.JUMP
            }
            if (bot.stuckTimer > STUCK_THRESHOLD * 2f) {
                bot.goalWaypoint = rng.nextInt(maxOf(1, arena.waypointCount))
                bot.strafeDir = -bot.strafeDir
                bot.stuckTimer = 0f
            }
        }

        bot.input.sequence = (bot.input.sequence + 1) and 0xFFFF
        bot.input.sanitize()
    }

    // ---- sensing ----------------------------------------------------------

    /** Nearest enemy with a clear line of sight, or null. */
    private fun acquireTarget(bot: BotEntity): GameEntity? {
        bot.eyePosition(eye)
        var best: GameEntity? = null
        var bestDist = viewRange

        for (e in world.entities.values) {
            if (e === bot || !e.alive) continue
            if (world.areAllies(bot, e)) continue

            val dist = bot.body.position.distanceTo(e.body.position)
            if (dist > bestDist) continue

            e.eyePosition(targetEye)
            if (!world.raycast.hasLineOfSight(eye, targetEye)) continue

            best = e
            bestDist = dist
        }
        return best
    }

    private fun chooseState(bot: BotEntity, target: GameEntity?, dt: Float) {
        if (target != null) {
            if (bot.targetId != target.id) {
                bot.targetId = target.id
                bot.reactionTimer = reactionSeconds
            }
            bot.hasLastKnown = true
            bot.lastKnownTargetPos.set(target.body.position)

            if (bot.reactionTimer > 0f) {
                bot.reactionTimer -= dt
            }

            val lowHealth = bot.health <= EVADE_HEALTH
            bot.setState(if (lowHealth) BotState.EVADE else BotState.ATTACK)
        } else {
            bot.targetId = 0
            if (bot.hasLastKnown && bot.stateTimer < SEEK_TIMEOUT) {
                if (bot.state == BotState.ATTACK || bot.state == BotState.EVADE) {
                    bot.setState(BotState.SEEK_TARGET)
                } else if (bot.state != BotState.SEEK_TARGET) {
                    bot.setState(BotState.PATROL)
                }
            } else {
                bot.hasLastKnown = false
                bot.setState(BotState.PATROL)
            }
        }
    }

    // ---- behaviours -------------------------------------------------------

    private fun actAttack(bot: BotEntity, target: GameEntity?, dt: Float) {
        if (target == null) { bot.setState(BotState.SEEK_TARGET); return }

        target.eyePosition(targetEye)
        aimAt(bot, targetEye, dt)

        val dist = bot.body.position.distanceTo(target.body.position)

        // Hold a comfortable fighting distance.
        val approach = when {
            dist > PREFERRED_RANGE_MAX -> 1f
            dist < PREFERRED_RANGE_MIN -> -1f
            else -> 0f
        }

        // Side-step, flipping direction periodically so it is not predictable.
        bot.strafeTimer -= dt
        if (bot.strafeTimer <= 0f) {
            bot.strafeDir = if (rng.nextBoolean()) 1f else -1f
            bot.strafeTimer = 0.5f + rng.nextFloat() * 1.2f
        }

        applyMove(bot, approach, bot.strafeDir * STRAFE_AMOUNT)

        if (bot.reactionTimer <= 0f && isAimedAt(bot, targetEye)) {
            bot.input.buttons = bot.input.buttons or InputButtons.FIRE
        }
    }

    private fun actEvade(bot: BotEntity, target: GameEntity?, dt: Float) {
        if (target == null) { bot.setState(BotState.SEEK_TARGET); return }

        target.eyePosition(targetEye)
        aimAt(bot, targetEye, dt)

        // Retreat while still returning fire occasionally.
        bot.strafeTimer -= dt
        if (bot.strafeTimer <= 0f) {
            bot.strafeDir = if (rng.nextBoolean()) 1f else -1f
            bot.strafeTimer = 0.4f + rng.nextFloat() * 0.6f
        }
        applyMove(bot, -1f, bot.strafeDir * STRAFE_AMOUNT)

        if (bot.reactionTimer <= 0f && isAimedAt(bot, targetEye) && rng.nextFloat() < 0.4f) {
            bot.input.buttons = bot.input.buttons or InputButtons.FIRE
        }
    }

    private fun actSeek(bot: BotEntity, dt: Float) {
        if (!bot.hasLastKnown) { bot.setState(BotState.PATROL); return }

        val reached = bot.body.position.horizontalDistanceTo(bot.lastKnownTargetPos) < 2.0f
        if (reached || bot.stateTimer > SEEK_TIMEOUT) {
            bot.hasLastKnown = false
            bot.setState(BotState.PATROL)
            return
        }
        navigateTowards(bot, bot.lastKnownTargetPos, dt)
        applyMove(bot, 1f, 0f)
    }

    private fun actPatrol(bot: BotEntity, dt: Float) {
        if (arena.waypointCount == 0) return

        if (bot.goalWaypoint < 0) {
            bot.goalWaypoint = rng.nextInt(arena.waypointCount)
        }
        val goalPos = arena.waypoint(bot.goalWaypoint)
        if (bot.body.position.horizontalDistanceTo(goalPos) < WAYPOINT_REACHED) {
            bot.goalWaypoint = pickNewPatrolGoal(bot)
        }
        navigateTowards(bot, arena.waypoint(bot.goalWaypoint), dt)
        applyMove(bot, PATROL_SPEED_SCALE, 0f)
    }

    private fun pickNewPatrolGoal(bot: BotEntity): Int {
        val n = arena.waypointCount
        if (n <= 1) return 0
        // Prefer a waypoint that is not the one we just reached.
        var candidate = rng.nextInt(n)
        var attempts = 0
        while (candidate == bot.goalWaypoint && attempts < 4) {
            candidate = rng.nextInt(n)
            attempts++
        }
        return candidate
    }

    // ---- steering ---------------------------------------------------------

    /**
     * Points the bot toward [worldTarget], routing through the waypoint graph
     * when there is no direct line of sight. This is what lets bots find the
     * gaps in the lane dividers instead of walking into them.
     */
    private fun navigateTowards(bot: BotEntity, worldTarget: Vec3, dt: Float) {
        bot.eyePosition(eye)
        tmp.set(worldTarget.x, worldTarget.y + 1.0f, worldTarget.z)

        val direct = world.raycast.hasLineOfSight(eye, tmp)
        val steerTo = if (direct) {
            worldTarget
        } else {
            val from = arena.nearestWaypoint(bot.body.position)
            val to = arena.nearestWaypoint(worldTarget)
            val hop = arena.nextWaypoint(from, to)
            if (hop >= 0) arena.waypoint(hop) else worldTarget
        }

        desiredDir.set(
            steerTo.x - bot.body.position.x,
            0f,
            steerTo.z - bot.body.position.z,
        )
        if (desiredDir.horizontalLength() > 1e-3f) {
            desiredDir.normalize()
            // Face the direction of travel while patrolling/seeking.
            val desiredYaw = yawTowards(desiredDir.x, desiredDir.z)
            turnToward(bot, desiredYaw, 0f, dt)
        }
    }

    /** Smoothly rotates the bot's view toward the given angles. */
    private fun turnToward(bot: BotEntity, desiredYaw: Float, desiredPitch: Float, dt: Float) {
        val maxStep = turnSpeedDeg * dt
        val dYaw = MathUtil.angleDeltaDeg(bot.input.yaw, desiredYaw)
        bot.input.yaw = MathUtil.wrapDegrees(
            bot.input.yaw + MathUtil.clamp(dYaw, -maxStep, maxStep),
        )
        val dPitch = desiredPitch - bot.input.pitch
        bot.input.pitch = MathUtil.clamp(
            bot.input.pitch + MathUtil.clamp(dPitch, -maxStep, maxStep),
            -GameConstants.MAX_PITCH_DEG,
            GameConstants.MAX_PITCH_DEG,
        )
    }

    /** Aims at a world point, including the current drifting aim error. */
    private fun aimAt(bot: BotEntity, point: Vec3, dt: Float) {
        bot.eyePosition(eye)
        val dx = point.x - eye.x
        val dy = point.y - eye.y
        val dz = point.z - eye.z
        val horiz = sqrt(dx * dx + dz * dz)

        val desiredYaw = yawTowards(dx, dz) + bot.aimErrorYaw
        val desiredPitch = (atan2(dy, horiz) * MathUtil.RAD_TO_DEG) + bot.aimErrorPitch
        turnToward(bot, desiredYaw, desiredPitch, dt)
    }

    /** True when the bot's current view is close enough to the target to fire. */
    private fun isAimedAt(bot: BotEntity, point: Vec3): Boolean {
        bot.eyePosition(eye)
        val dx = point.x - eye.x
        val dy = point.y - eye.y
        val dz = point.z - eye.z
        val horiz = sqrt(dx * dx + dz * dz)

        val wantYaw = yawTowards(dx, dz)
        val wantPitch = atan2(dy, horiz) * MathUtil.RAD_TO_DEG

        val yawErr = abs(MathUtil.angleDeltaDeg(bot.body.yaw, wantYaw))
        val pitchErr = abs(bot.body.pitch - wantPitch)
        return yawErr < FIRE_CONE_DEG && pitchErr < FIRE_CONE_DEG
    }

    /**
     * Converts a horizontal direction into a yaw, matching the project's
     * convention (yaw 0 => -Z, +yaw => +X).
     */
    private fun yawTowards(dx: Float, dz: Float): Float =
        atan2(dx, -dz) * MathUtil.RAD_TO_DEG

    /** Turns a world-space intent into forward/right stick values. */
    private fun applyMove(bot: BotEntity, forwardAmount: Float, strafeAmount: Float) {
        MathUtil.horizontalForward(bot.input.yaw, fwd)
        MathUtil.horizontalRight(bot.input.yaw, right)

        var f = forwardAmount
        var r = strafeAmount

        // When navigating, desiredDir holds the world direction we want to walk.
        if (bot.state == BotState.PATROL || bot.state == BotState.SEEK_TARGET) {
            if (desiredDir.horizontalLength() > 1e-3f) {
                f = desiredDir.dot(fwd) * abs(forwardAmount)
                r = desiredDir.dot(right) * abs(forwardAmount) + strafeAmount
            }
        }

        bot.input.moveForward = MathUtil.clamp(f, -1f, 1f)
        bot.input.moveRight = MathUtil.clamp(r, -1f, 1f)
    }

    // ---- housekeeping -----------------------------------------------------

    private fun updateAimError(bot: BotEntity, dt: Float) {
        bot.aimErrorTimer -= dt
        if (bot.aimErrorTimer <= 0f) {
            bot.aimErrorTimer = 0.25f + rng.nextFloat() * 0.5f
            bot.aimErrorYaw = (rng.nextFloat() * 2f - 1f) * maxAimErrorDeg
            bot.aimErrorPitch = (rng.nextFloat() * 2f - 1f) * maxAimErrorDeg * 0.5f
        }
    }

    private fun updateStuckDetection(bot: BotEntity, dt: Float) {
        val moved = bot.body.position.horizontalDistanceTo(bot.lastPosition)
        val wantsToMove = abs(bot.input.moveForward) > 0.1f || abs(bot.input.moveRight) > 0.1f
        if (wantsToMove && moved < STUCK_DISTANCE * dt * 60f) {
            bot.stuckTimer += dt
        } else {
            bot.stuckTimer = 0f
        }
        bot.lastPosition.set(bot.body.position)
    }

    companion object {
        private const val EVADE_HEALTH = 35
        private const val SEEK_TIMEOUT = 6f
        private const val WAYPOINT_REACHED = 2.5f
        private const val PREFERRED_RANGE_MIN = 6f
        private const val PREFERRED_RANGE_MAX = 18f
        private const val STRAFE_AMOUNT = 0.85f
        private const val PATROL_SPEED_SCALE = 0.75f
        private const val FIRE_CONE_DEG = 6.5f
        private const val STUCK_THRESHOLD = 0.6f
        private const val STUCK_DISTANCE = 0.01f
    }
}
