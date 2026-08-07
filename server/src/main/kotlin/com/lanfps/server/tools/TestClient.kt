package com.lanfps.server.tools

import com.lanfps.shared.BinaryReader
import com.lanfps.shared.BinaryWriter
import com.lanfps.shared.EntityState
import com.lanfps.shared.GameConstants
import com.lanfps.shared.GameMode
import com.lanfps.shared.InputButtons
import com.lanfps.shared.InputCommand
import com.lanfps.shared.MatchState
import com.lanfps.shared.PacketTypes
import com.lanfps.shared.Packets
import com.lanfps.shared.Protocol
import com.lanfps.shared.Snapshot
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt
import kotlin.system.exitProcess

/**
 * Headless protocol-level test client.
 *
 * It is a real client: it performs the full handshake, streams input at 60 Hz
 * with redundancy, consumes snapshots, and measures ping — but it has no
 * graphics. Two uses:
 *
 *  1. **Automated verification** of the whole network stack without a phone.
 *  2. **LAN troubleshooting** on site: if this connects from a laptop but the
 *     phones do not, the problem is the phone/Wi-Fi, not the server or firewall.
 *
 * Run it from the shipped jar:
 * ```
 *   java -cp server.jar com.lanfps.server.tools.TestClientKt --ip=192.168.1.25 --nick=Probe --seconds=10
 * ```
 */
fun main(args: Array<String>) {
    var ip = "127.0.0.1"
    var port = GameConstants.DEFAULT_UDP_PORT
    var nick = "Probe"
    var seconds = 10
    var mode = GameMode.DM
    var fire = true
    var verbose = false

    for (raw in args) {
        val a = raw.removePrefix("--")
        val eq = a.indexOf('=')
        val key = if (eq > 0) a.substring(0, eq) else a
        val value = if (eq > 0) a.substring(eq + 1) else ""
        when (key.lowercase()) {
            "ip", "host", "server" -> ip = value
            "port" -> port = value.toIntOrNull() ?: port
            "nick", "name" -> nick = value
            "seconds", "time" -> seconds = value.toIntOrNull() ?: seconds
            "mode" -> mode = GameMode.parse(value)
            "nofire" -> fire = false
            "verbose", "v" -> verbose = true
            "help", "h" -> {
                println(
                    """
                    LAN FPS headless test client

                      --ip=192.168.1.25   server address (default 127.0.0.1)
                      --port=7777         server UDP port
                      --nick=Probe        nickname to join with
                      --seconds=10        how long to play
                      --mode=DM|TDM       requested mode (only honoured on an empty server)
                      --nofire            do not shoot
                      --verbose           print every snapshot summary
                    """.trimIndent(),
                )
                return
            }
        }
    }

    val client = TestClient(ip, port, nick, mode, fire, verbose)
    val ok = client.run(seconds)
    exitProcess(if (ok) 0 else 1)
}

class TestClient(
    private val ip: String,
    private val port: Int,
    private val nick: String,
    private val mode: GameMode,
    private val shoot: Boolean,
    private val verbose: Boolean,
) {
    private val socket = DatagramSocket()
    private val address: InetAddress = InetAddress.getByName(ip)
    private val writer = BinaryWriter(2048)
    private val reader = BinaryReader()
    private val header = Protocol.Header()
    private val snapshot = Snapshot()

    /** P1-2: last full state, to rebuild full snapshots from DELTAs. */
    private val baseFull = ArrayList<EntityState>()
    private val reconstructed = ArrayList<EntityState>()

    private val running = AtomicBoolean(true)

    @Volatile private var playerId = -1
    @Volatile private var accepted = false
    @Volatile private var rejectedReason: String? = null

    // Statistics gathered from the server.
    @Volatile private var snapshotsReceived = 0
    @Volatile private var lobbyReceived = 0
    @Volatile private var matchEvents = 0
    @Volatile private var pongsReceived = 0
    @Volatile private var badPackets = 0
    @Volatile private var lastPingMs = 0
    @Volatile private var minPing = Int.MAX_VALUE
    @Volatile private var maxPing = 0
    @Volatile private var lastSnapshotTimeMs = 0L
    @Volatile private var maxEntitiesSeen = 0
    @Volatile private var lastAckedInput = 0
    @Volatile private var lastMatchState = -1
    @Volatile private var sawOwnEntity = false
    @Volatile private var tookDamage = false
    @Volatile private var minHealthSeen = GameConstants.MAX_HEALTH

    private var firstPos: FloatArray? = null
    private var lastPos = FloatArray(3)
    private var maxTravel = 0f

    private val history = ArrayList<InputCommand>()
    private var inputSequence = 0

    fun run(seconds: Int): Boolean {
        socket.soTimeout = 200
        val receiver = Thread({ receiveLoop() }, "test-client-rx").apply {
            isDaemon = true
            start()
        }

        println("[client] connecting to $ip:$port as '$nick' (mode ${mode.name})...")
        if (!handshake()) {
            println("[client] FAILED: ${rejectedReason ?: "no CONNECT_ACCEPTED within timeout"}")
            running.set(false)
            socket.close()
            return false
        }
        println("[client] connected. playerId=$playerId")

        playFor(seconds)

        running.set(false)
        sendDisconnect()
        Thread.sleep(120)
        socket.close()
        receiver.interrupt()

        return report(seconds)
    }

    // ---- handshake --------------------------------------------------------

    private fun handshake(): Boolean {
        val request = Packets.ConnectRequest().apply {
            nickname = nick
            preferredMode = mode.wire
            clientTimeMs = System.currentTimeMillis()
        }
        val deadline = System.currentTimeMillis() + 5000
        var attempt = 0
        while (System.currentTimeMillis() < deadline && !accepted && rejectedReason == null) {
            // Retry: CONNECT_REQUEST is not acked, so we simply repeat it.
            Protocol.begin(writer, PacketTypes.CONNECT_REQUEST, attempt)
            Packets.writeConnectRequest(writer, request)
            send(Protocol.end(writer))
            attempt++
            Thread.sleep(250)
        }
        return accepted
    }

    // ---- gameplay loop ----------------------------------------------------

    private fun playFor(seconds: Int) {
        val endAt = System.nanoTime() + seconds * 1_000_000_000L
        var nextTick = System.nanoTime()
        var lastPing = 0L
        var yaw = 0f
        var tick = 0

        while (System.nanoTime() < endAt) {
            val now = System.nanoTime()
            if (now < nextTick) {
                java.util.concurrent.locks.LockSupport.parkNanos(nextTick - now)
                continue
            }
            nextTick += GameConstants.TICK_NANOS

            // Walk in a slow circle so the server has to move us, and fire in bursts.
            yaw += 0.9f
            val cmd = InputCommand().apply {
                sequence = inputSequence
                clientTimeMs = System.currentTimeMillis()
                moveForward = 1f
                moveRight = 0f
                this.yaw = com.lanfps.shared.MathUtil.wrapDegrees(yaw)
                pitch = 0f
                buttons = if (shoot && (tick / 30) % 2 == 0) InputButtons.FIRE else 0
            }
            inputSequence = (inputSequence + 1) and 0xFFFF
            history.add(cmd)
            while (history.size > GameConstants.INPUT_REDUNDANCY) history.removeAt(0)

            Protocol.begin(writer, PacketTypes.CLIENT_INPUT, cmd.sequence)
            Packets.writeClientInput(writer, playerId, lastPingMs, history)
            send(Protocol.end(writer))

            // Ping once per second.
            if (System.currentTimeMillis() - lastPing >= GameConstants.PING_INTERVAL_MS) {
                lastPing = System.currentTimeMillis()
                Protocol.begin(writer, PacketTypes.PING)
                Packets.writePing(writer, lastPing)
                send(Protocol.end(writer))
            }
            tick++
        }
    }

    private fun sendDisconnect() {
        Protocol.begin(writer, PacketTypes.DISCONNECT)
        Packets.writeDisconnect(writer, "test client finished")
        send(Protocol.end(writer))
    }

    private fun send(length: Int) {
        try {
            socket.send(DatagramPacket(writer.buffer, 0, length, address, port))
        } catch (e: Exception) {
            if (running.get()) println("[client] send failed: $e")
        }
    }

    // ---- receive ----------------------------------------------------------

    private fun receiveLoop() {
        val buffer = ByteArray(GameConstants.MAX_PACKET_SIZE)
        val packet = DatagramPacket(buffer, buffer.size)
        val localReader = BinaryReader()
        val localHeader = Protocol.Header()

        while (running.get()) {
            try {
                packet.setData(buffer, 0, buffer.size)
                socket.receive(packet)
            } catch (e: java.net.SocketTimeoutException) {
                continue
            } catch (e: Exception) {
                if (running.get()) badPackets++
                continue
            }

            val result = Protocol.parse(packet.data, packet.length, localHeader, localReader)
            if (result != Protocol.ParseResult.OK) {
                badPackets++
                continue
            }

            try {
                when (localHeader.type) {
                    PacketTypes.CONNECT_ACCEPTED -> {
                        val a = Packets.readConnectAccepted(localReader)
                        playerId = a.playerId
                        accepted = true
                        println(
                            "[client] CONNECT_ACCEPTED id=${a.playerId} team=${a.team} " +
                                "mode=${GameMode.fromWire(a.mode).name} arena=${a.arena} " +
                                "arenaHash=0x%08X tick=${a.tickRate}Hz snap=${a.snapshotRate}Hz"
                                    .format(a.arenaHash),
                        )
                    }

                    PacketTypes.CONNECT_REJECTED -> {
                        rejectedReason = Packets.readConnectRejected(localReader)
                    }

                    PacketTypes.SERVER_SNAPSHOT -> handleSnapshot(localReader)

                    PacketTypes.PONG -> {
                        val pong = Packets.readPong(localReader)
                        val rtt = (System.currentTimeMillis() - pong.clientTimeMs).toInt()
                        lastPingMs = rtt
                        if (rtt < minPing) minPing = rtt
                        if (rtt > maxPing) maxPing = rtt
                        pongsReceived++
                    }

                    PacketTypes.LOBBY_STATE -> {
                        val lobby = Packets.readLobbyState(localReader)
                        lobbyReceived++
                        if (verbose && lobbyReceived <= 2) {
                            println(
                                "[client] LOBBY '${lobby.serverName}' mode=${lobby.mode} " +
                                    "players=${lobby.players.size} bots=${lobby.botCount}",
                            )
                            for (p in lobby.players) {
                                println(
                                    "           ${p.name} team=${p.team} bot=${p.bot} " +
                                        "K:${p.kills} D:${p.deaths} ping=${p.pingMs}",
                                )
                            }
                        }
                    }

                    PacketTypes.MATCH_EVENT -> {
                        val e = Packets.readMatchEvent(localReader)
                        matchEvents++
                        if (verbose) {
                            println("[client] MATCH_EVENT type=${e.eventType} ${e.killerName} -> ${e.victimName}")
                        }
                    }

                    PacketTypes.DISCONNECT -> {
                        println("[client] server said: ${Packets.readDisconnect(localReader)}")
                    }
                }
            } catch (e: Exception) {
                badPackets++
            }
        }
    }

    private fun handleSnapshot(r: BinaryReader) {
        Packets.readSnapshot(r, snapshot)

        // P1-2: the server sends DELTA snapshots between FULL keyframes. Rebuild
        // a full state from our last keyframe so the diagnostics below always see
        // the whole world, exactly like the Android client does.
        if (snapshot.kind == com.lanfps.shared.SnapshotKind.DELTA) {
            val d = com.lanfps.shared.SnapshotDelta()
            d.changed.addAll(snapshot.deltaChanged)
            d.removed.addAll(snapshot.deltaRemoved)
            snapshot.entities.clear()
            com.lanfps.shared.SnapshotDelta.apply(baseFull, d, reconstructed)
            snapshot.entities.addAll(reconstructed)
            snapshot.kind = com.lanfps.shared.SnapshotKind.FULL
            baseFull.clear()
            for (e in snapshot.entities) baseFull.add(e.copy())
        } else {
            baseFull.clear()
            for (e in snapshot.entities) baseFull.add(e.copy())
        }

        snapshotsReceived++
        lastSnapshotTimeMs = System.currentTimeMillis()
        lastAckedInput = snapshot.lastProcessedInputSeq
        lastMatchState = snapshot.matchState
        if (snapshot.entities.size > maxEntitiesSeen) maxEntitiesSeen = snapshot.entities.size

        val me: EntityState? = snapshot.findEntity(playerId)
        if (me != null) {
            sawOwnEntity = true
            if (me.health < minHealthSeen) {
                minHealthSeen = me.health
                if (me.health < GameConstants.MAX_HEALTH) tookDamage = true
            }
            if (firstPos == null) firstPos = floatArrayOf(me.x, me.y, me.z)
            lastPos[0] = me.x; lastPos[1] = me.y; lastPos[2] = me.z
            val f = firstPos!!
            val dx = me.x - f[0]; val dz = me.z - f[2]
            val travel = kotlin.math.sqrt(dx * dx + dz * dz)
            if (travel > maxTravel) maxTravel = travel
        }

        if (verbose && snapshotsReceived % 30 == 0) {
            println(
                "[client] snapshot #$snapshotsReceived tick=${snapshot.serverTick} " +
                    "entities=${snapshot.entities.size} ack=${snapshot.lastProcessedInputSeq} " +
                    "state=${MatchState.name(snapshot.matchState)} " +
                    "pos=(%.1f, %.1f, %.1f)".format(lastPos[0], lastPos[1], lastPos[2]),
            )
        }
    }

    // ---- verdict ----------------------------------------------------------

    private fun report(seconds: Int): Boolean {
        val expectedSnapshots = seconds * GameConstants.SNAPSHOT_RATE
        val snapshotRate = snapshotsReceived.toFloat() / seconds
        val checks = ArrayList<Pair<String, Boolean>>()

        checks += "handshake accepted" to accepted
        checks += "received snapshots" to (snapshotsReceived > 0)
        checks += "snapshot rate >= 80% of ${GameConstants.SNAPSHOT_RATE} Hz " +
            "(got ${"%.1f".format(snapshotRate)} Hz)" to (snapshotsReceived >= expectedSnapshots * 0.8)
        checks += "own entity present in snapshots" to sawOwnEntity
        checks += "bots replicated (entities > 1, saw $maxEntitiesSeen)" to (maxEntitiesSeen > 1)
        checks += "server acknowledged our input (ack=$lastAckedInput)" to (lastAckedInput > 0)
        checks += "server moved us (travelled ${"%.1f".format(maxTravel)} m)" to (maxTravel > 1.0f)
        checks += "ping/pong works (${pongsReceived} pongs, last ${lastPingMs} ms)" to (pongsReceived > 0)
        checks += "lobby state received ($lobbyReceived)" to (lobbyReceived > 0)
        checks += "no malformed packets (got $badPackets)" to (badPackets == 0)

        println()
        println("  ================ test client report ================")
        println("   server            : $ip:$port")
        println("   nickname          : $nick  (id $playerId)")
        println("   duration          : ${seconds}s")
        println("   snapshots         : $snapshotsReceived  (${"%.1f".format(snapshotRate)}/s)")
        println("   entities seen     : $maxEntitiesSeen")
        println("   match state       : ${MatchState.name(lastMatchState)}")
        println("   match events      : $matchEvents")
        println("   ping              : last ${lastPingMs} ms, min ${if (minPing == Int.MAX_VALUE) 0 else minPing}, max $maxPing")
        println("   input acked up to : $lastAckedInput (we sent $inputSequence)")
        println("   distance travelled: ${"%.2f".format(maxTravel)} m")
        println("   min health seen   : $minHealthSeen ${if (tookDamage) "(took damage)" else ""}")
        println("  ----------------------------------------------------")
        var allOk = true
        for ((label, ok) in checks) {
            println("   ${if (ok) "PASS" else "FAIL"}  $label")
            if (!ok) allOk = false
        }
        println("  ====================================================")
        println(if (allOk) "  RESULT: OK - the LAN path works end to end." else "  RESULT: PROBLEMS FOUND (see FAIL lines above)")
        println()
        return allOk
    }
}
