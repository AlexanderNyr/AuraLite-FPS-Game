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
import com.lanfps.shared.SnapshotDelta
import com.lanfps.shared.SnapshotKind
import com.lanfps.shared.Team
import com.lanfps.shared.Vec3
import com.lanfps.shared.Weapons
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

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
    @Volatile private var arena: ArenaDef,
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

    /** P1-2: last full snapshot (our delta base) and scratch for reconstruction. */
    private val baseFull = ArrayList<EntityState>()
    private val reconstructed = ArrayList<EntityState>()

    /** P3-4: a queued MODE_VOTE (-1 = none); sent by the tx thread. */
    private val pendingModeVote = AtomicInteger(-1)

    // Scratch for the per-pellet tracer scatter (tx thread only).
    private val tracerRng = java.util.Random()
    private val basisR = Vec3()
    private val basisU = Vec3()
    private val pelletDir = Vec3()

    /** P2-3: the activity swaps the arena when the server rotates maps. */
    fun updateArena(newArena: ArenaDef) {
        arena = newArena
    }

    /**
     * P3-4: queues a lobby vote for the ruleset of the next match. Called from
     * the UI thread; the actual datagram goes out on the tx thread, so it is
     * safe to poke at any time while connected (and harmless when not).
     */
    fun voteMode(mode: GameMode) {
        pendingModeVote.set(mode.wire)
    }

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
        req.password = state.password

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

        // P0-2: remember the resume token; P0-1: register our own name in the
        // roster (names no longer travel inside snapshots).
        state.resumeToken = acc.resumeToken
        state.setRosterName(acc.playerId, state.nickname)

        val localHash = arena.hash()
        state.arenaMismatch = acc.arenaHash != 0 && acc.arenaHash != localHash
        if (state.arenaMismatch) {
            AndroidLog.w(
                "ARENA MISMATCH: server=0x%08X client=0x%08X - geometry may differ"
                    .format(acc.arenaHash, localHash),
            )
            // P2-3: if the mismatch is just the server sitting on a rotated map
            // (we joined mid-rotation), hot-load that map instead of playing on
            // with hash-mismatched geometry.
            if (acc.arena.isNotEmpty() && acc.arena != state.arena.name) {
                state.pendingArenaName = acc.arena
                state.pendingArenaHash = acc.arenaHash
            }
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
        var lastPadCueMs = 0L

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

            // Server silence detection. P0-2: instead of bailing to the menu we
            // enter a reconnect loop that presents our resume token. Only if that
            // exhausts its window do we actually give up.
            if (state.lastServerContactMs > 0 &&
                now - state.lastServerContactMs > GameConstants.CLIENT_TIMEOUT_MS
            ) {
                if (!attemptReconnect(sock, addr, port, writer, now)) return
                continue
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
            // Corrections fade slower on a laggy link (see Prediction.decayError),
            // so a burst of them reads as one smooth re-join, not teleport spam.
            pred?.decayError(GameConstants.TICK_DT, rttMs.toFloat())
            publishLocalTransform(pred, cmd)

            // P4-4: a jump pad shove is a prediction-true vertical impulse far
            // larger than any jump (6.6 m/s); cue the whoosh, throttled.
            if (playing && state.alive && pred != null && pred.initialised &&
                pred.body.velocity.y > 8f && now - lastPadCueMs > 400
            ) {
                lastPadCueMs = now
                SoundManager.jumpPad()
            }

            // ---- predicted muzzle flash / tracer -----------------------------
            if (playing && state.alive && cmd.firePressed && now >= nextFireAt) {
                // The local fire click follows the same clock the server gives
                // the CURRENT weapon: shotgun thumps, sniper waits.
                nextFireAt = now + (Weapons.byId(state.localWeapon).fireInterval * 1000f).toLong()
                spawnLocalTracer(pred, eye, dir, now)
            }
            if (!cmd.firePressed && nextFireAt > now + 200) nextFireAt = now

            // ---- send ---------------------------------------------------------
            recentCommands.add(cmd.copy())
            while (recentCommands.size > GameConstants.INPUT_REDUNDANCY) recentCommands.removeAt(0)

            // P3-4: flush a queued lobby vote (one datagram; the server
            // timestamp-orders it with everything else from this endpoint).
            val vote = pendingModeVote.getAndSet(-1)
            if (vote >= 0) {
                try {
                    Protocol.begin(writer, PacketTypes.MODE_VOTE)
                    Packets.writeModeVote(writer, vote)
                    val vlen = Protocol.end(writer)
                    sock.send(DatagramPacket(writer.buffer, vlen, addr, port))
                    state.packetsOut++
                } catch (e: Exception) {
                    AndroidLog.d("mode vote send failed: ${e.message}")
                }
            }

            try {
                // P1-4: the header ack tells the server the newest MATCH_EVENT we
                // have processed, so it stops re-sending old events.
                Protocol.begin(
                    writer, PacketTypes.CLIENT_INPUT, cmd.sequence,
                    state.highestEventSeq.coerceAtLeast(0),
                )
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

    /**
     * P0-2: blocks in a short reconnect loop until the server re-accepts us with
     * our resume token (same id/score/team), or the window elapses.
     * @return true if reconnected, false if the caller should give up.
     */
    private fun attemptReconnect(
        sock: DatagramSocket,
        addr: InetAddress,
        port: Int,
        writer: BinaryWriter,
        nowMs: Long,
    ): Boolean {
        val phaseBefore = state.phase
        state.phase = Phase.RECONNECTING
        val msg = "Connection lost — reconnecting…"
        AndroidLog.w(msg)
        state.statusText = msg
        listener.onDisconnected(msg, false)

        // Clear the stale handshake result so we only react to a fresh
        // CONNECT_ACCEPTED from the server while reconnecting.
        accepted = null

        val deadline = nowMs + GameConstants.RECONNECT_TIMEOUT_MS
        var lastSend = 0L
        while (running.get() && !wantDisconnect.get()) {
            val now = System.currentTimeMillis()
            if (now >= deadline) {
                val fail = "Could not reconnect to the server."
                AndroidLog.w(fail)
                state.errorText = fail
                state.phase = Phase.DISCONNECTED
                listener.onDisconnected(fail, true)
                return false
            }

            drainSnapshots(now)
            drainEvents()

            val acc = accepted
            if (acc != null) {
                accepted = null
                onAccepted(acc)
                // Resume where we were (PLAYING/LOBBY) rather than dumping the
                // player back into a fresh handshake.
                state.phase = phaseBefore
                state.lastServerContactMs = now
                return true
            }

            if (now - lastSend >= 1000) {
                lastSend = now
                sendReconnectRequest(sock, addr, port, writer)
            }
            try {
                Thread.sleep(50)
            } catch (_: InterruptedException) {
                break
            }
        }
        return false
    }

    /** P0-2: resends CONNECT_REQUEST carrying our resume token. */
    private fun sendReconnectRequest(
        sock: DatagramSocket,
        addr: InetAddress,
        port: Int,
        writer: BinaryWriter,
    ) {
        val req = Packets.ConnectRequest().apply {
            nickname = state.nickname
            preferredMode = GameMode.DM.wire
            clientTimeMs = System.currentTimeMillis()
            resumeToken = state.resumeToken
            password = state.password
        }
        try {
            Protocol.begin(writer, PacketTypes.CONNECT_REQUEST)
            Packets.writeConnectRequest(writer, req)
            val len = Protocol.end(writer)
            sock.send(DatagramPacket(writer.buffer, len, addr, port))
            state.packetsOut++
        } catch (e: Exception) {
            AndroidLog.d("reconnect request send failed: ${e.message}")
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
     * Draws the burst the player just fired.
     *
     * This is cosmetic only. The rays are traced against level geometry and the
     * *interpolated* positions of other entities purely to decide where the
     * tracers should stop; whether anybody was actually hit is decided by the
     * server, on its own timeline, and reaches us as a health change.
     *
     * P2-1: the visual now honours the weapon being held — the shotgun throws a
     * visible fan of pellets, the sniper kicks like a mule.
     */
    private fun spawnLocalTracer(pred: Prediction?, eye: Vec3, dir: Vec3, now: Long) {
        if (pred == null) return
        pred.eyePosition(eye)
        MathUtil.forwardFromAngles(pred.body.yaw, pred.body.pitch, dir)

        val weapon = Weapons.byId(state.localWeapon)
        alignSpreadBasis(dir)
        val tanSpread = if (weapon.spreadDeg > 0f) {
            kotlin.math.tan(weapon.spreadDeg * MathUtil.DEG_TO_RAD)
        } else {
            0f
        }

        val snap = state.snapshots.latest
        val box = com.lanfps.shared.Aabb()

        for (p in 0 until weapon.pellets) {
            pelletDir.set(dir)
            if (tanSpread > 0f) {
                pelletDir.addScaled(basisR, (tracerRng.nextFloat() * 2f - 1f) * tanSpread)
                pelletDir.addScaled(basisU, (tracerRng.nextFloat() * 2f - 1f) * tanSpread)
                pelletDir.normalize()
            }

            var dist = RayMath.raycastArena(eye, pelletDir, weapon.range, arena)
            if (snap != null) {
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
                    val t = RayMath.rayAabb(eye, pelletDir, box, dist)
                    if (t != RayMath.NO_HIT && t < dist) dist = t
                }
            }

            state.addTracer(
                eye.x, eye.y - 0.08f, eye.z,
                eye.x + pelletDir.x * dist, eye.y + pelletDir.y * dist, eye.z + pelletDir.z * dist,
                local = true,
            )
        }

        state.lastLocalFireMs = now
        state.muzzleFlashUntilMs = now + 45
        state.recoilPitch = MathUtil.clamp(
            state.recoilPitch + weapon.recoilPitchDeg, 0f, 3.4f,
        )
        SoundManager.gunshot() // P1-3: local muzzle report
    }

    /**
     * Builds the orthonormal basis (dir, basisR, basisU) the pellet scatter is
     * sprayed in — identical maths to the server's burst cast, so what you see
     * is honestly what the server rolls.
     */
    private fun alignSpreadBasis(dir: Vec3) {
        basisR.set(-dir.z, 0f, dir.x)
        if (basisR.lengthSquared() < 1e-6f) basisR.set(1f, 0f, 0f)
        basisR.normalize()
        basisU.set(
            basisR.z * dir.y - basisR.y * dir.z,
            basisR.x * dir.z - basisR.z * dir.x,
            basisR.y * dir.x - basisR.x * dir.y,
        )
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
        // P1-2: a DELTA snapshot carries only changed entities + removed ids.
        // Rebuild a full state from our last keyframe so interpolation and the
        // HUD always have the whole world. If we have no base yet (first packet
        // after connect is always FULL, but guard anyway) wait for the keyframe.
        if (snap.kind == SnapshotKind.DELTA) {
            if (baseFull.isEmpty()) return
            val delta = SnapshotDelta()
            delta.changed.addAll(snap.deltaChanged)
            delta.removed.addAll(snap.deltaRemoved)
            SnapshotDelta.apply(baseFull, delta, reconstructed)
            snap.entities.clear()
            snap.entities.addAll(reconstructed)
            snap.kind = SnapshotKind.FULL
        } else {
            baseFull.clear()
            for (e in snap.entities) baseFull.add(e.copy())
        }

        // P0-1: names no longer arrive in snapshots; join them from the roster
        // so the interpolation buffer, HUD plates and scoreboard see them.
        for (e in snap.entities) {
            e.name = if (e.id == state.localPlayerId) {
                state.nickname
            } else {
                state.rosterName(e.id) ?: ""
            }
        }
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
                SoundManager.hit() // P1-3: hitmarker sound
            }
            prevHealth[e.id] = e.health

            // Remote muzzle flashes / tracers.
            if (e.firing && e.alive) {
                spawnRemoteTracer(e)
                // P1-3: hear distant shots, quieter the further away.
                val dist = kotlin.math.sqrt(
                    (e.x - state.eyeX) * (e.x - state.eyeX) +
                        (e.z - state.eyeZ) * (e.z - state.eyeZ),
                )
                val vol = (1f - dist / 70f).coerceIn(0f, 1f) * 0.5f
                if (vol > 0.02f) SoundManager.gunshot(vol)
            }
        }

        val me = snap.findEntity(state.localPlayerId) ?: return

        val wasAlive = state.alive
        val oldHealth = state.health
        state.health = me.health
        state.alive = me.alive
        state.kills = me.kills
        state.deaths = me.deaths
        state.localTeam = me.teamEnum
        // P2-1/P2-2: the server is the boss of what we are holding and how many
        // rounds are left in it; the HUD and viewmodel just mirror it.
        state.localWeapon = me.weapon
        state.localAmmo = me.ammo
        // P4: armor pool and grenade pouch mirror straight from the server.
        state.localArmor = me.armor
        state.localGrenades = me.grenades

        if (me.health < oldHealth && me.alive) {
            state.damageFlashUntilMs = now + 260
            state.lastDamageTakenMs = now // MainActivity vibrates on this edge
            SoundManager.damage() // P1-3: taking damage
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
                SoundManager.death() // P1-3: death sound
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
            state.spectateId = -1 // P2-5: back in our own body
            SoundManager.respawn() // P1-3: respawn cue
            AndroidLog.d("local player respawned at (%.1f, %.1f, %.1f)".format(me.x, me.y, me.z))
            return
        }

        pred.reconcile(me, snap.lastProcessedInputSeq)
    }

    private fun spawnRemoteTracer(e: EntityState) {
        val weapon = Weapons.byId(e.weapon)
        val dir = Vec3()
        MathUtil.forwardFromAngles(e.yaw, e.pitch, dir)
        val eye = Vec3(e.x, e.y + GameConstants.EYE_HEIGHT, e.z)

        alignSpreadBasis(dir)
        val tanSpread = if (weapon.spreadDeg > 0f) {
            kotlin.math.tan(weapon.spreadDeg * MathUtil.DEG_TO_RAD)
        } else {
            0f
        }
        // Full pellets would flood the tracer pool on a busy server; three rays
        // already read clearly as a shotgun blast.
        val rays = minOf(weapon.pellets, 3)

        for (p in 0 until rays) {
            pelletDir.set(dir)
            if (tanSpread > 0f) {
                pelletDir.addScaled(basisR, (tracerRng.nextFloat() * 2f - 1f) * tanSpread)
                pelletDir.addScaled(basisU, (tracerRng.nextFloat() * 2f - 1f) * tanSpread)
                pelletDir.normalize()
            }
            val dist = RayMath.raycastArena(eye, pelletDir, weapon.range, arena)
            state.addTracer(
                eye.x, eye.y - 0.05f, eye.z,
                eye.x + pelletDir.x * dist, eye.y + pelletDir.y * dist, eye.z + pelletDir.z * dist,
                local = false,
            )
        }
    }

    private fun winningTeam(snap: Snapshot): Int = when {
        snap.redScore > snap.blueScore -> Team.RED.wire
        snap.blueScore > snap.redScore -> Team.BLUE.wire
        else -> Team.NONE.wire
    }

    /** Decodes the "0x%08X" arena-hash field a MATCH_START event carries (P2-3). */
    private fun parseArenaHashField(text: String): Int {
        if (!text.startsWith("0x")) return 0
        return try {
            text.substring(2).toLong(16).toInt()
        } catch (_: Exception) {
            0
        }
    }

    private fun drainEvents() {
        var ev = events.poll()
        while (ev != null) {
            // P1-4: the server re-sends events until acked; drop duplicates and
            // stale ones (identified by sequence) before processing.
            if (state.highestEventSeq >= 0 &&
                !InputCommand.sequenceGreaterThan(ev.eventSeq, state.highestEventSeq)
            ) {
                ev = events.poll()
                continue
            }
            state.highestEventSeq = ev.eventSeq
            when (ev.eventType) {
                MatchEventType.KILL -> {
                    state.addKill(ev.killerName, ev.victimName, ev.killerId, ev.victimId)
                    // P2-5: while we wait out the respawn, watch the fight from
                    // the killer's eyes instead of staring at the floor.
                    if (ev.victimId == state.localPlayerId && ev.killerId != state.localPlayerId) {
                        state.spectateId = ev.killerId
                    }
                }

                MatchEventType.MATCH_START -> {
                    AndroidLog.i("match started")
                    state.clearKillFeed()
                    // NB: spectateId is deliberately NOT cleared here. A new
                    // match respawns everyone, and the alive-transition in
                    // applySnapshot is the authoritative P2-5 reset; clearing
                    // here would only add a second, racy owner of the same flag.
                    if (state.phase == Phase.ENDED) state.phase = Phase.PLAYING
                    // P2-3: MATCH_START carries the arena identity (name in
                    // killerName, "0x...." hash in victimName). When the match
                    // rotated to a different map, ask the activity to hot-load
                    // it before the mismatch hurts anyone.
                    if (ev.killerName.isNotEmpty() && ev.killerName != state.arena.name) {
                        state.pendingArenaName = ev.killerName
                        state.pendingArenaHash = parseArenaHashField(ev.victimName)
                        AndroidLog.i("match rotated to ${ev.killerName} - hot-loading the map")
                    }
                    listener.onMatchStateChanged(MatchState.ACTIVE, 0)
                }

                MatchEventType.MATCH_END -> {
                    AndroidLog.i("match ended, winner=${ev.extra}")
                    if (state.phase == Phase.PLAYING) state.phase = Phase.ENDED
                    SoundManager.matchEnd() // P1-3: end-of-match jingle
                    listener.onMatchStateChanged(MatchState.ENDED, ev.extra)
                }

                MatchEventType.PICKUP -> {
                    // P4-5: a pickup was consumed. Our own grabs get the bright
                    // chime; other people's grabs would be pure noise, skip them.
                    if (ev.killerId == state.localPlayerId) {
                        SoundManager.pickup()
                        AndroidLog.d("picked up kind=${ev.extra}")
                    }
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
                        // Always store: the tx thread nulls it after consuming, so
                        // this also feeds the P0-2 reconnect flow.
                        accepted = Packets.readConnectAccepted(reader)

                    PacketTypes.CONNECT_REJECTED ->
                        rejectedReason = Packets.readConnectRejected(reader)

                    PacketTypes.DISCONNECT ->
                        kickedReason = Packets.readDisconnect(reader).ifEmpty { "server closed" }

                    PacketTypes.PONG -> {
                        Packets.readPong(reader, pong)
                        val rtt = (System.currentTimeMillis() - pong.clientTimeMs).toDouble()
                        if (rtt in 0.0..4000.0) {
                            // P7-2: asymmetric smoothing. The 1 Hz sample rate
                            // means a slow alpha keeps a stale spike on the HUD
                            // for ~4 s — long enough to look "physically
                            // impossible" on a LAN. Trust good news fast
                            // (alpha 0.5), spread bad news slowly (alpha 0.25)
                            // so one buffered beacon burst cannot swamp the readout.
                            val alpha = if (haveRtt && rtt < rttMs) 0.5 else 0.25
                            rttMs = if (!haveRtt) rtt else rttMs + (rtt - rttMs) * alpha
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
                        // P2-6 / P3-4: the rules of the match and the vote tally.
                        state.killLimit = lobby.killLimit
                        state.votesDm = lobby.votesDm
                        state.votesTdm = lobby.votesTdm
                        // P0-1: LOBBY_STATE is now the ONLY place names arrive.
                        for (p in lobby.players) state.setRosterName(p.id, p.name)
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
