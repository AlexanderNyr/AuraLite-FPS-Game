package com.lanfps.server

import com.lanfps.shared.BinaryWriter
import com.lanfps.shared.EntityState
import com.lanfps.shared.GameConstants
import com.lanfps.shared.Packets
import com.lanfps.shared.Protocol
import com.lanfps.shared.Snapshot

/**
 * Serialises the world into a SERVER_SNAPSHOT datagram.
 *
 * The expensive part (walking every entity and encoding it) happens **once per
 * tick**, not once per client. The only per-client field is
 * `lastProcessedInputSeq`, which is patched into the finished buffer along with
 * a recomputed CRC before each send. With 8 clients that turns 8 full
 * serialisations into 1 + 8 cheap patches.
 */
class SnapshotBuilder {

    private val writer = BinaryWriter(8 * 1024)
    private val snapshot = Snapshot()

    /** Pool of entity-state objects so a 30 Hz broadcast allocates nothing. */
    private val statePool = ArrayList<EntityState>()

    /** Length in bytes of the datagram currently held in [buffer]. */
    @JvmField var length: Int = 0

    val buffer: ByteArray get() = writer.buffer

    /** Largest snapshot produced so far — logged to prove we stay under budget. */
    @JvmField var peakSize: Int = 0

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
        snapshot.lastProcessedInputSeq = 0 // patched per client
        snapshot.mode = world.mode.wire
        snapshot.matchState = match.state
        snapshot.matchTimeRemaining = match.timeRemaining
        snapshot.redScore = world.score.redScore
        snapshot.blueScore = world.score.blueScore

        var i = 0
        for (e in world.entities.values) {
            if (i >= MAX_ENTITIES_PER_SNAPSHOT) break
            while (statePool.size <= i) statePool.add(EntityState())
            val st = statePool[i]
            e.writeTo(st)
            snapshot.entities.add(st)
            i++
        }

        Protocol.begin(writer, com.lanfps.shared.PacketTypes.SERVER_SNAPSHOT, sequence)
        Packets.writeSnapshot(writer, snapshot)
        length = Protocol.end(writer)

        if (length > peakSize) peakSize = length
        // P0-1: a snapshot over the MTU-safe budget would fragment on Ethernet /
        // Wi-Fi and a single lost fragment would destroy the whole datagram.
        // Since names left the snapshot format this is now impossible on a real
        // population, so make it fail loudly instead of silently fragmenting.
        require(length <= GameConstants.SNAPSHOT_MAX_BYTES) {
            "snapshot is $length bytes, over the MTU-safe budget " +
                "${GameConstants.SNAPSHOT_MAX_BYTES}"
        }
        return length
    }

    /**
     * Stamps a client's own acknowledged input sequence into the shared buffer
     * and fixes the checksum. Must be called immediately before sending to that
     * client.
     */
    fun patchForClient(session: ClientSession) {
        Protocol.patchU16(
            writer.buffer,
            Packets.SNAPSHOT_LAST_INPUT_OFFSET,
            session.lastProcessedInputSeq and 0xFFFF,
        )
        Protocol.rechecksum(writer.buffer, length)
    }

    companion object {
        /** Wire format stores the count in one byte. */
        const val MAX_ENTITIES_PER_SNAPSHOT = 255
    }
}
