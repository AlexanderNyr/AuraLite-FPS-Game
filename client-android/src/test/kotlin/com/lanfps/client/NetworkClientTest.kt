package com.lanfps.client

import com.lanfps.shared.ArenaDef
import com.lanfps.shared.BinaryReader
import com.lanfps.shared.BinaryWriter
import com.lanfps.shared.EntityState
import com.lanfps.shared.GameConstants
import com.lanfps.shared.GameMode
import com.lanfps.shared.MatchEventType
import com.lanfps.shared.MatchState
import com.lanfps.shared.PacketTypes
import com.lanfps.shared.Packets
import com.lanfps.shared.Protocol
import com.lanfps.shared.Snapshot
import com.lanfps.shared.SnapshotKind
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Direct wire-level tests for [NetworkClient] (P3-4: the class previously had
 * none — 837 lines covered only indirectly through the server suite).
 *
 * The other end of the UDP socket is a scripted fake server speaking the real
 * protocol through the shared [Packets]/[Protocol] codecs, exactly like the
 * server's own ConnectHandshakeTest mirrors it from the opposite side. Runs on
 * a plain JVM (`:client-android:testDebugUnitTest`) because nothing here
 * touches the Android framework beyond stubs.
 */
class NetworkClientTest {

    /** A scripted UDP peer that plays the server role. */
    private class FakeServer(port: Int) {
        private val socket = DatagramSocket(port)
        private val running = java.util.concurrent.atomic.AtomicBoolean(true)
        private val thread: Thread

        /** Behaviour for the next CONNECT_REQUEST. */
        @Volatile var rejectWith: String? = null

        /** Arena identity packed into CONNECT_ACCEPTED. */
        @Volatile var acceptedArena: String = "arena01"
        @Volatile var acceptedArenaHash: Int = 0

        /** playerId handed out on connect. */
        @Volatile var playerId: Int = 42

        private val inputHeader = Protocol.Header()
        val inputSequences = ConcurrentLinkedQueue<Int>()
        val gotConnect = CountDownLatch(1)
        val gotVote = CountDownLatch(1)
        val voteMode = AtomicInteger(-1)
        val lastError = AtomicReference<String?>()

        init {
            thread = Thread({
                val buf = ByteArray(4096)
                val packet = DatagramPacket(buf, buf.size)
                val header = Protocol.Header()
                val reader = BinaryReader()
                while (running.get()) {
                    try {
                        packet.setData(buf, 0, buf.size)
                        socket.receive(packet)
                    } catch (_: Exception) {
                        break
                    }
                    try {
                        if (Protocol.parse(packet.data, packet.length, header, reader)
                            != Protocol.ParseResult.OK
                        ) {
                            continue
                        }
                        handle(packet, header, reader)
                    } catch (e: Exception) {
                        lastError.set(e.toString())
                    }
                }
            }, "fake-server")
            thread.isDaemon = true
            thread.start()
        }

        private fun handle(p: DatagramPacket, header: Protocol.Header, reader: BinaryReader) {
            when (header.type) {
                PacketTypes.CONNECT_REQUEST -> {
                    // Remember where to push unsolicited packets (snapshots,
                    // lobby state, events, kicks) after the handshake.
                    clientAddress = p.address
                    clientPort = p.port
                    gotConnect.countDown()
                    val reason = rejectWith
                    val w = BinaryWriter(1024)
                    if (reason != null) {
                        Protocol.begin(w, PacketTypes.CONNECT_REJECTED)
                        Packets.writeConnectRejected(w, reason)
                    } else {
                        Protocol.begin(w, PacketTypes.CONNECT_ACCEPTED)
                        Packets.writeConnectAccepted(
                            w,
                            Packets.ConnectAccepted().apply {
                                playerId = this@FakeServer.playerId
                                team = 0
                                mode = GameMode.DM.wire
                                arena = acceptedArena
                                tickRate = GameConstants.TICK_RATE
                                snapshotRate = GameConstants.SNAPSHOT_RATE
                                serverTimeMs = 0
                                assignedNickname = "JunitBot"
                                arenaHash = acceptedArenaHash
                                resumeToken = 0xABCD
                            },
                        )
                    }
                    val len = Protocol.end(w)
                    socket.send(DatagramPacket(w.buffer, len, p.address, p.port))
                }

                PacketTypes.CLIENT_INPUT -> {
                    inputSequences.add(header.sequence)
                }

                PacketTypes.MODE_VOTE -> {
                    voteMode.set(Packets.readModeVote(reader).wire)
                    gotVote.countDown()
                }

                PacketTypes.PING -> {
                    val t = Packets.readPing(reader)
                    val w = BinaryWriter(64)
                    Protocol.begin(w, PacketTypes.PONG)
                    Packets.writePong(w, t, 0)
                    val len = Protocol.end(w)
                    socket.send(DatagramPacket(w.buffer, len, p.address, p.port))
                }

                else -> {}
            }
        }

        /** Where to push unsolicited packets; learned from CONNECT_REQUEST. */
        @Volatile private var clientAddress: java.net.InetAddress? = null
        @Volatile private var clientPort = -1

        fun send(type: Int, fill: (BinaryWriter) -> Unit) {
            val addr = clientAddress ?: return
            val w = BinaryWriter(2048)
            Protocol.begin(w, type)
            fill(w)
            val len = Protocol.end(w)
            socket.send(DatagramPacket(w.buffer, len, addr, clientPort))
        }

        fun close() {
            running.set(false)
            try {
                socket.close()
            } catch (_: Exception) {
            }
            thread.join(1500)
        }
    }

    private class LatchListener : NetworkClient.Listener {
        val connected = CountDownLatch(1)
        val rejected = CountDownLatch(1)
        val disconnected = CountDownLatch(1)
        val rejectReason = AtomicReference<String?>()
        val disconnectReason = AtomicReference<String?>()
        val disconnectWasError = AtomicReference<Boolean?>(null)

        override fun onConnected(playerId: Int, team: com.lanfps.shared.Team, mode: GameMode) {
            connected.countDown()
        }

        override fun onRejected(reason: String) {
            rejectReason.set(reason)
            rejected.countDown()
        }

        override fun onDisconnected(reason: String, wasError: Boolean) {
            disconnectReason.set(reason)
            disconnectWasError.set(wasError)
            disconnected.countDown()
        }

        override fun onMatchStateChanged(newState: Int, winningTeam: Int) {}
    }

    private var net: NetworkClient? = null
    private var server: FakeServer? = null

    @AfterEach
    fun tearDown() {
        net?.stopNow()
        net = null
        server?.close()
        server = null
    }

    private fun connectClient(port: Int): Triple<ClientGameState, LatchListener, FakeServer> {
        val arena = ArenaDef.builtinArena01()
        val state = ClientGameState(arena)
        state.prediction = Prediction(arena)
        val input = InputController()
        val listener = LatchListener()
        val fake = FakeServer(port)
        server = fake
        val client = NetworkClient(state, input, arena, listener)
        net = client
        client.start("127.0.0.1", port, "Junit")
        return Triple(state, listener, fake)
    }

    @Test
    fun `the handshake reaches the lobby with the assigned identity`() {
        val port = 47901
        val (state, listener, _) = connectClient(port)

        assertTrue(
            listener.connected.await(6, TimeUnit.SECONDS),
            "client never received CONNECT_ACCEPTED (${(net != null)})",
        )
        assertEquals(42, state.localPlayerId)
        assertEquals("JunitBot", state.nickname, "server-assigned nickname wins")
        assertEquals(Phase.LOBBY, state.phase)
        assertEquals(0xABCD, state.resumeToken, "resume token must be stored for P0-2")
    }

    @Test
    fun `inputs stream out at tick rate with increasing sequences`() {
        val port = 47902
        val (_, listener, fake) = connectClient(port)
        assertTrue(listener.connected.await(6, TimeUnit.SECONDS))

        Thread.sleep(1500) // 60 Hz client tick: ~90 input packets should land

        val seqs = fake.inputSequences.toList()
        assertTrue(
            seqs.size >= 45,
            "expected ~90 input packets in 1.5 s at 60 Hz, got ${seqs.size}",
        )
        var increasing = true
        var prev = seqs.first()
        for (s in seqs.drop(1)) {
            // u16 wrap-aware: the successor must be strictly ahead.
            if (!com.lanfps.shared.InputCommand.sequenceGreaterThan(s, prev)) {
                increasing = false
                break
            }
            prev = s
        }
        assertTrue(increasing, "input packet sequences must increase monotonically")
    }

    @Test
    fun `a rejection is reported to the listener`() {
        val port = 47903
        // Pre-arm the refusal before the client starts.
        val arena = ArenaDef.builtinArena01()
        val state = ClientGameState(arena)
        state.prediction = Prediction(arena)
        val listener = LatchListener()
        val fake = FakeServer(port).apply { rejectWith = "Server is full (8 players)" }
        server = fake
        net = NetworkClient(state, InputController(), arena, listener)
        net!!.start("127.0.0.1", port, "Junit")

        assertTrue(listener.rejected.await(6, TimeUnit.SECONDS), "rejection never surfaced")
        assertEquals("Server is full (8 players)", listener.rejectReason.get())
        assertEquals(Phase.DISCONNECTED, state.phase)
    }

    @Test
    fun `the client votes for a game mode over the wire`() {
        val port = 47904
        val (_, listener, fake) = connectClient(port)
        assertTrue(listener.connected.await(6, TimeUnit.SECONDS))

        net!!.voteMode(GameMode.TDM)

        assertTrue(fake.gotVote.await(4, TimeUnit.SECONDS), "MODE_VOTE never arrived")
        assertEquals(GameMode.TDM.wire, fake.voteMode.get())
    }

    @Test
    fun `a server-side disconnect kicks us back to the menu`() {
        val port = 47905
        val (state, listener, fake) = connectClient(port)
        assertTrue(listener.connected.await(6, TimeUnit.SECONDS))

        fake.send(PacketTypes.DISCONNECT) { w -> Packets.writeDisconnect(w, "test kick") }

        assertTrue(listener.disconnected.await(6, TimeUnit.SECONDS), "kick never surfaced")
        assertEquals("test kick", listener.disconnectReason.get())
        assertEquals(false, listener.disconnectWasError.get())
        assertEquals(Phase.DISCONNECTED, state.phase)
    }

    @Test
    fun `kill and match-start events drive spectating and map hot-load`() {
        val port = 47906
        val (state, listener, fake) = connectClient(port)
        assertTrue(listener.connected.await(6, TimeUnit.SECONDS))

        // P2-5: our death makes us spectate the killer.
        fake.send(PacketTypes.MATCH_EVENT) { w ->
            Packets.writeMatchEvent(
                w,
                Packets.MatchEvent().apply {
                    eventSeq = 1
                    eventType = MatchEventType.KILL
                    killerId = 77
                    victimId = 42
                    killerName = "MeanBot"
                    victimName = "JunitBot"
                },
            )
        }
        // P2-3: MATCH_START on a rotated arena asks for a hot-load.
        fake.send(PacketTypes.MATCH_EVENT) { w ->
            Packets.writeMatchEvent(
                w,
                Packets.MatchEvent().apply {
                    eventSeq = 2
                    eventType = MatchEventType.MATCH_START
                    killerName = "arena02"
                    victimName = "0x0000BEEF"
                    extra = GameMode.TDM.wire
                },
            )
        }

        val deadline = System.currentTimeMillis() + 4000
        while (System.currentTimeMillis() < deadline &&
            (state.spectateId != 77 || state.pendingArenaName != "arena02")
        ) {
            Thread.sleep(25)
        }

        assertEquals(77, state.spectateId, "we should be spectating our killer (P2-5)")
        assertEquals("arena02", state.pendingArenaName, "map rotation must queue a hot-load")
        assertEquals(0x0000BEEF, state.pendingArenaHash)
        assertEquals(
            2, state.highestEventSeq,
            "events are acked in order so the server can stop re-sending",
        )
    }

    @Test
    fun `lobby state updates the roster, vote tally and goal`() {
        val port = 47907
        val (state, listener, fake) = connectClient(port)
        assertTrue(listener.connected.await(6, TimeUnit.SECONDS))

        fake.send(PacketTypes.LOBBY_STATE) { w ->
            Packets.writeLobbyState(
                w,
                Packets.LobbyState().apply {
                    serverName = "JUnit Arena"
                    arena = "arena01"
                    mode = GameMode.TDM.wire
                    matchState = MatchState.ACTIVE
                    botCount = 4
                    maxPlayers = 8
                    matchTimeRemaining = 120f
                    killLimit = 25
                    votesDm = 1
                    votesTdm = 3
                    players.add(
                        Packets.LobbyPlayer().apply {
                            id = 77
                            name = "MeanBot"
                            team = 2
                            bot = true
                            kills = 5
                            deaths = 3
                            pingMs = 0
                        },
                    )
                },
            )
        }

        val deadline = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < deadline && state.rosterName(77) == null) {
            Thread.sleep(25)
        }

        assertEquals(GameMode.TDM, state.mode)
        assertEquals(25, state.killLimit, "P2-6: the match goal must reach the UI")
        assertEquals(1, state.votesDm)
        assertEquals(3, state.votesTdm)
        assertEquals("MeanBot", state.rosterName(77), "P0-1: names live in LOBBY_STATE")
    }

    @Test
    fun `snapshots flow into the buffer and mirror our weapon and ammo`() {
        val port = 47908
        val (state, listener, fake) = connectClient(port)
        assertTrue(listener.connected.await(6, TimeUnit.SECONDS))

        fake.send(PacketTypes.SERVER_SNAPSHOT) { w ->
            Packets.writeSnapshot(
                w,
                Snapshot().apply {
                    serverTick = 100
                    serverTimeMs = 5000
                    lastProcessedInputSeq = 1
                    mode = GameMode.DM.wire
                    matchState = MatchState.ACTIVE
                    matchTimeRemaining = 240f
                    kind = SnapshotKind.FULL
                    entities.add(
                        EntityState().apply {
                            id = 42
                            type = 0
                            team = 0
                            x = 1f; y = 0f; z = -2f
                            yaw = 45f
                            health = 85
                            weapon = com.lanfps.shared.Weapons.SHOTGUN
                            ammo = 4
                            alive = true
                        },
                    )
                },
            )
        }

        val deadline = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < deadline && state.snapshots.latest == null) {
            Thread.sleep(25)
        }

        assertTrue(state.snapshots.latest != null, "snapshot never entered the buffer")
        assertEquals(85, state.health)
        assertTrue(state.alive)
        assertEquals(
            com.lanfps.shared.Weapons.SHOTGUN, state.localWeapon,
            "P2-1: the HUD follows the server's weapon verdict",
        )
        assertEquals(4, state.localAmmo, "P2-2: the HUD shows the magazine the server gives")
    }
}
