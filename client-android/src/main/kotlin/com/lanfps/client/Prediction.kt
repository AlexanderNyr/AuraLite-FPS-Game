package com.lanfps.client

import com.lanfps.shared.ArenaDef
import com.lanfps.shared.BodyState
import com.lanfps.shared.EntityState
import com.lanfps.shared.GameConstants
import com.lanfps.shared.InputCommand
import com.lanfps.shared.MovementSolver
import com.lanfps.shared.Vec3
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Client-side prediction and server reconciliation for the local player.
 *
 * How it works:
 *  1. Every tick the local input is applied immediately with the **same**
 *     [MovementSolver] the server runs, so the camera answers the stick with
 *     zero latency. The command is kept in [pending].
 *  2. Each snapshot carries `lastProcessedInputSeq`, the newest command the
 *     server has actually simulated. We drop every acknowledged command, snap
 *     the body onto the authoritative state and replay the rest.
 *
 * Because both sides run identical code over identical inputs, replay normally
 * reproduces exactly what we already had and nothing visibly moves. When it
 * doesn't (we were shot, blocked by a player we could not see, or a packet was
 * lost) the difference is absorbed into [errorOffset] and faded out over ~120 ms
 * instead of teleporting the camera.
 */
class Prediction(private val arena: ArenaDef) {

    val body = BodyState()
    private val solver = MovementSolver()

    /** Unacknowledged commands, oldest first. */
    private val pending = ArrayList<InputCommand>(64)

    /** Residual visual error, decayed to zero over a few frames. */
    private val errorOffset = Vec3()

    /** Render position = simulated position + the decaying error. */
    private val renderPos = Vec3()

    @Volatile var lastAckedSeq: Int = -1; private set
    @Volatile var corrections: Int = 0; private set
    @Volatile var lastErrorMeters: Float = 0f; private set
    @Volatile var pendingCount: Int = 0; private set

    /** True once the server has told us where we are at least once. */
    @Volatile var initialised: Boolean = false; private set

    /** Set when the last reconcile was a hard snap (respawn / teleport). */
    @Volatile var hardSnaps: Int = 0; private set

    fun reset() {
        pending.clear()
        errorOffset.zero()
        body.reset()
        lastAckedSeq = -1
        corrections = 0
        hardSnaps = 0
        lastErrorMeters = 0f
        pendingCount = 0
        initialised = false
    }

    /** Places the body without prediction history (first snapshot, respawn). */
    fun teleportTo(state: EntityState) {
        body.position.set(state.x, state.y, state.z)
        body.velocity.set(state.vx, state.vy, state.vz)
        body.yaw = state.yaw
        body.pitch = state.pitch
        body.crouching = state.crouching
        body.onGround = state.y <= 0.02f
        pending.clear()
        errorOffset.zero()
        pendingCount = 0
        initialised = true
    }

    /** Simulates one command locally and remembers it until the server acks it. */
    fun applyLocal(cmd: InputCommand) {
        solver.step(body, cmd, arena, GameConstants.TICK_DT)
        pending.add(cmd)
        // ~1.3 s of history: far more than any sane LAN round trip, and it stops
        // the list growing without bound if the server goes away.
        while (pending.size > 80) pending.removeAt(0)
        pendingCount = pending.size
    }

    /**
     * Snaps to the authoritative state and replays everything the server has not
     * processed yet.
     *
     * @param authoritative our own entity as the server sees it
     * @param lastProcessedSeq newest input sequence the server has applied
     */
    fun reconcile(authoritative: EntityState, lastProcessedSeq: Int) {
        if (!initialised) {
            teleportTo(authoritative)
            lastAckedSeq = lastProcessedSeq
            return
        }

        // Remember where we thought we were, to measure the correction.
        val prevX = body.position.x
        val prevY = body.position.y
        val prevZ = body.position.z

        body.position.set(authoritative.x, authoritative.y, authoritative.z)
        body.velocity.set(authoritative.vx, authoritative.vy, authoritative.vz)
        body.crouching = authoritative.crouching
        // onGround is not replicated; the first replayed step recomputes it and
        // this starting guess only matters for a single frame.
        body.onGround = authoritative.y <= 0.02f || authoritative.vy == 0f

        // Drop every command the server has already simulated.
        var i = 0
        while (i < pending.size &&
            !InputCommand.sequenceGreaterThan(pending[i].sequence, lastProcessedSeq)
        ) {
            i++
        }
        if (i > 0) pending.subList(0, i).clear()
        lastAckedSeq = lastProcessedSeq
        pendingCount = pending.size

        // Replay the unacknowledged tail.
        for (j in pending.indices) {
            solver.step(body, pending[j], arena, GameConstants.TICK_DT)
        }

        // Fold the difference into the visual error so the camera does not jump.
        val dx = prevX - body.position.x
        val dy = prevY - body.position.y
        val dz = prevZ - body.position.z
        val err = sqrt(dx * dx + dy * dy + dz * dz)
        lastErrorMeters = err

        if (err > HARD_SNAP_METERS) {
            // Respawn, teleport, or we have been out of sync long enough that
            // smoothing would look worse than an honest cut.
            errorOffset.zero()
            hardSnaps++
        } else if (err > 0.0005f) {
            errorOffset.set(
                errorOffset.x + dx,
                errorOffset.y + dy,
                errorOffset.z + dz,
            )
            // Never let the visual lie by more than half a metre.
            val mag = errorOffset.length()
            if (mag > 0.5f) errorOffset.scale(0.5f / mag)
            corrections++
        }
    }

    /** Fades the residual error. Call once per client tick. */
    fun decayError(dt: Float) {
        if (errorOffset.lengthSquared() < 1e-8f) {
            errorOffset.zero()
            return
        }
        // Exponential decay with a ~120 ms time constant.
        val k = exp(-dt / 0.12f)
        errorOffset.scale(k)
    }

    /** Feet position to draw at, including the smoothing offset. */
    fun renderPosition(out: Vec3 = renderPos): Vec3 = out.set(
        body.position.x + errorOffset.x,
        body.position.y + errorOffset.y,
        body.position.z + errorOffset.z,
    )

    /** Eye position to place the camera at. */
    fun eyePosition(out: Vec3): Vec3 {
        renderPosition(out)
        out.y += body.eyeHeight
        return out
    }

    val horizontalSpeed: Float get() = body.velocity.horizontalLength()

    companion object {
        /** Above this the correction is cut instead of smoothed. */
        const val HARD_SNAP_METERS: Float = 1.5f
    }
}
