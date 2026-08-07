package com.lanfps.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ProtocolTest {

    private val header = Protocol.Header()
    private val reader = BinaryReader()

    @Test
    fun `ping round trips through framing`() {
        val w = BinaryWriter()
        Protocol.begin(w, PacketTypes.PING, sequence = 42)
        Packets.writePing(w, 123456789L)
        val len = Protocol.end(w)

        val result = Protocol.parse(w.buffer, len, header, reader)
        assertEquals(Protocol.ParseResult.OK, result)
        assertEquals(PacketTypes.PING, header.type)
        assertEquals(42, header.sequence)
        assertEquals(123456789L, Packets.readPing(reader))
    }

    @Test
    fun `corrupted payload fails checksum`() {
        val w = BinaryWriter()
        Protocol.begin(w, PacketTypes.PONG)
        Packets.writePong(w, 1L, 2L)
        val len = Protocol.end(w)

        // Flip a payload byte.
        w.buffer[Protocol.HEADER_SIZE] = (w.buffer[Protocol.HEADER_SIZE] + 1).toByte()

        val result = Protocol.parse(w.buffer, len, header, reader)
        assertEquals(Protocol.ParseResult.BAD_CHECKSUM, result)
    }

    @Test
    fun `wrong magic is rejected`() {
        val w = BinaryWriter()
        Protocol.begin(w, PacketTypes.PING)
        Packets.writePing(w, 7L)
        val len = Protocol.end(w)
        w.buffer[0] = 0x00

        assertEquals(Protocol.ParseResult.BAD_MAGIC, Protocol.parse(w.buffer, len, header, reader))
    }

    @Test
    fun `wrong version is rejected`() {
        val w = BinaryWriter()
        Protocol.begin(w, PacketTypes.PING)
        Packets.writePing(w, 7L)
        val len = Protocol.end(w)
        w.buffer[4] = 99

        assertEquals(Protocol.ParseResult.BAD_VERSION, Protocol.parse(w.buffer, len, header, reader))
    }

    @Test
    fun `connect request round trips nickname`() {
        val req = Packets.ConnectRequest().apply {
            nickname = "Neo"
            preferredMode = GameMode.TDM.wire
            clientTimeMs = 999L
        }
        val w = BinaryWriter()
        Protocol.begin(w, PacketTypes.CONNECT_REQUEST)
        Packets.writeConnectRequest(w, req)
        val len = Protocol.end(w)

        assertEquals(Protocol.ParseResult.OK, Protocol.parse(w.buffer, len, header, reader))
        val decoded = Packets.readConnectRequest(reader)
        assertEquals("Neo", decoded.nickname)
        assertEquals(GameMode.TDM.wire, decoded.preferredMode)
        assertEquals(999L, decoded.clientTimeMs)
    }

    @Test
    fun `input command sanitize clamps and normalizes`() {
        val cmd = InputCommand().apply {
            moveForward = 5f
            moveRight = 5f
            pitch = 200f
            yaw = 540f
        }
        cmd.sanitize()
        val mag = kotlin.math.sqrt(cmd.moveForward * cmd.moveForward + cmd.moveRight * cmd.moveRight)
        assertTrue(mag <= 1.001f, "stick magnitude should be within unit circle, was $mag")
        assertTrue(cmd.pitch <= GameConstants.MAX_PITCH_DEG)
        assertTrue(cmd.yaw in -180f..180f)
    }

    @Test
    fun `snapshot round trips entities`() {
        val snap = Snapshot().apply {
            serverTick = 1234
            serverTimeMs = 55_000L
            mode = GameMode.TDM.wire
            matchState = MatchState.ACTIVE
            matchTimeRemaining = 240.5f
            redScore = 3
            blueScore = 5
            lastProcessedInputSeq = 77
        }
        snap.entities.add(EntityState().apply {
            id = 1; type = EntityType.PLAYER; team = Team.RED.wire
            x = 10f; y = 0f; z = -4f; yaw = 90f; pitch = -10f
            vx = 1.25f; vy = 0f; vz = -3.5f
            health = 80; kills = 2; deaths = 1; alive = true; name = "Alice"
        })
        snap.entities.add(EntityState().apply {
            id = GameConstants.BOT_ID_BASE; type = EntityType.BOT; team = Team.BLUE.wire
            x = -8f; y = 0f; z = 12f; health = 0; deaths = 3; alive = false; name = "Bot-1"
        })

        val w = BinaryWriter()
        Protocol.begin(w, PacketTypes.SERVER_SNAPSHOT)
        Packets.writeSnapshot(w, snap)
        val len = Protocol.end(w)

        assertEquals(Protocol.ParseResult.OK, Protocol.parse(w.buffer, len, header, reader))
        val decoded = Packets.readSnapshot(reader)
        assertEquals(1234, decoded.serverTick)
        assertEquals(GameMode.TDM.wire, decoded.mode)
        assertEquals(240.5f, decoded.matchTimeRemaining)
        assertEquals(2, decoded.entities.size)

        val a = decoded.findEntity(1)!!
        // P0-1: nicknames no longer travel inside snapshots (see EntityState).
        assertEquals("", a.name, "names were removed from the snapshot wire format")
        assertEquals(80, a.health)
        assertTrue(a.alive)
        assertEquals(10f, a.x)
        assertTrue(kotlin.math.abs(a.vx - 1.25f) < 0.02f)

        val bot = decoded.findEntity(GameConstants.BOT_ID_BASE)!!
        assertEquals(EntityType.BOT, bot.type)
        assertTrue(!bot.alive)
    }

    @Test
    fun `snapshot last input can be patched in place`() {
        val snap = Snapshot().apply { serverTick = 1; serverTimeMs = 10 }
        val w = BinaryWriter()
        Protocol.begin(w, PacketTypes.SERVER_SNAPSHOT)
        Packets.writeSnapshot(w, snap)
        val len = Protocol.end(w)

        Protocol.patchU16(w.buffer, Packets.SNAPSHOT_LAST_INPUT_OFFSET, 4321)
        Protocol.rechecksum(w.buffer, len)

        assertEquals(Protocol.ParseResult.OK, Protocol.parse(w.buffer, len, header, reader))
        val decoded = Packets.readSnapshot(reader)
        assertEquals(4321, decoded.lastProcessedInputSeq)
    }

    @Test
    fun `input packet carries redundant commands`() {
        val cmds = (1..GameConstants.INPUT_REDUNDANCY).map { i ->
            InputCommand().apply { sequence = i; moveForward = 1f; yaw = i * 10f }
        }
        val w = BinaryWriter()
        Protocol.begin(w, PacketTypes.CLIENT_INPUT)
        Packets.writeClientInput(w, playerId = 7, reportedPingMs = 42, commands = cmds)
        val len = Protocol.end(w)

        assertEquals(Protocol.ParseResult.OK, Protocol.parse(w.buffer, len, header, reader))
        val decoded = Packets.readClientInput(reader)
        assertEquals(7, decoded.playerId)
        assertEquals(42, decoded.reportedPingMs)
        assertEquals(GameConstants.INPUT_REDUNDANCY, decoded.commands.size)
        assertEquals(1, decoded.commands.first().sequence)
    }

    @Test
    fun `sequence comparison handles wraparound`() {
        assertTrue(InputCommand.sequenceGreaterThan(5, 3))
        assertTrue(InputCommand.sequenceGreaterThan(1, 65535)) // wrapped: 1 is newer
        assertTrue(!InputCommand.sequenceGreaterThan(65535, 1))
    }

    @Test
    fun `worst-case snapshot stays under the MTU budget (P0-4)`() {
        // A full server: maxPlayers humans + botCount bots, each with a 16-char
        // two-byte (Cyrillic/CJK) nickname. Before P0-1 this serialised every
        // name into every 30 Hz snapshot and a full server exceeded the MTU.
        val players = GameConstants.DEFAULT_MAX_PLAYERS
        val bots = GameConstants.DEFAULT_BOT_COUNT
        val twoByteName = "\u0421\u0438\u043d\u0434\u0440\u0438" + "йкхжзц" // 16 chars, 2 B/char
        val snap = Snapshot().apply {
            serverTick = 1
            serverTimeMs = 1L
            mode = GameMode.TDM.wire
            matchState = MatchState.ACTIVE
            redScore = 3
            blueScore = 5
        }
        for (i in 0 until players) {
            snap.entities.add(EntityState().apply {
                id = i + 1; type = EntityType.PLAYER; team = Team.RED.wire
                x = i.toFloat(); y = 0f; z = 0f; yaw = 0f; pitch = 0f
                health = 100; kills = 1; deaths = 0; alive = true
                name = twoByteName
            })
        }
        for (i in 0 until bots) {
            snap.entities.add(EntityState().apply {
                id = GameConstants.BOT_ID_BASE + i; type = EntityType.BOT
                x = 0f; y = 0f; z = i.toFloat()
                health = 100; alive = true
                name = twoByteName
            })
        }

        val w = BinaryWriter(8192)
        Protocol.begin(w, PacketTypes.SERVER_SNAPSHOT)
        Packets.writeSnapshot(w, snap)
        val len = Protocol.end(w)

        assertTrue(
            len < GameConstants.SNAPSHOT_MAX_BYTES,
            "full-server snapshot is $len bytes, over the MTU-safe budget " +
                "${GameConstants.SNAPSHOT_MAX_BYTES} (IP fragmentation would follow)",
        )
    }

    @Test
    fun `nickname is truncated to protocol limit`() {
        val w = BinaryWriter()
        val huge = "x".repeat(500)
        w.writeString(huge, GameConstants.MAX_NICKNAME_LENGTH * 4)
        val r = BinaryReader(w.toByteArray())
        val decoded = r.readString()
        assertTrue(decoded.length <= GameConstants.MAX_NICKNAME_LENGTH * 4)
        assertNotEquals(500, decoded.length)
    }
}
