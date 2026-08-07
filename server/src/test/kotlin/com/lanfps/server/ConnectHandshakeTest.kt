package com.lanfps.server

import com.lanfps.shared.BinaryReader
import com.lanfps.shared.BinaryWriter
import com.lanfps.shared.GameMode
import com.lanfps.shared.PacketTypes
import com.lanfps.shared.Packets
import com.lanfps.shared.Protocol
import com.lanfps.shared.Snapshot
import com.lanfps.shared.Team
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Boots a real [GameServer] on the loopback interface and speaks the real wire
 * protocol to it with a bare [DatagramSocket]. This covers the handshake end to
 * end — encode, socket, decode, session creation, snapshot flow — without a
 * phone.
 *
 * The regression it exists for: the server used to let the first client that
 * connected pick the game mode. Since the Android client always asks for DM,
 * starting the server with `--mode=TDM` produced a TDM server that flipped to
 * deathmatch (and reset the scores) the moment somebody joined.
 */
class ConnectHandshakeTest {

    private var server: GameServer? = null
    private var thread: Thread? = null
    private var client: DatagramSocket? = null

    private val port = 47811

    @AfterTest
    fun tearDown() {
        client?.close()
        server?.stop()
        thread?.join(3000)
    }

    private fun startServer(mode: GameMode, bots: Int = 2): GameServer {
        Log.setLevel("ERROR")
        val config = ServerConfig().apply {
            this.mode = mode
            udpPort = port
            bindAddress = "127.0.0.1"
            botCount = bots
            maxPlayers = 8
            enableDiscovery = true
        }
        val s = GameServer(config)
        server = s
        thread = Thread({ s.start() }, "test-server").apply {
            isDaemon = true
            start()
        }
        // Wait for the socket to actually accept traffic.
        val probe = DatagramSocket()
        probe.soTimeout = 200
        val w = BinaryWriter(512)
        val addr = InetAddress.getByName("127.0.0.1")
        val deadline = System.currentTimeMillis() + 5000
        var up = false
        while (System.currentTimeMillis() < deadline && !up) {
            Protocol.begin(w, PacketTypes.DISCOVERY_REQUEST)
            val len = Protocol.end(w)
            try {
                probe.send(DatagramPacket(w.buffer, len, addr, port))
                val buf = ByteArray(2048)
                val dp = DatagramPacket(buf, buf.size)
                probe.receive(dp)
                up = true
            } catch (_: Exception) {
                Thread.sleep(50)
            }
        }
        probe.close()
        assertTrue(up, "server did not come up on 127.0.0.1:$port")
        return s
    }

    /** Performs the handshake and returns the parsed CONNECT_ACCEPTED. */
    private fun connect(nick: String, preferred: GameMode): Packets.ConnectAccepted {
        val sock = DatagramSocket()
        client = sock
        sock.soTimeout = 400
        val addr = InetAddress.getByName("127.0.0.1")
        val w = BinaryWriter(1024)
        val request = Packets.ConnectRequest().apply {
            nickname = nick
            preferredMode = preferred.wire
            clientTimeMs = System.currentTimeMillis()
        }

        val header = Protocol.Header()
        val reader = BinaryReader()
        val buf = ByteArray(4096)

        repeat(20) {
            Protocol.begin(w, PacketTypes.CONNECT_REQUEST, it)
            Packets.writeConnectRequest(w, request)
            val len = Protocol.end(w)
            sock.send(DatagramPacket(w.buffer, len, addr, port))

            val deadline = System.currentTimeMillis() + 300
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
        throw AssertionError("no CONNECT_ACCEPTED from the server")
    }

    /** Waits for the first SERVER_SNAPSHOT and returns it. */
    private fun awaitSnapshot(timeoutMs: Long = 3000): Snapshot {
        val sock = client!!
        val header = Protocol.Header()
        val reader = BinaryReader()
        val buf = ByteArray(8192)
        val snapshot = Snapshot()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val dp = DatagramPacket(buf, buf.size)
            try {
                sock.receive(dp)
            } catch (_: Exception) {
                continue
            }
            if (Protocol.parse(dp.data, dp.length, header, reader) != Protocol.ParseResult.OK) continue
            if (header.type == PacketTypes.SERVER_SNAPSHOT) {
                Packets.readSnapshot(reader, snapshot)
                return snapshot
            }
        }
        throw AssertionError("no SERVER_SNAPSHOT within ${timeoutMs}ms")
    }

    @Test
    fun `a TDM server stays TDM when a client asks for DM`() {
        startServer(GameMode.TDM)

        // The Android client hard-codes DM as its preferred mode.
        val accepted = connect("Phone1", GameMode.DM)

        assertEquals(
            GameMode.TDM.wire, accepted.mode,
            "the operator configured TDM; a client must not be able to change it",
        )
        assertTrue(
            accepted.team == Team.RED.wire || accepted.team == Team.BLUE.wire,
            "a TDM player must be put on a team, got team=${accepted.team}",
        )
    }

    @Test
    fun `snapshots report the configured mode`() {
        startServer(GameMode.TDM)
        connect("Phone1", GameMode.DM)
        val snap = awaitSnapshot()
        assertEquals(GameMode.TDM.wire, snap.mode, "snapshot mode must match the server config")
        assertTrue(snap.entities.isNotEmpty(), "snapshot should contain us and the bots")
    }

    @Test
    fun `a DM server assigns no team and accepts the connection`() {
        startServer(GameMode.DM)
        val accepted = connect("Phone1", GameMode.DM)

        assertEquals(GameMode.DM.wire, accepted.mode)
        assertEquals(Team.NONE.wire, accepted.team, "deathmatch is free-for-all")
        assertTrue(accepted.playerId > 0, "we should get a real player id")
        assertEquals("Phone1", accepted.assignedNickname)
    }

    @Test
    fun `the handshake reports the arena the server actually loaded`() {
        startServer(GameMode.DM)
        val accepted = connect("Phone1", GameMode.DM)

        assertEquals("arena01", accepted.arena)
        assertEquals(
            com.lanfps.shared.ArenaDef.builtinArena01().hash(), accepted.arenaHash,
            "arena hash must match the built-in map the client ships with",
        )
        assertEquals(com.lanfps.shared.GameConstants.TICK_RATE, accepted.tickRate)
        assertEquals(com.lanfps.shared.GameConstants.SNAPSHOT_RATE, accepted.snapshotRate)
        assertNotNull(accepted.assignedNickname)
    }
}
