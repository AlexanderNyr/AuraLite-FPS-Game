package com.lanfps.server

import com.lanfps.shared.BinaryReader
import com.lanfps.shared.BinaryWriter
import com.lanfps.shared.GameConstants
import com.lanfps.shared.GameMode
import com.lanfps.shared.MatchEventType
import com.lanfps.shared.MatchState
import com.lanfps.shared.PacketTypes
import com.lanfps.shared.Packets
import com.lanfps.shared.Protocol
import com.lanfps.shared.ProtocolException
import java.net.InetAddress
import java.util.ArrayDeque
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.locks.LockSupport

/**
 * The headless authoritative game server.
 *
 * Threading model:
 *  - one background thread drains the UDP socket into a queue ([UdpServerSocket]);
 *  - this thread runs a fixed-timestep loop: consume packets, simulate at
 *    [GameConstants.TICK_RATE] Hz, broadcast snapshots at
 *    [GameConstants.SNAPSHOT_RATE] Hz.
 *
 * The simulation never blocks on I/O, and simulation speed is independent of how
 * fast the loop happens to spin.
 */
class GameServer(private val config: ServerConfig) {

    private lateinit var socket: UdpServerSocket
    private lateinit var serverArena: ServerArena
    private lateinit var world: World
    private lateinit var match: MatchController

    private val snapshotBuilder = SnapshotBuilder()

    /** Sessions keyed by "ip:port" — the identity of a UDP peer. */
    private val sessions = LinkedHashMap<String, ClientSession>()

    private var nextPlayerId = 1

    @Volatile private var running = false

    private val startNanos = System.nanoTime()

    // Reused decode/encode scratch (loop is single-threaded).
    private val header = Protocol.Header()
    private val reader = BinaryReader()
    private val writer = BinaryWriter(2048)
    private val inputPacket = Packets.ClientInputPacket()

    private var snapshotSequence = 0

    /** P1-4: global monotonically increasing MATCH_EVENT sequence (u16 wraps). */
    private var matchEventSequence = 0

    private var tickCount = 0
    private var ticksThisSecond = 0
    private var rejectedPackets = 0L
    private var rejectedConnects = 0L

    // ---- P0-3: connection flood protection --------------------------------
    // Timestamps of recently accepted brand-new connects, for a sliding 1 s
    // window (global and per-IP). The simulation thread is single-threaded so
    // no locking is needed.
    private val recentConnectTimes = ArrayDeque<Long>()
    private val recentConnectPerIp = HashMap<String, ArrayDeque<Long>>()
    private val connectWindowMs = 1000L

    private fun serverTimeMs(): Long = (System.nanoTime() - startNanos) / 1_000_000L

    // ---------------------------------------------------------------- startup

    fun start() {
        val arenaDef = ArenaLoader.load(config.arenaFile)
        serverArena = ServerArena(arenaDef)
        world = World(serverArena, config)
        match = MatchController(world, config)
        world.setBotCount(config.botCount)

        socket = UdpServerSocket(config.bindAddress, config.udpPort)
        socket.bind()
        running = true

        printBanner(arenaDef.describe())
        runLoop()
    }

    private fun printBanner(arenaDescription: String) {
        Log.raw("")
        Log.raw("  =============================================================")
        Log.raw("   LAN FPS - authoritative game server")
        Log.raw("  =============================================================")
        Log.info("listening on ${config.bindAddress}:${config.udpPort} (UDP)")
        Log.info("protocolVersion=${GameConstants.PROTOCOL_VERSION}")
        Log.info("arena=${config.arenaName}")
        Log.info("mode=${world.mode.name}")
        Log.info("tickRate=${GameConstants.TICK_RATE}Hz snapshotRate=${GameConstants.SNAPSHOT_RATE}Hz")
        Log.info(config.describe())
        Log.info(arenaDescription)
        Log.info(serverArena.describeGraph())
        Log.info("bots spawned: ${world.bots.size}")

        val ips = UdpServerSocket.localIpv4Addresses()
        if (ips.isEmpty()) {
            Log.warn("could not detect a LAN IPv4 address - run `ipconfig` and use that IP")
        } else {
            Log.info("this machine's LAN address(es) - enter one of these on the phones:")
            for (ip in ips) Log.info("    $ip")
        }
        Log.info("configured client default IP: ${config.defaultServerIp}")
        if (ips.none { it.startsWith(config.defaultServerIp) }) {
            Log.warn(
                "this machine does not appear to own ${config.defaultServerIp} - " +
                    "enter the actual IP above on the Android clients",
            )
        }
        Log.raw("  -------------------------------------------------------------")
        Log.info("server ready. Press Ctrl+C to stop.")
        Log.raw("")
    }

    // ------------------------------------------------------------- main loop

    private fun runLoop() {
        var previousNanos = System.nanoTime()
        var accumulator = 0L
        var snapshotAccum = 0L
        var lobbyAccum = 0L
        var statsAccum = 0L
        var pingAccum = 0L
        val selfTestNanos = config.selfTestSeconds * 1_000_000_000L

        while (running) {
            val now = System.nanoTime()
            var frame = now - previousNanos
            previousNanos = now
            // Guard against a huge stall (debugger, laptop sleep) turning into a
            // "spiral of death" where we try to simulate thousands of ticks.
            if (frame > MAX_FRAME_NANOS) frame = MAX_FRAME_NANOS

            accumulator += frame
            snapshotAccum += frame
            lobbyAccum += frame
            statsAccum += frame
            pingAccum += frame

            drainInbound()

            while (accumulator >= GameConstants.TICK_NANOS) {
                // Guns are hot only during a live match, so the final score can
                // never drift away from the result we just announced.
                world.combatEnabled = match.isActive
                world.tick(GameConstants.TICK_DT)
                match.update(GameConstants.TICK_DT)
                accumulator -= GameConstants.TICK_NANOS
                tickCount++
                ticksThisSecond++
            }

            if (snapshotAccum >= GameConstants.SNAPSHOT_INTERVAL_NANOS) {
                snapshotAccum = 0
                broadcastSnapshot()
                broadcastMatchEvents()
            }

            if (lobbyAccum >= LOBBY_INTERVAL_NANOS) {
                lobbyAccum = 0
                broadcastLobbyState()
                checkTimeouts()
            }

            // P1-1: server-measured RTT, used for lag compensation.
            if (pingAccum >= GameConstants.PING_INTERVAL_MS * 1_000_000L) {
                pingAccum = 0
                sendServerPings()
            }

            if (statsAccum >= STATS_INTERVAL_NANOS) {
                statsAccum = 0
                logStats()
            }

            if (selfTestNanos > 0 && now - startNanos >= selfTestNanos) {
                Log.info("self-test window elapsed (${config.selfTestSeconds}s) - shutting down")
                running = false
                break
            }

            // Sleep out the rest of the tick without burning a core.
            val remaining = GameConstants.TICK_NANOS - accumulator
            if (remaining > 1_500_000L) {
                LockSupport.parkNanos(remaining - 1_000_000L)
            } else {
                Thread.yield()
            }
        }
        shutdownInternal()
    }

    // -------------------------------------------------------------- inbound

    private fun drainInbound() {
        var processed = 0
        while (processed < MAX_PACKETS_PER_ITERATION) {
            val packet = socket.poll() ?: break
            processed++
            try {
                handlePacket(packet)
            } catch (e: ProtocolException) {
                rejectedPackets++
                Log.debug("malformed packet from ${packet.address}: ${e.message}")
            } catch (e: Exception) {
                // A bad packet must never take the server down.
                rejectedPackets++
                Log.warn("error handling packet from ${packet.address}: $e")
            }
        }
    }

    private fun handlePacket(p: InboundPacket) {
        val result = Protocol.parse(p.data, p.length, header, reader)
        if (result != Protocol.ParseResult.OK) {
            rejectedPackets++
            Log.debug("dropped packet from ${p.address}:${p.port} -> $result")
            return
        }

        val nowMs = System.currentTimeMillis()
        when (header.type) {
            PacketTypes.DISCOVERY_REQUEST -> handleDiscovery(p)
            PacketTypes.CONNECT_REQUEST -> handleConnect(p, nowMs)
            PacketTypes.CLIENT_INPUT -> handleInput(p, nowMs)
            PacketTypes.PING -> handlePing(p, nowMs)
            PacketTypes.PONG -> handlePong(p)
            PacketTypes.DISCONNECT -> handleDisconnect(p)
            else -> Log.debug("ignoring ${PacketTypes.name(header.type)} from ${p.address}")
        }
    }

    private fun handleDiscovery(p: InboundPacket) {
        if (!config.enableDiscovery) return
        val info = Packets.DiscoveryResponse().apply {
            serverName = config.serverName
            arena = config.arenaName
            mode = world.mode.wire
            playerCount = sessions.size
            maxPlayers = config.maxPlayers
            botCount = world.bots.size
            udpPort = config.udpPort
        }
        Protocol.begin(writer, PacketTypes.DISCOVERY_RESPONSE)
        Packets.writeDiscoveryResponse(writer, info)
        val len = Protocol.end(writer)
        socket.send(writer.buffer, len, p.address, p.port)
        Log.debug("discovery reply -> ${p.address}:${p.port}")
    }

    private fun handleConnect(p: InboundPacket, nowMs: Long) {
        val request = Packets.readConnectRequest(reader)
        val key = ClientSession.endpointKey(p.address, p.port)

        // Re-sending CONNECT_REQUEST is normal when the accept was lost: reply
        // again with the same identity instead of creating a duplicate session.
        val existing = sessions[key]
        if (existing != null) {
            existing.touch(nowMs)
            // If this was a silent zombie, the mere fact it is talking again means
            // it reconnected on the same socket - bring it back to life.
            reactivateIfZombie(existing)
            sendConnectAccepted(existing)
            Log.debug("re-accepted ${existing.nickname} from $key")
            return
        }

        // P0-2: reconnect on a NEW socket via the resume token. If a zombie
        // session holds the matching token, re-bind it under the new endpoint
        // and hand back the same id/score/team instead of a fresh session.
        if (request.resumeToken != 0) {
            val zombie = sessions.values.firstOrNull {
                it.resumeToken == request.resumeToken && it.zombie
            }
            if (zombie != null) {
                sessions.remove(zombie.key)
                zombie.key = key
                zombie.address = p.address
                zombie.port = p.port
                zombie.touch(nowMs)
                zombie.zombie = false
                zombie.zombieDeadlineMs = 0
                sessions[key] = zombie
                sendConnectAccepted(zombie)
                Log.info(
                    "RECONNECT '${zombie.nickname}' id=${zombie.id} via token from $key " +
                        "(slot kept, score/team preserved)",
                )
                return
            }
        }

        // P0-3: flood protection. New sessions are rate-limited before we spend
        // any CPU or hand out a slot.
        if (!allowConnect(p.address)) {
            rejectedConnects++
            reject(p.address, p.port, "Connection rate limited - please retry in a moment")
            Log.warn("rejected connection from $key: rate limited")
            return
        }
        if (countSessionsForIp(p.address) >= config.maxSessionsPerIp) {
            rejectedConnects++
            reject(p.address, p.port, "Too many connections from this device/network")
            Log.warn("rejected connection from $key: too many sessions for this IP")
            return
        }

        if (sessions.size >= config.maxPlayers) {
            rejectedConnects++
            reject(p.address, p.port, "Server is full (${config.maxPlayers} players)")
            Log.info("rejected connection from $key: server full")
            return
        }

        // The ruleset belongs to whoever runs the server: server.properties or
        // `run-server.bat --mode=TDM`. `preferredMode` in the request is only an
        // advisory hint and is deliberately ignored - otherwise the first phone
        // to connect (which always asks for DM) would silently turn a TDM server
        // into a deathmatch and reset the scores.
        val requestedMode = GameMode.fromWire(request.preferredMode)
        if (requestedMode != world.mode) {
            Log.debug(
                "client asked for ${requestedMode.name}, server stays on " +
                    "${world.mode.name} (set 'mode' in server.properties to change it)",
            )
        }

        val nickname = sanitizeNickname(request.nickname)
        val id = allocatePlayerId()
        val session = ClientSession(id, p.address, p.port, nickname)
        // P0-2: hand the client an opaque token it can present to resume this
        // exact session (same id/score/team) after a silent disconnect.
        session.resumeToken = ThreadLocalRandom.current().nextInt(1, Int.MAX_VALUE)
        session.serverTimeoutMs = config.serverTimeoutMs
        session.touch(nowMs)
        sessions[key] = session

        val player = world.addPlayer(session)
        sendConnectAccepted(session)

        Log.info(
            "CONNECT '$nickname' id=$id from $key team=${player.team.name} " +
                "(${sessions.size}/${config.maxPlayers} players)",
        )
        match.pendingEvents.add(
            Packets.MatchEvent().apply {
                eventType = MatchEventType.PLAYER_JOINED
                killerId = id
                killerName = nickname
            },
        )
    }

    private fun sendConnectAccepted(session: ClientSession) {
        val entity = world.entities[session.id]
        val accepted = Packets.ConnectAccepted().apply {
            playerId = session.id
            team = entity?.team?.wire ?: 0
            mode = world.mode.wire
            arena = config.arenaName
            tickRate = GameConstants.TICK_RATE
            snapshotRate = GameConstants.SNAPSHOT_RATE
            serverTimeMs = serverTimeMs()
            assignedNickname = session.nickname
            arenaHash = serverArena.def.hash()
            resumeToken = session.resumeToken
        }
        Protocol.begin(writer, PacketTypes.CONNECT_ACCEPTED)
        Packets.writeConnectAccepted(writer, accepted)
        socket.send(writer.buffer, Protocol.end(writer), session.address, session.port)
    }

    private fun handleInput(p: InboundPacket, nowMs: Long) {
        val key = ClientSession.endpointKey(p.address, p.port)
        val session = sessions[key]
        if (session == null) {
            // Unknown peer: tell it to go back to the menu and reconnect.
            Protocol.begin(writer, PacketTypes.DISCONNECT)
            Packets.writeDisconnect(writer, "Unknown session - please reconnect")
            socket.send(writer.buffer, Protocol.end(writer), p.address, p.port)
            return
        }
        session.touch(nowMs)
        reactivateIfZombie(session)

        // P1-4: the client's ack is the newest MATCH_EVENT sequence it processed;
        // drop every event up to and including it from the resend queue.
        session.acknowledgeEvents(header.ack)

        Packets.readClientInput(reader, inputPacket)
        if (inputPacket.playerId != session.id) {
            Log.debug("input playerId mismatch from $key, ignoring")
            return
        }
        session.reportedPingMs = inputPacket.reportedPingMs
        session.enqueueInputs(inputPacket.commands)
    }

    private fun handlePing(p: InboundPacket, nowMs: Long) {
        val clientTime = Packets.readPing(reader)
        val session = sessions[ClientSession.endpointKey(p.address, p.port)]
        if (session != null) {
            session.touch(nowMs)
            reactivateIfZombie(session)
        }

        Protocol.begin(writer, PacketTypes.PONG)
        Packets.writePong(writer, clientTime, serverTimeMs())
        socket.send(writer.buffer, Protocol.end(writer), p.address, p.port)
    }

    /**
     * P1-1: server side of the RTT measurement. The server sends each session a
     * PING every second; the client answers with PONG (already implemented in
     * NetworkClient.rxLoop), and we time the round trip here.
     */
    private fun handlePong(p: InboundPacket) {
        val pong = Packets.Pong()
        Packets.readPong(reader, pong)
        val session = sessions[ClientSession.endpointKey(p.address, p.port)] ?: return
        if (session.lastServerPingSentMs > 0) {
            val rtt = (System.currentTimeMillis() - session.lastServerPingSentMs).toDouble()
            if (rtt in 0.0..4000.0) {
                session.smoothedRttMs = if (session.smoothedRttMs == 0.0) {
                    rtt
                } else {
                    session.smoothedRttMs + (rtt - session.smoothedRttMs) * 0.25
                }
            }
        }
    }

    /** P1-1: sends a PING to every client so we can measure RTT for lag comp. */
    private fun sendServerPings() {
        if (sessions.isEmpty()) return
        val nowMs = System.currentTimeMillis()
        for (s in sessions.values) {
            s.lastServerPingSentMs = nowMs
            Protocol.begin(writer, PacketTypes.PING)
            Packets.writePing(writer, serverTimeMs())
            val len = Protocol.end(writer)
            socket.send(writer.buffer, len, s.address, s.port)
        }
    }

    private fun handleDisconnect(p: InboundPacket) {
        val key = ClientSession.endpointKey(p.address, p.port)
        val session = sessions.remove(key) ?: return
        world.removeEntity(session.id)
        Log.info("DISCONNECT '${session.nickname}' id=${session.id} (client requested)")
        match.pendingEvents.add(
            Packets.MatchEvent().apply {
                eventType = MatchEventType.PLAYER_LEFT
                killerId = session.id
                killerName = session.nickname
            },
        )
    }

    private fun checkTimeouts() {
        val nowMs = System.currentTimeMillis()
        val iterator = sessions.entries.iterator()
        while (iterator.hasNext()) {
            val session = iterator.next().value
            if (!session.isTimedOut(nowMs)) continue

            if (!session.zombie) {
                // P0-2: first silence timeout turns the session into a zombie.
                // We deliberately KEEP the entity in the world (standing still,
                // still shootable) so its score/team are preserved for a
                // reconnect with the resume token.
                session.zombie = true
                session.zombieDeadlineMs = nowMs + config.zombieTimeoutMs
                Log.info(
                    "TIMEOUT '${session.nickname}' id=${session.id} -> zombie for " +
                        "${config.zombieTimeoutMs} ms (awaiting reconnect)",
                )
            } else if (nowMs >= session.zombieDeadlineMs) {
                iterator.remove()
                world.removeEntity(session.id)
                Log.info(
                    "ZOMBIE EXPIRED '${session.nickname}' id=${session.id} - slot reclaimed",
                )
                match.pendingEvents.add(
                    Packets.MatchEvent().apply {
                        eventType = MatchEventType.PLAYER_LEFT
                        killerId = session.id
                        killerName = session.nickname
                    },
                )
            }
        }
        pruneConnectWindows(nowMs)
    }

    // ---- P0-2/P0-3 helpers --------------------------------------------------

    /** Clears the zombie flag on a session that just sent a packet again. */
    private fun reactivateIfZombie(session: ClientSession) {
        if (session.zombie) {
            session.zombie = false
            session.zombieDeadlineMs = 0
            Log.info(
                "session '${session.nickname}' id=${session.id} re-activated from zombie",
            )
        }
    }

    /** Sends a CONNECT_REJECTED with the given reason (uses the shared writer). */
    private fun reject(address: InetAddress, port: Int, reason: String) {
        Protocol.begin(writer, PacketTypes.CONNECT_REJECTED)
        Packets.writeConnectRejected(writer, reason)
        socket.send(writer.buffer, Protocol.end(writer), address, port)
    }

    /** P0-3: checks the global + per-IP sliding-window connect limits. */
    private fun allowConnect(address: InetAddress): Boolean {
        val now = System.currentTimeMillis()
        val ip = address.hostAddress
        pruneQueue(recentConnectTimes, now)
        if (recentConnectTimes.size >= config.maxConnectsPerSecond) return false

        val perIp = recentConnectPerIp.getOrPut(ip) { ArrayDeque() }
        pruneQueue(perIp, now)
        if (perIp.size >= config.maxConnectsPerIpPerSecond) return false

        recentConnectTimes.addLast(now)
        perIp.addLast(now)
        return true
    }

    /** How many sessions (including zombies) are active for a source IP. */
    private fun countSessionsForIp(address: InetAddress): Int =
        sessions.values.count { it.address.hostAddress == address.hostAddress }

    private fun pruneQueue(q: ArrayDeque<Long>, now: Long) {
        while (q.isNotEmpty() && now - q.first() >= connectWindowMs) q.removeFirst()
    }

    /** Drops empty per-IP windows so the map cannot grow without bound. */
    private fun pruneConnectWindows(now: Long) {
        val it = recentConnectPerIp.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            pruneQueue(e.value, now)
            if (e.value.isEmpty()) it.remove()
        }
    }

    // ------------------------------------------------------------- outbound

    private fun broadcastSnapshot() {
        if (sessions.isEmpty()) return
        snapshotSequence = (snapshotSequence + 1) and 0xFFFF
        for (session in sessions.values) {
            // P1-2: built per client so each gets FULL keyframes + DELTAs sized
            // to its own base. lastProcessedInputSeq is stamped inside.
            snapshotBuilder.buildForClient(
                world, match, tickCount, serverTimeMs(), snapshotSequence, session,
            )
            socket.send(
                snapshotBuilder.buffer, snapshotBuilder.length,
                session.address, session.port,
            )
        }
    }

    private fun broadcastMatchEvents() {
        // Assign a global sequence to each new event and queue it for delivery
        // to every session. Events are re-sent (below) until the client acks.
        if (match.pendingEvents.isNotEmpty()) {
            for (event in match.pendingEvents) {
                matchEventSequence = (matchEventSequence + 1) and 0xFFFF
                event.eventSeq = matchEventSequence
            }
            if (sessions.isNotEmpty()) {
                for (event in match.pendingEvents) {
                    for (session in sessions.values) session.addPendingEvent(event)
                }
            }
            match.pendingEvents.clear()
        }

        // P1-4: reliably deliver/redeliver every unacknowledged event.
        if (sessions.isEmpty()) return
        for (session in sessions.values) {
            for (event in session.drainPendingEvents()) {
                Protocol.begin(writer, PacketTypes.MATCH_EVENT)
                Packets.writeMatchEvent(writer, event)
                val len = Protocol.end(writer)
                socket.send(writer.buffer, len, session.address, session.port)
            }
        }
    }

    private fun broadcastLobbyState() {
        if (sessions.isEmpty()) return
        val lobby = Packets.LobbyState().apply {
            serverName = config.serverName
            arena = config.arenaName
            mode = world.mode.wire
            matchState = match.state
            botCount = world.bots.size
            maxPlayers = config.maxPlayers
            matchTimeRemaining = match.timeRemaining
        }
        for (e in world.score.standings(world.entities.values)) {
            lobby.players.add(
                Packets.LobbyPlayer().apply {
                    id = e.id
                    name = e.name
                    team = e.team.wire
                    bot = e.isBot
                    kills = e.kills
                    deaths = e.deaths
                    pingMs = (e as? PlayerEntity)?.session?.reportedPingMs ?: 0
                },
            )
        }
        Protocol.begin(writer, PacketTypes.LOBBY_STATE)
        Packets.writeLobbyState(writer, lobby)
        val len = Protocol.end(writer)
        for (session in sessions.values) {
            socket.send(writer.buffer, len, session.address, session.port)
        }
    }

    private fun logStats() {
        val tps = ticksThisSecond / (STATS_INTERVAL_NANOS / 1_000_000_000L)
        ticksThisSecond = 0
        Log.info(
            "stats: players=${sessions.size} bots=${world.bots.size} " +
                "tick=$tickCount (~${tps}/s) " +
                "state=${MatchState.name(match.state)} " +
                "time=${match.timeRemaining.toInt()}s " +
                "score[${world.scoreSummary()}] " +
                "rx=${socket.packetsReceived.get()} tx=${socket.packetsSent.get()} " +
                "rejected=$rejectedPackets rejectedConnects=$rejectedConnects " +
                "zombies=${sessions.values.count { it.zombie }} " +
                "snapPeak=${snapshotBuilder.peakSize}B",
        )
        for (session in sessions.values) {
            Log.debug(
                "  ${session.nickname}: ping=${session.reportedPingMs}ms " +
                    "serverRtt=${session.smoothedRttMs.toInt()}ms " +
                    "queued=${session.queuedInputs()} applied=${session.commandsApplied} " +
                    "dropped=${session.commandsDropped} " +
                    "pendingEvents=${session.pendingEventCount}",
            )
        }
    }

    // ------------------------------------------------------------- shutdown

    fun stop() {
        if (!running) return
        Log.info("shutdown requested")
        running = false
    }

    private fun shutdownInternal() {
        Log.info("closing sockets and disconnecting clients...")
        Protocol.begin(writer, PacketTypes.DISCONNECT)
        Packets.writeDisconnect(writer, "Server shutting down")
        val len = Protocol.end(writer)
        for (session in sessions.values) {
            socket.send(writer.buffer, len, session.address, session.port)
            Log.info("  disconnected ${session.nickname} (#${session.id})")
        }
        sessions.clear()
        socket.close()
        Log.info(
            "final stats: ticks=$tickCount rx=${socket.packetsReceived.get()} " +
                "tx=${socket.packetsSent.get()} rejected=$rejectedPackets " +
                "peakSnapshot=${snapshotBuilder.peakSize}B",
        )
        Log.info("server stopped.")
    }

    // -------------------------------------------------------------- helpers

    private fun allocatePlayerId(): Int {
        val used = sessions.values.map { it.id }.toHashSet()
        var candidate = nextPlayerId
        var guard = 0
        while (used.contains(candidate) || candidate >= GameConstants.BOT_ID_BASE) {
            candidate = if (candidate >= GameConstants.BOT_ID_BASE - 1) 1 else candidate + 1
            if (++guard > GameConstants.BOT_ID_BASE) break
        }
        nextPlayerId = if (candidate >= GameConstants.BOT_ID_BASE - 1) 1 else candidate + 1
        return candidate
    }

    /** Trims, strips control characters and de-duplicates player nicknames. */
    private fun sanitizeNickname(raw: String): String {
        var name = raw.trim().filter { it.code in 32..126 || it.code > 160 }
        if (name.length > GameConstants.MAX_NICKNAME_LENGTH) {
            name = name.substring(0, GameConstants.MAX_NICKNAME_LENGTH)
        }
        if (name.isBlank()) name = "Player"

        val taken = world.entities.values.map { it.name }.toHashSet()
        if (!taken.contains(name)) return name
        var suffix = 2
        while (taken.contains("$name$suffix") && suffix < 100) suffix++
        val candidate = "$name$suffix"
        return if (candidate.length <= GameConstants.MAX_NICKNAME_LENGTH) {
            candidate
        } else {
            candidate.substring(candidate.length - GameConstants.MAX_NICKNAME_LENGTH)
        }
    }

    /** Exposed for the headless self-test. */
    fun worldForTest(): World = world
    fun matchForTest(): MatchController = match

    companion object {
        private const val MAX_PACKETS_PER_ITERATION = 512
        private const val MAX_FRAME_NANOS = 250_000_000L
        private const val LOBBY_INTERVAL_NANOS = 500_000_000L
        private const val STATS_INTERVAL_NANOS = 10_000_000_000L
    }
}
