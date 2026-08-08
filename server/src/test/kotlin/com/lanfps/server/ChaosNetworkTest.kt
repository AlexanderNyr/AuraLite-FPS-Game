package com.lanfps.server

import com.lanfps.server.tools.ChaosProxy
import com.lanfps.shared.BinaryReader
import com.lanfps.shared.BinaryWriter
import com.lanfps.shared.GameConstants
import com.lanfps.shared.GameMode
import com.lanfps.shared.InputCommand
import com.lanfps.shared.PacketTypes
import com.lanfps.shared.Packets
import com.lanfps.shared.Protocol
import com.lanfps.shared.Snapshot
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * P3-1: the protocol must survive a hostile LAN.
 *
 * A real [GameServer] and a scripted UDP client are wired through a
 * [ChaosProxy] that drops, delays, jitters and reorders datagrams in both
 * directions. The assertions are the plan's acceptance criteria made
 * executable: the client can still connect, snapshots keep arriving at a sane
 * rate, the client's inputs keep being applied (convergence — the acked input
 * sequence tracks what we sent), and the server never crashes.
 */
class ChaosNetworkTest {

    private var server: GameServer? = null
    private var serverThread: Thread? = null
    private var proxy: ChaosProxy? = null
    private var client: DatagramSocket? = null

    @AfterTest
    fun tearDown() {
        try {
            client?.close()
        } catch (_: Exception) {
        }
        try {
            proxy?.close()
        } catch (_: Exception) {
        }
        server?.stop()
        serverThread?.join(4000)
    }

    private fun startServer(port: Int): GameServer {
        Log.setLevel("ERROR")
        val config = ServerConfig().apply {
            mode = GameMode.DM
            udpPort = port
            bindAddress = "127.0.0.1"
            botCount = 2
            maxPlayers = 8
        }
        val s = GameServer(config)
        server = s
        serverThread = Thread({ s.start() }, "chaos-server").apply {
            isDaemon = true
            start()
        }
        Thread.sleep(700) // bind before the proxy is aimed at us
        return s
    }

    /** Connects through the proxy, retrying until the handshake survives. */
    private fun connect(proxyPort: Int, nick: String): Packets.ConnectAccepted {
        val sock = client!!
        val addr = InetAddress.getByName("127.0.0.1")
        val w = BinaryWriter(1024)
        val request = Packets.ConnectRequest().apply {
            nickname = nick
            preferredMode = GameMode.DM.wire
            clientTimeMs = System.currentTimeMillis()
        }
        val header = Protocol.Header()
        val reader = BinaryReader()
        val buf = ByteArray(4096)

        repeat(30) { attempt ->
            Protocol.begin(w, PacketTypes.CONNECT_REQUEST, attempt)
            Packets.writeConnectRequest(w, request)
            val len = Protocol.end(w)
            sock.send(DatagramPacket(w.buffer, len, addr, proxyPort))

            // Generous window: worst case the round trip is 2x(latency+jitter),
            // and a loss simply costs us one attempt.
            val deadline = System.currentTimeMillis() + 900
            while (System.currentTimeMillis() < deadline) {
                val dp = DatagramPacket(buf, buf.size)
                try {
                    sock.receive(dp)
                } catch (_: Exception) {
                    break
                }
                if (Protocol.parse(dp.data, dp.length, header, reader) != Protocol.ParseResult.OK) continue
                if (header.type == PacketTypes.CONNECT_ACCEPTED) {
                    return Packets.readConnectAccepted(reader)
                }
            }
        }
        throw AssertionError("handshake never completed through the chaos proxy")
    }

    private fun runScenario(
        label: String,
        serverPort: Int,
        proxyPort: Int,
        loss: Double,
        latencyMs: Int,
        jitterMs: Int,
        reorder: Double,
        seconds: Int,
        minSnapshotRatio: Double,
    ) {
        startServer(serverPort)
        proxy = ChaosProxy(
            listenPort = proxyPort,
            targetHost = "127.0.0.1",
            targetPort = serverPort,
            loss = loss,
            latencyMs = latencyMs,
            jitterMs = jitterMs,
            reorder = reorder,
        ).start()

        val sock = DatagramSocket()
        client = sock
        sock.soTimeout = 120

        val accepted = connect(proxyPort, "ChaosMonkey")
        assertTrue(accepted.playerId > 0, "$label: handshake produced a player id")

        // ---- data phase: stream inputs, count what comes back ---------------
        val addr = InetAddress.getByName("127.0.0.1")
        val w = BinaryWriter(1024)
        val header = Protocol.Header()
        val reader = BinaryReader()
        val buf = ByteArray(8192)
        val snapshot = Snapshot()

        var seq = 1
        var snapshots = 0
        var fullSnapshots = 0
        var lastAckedInput = 0
        var goodbye = false

        val endAt = System.currentTimeMillis() + seconds * 1000L
        while (System.currentTimeMillis() < endAt && !goodbye) {
            // One packet per ~33 ms carrying the newest command plus a
            // redundant copy of the previous one, exactly like the real client.
            val commands = ArrayList<InputCommand>()
            for (back in 1 downTo 0) {
                val s = seq - back
                if (s < 1) continue
                commands.add(
                    InputCommand().apply {
                        sequence = s
                        yaw = 45f
                        pitch = 0f
                        moveForward = 1f
                        moveRight = 0f
                        buttons = 0
                        weapon = 0
                    },
                )
            }
            seq++
            Protocol.begin(w, PacketTypes.CLIENT_INPUT, seq and 0xFFFF)
            Packets.writeClientInput(w, accepted.playerId, 0, commands)
            val len = Protocol.end(w)
            sock.send(DatagramPacket(w.buffer, len, addr, proxyPort))

            // Drain everything that arrived in this 33 ms window.
            val windowEnd = System.currentTimeMillis() + 33
            while (System.currentTimeMillis() < windowEnd) {
                val dp = DatagramPacket(buf, buf.size)
                try {
                    sock.receive(dp)
                } catch (_: Exception) {
                    break
                }
                if (Protocol.parse(dp.data, dp.length, header, reader) != Protocol.ParseResult.OK) continue
                when (header.type) {
                    PacketTypes.SERVER_SNAPSHOT -> {
                        Packets.readSnapshot(reader, snapshot)
                        snapshots++
                        if (snapshot.kind == com.lanfps.shared.SnapshotKind.FULL) fullSnapshots++
                        if (com.lanfps.shared.InputCommand.sequenceGreaterThan(
                                snapshot.lastProcessedInputSeq, lastAckedInput,
                            )
                        ) {
                            lastAckedInput = snapshot.lastProcessedInputSeq
                        }
                    }
                    PacketTypes.DISCONNECT -> goodbye = true
                    else -> {}
                }
            }
        }

        assertTrue(!goodbye, "$label: the server kicked us out mid-test")
        assertTrue(
            serverThread!!.isAlive, "$label: server thread died under chaos conditions",
        )

        // Snapshots: at 30 Hz minus the downlink loss they must keep flowing.
        val expected = GameConstants.SNAPSHOT_RATE * seconds
        assertTrue(
            snapshots >= expected * minSnapshotRatio,
            "$label: too few snapshots ($snapshots < ${expected * minSnapshotRatio} " +
                "of $expected expected) — ${proxy?.describe()}",
        )
        assertTrue(
            fullSnapshots >= seconds / 2,
            "$label: keyframes must keep arriving (got $fullSnapshots in ${seconds}s)",
        )
        // Convergence: despite the lossy uplink the server applied most of our
        // stream (it sends at 60 Hz; we sample the ack at ~30 Hz plus loss).
        assertTrue(
            lastAckedInput >= seq * 0.6,
            "$label: input stream did not converge " +
                "(acked $lastAckedInput of $seq sent) — ${proxy?.describe()}",
        )
    }

    @Test
    fun `clean reference run - low latency, no loss`() {
        runScenario(
            "clean", serverPort = 47841, proxyPort = 47842,
            loss = 0.0, latencyMs = 10, jitterMs = 0, reorder = 0.0,
            seconds = 5, minSnapshotRatio = 0.8,
        )
    }

    @Test
    fun `moderate wifi - 10 percent loss, 60 ms latency, 40 ms jitter`() {
        runScenario(
            "moderate", serverPort = 47843, proxyPort = 47844,
            loss = 0.10, latencyMs = 60, jitterMs = 40, reorder = 0.05,
            seconds = 6, minSnapshotRatio = 0.5,
        )
    }

    @Test
    fun `hostile network - 20 percent loss, 120 ms latency, jitter and reordering`() {
        runScenario(
            "hostile", serverPort = 47845, proxyPort = 47846,
            loss = 0.20, latencyMs = 120, jitterMs = 60, reorder = 0.15,
            seconds = 7, minSnapshotRatio = 0.4,
        )
    }
}
