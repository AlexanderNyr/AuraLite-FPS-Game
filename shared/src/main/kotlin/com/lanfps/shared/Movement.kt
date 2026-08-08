package com.lanfps.shared

import kotlin.math.max

/**
 * The mutable part of a body that movement operates on.
 * Both the server entity and the client's predicted local player embed one.
 */
class BodyState {
    @JvmField val position: Vec3 = Vec3()
    @JvmField val velocity: Vec3 = Vec3()
    @JvmField var yaw: Float = 0f
    @JvmField var pitch: Float = 0f
    @JvmField var onGround: Boolean = false
    @JvmField var crouching: Boolean = false

    val height: Float
        get() = if (crouching) GameConstants.PLAYER_CROUCH_HEIGHT else GameConstants.PLAYER_HEIGHT

    val eyeHeight: Float
        get() = if (crouching) GameConstants.EYE_HEIGHT_CROUCH else GameConstants.EYE_HEIGHT

    /** World-space eye position (feet position + eye height). */
    fun eyePosition(out: Vec3): Vec3 =
        out.set(position.x, position.y + eyeHeight, position.z)

    fun copyFrom(o: BodyState): BodyState {
        position.set(o.position)
        velocity.set(o.velocity)
        yaw = o.yaw; pitch = o.pitch
        onGround = o.onGround; crouching = o.crouching
        return this
    }

    fun reset(): BodyState {
        position.zero(); velocity.zero()
        yaw = 0f; pitch = 0f
        onGround = false; crouching = false
        return this
    }
}

/**
 * Deterministic character movement + AABB collision.
 *
 * This class is the single implementation used by BOTH the authoritative server
 * and the client's prediction. Sharing it is what makes reconciliation stable:
 * given the same [BodyState] and the same [InputCommand], both sides produce
 * exactly the same result, so a correctly predicted frame needs no correction.
 *
 * Not thread-safe by design — it holds scratch state to stay allocation-free in
 * the tick loop. Create one instance per thread/loop.
 */
class MovementSolver {

    private val bodyBox = Aabb()
    private val wish = Vec3()
    private val fwd = Vec3()
    private val right = Vec3()

    /**
     * Advances [s] by exactly one fixed step.
     *
     * @param dt always [GameConstants.TICK_DT]; a parameter only so tests can
     *           exercise other rates. The client never chooses this value.
     */
    fun step(s: BodyState, cmd: InputCommand, arena: ArenaDef, dt: Float = GameConstants.TICK_DT) {
        // View angles are client-authoritative (aim feel must be instant).
        s.yaw = cmd.yaw
        s.pitch = cmd.pitch

        // Crouch: standing back up requires headroom.
        val wantCrouch = cmd.crouchPressed
        if (s.crouching && !wantCrouch) {
            if (hasHeadroom(s, arena)) s.crouching = false
        } else {
            s.crouching = wantCrouch
        }

        // Desired direction in world space.
        MathUtil.horizontalForward(s.yaw, fwd)
        MathUtil.horizontalRight(s.yaw, right)
        wish.zero()
        wish.addScaled(fwd, cmd.moveForward)
        wish.addScaled(right, cmd.moveRight)
        wish.y = 0f
        val wishLen = wish.horizontalLength()
        if (wishLen > 1f) wish.scale(1f / wishLen)

        val maxSpeed = if (s.crouching) GameConstants.CROUCH_SPEED else GameConstants.MOVE_SPEED

        // Ground friction.
        if (s.onGround) {
            val speed = s.velocity.horizontalLength()
            if (speed > 0.0001f) {
                val drop = speed * GameConstants.FRICTION * dt
                val scale = max(speed - drop, 0f) / speed
                s.velocity.x *= scale
                s.velocity.z *= scale
            }
        }

        // Quake-style directional acceleration: only ever adds speed along the
        // wish direction and never past maxSpeed, so it cannot be exploited.
        if (wishLen > 0.0001f) {
            val accel = if (s.onGround) {
                GameConstants.ACCELERATION
            } else {
                GameConstants.ACCELERATION * GameConstants.AIR_CONTROL
            }
            val currentSpeed = s.velocity.x * wish.x + s.velocity.z * wish.z
            val addSpeed = maxSpeed - currentSpeed
            if (addSpeed > 0f) {
                var accelSpeed = accel * dt * maxSpeed
                if (accelSpeed > addSpeed) accelSpeed = addSpeed
                s.velocity.x += wish.x * accelSpeed
                s.velocity.z += wish.z * accelSpeed
            }
        }

        // Jump.
        if (cmd.jumpPressed && s.onGround) {
            s.velocity.y = GameConstants.JUMP_VELOCITY
            s.onGround = false
        }

        // Gravity (terminal velocity guards against absurd fall speeds).
        s.velocity.y += GameConstants.GRAVITY * dt
        if (s.velocity.y < -60f) s.velocity.y = -60f

        // Integrate one axis at a time so we slide along walls instead of sticking.
        moveX(s, arena, s.velocity.x * dt)
        moveZ(s, arena, s.velocity.z * dt)
        moveY(s, arena, s.velocity.y * dt)

        // P4-4: launch pads. Checked after collision so the body is already
        // resting on the pad's surface; the impulse sets a deterministic
        // vertical velocity, so client prediction and the authoritative
        // server take off in the exact same tick. Horizontal speed is yours
        // to keep — flying in with momentum feels right.
        if (s.onGround && arena.jumpPads.isNotEmpty()) {
            for (i in arena.jumpPads.indices) {
                val pad = arena.jumpPads[i]
                val dx = s.position.x - pad.x
                val dz = s.position.z - pad.z
                if (dx * dx + dz * dz <= pad.radius * pad.radius && s.position.y < 0.6f) {
                    s.velocity.y = pad.impulseY
                    s.onGround = false
                    break
                }
            }
        }

        clampToBounds(s, arena)
    }

    /** True when the body could stand up at its current position. */
    fun hasHeadroom(s: BodyState, arena: ArenaDef): Boolean {
        bodyBox.setFromBody(s.position, GameConstants.PLAYER_RADIUS, GameConstants.PLAYER_HEIGHT)
        for (i in arena.collision.indices) {
            if (bodyBox.intersects(arena.collision[i])) return false
        }
        return true
    }

    /** True when a body of the given size fits at [pos] without overlapping geometry. */
    fun fits(pos: Vec3, arena: ArenaDef, height: Float = GameConstants.PLAYER_HEIGHT): Boolean {
        bodyBox.setFromBody(pos, GameConstants.PLAYER_RADIUS, height)
        for (i in arena.collision.indices) {
            if (bodyBox.intersects(arena.collision[i])) return false
        }
        return true
    }

    private fun moveX(s: BodyState, arena: ArenaDef, dx: Float) {
        if (dx == 0f) return
        s.position.x += dx
        bodyBox.setFromBody(s.position, GameConstants.PLAYER_RADIUS, s.height)
        val boxes = arena.collision
        for (i in boxes.indices) {
            val b = boxes[i]
            if (!bodyBox.intersects(b)) continue
            s.position.x = if (dx > 0f) {
                b.minX - GameConstants.PLAYER_RADIUS - EPSILON
            } else {
                b.maxX + GameConstants.PLAYER_RADIUS + EPSILON
            }
            s.velocity.x = 0f
            bodyBox.setFromBody(s.position, GameConstants.PLAYER_RADIUS, s.height)
        }
    }

    private fun moveZ(s: BodyState, arena: ArenaDef, dz: Float) {
        if (dz == 0f) return
        s.position.z += dz
        bodyBox.setFromBody(s.position, GameConstants.PLAYER_RADIUS, s.height)
        val boxes = arena.collision
        for (i in boxes.indices) {
            val b = boxes[i]
            if (!bodyBox.intersects(b)) continue
            s.position.z = if (dz > 0f) {
                b.minZ - GameConstants.PLAYER_RADIUS - EPSILON
            } else {
                b.maxZ + GameConstants.PLAYER_RADIUS + EPSILON
            }
            s.velocity.z = 0f
            bodyBox.setFromBody(s.position, GameConstants.PLAYER_RADIUS, s.height)
        }
    }

    private fun moveY(s: BodyState, arena: ArenaDef, dy: Float) {
        s.onGround = false
        s.position.y += dy
        bodyBox.setFromBody(s.position, GameConstants.PLAYER_RADIUS, s.height)
        val boxes = arena.collision
        for (i in boxes.indices) {
            val b = boxes[i]
            if (!bodyBox.intersects(b)) continue
            if (dy > 0f) {
                // Hit a ceiling.
                s.position.y = b.minY - s.height - EPSILON
                s.velocity.y = 0f
            } else {
                // Landed on top of a brush.
                s.position.y = b.maxY + EPSILON
                s.velocity.y = 0f
                s.onGround = true
            }
            bodyBox.setFromBody(s.position, GameConstants.PLAYER_RADIUS, s.height)
        }

        // Analytic ground plane.
        if (s.position.y <= 0f) {
            s.position.y = 0f
            if (s.velocity.y < 0f) s.velocity.y = 0f
            s.onGround = true
        }
    }

    private fun clampToBounds(s: BodyState, arena: ArenaDef) {
        val r = GameConstants.PLAYER_RADIUS
        s.position.x = MathUtil.clamp(s.position.x, arena.minX + r, arena.maxX - r)
        s.position.z = MathUtil.clamp(s.position.z, arena.minZ + r, arena.maxZ - r)
        if (s.position.y > 200f) s.position.y = 200f
    }

    companion object {
        /** Small separation kept between a body and a surface after resolution. */
        const val EPSILON: Float = 0.001f
    }
}
