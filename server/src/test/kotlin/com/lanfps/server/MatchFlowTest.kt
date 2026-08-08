package com.lanfps.server

import com.lanfps.shared.ArenaDef
import com.lanfps.shared.BinaryReader
import com.lanfps.shared.BinaryWriter
import com.lanfps.shared.GameConstants
import com.lanfps.shared.GameMode
import com.lanfps.shared.MatchEventType
import com.lanfps.shared.MatchState
import com.lanfps.shared.PacketTypes
import com.lanfps.shared.Packets
import com.lanfps.shared.Protocol
import com.lanfps.shared.Team
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The between-match machinery: lobby mode votes (P3-4), map rotation (P2-3),
 * team auto-balance (P2-7) and the optional server password (P0-3).
 *
 * The lifecycle tests drive [MatchController.update] with synthetic dt — 15
 * simulated seconds cost a few milliseconds of wall time. The password test
 * goes over real UDP like [ConnectHandshakeTest].
 */
class MatchFlowTest {

    private var server: GameServer? = null
    private var thread: Thread? = null

    @AfterTest
    fun tearDown() {
        server?.stop()
        thread?.join(3000)
    }

    // ---- synthetic lifecycle ------------------------------------------------

    private fun makeWorld(
        mode: GameMode,
        matchSeconds: Int = 2,
    ): Pair<World, ServerConfig> {
        val config = ServerConfig().apply {
            this.mode = mode
            botCount = 0
            matchTimeSeconds = matchSeconds
            killLimit = 1000
        }
        return World(ServerArena(ArenaDef.builtinArena01()), config) to config
    }

    /** Runs the match state machine until [predicate] holds or the budget ends. */
    private fun driveUntil(
        match: MatchController,
        world: World,
        maxSimSeconds: Float,
        predicate: () -> Boolean,
    ): Boolean {
        val dt = 0.1f
        var elapsed = 0f
        while (elapsed < maxSimSeconds) {
            match.update(dt)
            world.tick(dt)
            elapsed += dt
            if (predicate()) return true
        }
        return false
    }

    @Test
    fun `a lobby vote majority overrides the configured mode for one match`() {
        // P3-4: operator pinned TDM, the lobby voted DM -> next match is DM.
        val (world, config) = makeWorld(GameMode.TDM, matchSeconds = 1)
        world.setBotCount(2)
        val match = MatchController(world, config, voteWinner = { GameMode.DM })

        assertTrue(
            driveUntil(match, world, 3f) { match.state == MatchState.ACTIVE },
            "match should start",
        )
        assertEquals(GameMode.TDM, world.mode, "first match runs on the config mode")

        // past the 1 s match + the 12 s results screen => resetMatch ran.
        assertTrue(
            driveUntil(match, world, 20f) {
                match.state == MatchState.ACTIVE && world.mode == GameMode.DM
            },
            "the second match should run on the voted mode",
        )

        val startEvent = match.pendingEvents.lastOrNull {
            it.eventType == MatchEventType.MATCH_START
        }
        assertNotNull(startEvent, "a MATCH_START event must announce the new match")
        assertEquals(GameMode.DM.wire, startEvent.extra, "event carries the mode")
        assertEquals(world.serverArena.def.name, startEvent.killerName, "event carries the arena name")
        assertEquals(
            "0x%08X".format(world.serverArena.def.hash()), startEvent.victimName,
            "event carries the arena hash so clients can verify before hot-loading",
        )
    }

    @Test
    fun `without a majority the operator config stays in charge`() {
        val (world, config) = makeWorld(GameMode.TDM, matchSeconds = 1)
        world.setBotCount(2)
        val match = MatchController(world, config, voteWinner = { null })

        assertTrue(driveUntil(match, world, 3f) { match.state == MatchState.ACTIVE })
        assertTrue(
            driveUntil(match, world, 20f) {
                match.state == MatchState.ACTIVE && match.timeRemaining < 0.5f
            },
        )
        assertEquals(GameMode.TDM, world.mode, "no vote majority -> config mode kept")
    }

    @Test
    fun `map rotation swaps the world onto the next arena between matches`() {
        // P2-3: hook wired exactly the way GameServer wires it.
        val (world, config) = makeWorld(GameMode.DM, matchSeconds = 1)
        world.setBotCount(2)
        val rotated = ArenaLoader.load("arena02.json")
        var supplied = 0
        val match = MatchController(world, config, nextArena = { rotated.also { supplied++ } })

        assertTrue(driveUntil(match, world, 3f) { match.state == MatchState.ACTIVE })
        assertTrue(
            driveUntil(match, world, 20f) { world.serverArena.def.name == "arena02" },
            "the world should have rotated to arena02",
        )
        assertEquals(1, supplied, "the hook is consulted exactly once per cycle")

        // Everyone got respawned into the new geometry (this throws on an
        // illegal spawn via fits() failing downstream, so assert explicitly).
        val physics = MovementSolverCheck(world)
        for (e in world.entities.values) {
            assertTrue(physics.fits(e), "entity ${e.name} outside the new arena")
        }
    }

    /** Tiny fits() helper so this file does not need the physics internals. */
    private class MovementSolverCheck(private val world: World) {
        private val solver = com.lanfps.shared.MovementSolver()
        fun fits(e: GameEntity): Boolean =
            solver.fits(e.body.position, world.serverArena.def, e.body.height)
    }

    @Test
    fun `team auto-balance alternates the top scorers between matches`() {
        // P2-7: stack the deck — feed the world players with descending kills.
        val (world, _) = makeWorld(GameMode.TDM)
        val kills = listOf(50, 40, 30, 20, 10, 0)
        kills.forEachIndexed { i, k ->
            val session = ClientSession(
                100 + i, InetAddress.getLoopbackAddress(), 41000 + i, "P$i",
            )
            world.addPlayer(session).kills = k
        }

        world.balanceTeams()

        val red = world.entities.values.filter { it.team == Team.RED }
        val blue = world.entities.values.filter { it.team == Team.BLUE }
        assertTrue(
            abs(red.size - blue.size) <= 1,
            "teams must stay within one player: RED=${red.size} BLUE=${blue.size}",
        )
        val top = world.entities.values.sortedByDescending { it.kills }
        assertNotEquals(
            top[0].team, top[1].team,
            "the two best players must land on opposite sides",
        )
    }

    // ---- password lock (real UDP) -------------------------------------------

    private val port = 47831

    private fun startServer(password: String) {
        Log.setLevel("ERROR")
        val config = ServerConfig().apply {
            mode = GameMode.DM
            udpPort = port
            bindAddress = "127.0.0.1"
            botCount = 0
            maxPlayers = 8
            this.password = password
            // The test pipelines several connects from one IP within a second;
            // keep the P0-3 flood guard from flunking the password assertions.
            maxConnectsPerSecond = 100
            maxConnectsPerIpPerSecond = 100
        }
        val s = GameServer(config)
        server = s
        thread = Thread({ s.start() }, "test-server-pw").apply {
            isDaemon = true
            start()
        }
        Thread.sleep(700) // let the socket bind
    }

    private fun attempt(password: String): Pair<Packets.ConnectAccepted?, String?> {
        val sock = DatagramSocket()
        try {
            sock.soTimeout = 400
            val addr = InetAddress.getByName("127.0.0.1")
            val w = BinaryWriter(1024)
            val request = Packets.ConnectRequest().apply {
                nickname = "Locked"
                preferredMode = GameMode.DM.wire
                clientTimeMs = System.currentTimeMillis()
                this.password = password
            }
            val header = Protocol.Header()
            val reader = BinaryReader()
            val buf = ByteArray(4096)
            repeat(12) {
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
                    when (header.type) {
                        PacketTypes.CONNECT_ACCEPTED -> return Packets.readConnectAccepted(reader) to null
                        PacketTypes.CONNECT_REJECTED -> return null to Packets.readConnectRejected(reader)
                    }
                }
            }
            return null to null
        } finally {
            sock.close()
        }
    }

    @Test
    fun `a password-protected server rejects strangers and admits friends`() {
        startServer(password = "hunter2")

        val (_, noPasswordReject) = attempt("")
        assertNotNull(noPasswordReject, "connecting without the password must be rejected")

        val (_, wrongReject) = attempt("letmein")
        assertNotNull(wrongReject, "the wrong password must be rejected")

        val (accepted, rightReject) = attempt("hunter2")
        assertNull(rightReject, "the right password must not be rejected")
        assertNotNull(accepted, "the right password gets a session")
    }
}
