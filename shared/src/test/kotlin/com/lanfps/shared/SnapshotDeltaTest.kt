package com.lanfps.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P1-2: delta compression — diff/apply round-trip and serialisation.
 */
class SnapshotDeltaTest {

    private fun entity(id: Int, x: Float, health: Int = 100, alive: Boolean = true) =
        EntityState().apply {
            this.id = id
            this.x = x
            this.y = 0f
            this.z = 0f
            this.health = health
            this.alive = alive
            this.name = "E$id"
        }

    private fun baseOf(list: List<EntityState>): Map<Int, EntityState> {
        val m = HashMap<Int, EntityState>()
        for (e in list) m[e.id] = e
        return m
    }

    @Test
    fun `unchanged entities produce no delta`() {
        val base = baseOf(listOf(entity(1, 0f), entity(2, 5f)))
        val current = listOf(entity(1, 0f), entity(2, 5f))
        val delta = SnapshotDelta()
        SnapshotDelta.compute(base, current, delta)
        assertTrue(delta.changed.isEmpty())
        assertTrue(delta.removed.isEmpty())
    }

    @Test
    fun `changed and new entities are included, removed ids are reported`() {
        val base = baseOf(listOf(entity(1, 0f), entity(2, 5f), entity(3, 9f)))
        val current = listOf(
            entity(1, 0f),          // unchanged
            entity(2, 7f),          // moved
            entity(4, 1f, health = 50), // new
            // entity 3 removed
        )
        val delta = SnapshotDelta()
        SnapshotDelta.compute(base, current, delta)

        val changedIds = delta.changed.map { it.id }.toSet()
        assertEquals(setOf(2, 4), changedIds)
        assertEquals(listOf(3), delta.removed)
    }

    @Test
    fun `apply reconstructs the current state from base and delta`() {
        val base = listOf(entity(1, 0f), entity(2, 5f), entity(3, 9f))
        val current = listOf(entity(1, 0f), entity(2, 7f), entity(4, 1f, health = 50))
        val delta = SnapshotDelta()
        SnapshotDelta.compute(baseOf(base), current, delta)

        val out = ArrayList<EntityState>()
        SnapshotDelta.apply(base, delta, out)

        val byId = out.associateBy { it.id }
        assertEquals(3, out.size, "3 and 4: 3 removed, 4 added")
        assertEquals(7f, byId[2]!!.x)
        assertEquals(50, byId[4]!!.health)
        assertTrue(byId[3] == null, "removed id 3 must be gone")
    }

    @Test
    fun `delta round trips through the wire format`() {
        val base = listOf(entity(1, 0f), entity(2, 5f))
        val current = listOf(entity(1, 0f), entity(2, 8f), entity(9, 3f))
        val delta = SnapshotDelta()
        SnapshotDelta.compute(baseOf(base), current, delta)

        val w = BinaryWriter()
        delta.write(w)
        val decoded = SnapshotDelta().read(BinaryReader(w.toByteArray()))

        val changedIds = decoded.changed.map { it.id }.toSet()
        assertEquals(setOf(2, 9), changedIds)
        assertEquals(8f, decoded.changed.first { it.id == 2 }.x)
    }

    @Test
    fun `full and delta snapshot round trip through Packets`() {
        val snap = Snapshot().apply {
            serverTick = 10
            serverTimeMs = 20L
            mode = GameMode.DM.wire
            matchState = MatchState.ACTIVE
            kind = SnapshotKind.DELTA
            deltaChanged.add(entity(5, 3f))
            deltaRemoved.add(7)
        }
        val w = BinaryWriter()
        Protocol.begin(w, PacketTypes.SERVER_SNAPSHOT)
        Packets.writeSnapshot(w, snap)
        val len = Protocol.end(w)

        val reader = BinaryReader()
        val header = Protocol.Header()
        assertEquals(Protocol.ParseResult.OK, Protocol.parse(w.buffer, len, header, reader))
        val decoded = Packets.readSnapshot(reader)
        assertEquals(SnapshotKind.DELTA, decoded.kind)
        assertEquals(3f, decoded.deltaChanged.first().x)
        assertEquals(listOf(7), decoded.deltaRemoved)
    }
}
