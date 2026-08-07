package com.lanfps.shared

/**
 * One tick of player intent.
 *
 * Design note: the client does **not** send a delta-time. Every command is worth
 * exactly one fixed [GameConstants.TICK_DT] step on the server. This removes the
 * classic "lie about dt to move faster" exploit and makes client prediction
 * bit-comparable with the server simulation.
 *
 * Wire size: 2 + 4 + 4 + 4 + 4 + 1 + 1 = 20 bytes (+ 6 for the absolute time).
 */
class InputCommand {
    /** Monotonically increasing per client, wraps at 16 bits on the wire. */
    @JvmField var sequence: Int = 0

    /** Client clock when produced. Only used for diagnostics/RTT, never trusted. */
    @JvmField var clientTimeMs: Long = 0

    /** -1..1 */
    @JvmField var moveForward: Float = 0f

    /** -1..1 */
    @JvmField var moveRight: Float = 0f

    /** Absolute view angles in degrees; the client owns its aim. */
    @JvmField var yaw: Float = 0f
    @JvmField var pitch: Float = 0f

    /** Bitmask of [InputButtons]. */
    @JvmField var buttons: Int = 0

    @JvmField var weapon: Int = 0

    val firePressed: Boolean get() = (buttons and InputButtons.FIRE) != 0
    val jumpPressed: Boolean get() = (buttons and InputButtons.JUMP) != 0
    val crouchPressed: Boolean get() = (buttons and InputButtons.CROUCH) != 0

    fun clear(): InputCommand {
        sequence = 0
        clientTimeMs = 0
        moveForward = 0f
        moveRight = 0f
        yaw = 0f
        pitch = 0f
        buttons = 0
        weapon = 0
        return this
    }

    fun copyFrom(o: InputCommand): InputCommand {
        sequence = o.sequence
        clientTimeMs = o.clientTimeMs
        moveForward = o.moveForward
        moveRight = o.moveRight
        yaw = o.yaw
        pitch = o.pitch
        buttons = o.buttons
        weapon = o.weapon
        return this
    }

    fun copy(): InputCommand = InputCommand().copyFrom(this)

    /** Clamps analog axes so a tampered client cannot request >1 stick deflection. */
    fun sanitize(): InputCommand {
        moveForward = MathUtil.clamp(moveForward, -1f, 1f)
        moveRight = MathUtil.clamp(moveRight, -1f, 1f)
        // Keep the combined stick magnitude within the unit circle.
        val mag = kotlin.math.sqrt(moveForward * moveForward + moveRight * moveRight)
        if (mag > 1f) {
            moveForward /= mag
            moveRight /= mag
        }
        yaw = MathUtil.wrapDegrees(yaw)
        pitch = MathUtil.clamp(pitch, -GameConstants.MAX_PITCH_DEG, GameConstants.MAX_PITCH_DEG)
        if (!yaw.isFinite()) yaw = 0f
        if (!pitch.isFinite()) pitch = 0f
        if (!moveForward.isFinite()) moveForward = 0f
        if (!moveRight.isFinite()) moveRight = 0f
        return this
    }

    fun write(w: BinaryWriter) {
        w.writeU16(sequence and 0xFFFF)
        w.writeI32((clientTimeMs and 0xFFFFFFFFL).toInt())
        w.writeF32(moveForward)
        w.writeF32(moveRight)
        w.writeF32(yaw)
        w.writeF32(pitch)
        w.writeU8(buttons)
        w.writeU8(weapon)
    }

    fun read(r: BinaryReader): InputCommand {
        sequence = r.readU16()
        clientTimeMs = r.readI32().toLong() and 0xFFFFFFFFL
        moveForward = r.readF32()
        moveRight = r.readF32()
        yaw = r.readF32()
        pitch = r.readF32()
        buttons = r.readU8()
        weapon = r.readU8()
        return this
    }

    override fun toString(): String =
        "Input(seq=$sequence, fwd=%.2f, right=%.2f, yaw=%.1f, pitch=%.1f, btn=$buttons)"
            .format(moveForward, moveRight, yaw, pitch)

    companion object {
        /** Bytes on the wire per command. Keep in sync with [write]. */
        const val WIRE_SIZE: Int = 2 + 4 + 4 + 4 + 4 + 4 + 1 + 1

        /**
         * 16-bit sequence comparison that survives wraparound.
         * Returns true when [a] is newer than [b].
         */
        fun sequenceGreaterThan(a: Int, b: Int): Boolean {
            val ua = a and 0xFFFF
            val ub = b and 0xFFFF
            return ((ua > ub) && (ua - ub <= 32768)) || ((ub > ua) && (ub - ua > 32768))
        }
    }
}
