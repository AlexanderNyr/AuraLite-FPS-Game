package com.lanfps.server

import java.net.InetAddress
import java.util.ArrayDeque

/**
 * Owns the endpoint -> session map and everything that guards it:
 *  - **P0-3** connection flood protection (sliding-window rate limits plus a
 *    hard cap of simultaneous sessions per source IP);
 *  - **P0-2** zombie sessions: a silent client is kept (with its entity, score
 *    and team) for `zombieTimeoutMs` so it can resume with its resume token.
 *
 * Extracted from [GameServer] (P3-4 of docs/IMPROVEMENT_PLAN.md): the
 * simulation thread is the only caller, so no synchronisation is needed.
 * Behaviour is unchanged from the old in-place implementation — the
 * ConnectHandshakeTest suite pins it (handshake, reconnect, flood throttle).
 */
class SessionManager(private val config: ServerConfig) {

    /** Sessions keyed by "ip:port" — the identity of a UDP peer. */
    val sessions = LinkedHashMap<String, ClientSession>()

    @JvmField
    var rejectedConnects = 0L

    private val recentConnectTimes = ArrayDeque<Long>()
    private val recentConnectPerIp = HashMap<String, ArrayDeque<Long>>()
    private val connectWindowMs = 1000L

    fun byKey(key: String): ClientSession? = sessions[key]

    fun register(session: ClientSession) {
        sessions[session.key] = session
    }

    fun remove(key: String): ClientSession? = sessions.remove(key)

    fun clearAll() = sessions.clear()

    /** The zombie session holding this resume token, if any. */
    fun findZombieByToken(resumeToken: Int): ClientSession? =
        sessions.values.firstOrNull { it.resumeToken == resumeToken && it.zombie }

    /** Re-binds a session to a new endpoint after a reconnect on a new socket. */
    fun rebind(session: ClientSession, address: InetAddress, port: Int, nowMs: Long) {
        sessions.remove(session.key)
        session.key = ClientSession.endpointKey(address, port)
        session.address = address
        session.port = port
        session.touch(nowMs)
        session.zombie = false
        session.zombieDeadlineMs = 0
        sessions[session.key] = session
    }

    /** Clears the zombie flag on a session that just sent a packet again. */
    fun reactivateIfZombie(session: ClientSession) {
        if (session.zombie) {
            session.zombie = false
            session.zombieDeadlineMs = 0
            Log.info(
                "session '${session.nickname}' id=${session.id} re-activated from zombie",
            )
        }
    }

    // ---- P0-3: flood protection --------------------------------------------

    /** True when a brand-new session from [address] is within the rate limits. */
    fun allowConnect(address: InetAddress, nowMs: Long): Boolean {
        val ip = address.hostAddress
        pruneQueue(recentConnectTimes, nowMs)
        if (recentConnectTimes.size >= config.maxConnectsPerSecond) return false

        val perIp = recentConnectPerIp.getOrPut(ip) { ArrayDeque() }
        pruneQueue(perIp, nowMs)
        if (perIp.size >= config.maxConnectsPerIpPerSecond) return false

        recentConnectTimes.addLast(nowMs)
        perIp.addLast(nowMs)
        return true
    }

    /** How many sessions (including zombies) are active for a source IP. */
    fun countSessionsForIp(address: InetAddress): Int =
        sessions.values.count { it.address.hostAddress == address.hostAddress }

    /**
     * Marks silent sessions as zombies and finally reclaims zombies whose
     * reconnect window has expired.
     *
     * @return the sessions whose slots were reclaimed this sweep — the caller
     *         removes their entities and posts PLAYER_LEFT events.
     */
    fun sweepTimeouts(nowMs: Long): List<ClientSession> {
        val expired = ArrayList<ClientSession>()
        val iterator = sessions.entries.iterator()
        while (iterator.hasNext()) {
            val session = iterator.next().value
            if (!session.isTimedOut(nowMs)) continue

            if (!session.zombie) {
                // P0-2: first silence timeout turns the session into a zombie.
                // The entity deliberately stays in the world (standing still,
                // still shootable) so its score/team survive for the reconnect.
                session.zombie = true
                session.zombieDeadlineMs = nowMs + config.zombieTimeoutMs
                Log.info(
                    "TIMEOUT '${session.nickname}' id=${session.id} -> zombie for " +
                        "${config.zombieTimeoutMs} ms (awaiting reconnect)",
                )
            } else if (nowMs >= session.zombieDeadlineMs) {
                iterator.remove()
                expired.add(session)
                Log.info(
                    "ZOMBIE EXPIRED '${session.nickname}' id=${session.id} - slot reclaimed",
                )
            }
        }
        pruneConnectWindows(nowMs)
        return expired
    }

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
}
