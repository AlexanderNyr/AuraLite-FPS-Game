package com.lanfps.client

import com.lanfps.shared.ArenaDef
import com.lanfps.shared.EntityState
import com.lanfps.shared.GameConstants
import com.lanfps.shared.GameMode
import com.lanfps.shared.MatchState
import com.lanfps.shared.Team

/** Where the app currently is. Drives which View is on top. */
enum class Phase {
    MENU,
    CONNECTING,
    LOBBY,
    PLAYING,
    ENDED,
    DISCONNECTED,

    /** P0-2: the link dropped but we are retrying with our resume token. */
    RECONNECTING,
}

/** One line of the kill feed. */
class KillFeedEntry(
    @JvmField val killer: String,
    @JvmField val victim: String,
    @JvmField val killerIsLocal: Boolean,
    @JvmField val victimIsLocal: Boolean,
    @JvmField val bornMs: Long,
)

/** A bullet trail to draw for a few frames. */
class Tracer {
    @JvmField var x0 = 0f; @JvmField var y0 = 0f; @JvmField var z0 = 0f
    @JvmField var x1 = 0f; @JvmField var y1 = 0f; @JvmField var z1 = 0f
    @JvmField var bornMs = 0L
    @JvmField var local = false
}

/** A server found by UDP broadcast discovery. */
class DiscoveredServer(
    @JvmField val ip: String,
    @JvmField val port: Int,
    @JvmField val name: String,
    @JvmField val mode: String,
    @JvmField val players: Int,
    @JvmField val maxPlayers: Int,
)

/**
 * Everything the three threads (UI / GL / network) need to see about the current
 * session.
 *
 * Rules of the road:
 *  - scalars are `@Volatile`, written by exactly one thread each;
 *  - the collections are guarded by their own monitors;
 *  - [snapshots] and [prediction] carry their own synchronisation.
 *
 * Keeping all cross-thread state in one place is what stops the renderer and the
 * network loop from quietly disagreeing.
 */
class ClientGameState(@JvmField val arena: ArenaDef) {

    // ---- session ----------------------------------------------------------
    @Volatile var phase: Phase = Phase.MENU
    @Volatile var statusText: String = ""
    @Volatile var errorText: String = ""

    @Volatile var serverIp: String = GameConstants.DEFAULT_SERVER_IP
    @Volatile var serverPort: Int = GameConstants.DEFAULT_UDP_PORT
    @Volatile var nickname: String = "Player"

    @Volatile var localPlayerId: Int = -1
    @Volatile var localTeam: Team = Team.NONE
    @Volatile var serverName: String = "LAN FPS Server"
    @Volatile var arenaMismatch: Boolean = false

    /** P0-2: token from CONNECT_ACCEPTED, presented to resume this session. */
    @Volatile var resumeToken: Int = 0

    /** P1-4: newest MATCH_EVENT sequence we have processed. Acked back to the
     *  server in the CLIENT_INPUT header so it stops re-sending old events. */
    @Volatile var highestEventSeq: Int = -1

    // ---- match ------------------------------------------------------------
    @Volatile var mode: GameMode = GameMode.DM
    @Volatile var matchState: Int = MatchState.WARMUP
    @Volatile var matchTimeRemaining: Float = 0f
    @Volatile var redScore: Int = 0
    @Volatile var blueScore: Int = 0

    // ---- local player -----------------------------------------------------
    @Volatile var health: Int = GameConstants.MAX_HEALTH
    @Volatile var alive: Boolean = false
    @Volatile var kills: Int = 0
    @Volatile var deaths: Int = 0
    @Volatile var respawnInSec: Float = 0f

    /** Predicted render transform, published by the network thread each tick. */
    @Volatile var eyeX: Float = 0f
    @Volatile var eyeY: Float = GameConstants.EYE_HEIGHT
    @Volatile var eyeZ: Float = 0f
    @Volatile var viewYaw: Float = 0f
    @Volatile var viewPitch: Float = 0f
    @Volatile var localSpeed: Float = 0f
    @Volatile var localOnGround: Boolean = true

    // ---- network health ---------------------------------------------------
    @Volatile var pingMs: Int = 0
    @Volatile var packetsIn: Long = 0
    @Volatile var packetsOut: Long = 0
    @Volatile var malformedIn: Long = 0
    @Volatile var lastServerContactMs: Long = 0
    @Volatile var snapshotsPerSec: Float = 0f

    // ---- effects ----------------------------------------------------------
    @Volatile var lastLocalFireMs: Long = 0
    @Volatile var hitMarkerUntilMs: Long = 0
    @Volatile var damageFlashUntilMs: Long = 0
    @Volatile var muzzleFlashUntilMs: Long = 0
    /** Extra pitch/yaw kick applied by recoil, decayed by the renderer. */
    @Volatile var recoilPitch: Float = 0f

    @JvmField val snapshots = SnapshotBuffer()

    /** Non-null once the arena is known; created by [MainActivity]. */
    @Volatile var prediction: Prediction? = null

    private val killFeedLock = Any()
    private val killFeed = ArrayList<KillFeedEntry>()

    private val tracerLock = Any()
    private val tracers = ArrayList<Tracer>()
    private val tracerPool = ArrayList<Tracer>()

    private val discoveryLock = Any()
    private val discovered = ArrayList<DiscoveredServer>()

    // ---- renderer -> HUD hand-off -----------------------------------------
    // The HUD draws name plates and health bars above other players, which means
    // it needs the exact matrix the GL thread used this frame. Publishing a copy
    // under a monitor is far simpler (and cheaper) than duplicating the camera
    // maths on the UI thread and hoping the two stay in step.
    private val vpLock = Any()
    private val viewProj = FloatArray(16)
    private var viewProjValid = false

    @Volatile var viewportWidth: Int = 0
    @Volatile var viewportHeight: Int = 0
    @Volatile var fps: Float = 0f

    fun publishViewProj(m: FloatArray) {
        synchronized(vpLock) {
            System.arraycopy(m, 0, viewProj, 0, 16)
            viewProjValid = true
        }
    }

    fun copyViewProj(out: FloatArray): Boolean = synchronized(vpLock) {
        if (!viewProjValid) return@synchronized false
        System.arraycopy(viewProj, 0, out, 0, 16)
        true
    }

    // ---- kill feed --------------------------------------------------------

    fun addKill(killer: String, victim: String, killerId: Int, victimId: Int) {
        synchronized(killFeedLock) {
            killFeed.add(
                KillFeedEntry(
                    killer, victim,
                    killerId == localPlayerId, victimId == localPlayerId,
                    System.currentTimeMillis(),
                ),
            )
            while (killFeed.size > 6) killFeed.removeAt(0)
        }
    }

    /** Entries younger than [ttlMs], oldest first. */
    fun killFeedSnapshot(nowMs: Long, ttlMs: Long, out: ArrayList<KillFeedEntry>) {
        out.clear()
        synchronized(killFeedLock) {
            var i = 0
            while (i < killFeed.size) {
                if (nowMs - killFeed[i].bornMs > ttlMs) killFeed.removeAt(i) else i++
            }
            out.addAll(killFeed)
        }
    }

    fun clearKillFeed() = synchronized(killFeedLock) { killFeed.clear() }

    // ---- tracers ----------------------------------------------------------

    fun addTracer(
        x0: Float, y0: Float, z0: Float,
        x1: Float, y1: Float, z1: Float,
        local: Boolean,
    ) {
        synchronized(tracerLock) {
            val t = if (tracerPool.isEmpty()) Tracer() else tracerPool.removeAt(tracerPool.size - 1)
            t.x0 = x0; t.y0 = y0; t.z0 = z0
            t.x1 = x1; t.y1 = y1; t.z1 = z1
            t.bornMs = System.currentTimeMillis()
            t.local = local
            tracers.add(t)
            while (tracers.size > 48) tracerPool.add(tracers.removeAt(0))
        }
    }

    /** Copies live tracers into [out] and recycles expired ones. */
    fun collectTracers(nowMs: Long, ttlMs: Long, out: ArrayList<Tracer>) {
        out.clear()
        synchronized(tracerLock) {
            var i = 0
            while (i < tracers.size) {
                val t = tracers[i]
                if (nowMs - t.bornMs > ttlMs) {
                    tracers.removeAt(i)
                    tracerPool.add(t)
                } else {
                    out.add(t)
                    i++
                }
            }
        }
    }

    fun clearTracers() = synchronized(tracerLock) {
        tracerPool.addAll(tracers)
        tracers.clear()
    }

    // ---- discovery --------------------------------------------------------

    fun addDiscovered(server: DiscoveredServer) {
        synchronized(discoveryLock) {
            discovered.removeAll { it.ip == server.ip && it.port == server.port }
            discovered.add(server)
        }
    }

    fun discoveredServers(): List<DiscoveredServer> =
        synchronized(discoveryLock) { ArrayList(discovered) }

    fun clearDiscovered() = synchronized(discoveryLock) { discovered.clear() }

    // ---- P0-1: name roster ------------------------------------------------
    // Nicknames no longer travel inside snapshots (they would blow past the MTU
    // on a full server). They arrive once per LOBBY_STATE, and are joined to
    // entities by id here so the HUD/scoreboard/end-of-match can still show them.
    private val rosterLock = Any()
    private val nameById = HashMap<Int, String>()

    fun setRosterName(id: Int, name: String) = synchronized(rosterLock) {
        if (name.isNotEmpty()) nameById[id] = name
    }

    fun rosterName(id: Int): String? = synchronized(rosterLock) { nameById[id] }

    private fun clearRoster() = synchronized(rosterLock) { nameById.clear() }

    // ---- scoreboard -------------------------------------------------------

    /**
     * Players sorted for the scoreboard: kills descending, then deaths
     * ascending, then name. Reads the newest snapshot, so it is always
     * consistent with what the server believes.
     */
    fun scoreboardRows(): List<EntityState> {
        val snap = snapshots.latest ?: return emptyList()
        val rows = ArrayList<EntityState>(snap.entities.size)
        for (e in snap.entities) rows.add(e.copy())
        rows.sortWith(
            compareByDescending<EntityState> { it.kills }
                .thenBy { it.deaths }
                .thenBy { it.name.lowercase() },
        )
        return rows
    }

    fun resetForNewSession() {
        snapshots.clear()
        prediction?.reset()
        clearKillFeed()
        clearTracers()
        clearRoster()
        localPlayerId = -1
        localTeam = Team.NONE
        resumeToken = 0
        highestEventSeq = -1
        health = GameConstants.MAX_HEALTH
        alive = false
        kills = 0
        deaths = 0
        respawnInSec = 0f
        redScore = 0
        blueScore = 0
        matchState = MatchState.WARMUP
        matchTimeRemaining = 0f
        pingMs = 0
        packetsIn = 0
        packetsOut = 0
        malformedIn = 0
        snapshotsPerSec = 0f
        arenaMismatch = false
        errorText = ""
        recoilPitch = 0f
    }
}
