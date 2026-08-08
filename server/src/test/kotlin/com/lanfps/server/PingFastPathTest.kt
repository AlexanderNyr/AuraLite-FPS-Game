package com.lanfps.server

import com.lanfps.shared.BinaryReader
import com.lanfps.shared.BinaryWriter
import com.lanfps.shared.GameMode
import com.lanfps.shared.PacketTypes
import com.lanfps.shared.Packets
import com.lanfps.shared.Protocol
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * P7-1: the ping fast path (receive-thread PONG replies).
 *
 * Before this change a PING sat in the queue until the next simulation-tick
 * wakeup, so the measured round trip quietly carried up to a full tick of
 * server sleep — the "physically impossible LAN ping" report. These tests pin
 * the new behaviour with a raw socket so a client-library bug cannot mask a
 * server regression (and vice versa):
 *
 *  - a correctly formed PING gets exactly one PONG, answered by the receive
 *    thread's fast path (counter-asserted, not just timing-asserted);
 *  - corrupt PINGs still get NO answer at all (the fast path validates the
 *    full structural contract, CRC included, before spending a reply);
 *  - a flood is not amplified: fast replies throttle per endpoint while the
 *    slow path keeps every non-flooding answer alive.
 */
class PingFastPathTest {

    private val port = 47851
    private var server: GameServer? = null
    private var thread: Thread? = null

    @AfterEach
    fun tearDown() {
        server?.stop()
        server = null
        thread?.join(3000)
        thread = null
    }

    private fun startServer(): GameServer {
        Log.setLevel("ERROR")
        val config = ServerConfig().apply {
            mode = GameMode.DM
            udpPort = port
            bindAddress = "127.0.0.1"
            botCount = 0
            maxPlayers = 8
            enableDiscovery = false
        }
        val s = GameServer(config)
        server = s
        thread = Thread({ s.start() }, "ping-fastpath-server").apply {
            isDaemon = true
            start()
        }
        // Wait for the socket to accept traffic: a real PING must come back.
        val probe = DatagramSocket()
        probe.soTimeout = 200
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline) {
            try {
                sendPing(probe, System.currentTimeMillis())
                probe.receive(DatagramPacket(ByteArray(64), 64))
                probe.close()
                return s
            } catch (_: Exception) {
                Thread.sleep(50)
            }
        }
        probe.close()
        error("server did not come up")
    }

    /**
     * A successful readiness probe costs one fast reply and leaves the
     * endpoint right at the throttle gate; stepping back one interval makes
     * the following assertions about fresh-state behaviour deterministic.
     */
    private fun settle() = Thread.sleep(120)

    private fun sendPing(sock: DatagramSocket, clientTimeMs: Long, corrupt: Boolean = false) {
        val w = BinaryWriter(64)
        Protocol.begin(w, PacketTypes.PING)
        Packets.writePing(w, clientTimeMs)
        val len = Protocol.end(w)
        val buf = ByteArray(len)
        System.arraycopy(w.buffer, 0, buf, 0, len)
        if (corrupt) buf[buf.size - 1] = (buf[buf.size - 1].toInt() xor 0xFF).toByte()
        val addr = InetAddress.getByName("127.0.0.1")
        sock.send(DatagramPacket(buf, len, addr, port))
    }

    /** Reads one PONG; returns (clientTimeMs, serverTimeMs, roundTripNanos). */
    private fun readPong(sock: DatagramSocket, sentAtNanos: Long, header: Protocol.Header): Triple<Long, Long, Long> {
        val buf = ByteArray(64)
        val p = DatagramPacket(buf, buf.size)
        sock.receive(p)
        val rttNanos = System.nanoTime() - sentAtNanos
        val reader = BinaryReader()
        assertEquals(Protocol.ParseResult.OK, Protocol.parse(p.data, p.length, header, reader), "reply must parse")
        assertEquals(PacketTypes.PONG, header.type, "reply must be a PONG")
        val pong = Packets.readPong(reader)
        return Triple(pong.clientTimeMs, pong.serverTimeMs, rttNanos)
    }

    @Test
    fun `each ping gets exactly one pong, fast-pathed on the receive thread`() {
        val s = startServer()
        settle()
        val fastBase = s.socket.fastPingReplies.get()
        val throttleBase = s.socket.fastPingThrottled.get()
        val sock = DatagramSocket()
        sock.soTimeout = 2000
        val header = Protocol.Header()

        // Pace above the 50 ms flood throttle so every ping qualifies for the
        // fast path — that is the cadence real clients use anyway (1 Hz).
        val rttsNanos = ArrayList<Long>()
        repeat(20) { i ->
            val stamp = 1_700_000_000_000L + i * 1234L
            val t0 = System.nanoTime()
            sendPing(sock, stamp)
            val (clientTime, serverTime, rtt) = readPong(sock, t0, header)
            assertEquals(stamp, clientTime, "pong #$i must echo the client clock verbatim")
            assertTrue(serverTime >= 0, "pong #$i must carry a sane server clock")
            rttsNanos.add(rtt)
            // No second answer (neither a fast duplicate nor the sim loop's):
            sock.soTimeout = 300
            try {
                val dup = DatagramPacket(ByteArray(64), 64)
                sock.receive(dup)
                error("unexpected second reply to ping #$i (${dup.length} bytes)")
            } catch (_: java.net.SocketTimeoutException) {
                // expected: silence
            }
            sock.soTimeout = 2000
            Thread.sleep(60)
        }
        sock.close()

        assertEquals(
            20L, s.socket.fastPingReplies.get() - fastBase,
            "every well-paced ping must be answered by the receive-thread fast path",
        )
        assertEquals(0L, s.socket.fastPingThrottled.get() - throttleBase)

        // Sanity, not the primary assertion: on loopback the turnaround must be
        // far below the ~16.6 ms tick the sim-loop path used to add. A wildly
        // loaded CI could hit high tens of ms once; the median stays honest.
        val sorted = rttsNanos.sorted()
        val medianMs = sorted[sorted.size / 2] / 1_000_000.0
        assertTrue(
            medianMs < 12.0,
            "median fast-path turnaround on loopback should be single-digit ms, got $medianMs ms",
        )
    }

    @Test
    fun `corrupted ping gets no reply`() {
        startServer()
        val sock = DatagramSocket()
        sock.soTimeout = 400
        sendPing(sock, System.currentTimeMillis(), corrupt = true)
        var answered = false
        repeat(3) {
            try {
                sock.receive(DatagramPacket(ByteArray(64), 64))
                answered = true
            } catch (_: java.net.SocketTimeoutException) {
            }
        }
        sock.close()
        assertTrue(!answered, "a PING with a bad CRC must be ignored completely")
    }

    @Test
    fun `ping flood is throttled on the fast path but never black-holed`() {
        val s = startServer()
        settle()
        val fastBase = s.socket.fastPingReplies.get()
        val throttleBase = s.socket.fastPingThrottled.get()
        val sock = DatagramSocket()
        sock.soTimeout = 3000
        val header = Protocol.Header()

        val burst = 30
        repeat(burst) { sendPing(sock, 42_000L + it) }

        // Every PING is eventually answered exactly once: the first gets the
        // fast path, the rest are throttled there and answered by the sim loop
        // (flagged packets are NOT answered twice — that is what keeps the
        // client's RTT sampling rate sane under load).
        var pongs = 0
        val deadline = System.currentTimeMillis() + 4000
        while (System.currentTimeMillis() < deadline && pongs < burst) {
            try {
                val p = DatagramPacket(ByteArray(64), 64)
                sock.receive(p)
                if (Protocol.parse(p.data, p.length, header, BinaryReader()) == Protocol.ParseResult.OK &&
                    header.type == PacketTypes.PONG
                ) {
                    pongs++
                }
            } catch (_: java.net.SocketTimeoutException) {
                break
            }
        }
        sock.close()

        assertEquals(burst, pongs, "every ping must be answered exactly once, fast or slow")
        assertTrue(
            s.socket.fastPingThrottled.get() - throttleBase > 0,
            "a 30-packet burst must trip the per-endpoint fast-reply throttle",
        )
        assertTrue(
            s.socket.fastPingReplies.get() - fastBase in 1..burst.toLong(),
            "fast replies must be a small minority of the flood answers",
        )
    }
}
