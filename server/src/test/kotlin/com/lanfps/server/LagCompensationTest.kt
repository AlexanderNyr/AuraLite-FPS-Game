package com.lanfps.server

import com.lanfps.shared.InputCommand
import com.lanfps.shared.MatchEventType
import com.lanfps.shared.Packets
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P1-1 + P1-4: unit tests for lag-compensation history and the reliable
 * match-event queue.
 */
class LagCompensationTest {

    // ------------------------------------------------------------ P1-1

    @Test
    fun `history returns positions as of N ticks ago`() {
        val history = PositionHistory(maxFrames = 10)
        val bot = BotEntity(1, "A").apply { alive = true }
        for (i in 0 until 5) {
            bot.body.position.set(i.toFloat(), 0f, 0f)
            history.record(listOf(bot))
        }
        // We recorded 5 frames (x = 0..4). 2 ticks ago from the newest (x=4) is x=2.
        val positions = history.positionsAtTicksAgo(2)
        assertTrue(positions != null)
        assertEquals(2f, positions!![1]!!.x)
    }

    @Test
    fun `history clamps to the oldest frame when ticks ago exceeds it`() {
        val history = PositionHistory(maxFrames = 4)
        val bot = BotEntity(1, "A").apply { alive = true }
        for (i in 0 until 3) {
            bot.body.position.set(i.toFloat(), 0f, 0f)
            history.record(listOf(bot))
        }
        val positions = history.positionsAtTicksAgo(100)!!
        assertEquals(0f, positions[1]!!.x)
    }

    // ------------------------------------------------------------ P1-4

    private fun event(seq: Int) = Packets.MatchEvent().apply {
        eventSeq = seq
        eventType = MatchEventType.KILL
        killerName = "K"
        victimName = "V"
    }

    @Test
    fun `acknowledging an event drains it and older ones from the queue`() {
        val session = ClientSession(
            7, InetAddress.getByName("127.0.0.1"), 9000, "Probe",
        )
        session.addPendingEvent(event(1))
        session.addPendingEvent(event(2))
        session.addPendingEvent(event(3))
        assertEquals(3, session.pendingEventCount)

        session.acknowledgeEvents(2)
        assertEquals(1, session.pendingEventCount, "events 1..2 should be acknowledged")
        assertEquals(3, session.drainPendingEvents().first().eventSeq)
    }

    @Test
    fun `acknowledging a sequence above all events empties the queue`() {
        val session = ClientSession(
            7, InetAddress.getByName("127.0.0.1"), 9000, "Probe",
        )
        session.addPendingEvent(event(5))
        session.addPendingEvent(event(6))
        session.acknowledgeEvents(6)
        assertEquals(0, session.pendingEventCount)
    }

    @Test
    fun `pending event queue is bounded`() {
        val session = ClientSession(
            7, InetAddress.getByName("127.0.0.1"), 9000, "Probe",
        )
        val cap = com.lanfps.shared.GameConstants.MAX_PENDING_MATCH_EVENTS
        for (i in 1..(cap + 20)) session.addPendingEvent(event(i))
        assertTrue(session.pendingEventCount <= cap, "queue must be capped at $cap")
    }

    @Test
    fun `sequence comparison handles event seq wraparound`() {
        // Sanity: the same 16-bit comparison the events rely on.
        assertTrue(InputCommand.sequenceGreaterThan(1, 65535))
    }
}
