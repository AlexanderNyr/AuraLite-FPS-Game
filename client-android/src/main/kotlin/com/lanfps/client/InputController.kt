package com.lanfps.client

import com.lanfps.shared.GameConstants
import com.lanfps.shared.InputButtons
import com.lanfps.shared.InputCommand
import com.lanfps.shared.MathUtil

/**
 * The bridge between the touch layer (UI thread) and the network tick
 * (network thread).
 *
 * [TouchControlsView] pushes raw intent in here; [NetworkClient] samples it 60
 * times a second into an [InputCommand]. Look deltas are *accumulated* rather
 * than sampled, so a fast flick between two ticks is never dropped - it is
 * folded into the next command instead.
 */
class InputController {

    // ---- movement stick (-1..1) -------------------------------------------
    @Volatile var moveForward: Float = 0f
    @Volatile var moveRight: Float = 0f

    // ---- buttons -----------------------------------------------------------
    @Volatile var firing: Boolean = false
    @Volatile var jumpQueued: Boolean = false
    @Volatile var crouching: Boolean = false

    // ---- view --------------------------------------------------------------
    /** Absolute view angles, owned by the client. */
    @Volatile var yaw: Float = 0f
        private set

    @Volatile var pitch: Float = 0f
        private set

    /** Degrees per dp of finger travel. */
    @Volatile var sensitivity: Float = DEFAULT_SENSITIVITY

    @Volatile var invertY: Boolean = false

    private val lookLock = Any()
    private var pendingYaw = 0f
    private var pendingPitch = 0f

    private var sequence = 0

    /** Called from the touch thread with finger travel in dp. */
    fun addLook(dxDp: Float, dyDp: Float) {
        synchronized(lookLock) {
            pendingYaw += dxDp * sensitivity
            pendingPitch += (if (invertY) dyDp else -dyDp) * sensitivity
        }
    }

    /** Direct set, used when restoring a spawn orientation. */
    fun setAngles(newYaw: Float, newPitch: Float) {
        synchronized(lookLock) {
            pendingYaw = 0f
            pendingPitch = 0f
        }
        yaw = MathUtil.wrapDegrees(newYaw)
        pitch = MathUtil.clamp(newPitch, -GameConstants.MAX_PITCH_DEG, GameConstants.MAX_PITCH_DEG)
    }

    fun releaseAll() {
        moveForward = 0f
        moveRight = 0f
        firing = false
        crouching = false
        jumpQueued = false
    }

    /**
     * Produces the command for this tick. Consumes the accumulated look delta
     * and the queued jump.
     */
    fun sample(out: InputCommand, nowMs: Long): InputCommand {
        val dy: Float
        val dp: Float
        synchronized(lookLock) {
            dy = pendingYaw; pendingYaw = 0f
            dp = pendingPitch; pendingPitch = 0f
        }
        yaw = MathUtil.wrapDegrees(yaw + dy)
        pitch = MathUtil.clamp(
            pitch + dp,
            -GameConstants.MAX_PITCH_DEG,
            GameConstants.MAX_PITCH_DEG,
        )

        sequence = (sequence + 1) and 0xFFFF

        out.clear()
        out.sequence = sequence
        out.clientTimeMs = nowMs
        out.moveForward = moveForward
        out.moveRight = moveRight
        out.yaw = yaw
        out.pitch = pitch

        var buttons = 0
        if (firing) buttons = buttons or InputButtons.FIRE
        if (crouching) buttons = buttons or InputButtons.CROUCH
        if (jumpQueued) {
            buttons = buttons or InputButtons.JUMP
            // A jump tap must survive exactly one tick: consume it here so a
            // single tap can never turn into a bunny-hop the player did not ask
            // for, and can never be missed either.
            jumpQueued = false
        }
        out.buttons = buttons
        out.weapon = 0
        return out.sanitize()
    }

    /** Zeroed command with the current view angles - used in the lobby. */
    fun sampleIdle(out: InputCommand, nowMs: Long): InputCommand {
        sequence = (sequence + 1) and 0xFFFF
        out.clear()
        out.sequence = sequence
        out.clientTimeMs = nowMs
        out.yaw = yaw
        out.pitch = pitch
        return out.sanitize()
    }

    companion object {
        const val DEFAULT_SENSITIVITY: Float = 0.32f
        const val MIN_SENSITIVITY: Float = 0.08f
        const val MAX_SENSITIVITY: Float = 0.90f
    }
}
