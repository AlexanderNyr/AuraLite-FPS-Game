package com.lanfps.server

import com.lanfps.shared.BinaryWriter
import com.lanfps.shared.EntityState
import com.lanfps.shared.GameConstants
import com.lanfps.shared.Packets
import com.lanfps.shared.Protocol
import com.lanfps.shared.Snapshot
import com.lanfps.shared.SnapshotDelta
import com.lanfps.shared.SnapshotKind

/**
 * Serialises the world into a SERVER_SNAPSHOT datagram, per client.
 *
 * P1-2 delta compression: every [DELTA_KEYFRAME_INTERVAL] snapshots (1 s) we
 * send a FULL snapshot (which also resets the client's base); the snapshots in
 * between are DELTAs listing only the entities whose state changed since the
 * client's last keyframe, plus removed ids. When [config.deltaCompression] is
 * off we always send FULL snapshots.
 *
 * [lastProcessedInputSeq] is per-recipient (it lets each client reconcile its
 * own prediction), so it is stamped directly per client.
 */
class SnapshotBuilder {

    private val writer = BinaryWriter(8 * 1024)
    private val snapshot = Snapshot()

    /** Pool of entity-state objects so a 30 Hz broadcast allocates nothing. */
    private val statePool = ArrayList<EntityState>()

    /** P4: pooled section buffers for pickups and grenades. */
    private val pickupPool = ArrayList<com.lanfps.shared.PickupState>()
    private val grenadePool = ArrayList<com.lanfps.shared.GrenadeState>()

    private val deltaScratch = SnapshotDelta()

    /** Length in bytes of the datagram currently held in [buffer]. */
    @JvmField var length: Int = 0

    val buffer: ByteArray get() = writer.buffer

    /** Largest snapshot produced so far — logged to prove we stay under budget. */
    @JvmField var peakSize: Int = 0

    /**
     * Per-client delta bookkeeping, keyed by player id (stable across a P0-2
     * reconnect on a new socket). Keyed by id also means a freshly-assigned
     * player with a recycled id simply gets a FULL first (keyframesUntil 0).
     */
    private class ClientDeltaState {
        var keyframesUntil = 0
        val base = HashMap<Int, EntityState>()
        var hasBase = false
    }

    private val clientStates = HashMap<Int, ClientDeltaState>()

    /**
     * Builds the datagram for one recipient. Callers must send immediately —
     * [buffer]/[length] are overwritten on the next call.
     */
    fun buildForClient(
        world: World,
        match: MatchController,
        tick: Int,
        serverTimeMs: Long,
        sequence: Int,
        session: ClientSession,
    ): Int {
        snapshot.entities.clear()
        snapshot.serverTick = tick
        snapshot.serverTimeMs = serverTimeMs
        snapshot.lastProcessedInputSeq = session.lastProcessedInputSeq and 0xFFFF
        snapshot.mode = world.mode.wire
        snapshot.matchState = match.state
        snapshot.matchTimeRemaining = match.timeRemaining
        snapshot.redScore = world.score.redScore
        snapshot.blueScore = world.score.blueScore
        snapshot.deltaChanged.clear()
        snapshot.deltaRemoved.clear()

        var i = 0
        for (e in world.entities.values) {
            if (i >= MAX_ENTITIES_PER_SNAPSHOT) break
            while (statePool.size <= i) statePool.add(EntityState())
            e.writeTo(statePool[i])
            snapshot.entities.add(statePool[i])
            i++
        }

        val st = clientStates.getOrPut(session.id) { ClientDeltaState() }
        val useDelta = world.config.deltaCompression && st.hasBase
        val sendFull = !useDelta || st.keyframesUntil <= 0

        if (sendFull) {
            snapshot.kind = SnapshotKind.FULL
            st.keyframesUntil = DELTA_KEYFRAME_INTERVAL
            st.hasBase = true
            copyIntoBase(st, snapshot.entities)
        } else {
            snapshot.kind = SnapshotKind.DELTA
            SnapshotDelta.compute(st.base, snapshot.entities, deltaScratch)
            snapshot.deltaChanged.addAll(deltaScratch.changed)
            snapshot.deltaRemoved.addAll(deltaScratch.removed)
            st.keyframesUntil--
            copyIntoBase(st, snapshot.entities)
        }

        world.pickups.snapshotTo(snapshot.pickups, pickupPool)
        world.grenades.snapshotTo(snapshot.grenades, grenadePool)

        Protocol.begin(writer, com.lanfps.shared.PacketTypes.SERVER_SNAPSHOT, sequence)
        Packets.writeSnapshot(writer, snapshot)
        length = Protocol.end(writer)

        if (length > peakSize) peakSize = length
        // P0-1: never over the MTU-safe budget (would fragment and lose whole
        // snapshots). Names left the format so this is now impossible on a real
        // population; fail loudly rather than silently fragment.
        require(length <= GameConstants.SNAPSHOT_MAX_BYTES) {
            "snapshot is $length bytes, over the MTU-safe budget " +
                "${GameConstants.SNAPSHOT_MAX_BYTES}"
        }
        return length
    }

    /**
     * Builds a full snapshot for tests/headless clients that don't track delta
     * state (e.g. the TestClient, which reconstructs nothing and wants full
     * states). This is also what a delta-disabled server effectively sends.
     */
    fun build(
        world: World,
        match: MatchController,
        tick: Int,
        serverTimeMs: Long,
        sequence: Int,
    ): Int {
        snapshot.entities.clear()
        snapshot.serverTick = tick
        snapshot.serverTimeMs = serverTimeMs
        snapshot.lastProcessedInputSeq = 0
        snapshot.mode = world.mode.wire
        snapshot.matchState = match.state
        snapshot.matchTimeRemaining = match.timeRemaining
        snapshot.redScore = world.score.redScore
        snapshot.blueScore = world.score.blueScore
        snapshot.kind = SnapshotKind.FULL
        snapshot.deltaChanged.clear()
        snapshot.deltaRemoved.clear()

        var i = 0
        for (e in world.entities.values) {
            if (i >= MAX_ENTITIES_PER_SNAPSHOT) break
            while (statePool.size <= i) statePool.add(EntityState())
            e.writeTo(statePool[i])
            snapshot.entities.add(statePool[i])
            i++
        }

        world.pickups.snapshotTo(snapshot.pickups, pickupPool)
        world.grenades.snapshotTo(snapshot.grenades, grenadePool)

        Protocol.begin(writer, com.lanfps.shared.PacketTypes.SERVER_SNAPSHOT, sequence)
        Packets.writeSnapshot(writer, snapshot)
        length = Protocol.end(writer)

        if (length > peakSize) peakSize = length
        require(length <= GameConstants.SNAPSHOT_MAX_BYTES) {
            "snapshot is $length bytes, over the MTU-safe budget " +
                "${GameConstants.SNAPSHOT_MAX_BYTES}"
        }
        return length
    }

    private fun copyIntoBase(state: ClientDeltaState, entities: List<EntityState>) {
        state.base.clear()
        for (e in entities) state.base[e.id] = e.copy()
    }

    companion object {
        /** Wire format stores the count in one byte. */
        const val MAX_ENTITIES_PER_SNAPSHOT = 255

        /** Send a FULL snapshot this often (snapshots) — 1 second at 30 Hz. */
        const val DELTA_KEYFRAME_INTERVAL = 30
    }
}
