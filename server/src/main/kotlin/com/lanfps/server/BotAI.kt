package com.lanfps.server

import com.lanfps.shared.GameConstants
import com.lanfps.shared.InputButtons
import com.lanfps.shared.PickupKind
import com.lanfps.shared.MathUtil
import com.lanfps.shared.Vec3
import com.lanfps.shared.Weapons
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
    seed: Long = 0xB07L,
) {
    private val rng = Random(seed)

    private val eye = Vec3()
    private val targetEye = Vec3()
    private val desiredDir = Vec3()
    private val fwd = Vec3()
    private val right = Vec3()
    private val tmp = Vec3()

    // P2-4: behaviour knobs are per-bot now ([BotEntity.skill]) — the config
    // difficulty is only the mean assigned when bots are created.
    private fun turnSpeedDeg(bot: BotEntity) = MathUtil.lerp(180f, 520f, bot.skill)
    private fun maxAimErrorDeg(bot: BotEntity) = MathUtil.lerp(9f, 1.6f, bot.skill)
    private fun reactionSeconds(bot: BotEntity) = MathUtil.lerp(0.55f, 0.12f, bot.skill)
    private fun viewRange(bot: BotEntity) = MathUtil.lerp(32f, 55f, bot.skill)

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
            BotState.GET_PICKUP -> actGetPickup(bot, dt)
            BotState.FLANK -> actFlank(bot, target, dt)
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
        var bestDist = viewRange(bot)

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
                bot.reactionTimer = reactionSeconds(bot)
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
                // Getting a pickup is a solo mission: losing a stale trail
                // while travelling to one does NOT cancel the errand.
                if (bot.state != BotState.GET_PICKUP) bot.setState(BotState.PATROL)
            }
        }
    }

    // ---- behaviours -------------------------------------------------------

    private fun actAttack(bot: BotEntity, target: GameEntity?, dt: Float) {
        if (target == null) { bot.setState(BotState.SEEK_TARGET); return }

        target.eyePosition(targetEye)
        aimAt(bot, targetEye, dt)

        val dist = bot.body.position.distanceTo(target.body.position)

        // P4-7: far enemies are circled from the side instead of walked into
        // face-first, about once in a blue moon so it reads as intent.
        if (dist > FLANK_MIN_RANGE && rng.nextFloat() < dt * FLANK_CHANCE_PER_SEC) {
            val flank = findFlankWaypoint(bot, target)
            if (flank >= 0) {
                bot.flankWaypoint = flank
                bot.setState(BotState.FLANK)
                return
            }
        }

        // P4-6: a grenade arcs in when we have them and the range is juicy.
        // Deliberately rare: every bot being a nade turret would drown the map.
        if (bot.grenades > 0 && dist in GRENADE_MIN_RANGE..GRENADE_MAX_RANGE &&
            bot.reactionTimer <= 0f && rng.nextFloat() < dt * GRENADE_CHANCE_PER_SEC
        ) {
            bot.input.buttons = bot.input.buttons or InputButtons.GRENADE
        }

        // P2-1: pick the right tool for the range.
        bot.input.weapon = weaponForRange(dist)

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

        // P2-4: run for *actual* cover — a waypoint the target has no line of
        // sight to — instead of just backpedalling into the open. The cover pick
        // is refreshed when reached or about twice a second.
        val coverPos = bot.coverWaypoint.takeIf { it >= 0 }?.let { arena.waypoint(it) }
        val reachedCover = coverPos != null &&
            bot.body.position.horizontalDistanceTo(coverPos) < 2.0f
        if (bot.coverWaypoint < 0 || reachedCover || rng.nextFloat() < dt / 2.5f) {
            bot.coverWaypoint = findCoverWaypoint(bot, targetEye)
        }

        if (bot.coverWaypoint >= 0 && !reachedCover) {
            // Travel to cover: face the threat while the steering maps the
            // world-space route onto the stick (see applyMove's EVADE branch).
            navigateTowards(bot, arena.waypoint(bot.coverWaypoint), dt)
            aimAt(bot, targetEye, dt)
            applyMove(bot, 1f, 0f)
        } else {
            // In cover (or none found): hold and jink.
            aimAt(bot, targetEye, dt)
            bot.strafeTimer -= dt
            if (bot.strafeTimer <= 0f) {
                bot.strafeDir = if (rng.nextBoolean()) 1f else -1f
                bot.strafeTimer = 0.4f + rng.nextFloat() * 0.6f
            }
            applyMove(bot, if (bot.coverWaypoint >= 0) 0f else -1f, bot.strafeDir * STRAFE_AMOUNT)
        }

        if (bot.reactionTimer <= 0f && isAimedAt(bot, targetEye) && rng.nextFloat() < 0.4f) {
            bot.input.buttons = bot.input.buttons or InputButtons.FIRE
        }
    }

    /** P2-4: nearest waypoint that breaks line of sight to the enemy's eye. */
    private fun findCoverWaypoint(bot: BotEntity, enemyEye: Vec3): Int {
        var best = -1
        var bestScore = Float.MAX_VALUE
        for (i in 0 until arena.waypointCount) {
            val wp = arena.waypoint(i)
            tmp.set(wp.x, wp.y + 0.9f, wp.z)
            // Only useful if the enemy cannot watch us there.
            if (world.raycast.hasLineOfSight(tmp, enemyEye)) continue
            val dist = bot.body.position.horizontalDistanceTo(wp)
            // Prefer close cover, and routeable cover.
            val from = arena.nearestWaypoint(bot.body.position)
            if (!arena.hasRoute(from, i)) continue
            val score = dist + rng.nextFloat() * 2f
            if (score < bestScore) {
                bestScore = score
                best = i
            }
        }
        return best
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

    /**
     * P4-7 pickup run: travel to the chosen slot; the [PickupManager] consumes
     * it the instant we step on it. Slots that vanished mid-trip or a long
     * stale route poke us back onto patrol duty.
     */
    private fun actGetPickup(bot: BotEntity, dt: Float) {
        val slots = world.pickups.slots
        if (bot.pickupIndex !in slots.indices || !slots[bot.pickupIndex].active) {
            bot.setState(BotState.PATROL)
            return
        }
        if (bot.stateTimer > GET_PICKUP_TIMEOUT) {
            bot.pickupIndex = -1
            bot.setState(BotState.PATROL)
            return
        }
        val slot = slots[bot.pickupIndex]
        navigateCheatDestination.set(slot.x, slot.y, slot.z)
        // Close enough? The trigger will fire on contact; arrive -> patrol.
        if (bot.body.position.horizontalDistanceTo(navigateCheatDestination) < 1.2f) {
            bot.pickupIndex = -1
            bot.setState(BotState.PATROL)
            return
        }
        navigateTowards(bot, navigateCheatDestination, dt)
        applyMove(bot, 1f, 0f)
    }

    /** Scratch world target for the pickup navigation call (no allocation). */
    private val navigateCheatDestination = com.lanfps.shared.Vec3()

    /**
     * P4-7: circling to a side angle before engaging — the bot keeps walking
     * the flank route with its aim on the enemy, then slides into SEEK for
     * the actual approach.
     */
    private fun actFlank(bot: BotEntity, target: GameEntity?, dt: Float) {
        if (target == null || bot.flankWaypoint < 0 ||
            bot.flankWaypoint >= arena.waypointCount
        ) {
            bot.flankWaypoint = -1
            bot.setState(BotState.SEEK_TARGET)
            return
        }
        target.eyePosition(targetEye)
        aimAt(bot, targetEye, dt)

        val here = arena.waypoint(bot.flankWaypoint)
        if (bot.body.position.horizontalDistanceTo(here) < WAYPOINT_REACHED ||
            bot.stateTimer > FLANK_TIMEOUT
        ) {
            bot.flankWaypoint = -1
            bot.setState(BotState.SEEK_TARGET)
            return
        }
        navigateTowards(bot, here, dt)
        applyMove(bot, 1f, 0f)
    }

    /**
     * P4-7: a waypoint that is close-ish to the enemy but hidden from their
     * eyes — stepping there keeps us out of their crosshair until we are
     * *much* closer. Returns -1 when nothing sane qualifies.
     */
    private fun findFlankWaypoint(bot: BotEntity, target: GameEntity): Int {
        var best = -1
        var bestScore = Float.MAX_VALUE
        val from = arena.nearestWaypoint(bot.body.position)
        for (i in 0 until arena.waypointCount) {
            val wp = arena.waypoint(i)
            val toTarget = wp.horizontalDistanceTo(target.body.position)
            if (toTarget < 4f || toTarget > 18f) continue
            tmp.set(wp.x, wp.y + 0.9f, wp.z)
            target.eyePosition(targetEye)
            // Routeable from us, invisible from them: the whole point.
            if (!arena.hasRoute(from, i)) continue
            if (world.raycast.hasLineOfSight(tmp, targetEye)) continue
            val score = bot.body.position.horizontalDistanceTo(wp) + rng.nextFloat() * 3f
            if (score < bestScore) {
                bestScore = score
                best = i
            }
        }
        return best
    }

    /**
     * P4-7: the "want a pickup" brain. Returns the slot index to run for, or
     * -1. Needs are weighted: bleeding bots run for medkits, armoured-hungry
     * ones for shields; grenade-rich bots skip the pouch entirely.
     */
    private fun pickupWanted(bot: BotEntity): Int {
        val slots = world.pickups.slots
        if (slots.isEmpty()) return -1
        var best = -1
        var bestScore = Float.MAX_VALUE
        val from = arena.nearestWaypoint(bot.body.position)
        for (i in slots.indices) {
            val slot = slots[i]
            if (!slot.active) continue
            val desire = when (slot.kind) {
                PickupKind.HEALTH -> if (bot.health < 75) 1.0f - bot.health / 100f else 0f
                PickupKind.ARMOR -> if (bot.armor < 50) 0.6f else 0f
                PickupKind.SMG -> if (bot.weapon != Weapons.SMG) 0.25f else 0f
                PickupKind.GRENADES -> if (bot.grenades < GameConstants.MAX_GRENADES) 0.35f else 0f
            }
            if (desire <= 0f) continue
            navigateCheatDestination.set(slot.x, slot.y, slot.z)
            val to = arena.nearestWaypoint(navigateCheatDestination)
            if (!arena.hasRoute(from, to)) continue
            val dist = bot.body.position.horizontalDistanceTo(navigateCheatDestination)
            if (dist > PICKUP_MAX_TRAVEL) continue
            // High-skilled bots commit harder to pickups (they read the map).
            val score = dist * (1.6f - desire) - bot.skill * 4f
            if (score < bestScore) {
                bestScore = score
                best = i
            }
        }
        return best
    }

    private fun actPatrol(bot: BotEntity, dt: Float) {
        if (arena.waypointCount == 0) return

        // P4-7: before the next patrol leg, check whether a pickup is worth
        // the detour. Fresh goals only — never re-decided mid-walk (prevents
        // goal flapping between two equidistant medkits).
        if (bot.goalWaypoint < 0 || rng.nextFloat() < dt * 0.4f) {
            val want = pickupWanted(bot)
            if (want >= 0) {
                bot.pickupIndex = want
                bot.setState(BotState.GET_PICKUP)
                return
            }
        }

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
        val maxStep = turnSpeedDeg(bot) * dt
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
        // EVADE is included: the bot keeps its aim on the threat but still walks
        // the route toward cover.
        if (bot.state == BotState.PATROL || bot.state == BotState.SEEK_TARGET ||
            bot.state == BotState.EVADE
        ) {
            if (desiredDir.horizontalLength() > 1e-3f) {
                f = desiredDir.dot(fwd) * abs(forwardAmount)
                r = desiredDir.dot(right) * abs(forwardAmount) + strafeAmount
            }
        }

        bot.input.moveForward = MathUtil.clamp(f, -1f, 1f)
        bot.input.moveRight = MathUtil.clamp(r, -1f, 1f)
    }

    /** P2-1/P4-1: arsenal by engagement range — sniper far, shotgun close,
     *  SMG in the pressure band, rifle mid-range. Instagib clamps later. */
    private fun weaponForRange(dist: Float): Int = when {
        dist >= SNIPER_MIN_RANGE -> Weapons.SNIPER
        dist <= SHOTGUN_MAX_RANGE -> Weapons.SHOTGUN
        dist <= SMG_MAX_RANGE -> Weapons.SMG
        else -> Weapons.RIFLE
    }

    /**
     * P2-4: hearing. A gunshot within [HEARING_RANGE] puts idle bots on the
     * trail of the shooter (SEEK toward the source). Bots already in a fight,
     * and teammates of the shooter in TDM, ignore it.
     */
    fun onShotFired(shooter: GameEntity) {
        for (bot in world.bots) {
            if (bot === shooter || !bot.alive) continue
            if (world.areAllies(bot, shooter)) continue
            if (bot.state == BotState.ATTACK) continue
            val dist = bot.body.position.distanceTo(shooter.body.position)
            if (dist > HEARING_RANGE) continue
            bot.hasLastKnown = true
            bot.lastKnownTargetPos.set(shooter.body.position)
            if (bot.state != BotState.SEEK_TARGET && bot.state != BotState.EVADE) {
                bot.setState(BotState.SEEK_TARGET)
            }
        }
    }

    // ---- housekeeping -----------------------------------------------------

    private fun updateAimError(bot: BotEntity, dt: Float) {
        bot.aimErrorTimer -= dt
        if (bot.aimErrorTimer <= 0f) {
            bot.aimErrorTimer = 0.25f + rng.nextFloat() * 0.5f
            bot.aimErrorYaw = (rng.nextFloat() * 2f - 1f) * maxAimErrorDeg(bot)
            bot.aimErrorPitch = (rng.nextFloat() * 2f - 1f) * maxAimErrorDeg(bot) * 0.5f
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

        /** P2-4: a gunshot this far away (metres) can be heard by bots. */
        private const val HEARING_RANGE = 30f

        /** P2-1/P4-1: range bands the bots use to pick a weapon. */
        private const val SNIPER_MIN_RANGE = 24f
        private const val SHOTGUN_MAX_RANGE = 8f
        private const val SMG_MAX_RANGE = 17f

        /** P4-7: patrol side-quest to pickups. */
        private const val GET_PICKUP_TIMEOUT = 8f
        private const val PICKUP_MAX_TRAVEL = 32f

        /** P4-7: flank manoeuvre parameters. */
        private const val FLANK_MIN_RANGE = 16f
        private const val FLANK_CHANCE_PER_SEC = 0.12f
        private const val FLANK_TIMEOUT = 5f

        /** P4-6: grenade behaviour. */
        private const val GRENADE_MIN_RANGE = 7f
        private const val GRENADE_MAX_RANGE = 22f
        private const val GRENADE_CHANCE_PER_SEC = 0.22f
    }
}
