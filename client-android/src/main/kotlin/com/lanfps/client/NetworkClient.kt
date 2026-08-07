package com.lanfps.client

import com.lanfps.shared.ArenaDef
import com.lanfps.shared.BinaryReader
import com.lanfps.shared.BinaryWriter
import com.lanfps.shared.EntityState
import com.lanfps.shared.GameConstants
import com.lanfps.shared.GameMode
import com.lanfps.shared.InputCommand
import com.lanfps.shared.MatchEventType
import com.lanfps.shared.MatchState
import com.lanfps.shared.MathUtil
import com.lanfps.shared.PacketTypes
import com.lanfps.shared.Packets
import com.lanfps.shared.Protocol
import com.lanfps.shared.RayMath
import com.lanfps.shared.Snapshot
import com.lanfps.shared.Team
import com.lanfps.shared.Vec3
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The whole client side of the protocol: one UDP socket, one send/simulate
 * thread and one receive thread.
 *
 * Division of labour:
 *  - **rx thread** does nothing but validate datagrams and hand decoded
 *    snapshots/events to the tx thread through a queue. It never touches game
 *    state, so a malformed packet can never corrupt the simulation.
 *  - **tx thread** owns prediction. Once per tick it drains the queue,
 *    reconciles, samples the touch controls, predicts locally and sends the
 *    command (plus the previous two, for redundancy).
 *
 * The client is deliberately powerless: it sends *intent* only. It never tells
 * the server "I hit someone" - it fires a ray purely to draw a tracer, and the
 * server independently decides whether anything was hit.
 */
class NetworkClient(
    private val state: ClientGameState,
    private val input: InputController,
    private val arena: ArenaDef,
    private val listener: Listener,
) {

    interface Listener {
        fun onConnected(playerId: Int, team: Team, mode: GameMode)
        fun onRejected(reason: String)
        fun onDisconnected(reason: String, wasError: Boolean)
        fun onMatchStateChanged(newState: Int, winningTeam: Int)
    }

    private val running = AtomicBoolean(false)
    private val connected = AtomicBoolean(false)
    private val wantDisconnect = AtomicBoolean(false)

    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var serverAddress: InetAddress? = null
    @Volatile private var serverPort: Int = GameConstants.DEFAULT_UDP_PORT

    private var txThread: Thread? = null
    private var rxThread: Thread? = null

    /** Decoded snapshots waiting for the tx thread. */
    private val inbox = ConcurrentLinkedQueue<Snapshot>()

    /** Decoded match events waiting for the tx thread. */
    private val events = ConcurrentLinkedQueue<Packets.MatchEvent>()

    /** Signals from rx -> tx about the handshake. */
    @Volatile private var accepted: Packets.ConnectAccepted? = null
    @Volatile private var rejectedReason: String? = null
    @Volatile private var kickedReason: String? = null

    /** Round-trip time, exponentially smoothed. */
    @Volatile private var rttMs: Double = 0.0
    @Volatile private var haveRtt = false

    /** History used for the redundancy window. */
    private val recentCommands = ArrayList<InputCommand>(8)

    /** Previous per-entity health, for the hit marker heuristic. */
    private val prevHealth = HashMap<Int, Int>()

    // ------------------------------------------------------------------ API

    fun isRunning(): Boolean = running.get()

    fun isConnected(): Boolean = connected.get()

    /** Starts the handshake. Returns immediately; progress arrives via [Listener]. */
    fun start(ip: String, port: Int, nickname: String) {
        if (running.getAndSet(true)) return
        wantDisconnect.set(false)
        connected.set(false)
        accepted = null
        rejectedReason = null
        kickedReason = null
        haveRtt = false
        rttMs = 0.0
        inbox.clear()
        events.clear()
        prevHealth.clear()
        recentCommands.clear()

        state.serverIp = ip
        state.serverPort = port
        state.nickname = nickname
        state.phase = Phase.CONNECTING
        state.statusText = "Resolving $ip:$port ..."

        txThread = Thread({ txLoop(ip, port, nickname) }, "lanfps-net-tx").apply {
            priority = Thread.NORM_PRIORITY + 1
            start()
        }
    }

    /** Asks the threads to stop and sends a courtesy DISCONNECT. */
    fun stop() {
        if (!running.get()) return
        wantDisconnect.set(true)
        // Give the tx thread ~200 ms to send DISCONNECT, then force the sockets shut.
        Thread {
            try {
                txThread?.join(400)
            } catch (_: InterruptedException) {
            }
            shutdownSockets()
        }.start()
    }

    /** Hard stop, used from Activity.onDestroy. */
    fun stopNow() {
        wantDisconnect.set(true)
        running.set(false)
        shutdownSockets()
    }

    private fun shutdownSockets() {
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
        rxThread?.interrupt()
    }

    // ------------------------------------------------------------- tx thread

    private fun txLoop(ip: String, port: Int, nickname: String) {
        val writer = BinaryWriter(1024)
        var sock: DatagramSocket? = null
        try {
            val addr = InetAddress.getByName(ip)
            serverAddress = addr
            serverPort = port

            sock = DatagramSocket()
            sock.soTimeout = 0
            sock.trafficClass = 0x10 // IPTOS_LOWDELAY - best effort, ignored if unsupported
            socket = sock

            rxThread = Thread({ rxLoop(sock) }, "lanfps-net-rx").apply {
                priority = Thread.NORM_PRIORITY + 1
                start()
            }

            if (!handshake(sock, addr, port, nickname, writer)) return

            gameLoop(sock, addr, port, writer)
        } catch (e: Exception) {
            if (running.get() && !wantDisconnect.get()) {
                AndroidLog.e("network thread failed", e)
                state.errorText = "${e.javaClass.simpleName}: ${e.message ?: "network error"}"
                state.phase = Phase.DISCONNECTED
                listener.onDisconnected(state.errorText, true)
            }
        } finally {
            // Courtesy DISCONNECT so the server frees the slot instantly instead of
            // waiting for the 8 s timeout.
            try {
                val a = serverAddress
                if (a != null && sock != null && !sock.isClosed && connected.get()) {
                    Protocol.begin(writer, PacketTypes.DISCONNECT)
                    Packets.writeDisconnect(writer, "client quit")
                    val len = Protocol.end(writer)
                    sock.send(DatagramPacket(writer.buffer, len, a, serverPort))
                }
            } catch (_: Exception) {
            }
            connected.set(false)
            running.set(false)
            try {
                sock?.close()
            } catch (_: Exception) {
            }
            socket = null
            AndroidLog.i("network thread stopped")
        }
    }

    /** Sends CONNECT_REQUEST until the server answers or we give up. */
    private fun handshake(
        sock: DatagramSocket,
        addr: InetAddress,
        port: Int,
        nickname: String,
        writer: BinaryWriter,
    ): Boolean {
        val req = Packets.ConnectRequest()
        req.nickname = nickname
        req.preferredMode = GameMode.DM.wire

        var attempt = 0
        while (attempt < CONNECT_ATTEMPTS && running.get() && !wantDisconnect.get()) {
            attempt++
            state.statusText = "Connecting to $addr:$port  (try $attempt/$CONNECT_ATTEMPTS)"
            req.clientTimeMs = System.currentTimeMillis()

            Protocol.begin(writer, PacketTypes.CONNECT_REQUEST, attempt)
            Packets.writeConnectRequest(writer, req)
            val len = Protocol.end(writer)
            try {
                sock.send(DatagramPacket(writer.buffer, len, addr, port))
                state.packetsOut++
            } catch (e: Exception) {
                AndroidLog.w("connect send failed: ${e.message}")
            }

            // Wait up to 300 ms for an answer.
            val deadline = System.currentTimeMillis() + CONNECT_RETRY_MS
            while (System.currentTimeMillis() < deadline) {
                val acc = accepted
                if (acc != null) {
                    onAccepted(acc)
                    return true
                }
                val rej = rejectedReason
                if (rej != null) {
                    AndroidLog.w("server rejected us: $rej")
                    state.errorText = rej
                    state.phase = Phase.DISCONNECTED
                    listener.onRejected(rej)
                    return false
                }
                Thread.sleep(10)
            }
        }

        if (running.get() && !wantDisconnect.get()) {
            val msg = "No answer from $addr:$port after $CONNECT_ATTEMPTS tries.\n" +
                "Check: same Wi-Fi, server running, Windows firewall allows UDP $port."
            AndroidLog.w("handshake timed out")
            state.errorText = msg
            state.phase = Phase.DISCONNECTED
            listener.onDisconnected(msg, true)
        }
        return false
    }

    private fun onAccepted(acc: Packets.ConnectAccepted) {
        connected.set(true)
        state.localPlayerId = acc.playerId
        state.localTeam = Team.fromWire(acc.team)
        state.mode = GameMode.fromWire(acc.mode)
        state.nickname = acc.assignedNickname.ifEmpty { state.nickname }
        state.lastServerContactMs = System.currentTimeMillis()

        val localHash = arena.hash()
        state.arenaMismatch = acc.arenaHash != 0 && acc.arenaHash != localHash
        if (state.arenaMismatch) {
            AndroidLog.w(
                "ARENA MISMATCH: server=0x%08X client=0x%08X - geometry may differ"
                    .format(acc.arenaHash, localHash),
            )
        }

        state.prediction?.reset()
        state.statusText = "Connected as ${state.nickname} (id ${acc.playerId})"
        state.phase = Phase.LOBBY
        AndroidLog.i(
            "connected: id=${acc.playerId} team=${state.localTeam} mode=${state.mode} " +
                "arena=${acc.arena} tick=${acc.tickRate} snap=${acc.snapshotRate}",
        )
        listener.onConnected(acc.playerId, state.localTeam, state.mode)
    }

    /** Fixed 60 Hz: reconcile -> sample -> predict -> send. */
    private fun gameLoop(
        sock: DatagramSocket,
        addr: InetAddress,
        port: Int,
        writer: BinaryWriter,
    ) {
        val cmd = InputCommand()
        val eye = Vec3()
        val dir = Vec3()

        var nextTick = System.nanoTime()
        var lastPing = 0L
        var lastSnapCountAt = System.currentTimeMillis()
        var lastSnapCount = 0
        var nextFireAt = 0L

        while (running.get() && !wantDisconnect.get()) {
            val nowNanos = System.nanoTime()
            if (nowNanos < nextTick) {
                val sleepMs = (nextTick - nowNanos) / 1_000_000L
                if (sleepMs > 1) {
                    try {
                        Thread.sleep(sleepMs - 1)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
                continue
            }
            nextTick += GameConstants.TICK_NANOS
            // If we fell far behind (app was backgrounded) do not try to catch up.
            if (System.nanoTime() - nextTick > 250_000_000L) nextTick = System.nanoTime()

            val now = System.currentTimeMillis()

            kickedReason?.let {
                AndroidLog.i("server disconnected us: $it")
                state.errorText = it
                state.phase = Phase.DISCONNECTED
                listener.onDisconnected(it, false)
                return
            }

            drainSnapshots(now)
            drainEvents()

            // Server silence detection.
            if (state.lastServerContactMs > 0 &&
                now - state.lastServerContactMs > GameConstants.CLIENT_TIMEOUT_MS
            ) {
                val msg = "Lost connection to the server (no packets for " +
                    "${GameConstants.CLIENT_TIMEOUT_MS / 1000} s)."
                AndroidLog.w(msg)
                state.errorText = msg
                state.phase = Phase.DISCONNECTED
                listener.onDisconnected(msg, true)
                return
            }

            val pred = state.prediction
            val playing = state.phase == Phase.PLAYING

            // ---- sample input + predict --------------------------------------
            if (playing && state.alive) {
                input.sample(cmd, now)
            } else {
                input.sampleIdle(cmd, now)
            }

            if (pred != null && pred.initialised && playing && state.alive) {
                pred.applyLocal(cmd.copy())
            } else {
                // Not simulating locally: keep view angles live so the camera still
                // responds while dead or in the lobby.
                pred?.body?.yaw = cmd.yaw
                pred?.body?.pitch = cmd.pitch
            }
            pred?.decayError(GameConstants.TICK_DT)
            publishLocalTransform(pred, cmd)

            // ---- predicted muzzle flash / tracer -----------------------------
            if (playing && state.alive && cmd.firePressed && now >= nextFireAt) {
                nextFireAt = now + (GameConstants.WEAPON_FIRE_INTERVAL * 1000f).toLong()
                spawnLocalTracer(pred, eye, dir, now)
            }
            if (!cmd.firePressed && nextFireAt > now + 200) nextFireAt = now

            // ---- send ---------------------------------------------------------
            recentCommands.add(cmd.copy())
            while (recentCommands.size > GameConstants.INPUT_REDUNDANCY) recentCommands.removeAt(0)

            try {
                Protocol.begin(writer, PacketTypes.CLIENT_INPUT, cmd.sequence)
                Packets.writeClientInput(writer, state.localPlayerId, state.pingMs, recentCommands)
                val len = Protocol.end(writer)
                sock.send(DatagramPacket(writer.buffer, len, addr, port))
                state.packetsOut++
            } catch (e: Exception) {
                AndroidLog.w("input send failed: ${e.message}")
            }

            if (now - lastPing >= GameConstants.PING_INTERVAL_MS) {
                lastPing = now
                try {
                    Protocol.begin(writer, PacketTypes.PING)
                    Packets.writePing(writer, now)
                    val len = Protocol.end(writer)
                    sock.send(DatagramPacket(writer.buffer, len, addr, port))
                    state.packetsOut++
                } catch (_: Exception) {
                }
            }

            // Snapshot rate meter, for the HUD / troubleshooting.
            if (now - lastSnapCountAt >= 1000) {
                val c = state.snapshots.receivedCount
                state.snapshotsPerSec = (c - lastSnapCount) * 1000f / (now - lastSnapCountAt)
                lastSnapCount = c
                lastSnapCountAt = now
            }
        }
    }

    private fun publishLocalTransform(pred: Prediction?, cmd: InputCommand) {
        if (pred != null && pred.initialised) {
            val p = pred.renderPosition()
            state.eyeX = p.x
            state.eyeY = p.y + pred.body.eyeHeight
            state.eyeZ = p.z
            state.localSpeed = pred.horizontalSpeed
            state.localOnGround = pred.body.onGround
        }
        state.viewYaw = cmd.yaw
        state.viewPitch = cmd.pitch
    }

    /**
     * Draws the bullet the player just fired.
     *
     * This is cosmetic only. The ray is traced against level geometry and the
     * *interpolated* positions of other entities purely to decide where the
     * tracer should stop; whether anybody was actually hit is decided by the
     * server, on its own timeline, and reaches us as a health change.
     */
    private fun spawnLocalTracer(pred: Prediction?, eye: Vec3, dir: Vec3, now: Long) {
        if (pred == null) return
        pred.eyePosition(eye)
        MathUtil.forwardFromAngles(pred.body.yaw, pred.body.pitch, dir)

        var dist = RayMath.raycastArena(eye, dir, GameConstants.WEAPON_RANGE, arena)

        val snap = state.snapshots.latest
        if (snap != null) {
            val box = com.lanfps.shared.Aabb()
            for (e in snap.entities) {
                if (e.id == state.localPlayerId || !e.alive) continue
                val h = if (e.crouching) {
                    GameConstants.PLAYER_CROUCH_HEIGHT
                } else {
                    GameConstants.PLAYER_HEIGHT
                }
                box.set(
                    e.x - GameConstants.PLAYER_RADIUS, e.y, e.z - GameConstants.PLAYER_RADIUS,
                    e.x + GameConstants.PLAYER_RADIUS, e.y + h, e.z + GameConstants.PLAYER_RADIUS,
                )
                val t = RayMath.rayAabb(eye, dir, box, dist)
                if (t != RayMath.NO_HIT && t < dist) dist = t
            }
        }

        state.addTracer(
            eye.x, eye.y - 0.08f, eye.z,
            eye.x + dir.x * dist, eye.y + dir.y * dist, eye.z + dir.z * dist,
            local = true,
        )
        state.lastLocalFireMs = now
        state.muzzleFlashUntilMs = now + 45
        state.recoilPitch = MathUtil.clamp(state.recoilPitch + 0.55f, 0f, 3.0f)
    }

    // ---- inbox draining ----------------------------------------------------

    private fun drainSnapshots(now: Long) {
        var snap = inbox.poll()
        var applied = 0
        while (snap != null) {
            applySnapshot(snap, now)
            applied++
            snap = inbox.poll()
        }
        if (applied > 0) state.lastServerContactMs = now
    }

    private fun applySnapshot(snap: Snapshot, now: Long) {
        state.snapshots.add(snap, now)

        state.mode = GameMode.fromWire(snap.mode)
        state.matchTimeRemaining = snap.matchTimeRemaining
        state.redScore = snap.redScore
        state.blueScore = snap.blueScore

        val previousMatchState = state.matchState
        if (snap.matchState != previousMatchState) {
            state.matchState = snap.matchState
            if (snap.matchState == MatchState.ENDED) {
                if (state.phase == Phase.PLAYING) state.phase = Phase.ENDED
                listener.onMatchStateChanged(snap.matchState, winningTeam(snap))
            } else if (snap.matchState == MatchState.ACTIVE && state.phase == Phase.ENDED) {
                state.phase = Phase.PLAYING
                state.clearKillFeed()
                listener.onMatchStateChanged(snap.matchState, 0)
            } else {
                listener.onMatchStateChanged(snap.matchState, 0)
            }
        }

        // Hit-marker heuristic: someone else lost health shortly after we fired.
        val firedRecently = now - state.lastLocalFireMs < 260
        for (e in snap.entities) {
            if (e.id == state.localPlayerId) continue
            val old = prevHealth[e.id]
            if (old != null && e.health < old && firedRecently && e.health >= 0) {
                state.hitMarkerUntilMs = now + 140
            }
            prevHealth[e.id] = e.health

            // Remote muzzle flashes / tracers.
            if (e.firing && e.alive) spawnRemoteTracer(e)
        }

        val me = snap.findEntity(state.localPlayerId) ?: return

        val wasAlive = state.alive
        val oldHealth = state.health
        state.health = me.health
        state.alive = me.alive
        state.kills = me.kills
        state.deaths = me.deaths
        state.localTeam = me.teamEnum

        if (me.health < oldHealth && me.alive) {
            state.damageFlashUntilMs = now + 260
        }

        val pred = state.prediction ?: return
        if (!me.alive) {
            // Dead: no prediction, camera sits where the server says the body is.
            pred.teleportTo(me)
            state.eyeX = me.x
            state.eyeY = me.y + 0.5f
            state.eyeZ = me.z
            if (wasAlive) {
                state.respawnInSec = GameConstants.RESPAWN_DELAY_SEC
                AndroidLog.d("local player died")
            } else {
                state.respawnInSec = (state.respawnInSec - 1f / GameConstants.SNAPSHOT_RATE)
                    .coerceAtLeast(0f)
            }
            return
        }

        if (!wasAlive && me.alive) {
            // Respawned: adopt the server spawn transform, including its yaw.
            pred.teleportTo(me)
            input.setAngles(me.yaw, 0f)
            state.respawnInSec = 0f
            AndroidLog.d("local player respawned at (%.1f, %.1f, %.1f)".format(me.x, me.y, me.z))
            return
        }

        pred.reconcile(me, snap.lastProcessedInputSeq)
    }

    private fun spawnRemoteTracer(e: EntityState) {
        val dir = Vec3()
        MathUtil.forwardFromAngles(e.yaw, e.pitch, dir)
        val eye = Vec3(e.x, e.y + GameConstants.EYE_HEIGHT, e.z)
        val dist = RayMath.raycastArena(eye, dir, GameConstants.WEAPON_RANGE, arena)
        state.addTracer(
            eye.x, eye.y - 0.05f, eye.z,
            eye.x + dir.x * dist, eye.y + dir.y * dist, eye.z + dir.z * dist,
            local = false,
        )
    }

    private fun winningTeam(snap: Snapshot): Int = when {
        snap.redScore > snap.blueScore -> Team.RED.wire
        snap.blueScore > snap.redScore -> Team.BLUE.wire
        else -> Team.NONE.wire
    }

    private fun drainEvents() {
        var ev = events.poll()
        while (ev != null) {
            when (ev.eventType) {
                MatchEventType.KILL ->
                    state.addKill(ev.killerName, ev.victimName, ev.killerId, ev.victimId)

                MatchEventType.MATCH_START -> {
                    AndroidLog.i("match started")
                    state.clearKillFeed()
                    if (state.phase == Phase.ENDED) state.phase = Phase.PLAYING
                    listener.onMatchStateChanged(MatchState.ACTIVE, 0)
                }

                MatchEventType.MATCH_END -> {
                    AndroidLog.i("match ended, winner=${ev.extra}")
                    if (state.phase == Phase.PLAYING) state.phase = Phase.ENDED
                    listener.onMatchStateChanged(MatchState.ENDED, ev.extra)
                }

                MatchEventType.PLAYER_JOINED -> AndroidLog.d("player joined: ${ev.killerName}")
                MatchEventType.PLAYER_LEFT -> AndroidLog.d("player left: ${ev.killerName}")
            }
            ev = events.poll()
        }
    }

    // ------------------------------------------------------------- rx thread

    private fun rxLoop(sock: DatagramSocket) {
        val buf = ByteArray(GameConstants.MAX_PACKET_SIZE)
        val packet = DatagramPacket(buf, buf.size)
        val header = Protocol.Header()
        val reader = BinaryReader()
        val pong = Packets.Pong()

        AndroidLog.i("rx thread started on local port ${sock.localPort}")

        while (running.get() && !sock.isClosed) {
            try {
                packet.setData(buf, 0, buf.size)
                sock.receive(packet)
            } catch (e: Exception) {
                if (running.get() && !wantDisconnect.get()) {
                    AndroidLog.d("receive ended: ${e.javaClass.simpleName}")
                }
                break
            }

            // Ignore anything that did not come from our server: a LAN is full of
            // broadcast noise and we must never let it reach the decoders.
            val expected = serverAddress
            if (expected != null && packet.address != expected) continue

            state.packetsIn++

            val result = Protocol.parse(buf, packet.length, header, reader)
            if (result != Protocol.ParseResult.OK) {
                state.malformedIn++
                if (state.malformedIn <= 5) AndroidLog.w("dropped packet: $result")
                continue
            }

            try {
                when (header.type) {
                    PacketTypes.SERVER_SNAPSHOT -> {
                        val snap = Snapshot()
                        Packets.readSnapshot(reader, snap)
                        inbox.add(snap)
                        // Bound the queue: if the tx thread ever stalls we would
                        // rather drop old state than run out of memory.
                        while (inbox.size > 32) inbox.poll()
                    }

                    PacketTypes.CONNECT_ACCEPTED ->
                        if (accepted == null) accepted = Packets.readConnectAccepted(reader)

                    PacketTypes.CONNECT_REJECTED ->
                        rejectedReason = Packets.readConnectRejected(reader)

                    PacketTypes.DISCONNECT ->
                        kickedReason = Packets.readDisconnect(reader).ifEmpty { "server closed" }

                    PacketTypes.PONG -> {
                        Packets.readPong(reader, pong)
                        val rtt = (System.currentTimeMillis() - pong.clientTimeMs).toDouble()
                        if (rtt in 0.0..4000.0) {
                            rttMs = if (!haveRtt) rtt else rttMs + (rtt - rttMs) * 0.25
                            haveRtt = true
                            state.pingMs = rttMs.toInt()
                        }
                        state.lastServerContactMs = System.currentTimeMillis()
                    }

                    PacketTypes.PING -> {
                        // Server-initiated keepalive: answer it so it can measure us.
                        val t = Packets.readPing(reader)
                        val w = BinaryWriter(64)
                        Protocol.begin(w, PacketTypes.PONG)
                        Packets.writePong(w, t, System.currentTimeMillis())
                        val len = Protocol.end(w)
                        val a = serverAddress
                        if (a != null) sock.send(DatagramPacket(w.buffer, len, a, serverPort))
                    }

                    PacketTypes.MATCH_EVENT -> {
                        val ev = Packets.MatchEvent()
                        Packets.readMatchEvent(reader, ev)
                        events.add(ev)
                        while (events.size > 32) events.poll()
                    }

                    PacketTypes.LOBBY_STATE -> {
                        val lobby = Packets.readLobbyState(reader)
                        state.serverName = lobby.serverName
                        state.mode = GameMode.fromWire(lobby.mode)
                    }

                    else -> AndroidLog.d("ignored packet type ${header.type}")
                }
            } catch (e: Exception) {
                state.malformedIn++
                AndroidLog.w("failed to decode ${PacketTypes.name(header.type)}: ${e.message}")
            }
        }
        AndroidLog.i("rx thread stopped")
    }

    // ------------------------------------------------------------- discovery

    /**
     * Broadcasts DISCOVERY_REQUEST and collects answers for [timeoutMs].
     *
     * Broadcast can be blocked by AP client isolation or by the Windows
     * firewall, so this is strictly a convenience: typing the IP by hand always
     * works and is what the README tells people to do first.
     */
    fun discover(port: Int, timeoutMs: Int, onDone: (List<DiscoveredServer>) -> Unit) {
        Thread {
            val found = ArrayList<DiscoveredServer>()
            var sock: DatagramSocket? = null
            try {
                sock = DatagramSocket().apply {
                    broadcast = true
                    soTimeout = 250
                }
                val writer = BinaryWriter(64)
                Protocol.begin(writer, PacketTypes.DISCOVERY_REQUEST)
                Packets.writeDiscoveryRequest(writer, "android")
                val len = Protocol.end(writer)

                for (target in broadcastTargets()) {
                    try {
                        sock.send(DatagramPacket(writer.buffer, len, target, port))
                    } catch (e: Exception) {
                        AndroidLog.d("broadcast to $target failed: ${e.message}")
                    }
                }

                val buf = ByteArray(2048)
                val packet = DatagramPacket(buf, buf.size)
                val header = Protocol.Header()
                val reader = BinaryReader()
                val deadline = System.currentTimeMillis() + timeoutMs

                while (System.currentTimeMillis() < deadline) {
                    try {
                        packet.setData(buf, 0, buf.size)
                        sock.receive(packet)
                    } catch (_: Exception) {
                        continue
                    }
                    if (Protocol.parse(buf, packet.length, header, reader)
                        != Protocol.ParseResult.OK
                    ) {
                        continue
                    }
                    if (header.type != PacketTypes.DISCOVERY_RESPONSE) continue
                    val d = Packets.readDiscoveryResponse(reader)
                    val server = DiscoveredServer(
                        ip = packet.address.hostAddress ?: continue,
                        port = if (d.udpPort in 1..65535) d.udpPort else port,
                        name = d.serverName,
                        mode = GameMode.fromWire(d.mode).name,
                        players = d.playerCount,
                        maxPlayers = d.maxPlayers,
                    )
                    if (found.none { it.ip == server.ip && it.port == server.port }) {
                        found.add(server)
                        AndroidLog.i("discovered ${server.name} at ${server.ip}:${server.port}")
                    }
                }
            } catch (e: Exception) {
                AndroidLog.w("discovery failed: ${e.message}")
            } finally {
                try {
                    sock?.close()
                } catch (_: Exception) {
                }
            }
            onDone(found)
        }.apply { isDaemon = true }.start()
    }

    /** 255.255.255.255 plus the directed broadcast of every IPv4 interface. */
    private fun broadcastTargets(): List<InetAddress> {
        val out = ArrayList<InetAddress>()
        try {
            out.add(InetAddress.getByName("255.255.255.255"))
        } catch (_: Exception) {
        }
        try {
            val ifaces = java.net.NetworkInterface.getNetworkInterfaces() ?: return out
            for (nif in ifaces) {
                if (!nif.isUp || nif.isLoopback) continue
                for (ia in nif.interfaceAddresses) {
                    val b = ia.broadcast ?: continue
                    if (out.none { it == b }) out.add(b)
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    companion object {
        const val CONNECT_ATTEMPTS = 16
        const val CONNECT_RETRY_MS = 300L

        /** Convenience for the menu: is this a plausible IPv4 literal? */
        fun looksLikeIpv4(s: String): Boolean {
            val parts = s.trim().split('.')
            if (parts.size != 4) return false
            return parts.all { p ->
                p.isNotEmpty() && p.length <= 3 && p.all { it.isDigit() } && p.toInt() <= 255
            }
        }

        fun parseEndpoint(text: String, defaultPort: Int): InetSocketAddress? {
            val t = text.trim()
            if (t.isEmpty()) return null
            val idx = t.lastIndexOf(':')
            return try {
                if (idx > 0) {
                    InetSocketAddress(t.substring(0, idx), t.substring(idx + 1).toInt())
                } else {
                    InetSocketAddress(t, defaultPort)
                }
            } catch (_: Exception) {
                null
            }
        }
    }
}
