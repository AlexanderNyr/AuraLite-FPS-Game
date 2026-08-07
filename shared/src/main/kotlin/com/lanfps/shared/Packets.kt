package com.lanfps.shared

/**
 * Payload encoders/decoders for every packet type.
 *
 * Each `writeX` assumes [Protocol.begin] has already been called on the writer and
 * that the caller will invoke [Protocol.end] afterwards. Each `readX` assumes the
 * reader was positioned by a successful [Protocol.parse].
 */
object Packets {

    // ---------------------------------------------------------------- discovery

    class DiscoveryResponse {
        @JvmField var serverName: String = "LAN FPS Server"
        @JvmField var arena: String = GameConstants.ARENA_NAME
        @JvmField var mode: Int = GameMode.DM.wire
        @JvmField var playerCount: Int = 0
        @JvmField var maxPlayers: Int = GameConstants.DEFAULT_MAX_PLAYERS
        @JvmField var botCount: Int = 0
        @JvmField var udpPort: Int = GameConstants.DEFAULT_UDP_PORT
    }

    fun writeDiscoveryRequest(w: BinaryWriter, clientTag: String) {
        w.writeString(clientTag, 32)
    }

    fun readDiscoveryRequest(r: BinaryReader): String = r.readString()

    fun writeDiscoveryResponse(w: BinaryWriter, d: DiscoveryResponse) {
        w.writeString(d.serverName, 48)
        w.writeString(d.arena, 32)
        w.writeU8(d.mode)
        w.writeU8(d.playerCount)
        w.writeU8(d.maxPlayers)
        w.writeU8(d.botCount)
        w.writeU16(d.udpPort)
    }

    fun readDiscoveryResponse(r: BinaryReader): DiscoveryResponse {
        val d = DiscoveryResponse()
        d.serverName = r.readString()
        d.arena = r.readString()
        d.mode = r.readU8()
        d.playerCount = r.readU8()
        d.maxPlayers = r.readU8()
        d.botCount = r.readU8()
        d.udpPort = r.readU16()
        return d
    }

    // ------------------------------------------------------------------ connect

    class ConnectRequest {
        @JvmField var nickname: String = "Player"
        @JvmField var preferredMode: Int = GameMode.DM.wire
        @JvmField var clientTimeMs: Long = 0

        /** P0-2: token handed out in CONNECT_ACCEPTED. A non-zero token on a
         *  reconnect tells the server to resume the same entity/score/team
         *  instead of creating a brand-new session. 0 = first connection. */
        @JvmField var resumeToken: Int = 0
    }

    fun writeConnectRequest(w: BinaryWriter, c: ConnectRequest) {
        w.writeString(c.nickname, GameConstants.MAX_NICKNAME_LENGTH * 4)
        w.writeU8(c.preferredMode)
        w.writeI64(c.clientTimeMs)
        w.writeI32(c.resumeToken)
    }

    fun readConnectRequest(r: BinaryReader): ConnectRequest {
        val c = ConnectRequest()
        c.nickname = r.readString()
        c.preferredMode = r.readU8()
        c.clientTimeMs = r.readI64()
        c.resumeToken = r.readI32()
        return c
    }

    class ConnectAccepted {
        @JvmField var playerId: Int = 0
        @JvmField var team: Int = Team.NONE.wire
        @JvmField var mode: Int = GameMode.DM.wire
        @JvmField var arena: String = GameConstants.ARENA_NAME
        @JvmField var tickRate: Int = GameConstants.TICK_RATE
        @JvmField var snapshotRate: Int = GameConstants.SNAPSHOT_RATE
        @JvmField var serverTimeMs: Long = 0
        @JvmField var assignedNickname: String = ""
        /** Geometry fingerprint so the client can warn about a mismatched map. */
        @JvmField var arenaHash: Int = 0
        /** P0-2: the token to present on a future reconnect (0 = not yet). */
        @JvmField var resumeToken: Int = 0
    }

    fun writeConnectAccepted(w: BinaryWriter, a: ConnectAccepted) {
        w.writeU16(a.playerId)
        w.writeU8(a.team)
        w.writeU8(a.mode)
        w.writeString(a.arena, 32)
        w.writeU8(a.tickRate)
        w.writeU8(a.snapshotRate)
        w.writeI64(a.serverTimeMs)
        w.writeString(a.assignedNickname, GameConstants.MAX_NICKNAME_LENGTH * 4)
        w.writeI32(a.arenaHash)
        w.writeI32(a.resumeToken)
    }

    fun readConnectAccepted(r: BinaryReader): ConnectAccepted {
        val a = ConnectAccepted()
        a.playerId = r.readU16()
        a.team = r.readU8()
        a.mode = r.readU8()
        a.arena = r.readString()
        a.tickRate = r.readU8()
        a.snapshotRate = r.readU8()
        a.serverTimeMs = r.readI64()
        a.assignedNickname = r.readString()
        a.arenaHash = r.readI32()
        a.resumeToken = r.readI32()
        return a
    }

    fun writeConnectRejected(w: BinaryWriter, reason: String) = w.writeString(reason, 120)
    fun readConnectRejected(r: BinaryReader): String = r.readString()

    fun writeDisconnect(w: BinaryWriter, reason: String) = w.writeString(reason, 120)
    fun readDisconnect(r: BinaryReader): String = r.readString()

    // -------------------------------------------------------------------- input

    /**
     * Input packet: the newest command plus up to
     * [GameConstants.INPUT_REDUNDANCY] previous ones. Re-sending recent commands
     * is how we survive UDP loss without any ack machinery — a single delivered
     * packet heals the gap.
     */
    fun writeClientInput(
        w: BinaryWriter,
        playerId: Int,
        reportedPingMs: Int,
        commands: List<InputCommand>,
    ) {
        w.writeU16(playerId)
        // Client's own RTT measurement, display-only (never trusted for logic).
        w.writeU16(MathUtil.clamp(reportedPingMs, 0, 65535))
        val count = if (commands.size > 255) 255 else commands.size
        w.writeU8(count)
        for (i in 0 until count) commands[i].write(w)
    }

    class ClientInputPacket {
        @JvmField var playerId: Int = 0
        @JvmField var reportedPingMs: Int = 0
        @JvmField var commands: ArrayList<InputCommand> = ArrayList()
    }

    fun readClientInput(r: BinaryReader, out: ClientInputPacket = ClientInputPacket()): ClientInputPacket {
        out.playerId = r.readU16()
        out.reportedPingMs = r.readU16()
        val count = r.readU8()
        out.commands.clear()
        for (i in 0 until count) {
            out.commands.add(InputCommand().read(r))
        }
        return out
    }

    // ----------------------------------------------------------------- snapshot

    /**
     * Byte offset (from the start of the datagram) of the per-recipient
     * `lastProcessedInputSeq` field. The server writes the shared part of a
     * snapshot once, then patches this field and the CRC per client.
     */
    const val SNAPSHOT_LAST_INPUT_OFFSET: Int = Protocol.HEADER_SIZE + 4 + 8

    fun writeSnapshot(w: BinaryWriter, s: Snapshot) {
        w.writeI32(s.serverTick)
        w.writeI64(s.serverTimeMs)
        w.writeU16(s.lastProcessedInputSeq and 0xFFFF) // set per client
        w.writeU8(s.mode)
        w.writeU8(s.matchState)
        w.writeF32(s.matchTimeRemaining)
        w.writeU16(MathUtil.clamp(s.redScore, 0, 65535))
        w.writeU16(MathUtil.clamp(s.blueScore, 0, 65535))
        w.writeU8(s.kind)
        if (s.kind == SnapshotKind.FULL) {
            val n = if (s.entities.size > 255) 255 else s.entities.size
            w.writeU8(n)
            for (i in 0 until n) s.entities[i].write(w)
        } else {
            // P1-2: delta. Reuse a scratch SnapshotDelta for serialisation.
            val d = SnapshotDelta()
            d.changed.addAll(s.deltaChanged)
            d.removed.addAll(s.deltaRemoved)
            d.write(w)
        }
    }

    fun readSnapshot(r: BinaryReader, out: Snapshot = Snapshot()): Snapshot {
        out.serverTick = r.readI32()
        out.serverTimeMs = r.readI64()
        out.lastProcessedInputSeq = r.readU16()
        out.mode = r.readU8()
        out.matchState = r.readU8()
        out.matchTimeRemaining = r.readF32()
        out.redScore = r.readU16()
        out.blueScore = r.readU16()
        out.kind = r.readU8()
        if (out.kind == SnapshotKind.FULL) {
            val n = r.readU8()
            out.entities.clear()
            out.deltaChanged.clear()
            out.deltaRemoved.clear()
            for (i in 0 until n) out.entities.add(EntityState().read(r))
        } else {
            val d = SnapshotDelta().read(r)
            out.entities.clear()
            out.deltaChanged.clear()
            out.deltaChanged.addAll(d.changed)
            out.deltaRemoved.clear()
            out.deltaRemoved.addAll(d.removed)
        }
        return out
    }

    // --------------------------------------------------------------- ping/pong

    fun writePing(w: BinaryWriter, clientTimeMs: Long) = w.writeI64(clientTimeMs)
    fun readPing(r: BinaryReader): Long = r.readI64()

    fun writePong(w: BinaryWriter, clientTimeMs: Long, serverTimeMs: Long) {
        w.writeI64(clientTimeMs)
        w.writeI64(serverTimeMs)
    }

    class Pong {
        @JvmField var clientTimeMs: Long = 0
        @JvmField var serverTimeMs: Long = 0
    }

    fun readPong(r: BinaryReader, out: Pong = Pong()): Pong {
        out.clientTimeMs = r.readI64()
        out.serverTimeMs = r.readI64()
        return out
    }

    // -------------------------------------------------------------------- lobby

    class LobbyPlayer {
        @JvmField var id: Int = 0
        @JvmField var name: String = ""
        @JvmField var team: Int = Team.NONE.wire
        @JvmField var bot: Boolean = false
        @JvmField var kills: Int = 0
        @JvmField var deaths: Int = 0
        @JvmField var pingMs: Int = 0
    }

    class LobbyState {
        @JvmField var serverName: String = "LAN FPS Server"
        @JvmField var arena: String = GameConstants.ARENA_NAME
        @JvmField var mode: Int = GameMode.DM.wire
        @JvmField var matchState: Int = MatchState.WARMUP
        @JvmField var botCount: Int = 0
        @JvmField var maxPlayers: Int = GameConstants.DEFAULT_MAX_PLAYERS
        @JvmField var matchTimeRemaining: Float = 0f
        @JvmField var players: ArrayList<LobbyPlayer> = ArrayList()
    }

    fun writeLobbyState(w: BinaryWriter, l: LobbyState) {
        w.writeString(l.serverName, 48)
        w.writeString(l.arena, 32)
        w.writeU8(l.mode)
        w.writeU8(l.matchState)
        w.writeU8(l.botCount)
        w.writeU8(l.maxPlayers)
        w.writeF32(l.matchTimeRemaining)
        val n = if (l.players.size > 255) 255 else l.players.size
        w.writeU8(n)
        for (i in 0 until n) {
            val p = l.players[i]
            w.writeU16(p.id)
            w.writeString(p.name, GameConstants.MAX_NICKNAME_LENGTH * 4)
            w.writeU8(p.team)
            w.writeBool(p.bot)
            w.writeU16(MathUtil.clamp(p.kills, 0, 65535))
            w.writeU16(MathUtil.clamp(p.deaths, 0, 65535))
            w.writeU16(MathUtil.clamp(p.pingMs, 0, 65535))
        }
    }

    fun readLobbyState(r: BinaryReader, out: LobbyState = LobbyState()): LobbyState {
        out.serverName = r.readString()
        out.arena = r.readString()
        out.mode = r.readU8()
        out.matchState = r.readU8()
        out.botCount = r.readU8()
        out.maxPlayers = r.readU8()
        out.matchTimeRemaining = r.readF32()
        val n = r.readU8()
        out.players.clear()
        for (i in 0 until n) {
            val p = LobbyPlayer()
            p.id = r.readU16()
            p.name = r.readString()
            p.team = r.readU8()
            p.bot = r.readBool()
            p.kills = r.readU16()
            p.deaths = r.readU16()
            p.pingMs = r.readU16()
            out.players.add(p)
        }
        return out
    }

    // -------------------------------------------------------------- match event

    class MatchEvent {
        /** P1-4: monotonically increasing per-server sequence; clients use it to
         *  drop duplicates and to acknowledge (via the CLIENT_INPUT header ack)
         *  so the server stops re-sending. */
        @JvmField var eventSeq: Int = 0
        @JvmField var eventType: Int = MatchEventType.KILL
        @JvmField var killerId: Int = 0
        @JvmField var victimId: Int = 0
        @JvmField var killerName: String = ""
        @JvmField var victimName: String = ""
        /** Winning team for MATCH_END, otherwise unused. */
        @JvmField var extra: Int = 0
    }

    fun writeMatchEvent(w: BinaryWriter, e: MatchEvent) {
        w.writeU16(e.eventSeq)
        w.writeU8(e.eventType)
        w.writeU16(e.killerId)
        w.writeU16(e.victimId)
        w.writeString(e.killerName, GameConstants.MAX_NICKNAME_LENGTH * 4)
        w.writeString(e.victimName, GameConstants.MAX_NICKNAME_LENGTH * 4)
        w.writeI32(e.extra)
    }

    fun readMatchEvent(r: BinaryReader, out: MatchEvent = MatchEvent()): MatchEvent {
        out.eventSeq = r.readU16()
        out.eventType = r.readU8()
        out.killerId = r.readU16()
        out.victimId = r.readU16()
        out.killerName = r.readString()
        out.victimName = r.readString()
        out.extra = r.readI32()
        return out
    }
}
