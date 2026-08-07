package com.lanfps.client

import com.lanfps.shared.ArenaDef
import com.lanfps.shared.BodyState
import com.lanfps.shared.EntityState
import com.lanfps.shared.GameConstants
import com.lanfps.shared.InputButtons
import com.lanfps.shared.InputCommand
import com.lanfps.shared.MovementSolver
import com.lanfps.shared.Snapshot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * JVM tests for the two pieces of client logic that are pure maths and
 * therefore the two that are worth testing without a phone: entity
 * interpolation and prediction/reconciliation.
 *
 * Neither class touches an Android API, so these run as ordinary unit tests
 * (`:client-android:testDebugUnitTest`).
 */
class ClientLogicTest {

    private val arena: ArenaDef = ArenaDef.builtinArena01()

    // ------------------------------------------------------------- helpers

    private fun entity(
        id: Int,
        x: Float,
        y: Float = 0f,
        z: Float = 0f,
        yaw: Float = 0f,
        alive: Boolean = true,
        health: Int = 100,
    ) = EntityState().apply {
        this.id = id
        this.x = x; this.y = y; this.z = z
        this.yaw = yaw
        this.health = health
        this.alive = alive
        this.name = "E$id"
    }

    private fun snapshot(tick: Int, serverTimeMs: Long, vararg entities: EntityState) =
        Snapshot().apply {
            serverTick = tick
            this.serverTimeMs = serverTimeMs
            for (e in entities) this.entities.add(e)
        }

    private fun cmd(
        seq: Int,
        forward: Float = 0f,
        right: Float = 0f,
        yaw: Float = 0f,
        pitch: Float = 0f,
        buttons: Int = 0,
    ) = InputCommand().apply {
        sequence = seq
        moveForward = forward
        moveRight = right
        this.yaw = yaw
        this.pitch = pitch
        this.buttons = buttons
    }

    // ------------------------------------------------------- SnapshotBuffer

    @Test
    @DisplayName("interpolation returns the midpoint between two snapshots")
    fun interpolatesBetweenSnapshots() {
        val buf = SnapshotBuffer()
        // serverTime = localTime + 1000 -> clock offset is exactly 1000 ms.
        buf.add(snapshot(1, 10_000, entity(7, x = 0f, z = 0f)), 9_000)
        buf.add(snapshot(2, 10_100, entity(7, x = 10f, z = 4f)), 9_100)

        // renderServerTime = localNow + 1000 - 90. We want 10_050 -> localNow 9_140.
        assertEquals(10_050L, buf.renderServerTime(9_140))

        val out = ArrayList<EntityState>()
        val pool = ArrayList<EntityState>()
        assertTrue(buf.sampleInto(out, pool, 9_140))
        assertEquals(1, out.size)
        assertEquals(5f, out[0].x, 0.001f, "x should be halfway")
        assertEquals(2f, out[0].z, 0.001f, "z should be halfway")
    }

    @Test
    @DisplayName("render time past the newest snapshot freezes instead of extrapolating")
    fun freezesInsteadOfExtrapolating() {
        val buf = SnapshotBuffer()
        buf.add(snapshot(1, 10_000, entity(7, x = 0f)), 9_000)
        buf.add(snapshot(2, 10_100, entity(7, x = 10f)), 9_100)

        val out = ArrayList<EntityState>()
        val pool = ArrayList<EntityState>()
        // Way past the newest snapshot: a naive extrapolation would shoot the
        // entity through a wall; we must clamp to the last known state.
        assertTrue(buf.sampleInto(out, pool, 12_000))
        assertEquals(10f, out[0].x, 0.001f)
    }

    @Test
    @DisplayName("render time before the oldest snapshot clamps to the oldest")
    fun clampsToOldest() {
        val buf = SnapshotBuffer()
        buf.add(snapshot(1, 10_000, entity(7, x = 3f)), 9_000)
        val out = ArrayList<EntityState>()
        val pool = ArrayList<EntityState>()
        assertTrue(buf.sampleInto(out, pool, 8_000))
        assertEquals(3f, out[0].x, 0.001f)
    }

    @Test
    @DisplayName("out-of-order snapshots are re-sorted and duplicates dropped")
    fun handlesOutOfOrderAndDuplicates() {
        val buf = SnapshotBuffer()
        buf.add(snapshot(1, 10_000, entity(7, x = 0f)), 9_000)
        buf.add(snapshot(3, 10_200, entity(7, x = 20f)), 9_200)
        // Arrives late, belongs in the middle.
        buf.add(snapshot(2, 10_100, entity(7, x = 10f)), 9_205)
        // Exact duplicate of tick 2: must be ignored.
        buf.add(snapshot(2, 10_100, entity(7, x = 999f)), 9_206)

        assertEquals(2, buf.outOfOrderCount)
        assertEquals(4, buf.receivedCount)

        val out = ArrayList<EntityState>()
        val pool = ArrayList<EntityState>()
        // Target 10_150 -> halfway between tick 2 (10_100, x=10) and tick 3 (10_200, x=20).
        buf.sampleInto(out, pool, 9_240)
        assertEquals(15f, out[0].x, 0.001f)
    }

    @Test
    @DisplayName("a one-tick firing flag survives interpolation")
    fun keepsFiringPulse() {
        val buf = SnapshotBuffer()
        buf.add(snapshot(1, 10_000, entity(7, x = 0f).apply { firing = true }), 9_000)
        buf.add(snapshot(2, 10_100, entity(7, x = 1f).apply { firing = false }), 9_100)

        val out = ArrayList<EntityState>()
        val pool = ArrayList<EntityState>()
        buf.sampleInto(out, pool, 9_140)
        assertTrue(out[0].firing, "muzzle flash must not be swallowed by interpolation")
    }

    @Test
    @DisplayName("latest snapshot is exposed for the HUD and scoreboard")
    fun exposesLatest() {
        val buf = SnapshotBuffer()
        assertEquals(null, buf.latest)
        buf.add(snapshot(1, 10_000, entity(7, x = 0f)), 9_000)
        buf.add(snapshot(2, 10_100, entity(7, x = 1f)), 9_100)
        assertNotNull(buf.latest)
        assertEquals(2, buf.latest!!.serverTick)
        buf.clear()
        assertEquals(null, buf.latest)
    }

    // ----------------------------------------------------------- Prediction

    /**
     * Builds a start body the way [Prediction.teleportTo] reconstructs one from
     * a snapshot. `onGround` is not on the wire (it is derived, not replicated),
     * so both sides must derive it with the same rule or the very first step
     * diverges: ground friction and full acceleration versus air control.
     */
    private fun startBody(x: Float, y: Float, z: Float) = BodyState().apply {
        position.set(x, y, z)
        onGround = y <= 0.02f
    }

    /** Runs the authoritative simulation the way the server does. */
    private fun simulate(start: BodyState, commands: List<InputCommand>): BodyState {
        val solver = MovementSolver()
        val body = BodyState().copyFrom(start)
        for (c in commands) solver.step(body, c, arena, GameConstants.TICK_DT)
        return body
    }

    private fun asEntity(body: BodyState, id: Int = 1) = EntityState().apply {
        this.id = id
        x = body.position.x; y = body.position.y; z = body.position.z
        vx = body.velocity.x; vy = body.velocity.y; vz = body.velocity.z
        yaw = body.yaw; pitch = body.pitch
        alive = true
        crouching = body.crouching
        health = 100
    }

    @Test
    @DisplayName("local prediction reproduces the server simulation exactly")
    fun predictionMatchesServer() {
        val start = startBody(0f, 0f, 12f)
        val commands = (1..30).map { cmd(it, forward = 1f, yaw = 25f) }

        val server = simulate(start, commands)

        val pred = Prediction(arena)
        pred.teleportTo(asEntity(start))
        for (c in commands) pred.applyLocal(c)

        assertEquals(server.position.x, pred.body.position.x, 1e-4f)
        assertEquals(server.position.y, pred.body.position.y, 1e-4f)
        assertEquals(server.position.z, pred.body.position.z, 1e-4f)
        assertEquals(30, pred.pendingCount, "nothing acked yet, all 30 are pending")
    }

    @Test
    @DisplayName("a fully acknowledged, agreeing snapshot causes no correction")
    fun reconcileWithFullAckIsSilent() {
        val start = startBody(0f, 0f, 12f)
        val commands = (1..10).map { cmd(it, forward = 1f) }

        val pred = Prediction(arena)
        pred.teleportTo(asEntity(start))
        for (c in commands) pred.applyLocal(c)

        val server = simulate(start, commands)
        pred.reconcile(asEntity(server), lastProcessedSeq = 10)

        assertEquals(0, pred.pendingCount, "every command was acknowledged")
        assertEquals(10, pred.lastAckedSeq)
        assertTrue(pred.lastErrorMeters < 1e-4f, "error was ${pred.lastErrorMeters}")
        assertEquals(0, pred.corrections)
        assertEquals(0, pred.hardSnaps)
    }

    @Test
    @DisplayName("a partially acknowledged snapshot replays the unacked tail")
    fun reconcileReplaysUnackedCommands() {
        val start = startBody(-10f, 0f, 12f)
        val commands = (1..12).map { cmd(it, forward = 1f, right = 0.4f, yaw = 70f) }

        val pred = Prediction(arena)
        pred.teleportTo(asEntity(start))
        for (c in commands) pred.applyLocal(c)

        // The server is 5 commands behind (typical on a real link).
        val serverBody = simulate(start, commands.subList(0, 7))
        pred.reconcile(asEntity(serverBody), lastProcessedSeq = 7)

        assertEquals(5, pred.pendingCount, "commands 8..12 stay pending")

        // After replay we must be exactly where the server will end up.
        val expected = simulate(start, commands)
        assertEquals(expected.position.x, pred.body.position.x, 1e-4f)
        assertEquals(expected.position.y, pred.body.position.y, 1e-4f)
        assertEquals(expected.position.z, pred.body.position.z, 1e-4f)
        assertTrue(pred.lastErrorMeters < 1e-3f, "replay should land on the same spot")
    }

    @Test
    @DisplayName("a large disagreement snaps hard instead of sliding")
    fun bigDisagreementSnaps() {
        val start = startBody(0f, 0f, 12f)
        val pred = Prediction(arena)
        pred.teleportTo(asEntity(start))
        for (i in 1..5) pred.applyLocal(cmd(i, forward = 1f))

        // Server says we are 12 m away (respawn / teleport).
        val far = startBody(12f, 0f, 12f)
        pred.reconcile(asEntity(far), lastProcessedSeq = 5)

        assertEquals(1, pred.hardSnaps)
        assertTrue(pred.lastErrorMeters > Prediction.HARD_SNAP_METERS)
        // No residual offset: the render position is the authoritative one.
        val r = pred.renderPosition()
        assertEquals(pred.body.position.x, r.x, 1e-5f)
        assertEquals(pred.body.position.z, r.z, 1e-5f)
    }

    @Test
    @DisplayName("a small disagreement is smoothed away, never teleported")
    fun smallDisagreementIsSmoothed() {
        val start = startBody(0f, 0f, 12f)
        val pred = Prediction(arena)
        pred.teleportTo(asEntity(start))
        for (i in 1..5) pred.applyLocal(cmd(i, forward = 1f))

        val nudged = BodyState().copyFrom(pred.body).apply { position.x += 0.20f }
        pred.reconcile(asEntity(nudged), lastProcessedSeq = 5)

        assertEquals(1, pred.corrections)
        assertEquals(0, pred.hardSnaps)

        // Immediately after, the drawn position still lags the authoritative one:
        // that is the whole point - no visible jump.
        val before = pred.renderPosition().copy()
        assertTrue(
            abs(before.x - pred.body.position.x) > 0.05f,
            "expected a visible smoothing offset, got ${abs(before.x - pred.body.position.x)}",
        )

        // ...and it fades out within a few hundred milliseconds.
        repeat(60) { pred.decayError(GameConstants.TICK_DT) }
        val after = pred.renderPosition()
        assertTrue(
            abs(after.x - pred.body.position.x) < 0.01f,
            "offset should have decayed, still ${abs(after.x - pred.body.position.x)}",
        )
    }

    @Test
    @DisplayName("the smoothing offset is clamped so the view can never lie badly")
    fun smoothingOffsetIsClamped() {
        val start = startBody(0f, 0f, 12f)
        val pred = Prediction(arena)
        pred.teleportTo(asEntity(start))

        // Ten consecutive small corrections in the same direction.
        repeat(10) {
            pred.applyLocal(cmd(it + 1, forward = 1f))
            val nudged = BodyState().copyFrom(pred.body).apply { position.x += 0.14f }
            pred.reconcile(asEntity(nudged), lastProcessedSeq = it + 1)
        }

        val r = pred.renderPosition()
        val dx = abs(r.x - pred.body.position.x)
        val dy = abs(r.y - pred.body.position.y)
        val dz = abs(r.z - pred.body.position.z)
        val mag = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
        assertTrue(mag <= 0.5f + 1e-4f, "offset must stay clamped at 0.5 m, was $mag")
    }

    @Test
    @DisplayName("prediction obeys arena collision exactly like the server")
    fun predictionRespectsWalls() {
        // Run straight at the west wall (arena minX = -30).
        val start = startBody(-27f, 0f, 0f)
        val commands = (1..120).map { cmd(it, forward = 1f, yaw = -90f) }

        val pred = Prediction(arena)
        pred.teleportTo(asEntity(start))
        for (c in commands) pred.applyLocal(c)

        assertTrue(
            pred.body.position.x >= arena.minX + GameConstants.PLAYER_RADIUS - 0.01f,
            "walked through the west wall to ${pred.body.position.x}",
        )
        val server = simulate(start, commands)
        assertEquals(server.position.x, pred.body.position.x, 1e-4f)
    }

    @Test
    @DisplayName("jump is predicted identically to the server")
    fun jumpMatchesServer() {
        val start = startBody(0f, 0f, 12f)
        val commands = ArrayList<InputCommand>()
        commands.add(cmd(1, buttons = InputButtons.JUMP))
        for (i in 2..40) commands.add(cmd(i))

        val pred = Prediction(arena)
        pred.teleportTo(asEntity(start))
        for (c in commands) pred.applyLocal(c)

        val server = simulate(start, commands)
        assertEquals(server.position.y, pred.body.position.y, 1e-4f)
        assertTrue(pred.body.onGround, "should have landed again after 40 ticks")
    }

    @Test
    @DisplayName("reset clears every trace of the previous session")
    fun resetIsClean() {
        val pred = Prediction(arena)
        pred.teleportTo(entity(1, x = 5f))
        pred.applyLocal(cmd(1, forward = 1f))
        assertTrue(pred.initialised)

        pred.reset()
        assertFalse(pred.initialised)
        assertEquals(0, pred.pendingCount)
        assertEquals(-1, pred.lastAckedSeq)
        assertEquals(0f, pred.body.position.x, 1e-6f)
    }
}
