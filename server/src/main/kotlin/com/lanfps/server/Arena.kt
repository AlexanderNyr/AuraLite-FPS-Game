package com.lanfps.server

import com.lanfps.shared.ArenaDef
import com.lanfps.shared.RayMath
import com.lanfps.shared.SpawnPoint
import com.lanfps.shared.Team
import com.lanfps.shared.Vec3

/**
 * Server-side runtime wrapper around the shared [ArenaDef].
 *
 * Adds two things the client does not need:
 *  - a **navigation graph** over the arena waypoints, so bots walk through the
 *    lane gaps instead of grinding against a divider wall;
 *  - **spawn selection** that avoids dropping a player in front of an enemy.
 */
class ServerArena(@JvmField val def: ArenaDef) {

    /** waypoint index -> indices of waypoints reachable in a straight line. */
    @JvmField val neighbours: Array<IntArray>

    /**
     * `nextHop[from][to]` = the neighbour to step to when travelling from `from`
     * to `to`. Precomputed with a BFS from every node (the graph is tiny).
     */
    private val nextHop: Array<IntArray>

    private val scratchDir = Vec3()
    private val scratchA = Vec3()
    private val scratchB = Vec3()

    init {
        val n = def.waypoints.size
        neighbours = Array(n) { IntArray(0) }

        // Two waypoints are linked when there is clear line of sight between
        // them at chest height and they are not absurdly far apart.
        // Deliberately low: below the 1 m crate tops, so a crate BLOCKS a link and
        // bots route around it rather than grinding into it.
        val eye = 0.6f
        for (i in 0 until n) {
            val list = ArrayList<Int>()
            val a = def.waypoints[i]
            scratchA.set(a.x, a.y + eye, a.z)
            for (j in 0 until n) {
                if (i == j) continue
                val b = def.waypoints[j]
                scratchB.set(b.x, b.y + eye, b.z)
                if (scratchA.distanceTo(scratchB) > MAX_LINK_DISTANCE) continue
                if (RayMath.hasLineOfSight(scratchA, scratchB, def, scratchDir)) list.add(j)
            }
            neighbours[i] = list.toIntArray()
        }

        nextHop = Array(n) { IntArray(n) { -1 } }
        for (start in 0 until n) computeRoutesFrom(start, n)
    }

    /** BFS from [start]; fills nextHop[start][*]. */
    private fun computeRoutesFrom(start: Int, n: Int) {
        val prev = IntArray(n) { -1 }
        val visited = BooleanArray(n)
        val queue = ArrayDeque<Int>()
        visited[start] = true
        queue.addLast(start)
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            for (nb in neighbours[cur]) {
                if (visited[nb]) continue
                visited[nb] = true
                prev[nb] = cur
                queue.addLast(nb)
            }
        }
        // Walk each destination back to the node right after `start`.
        for (dest in 0 until n) {
            if (dest == start || !visited[dest]) continue
            var step = dest
            while (prev[step] != start && prev[step] != -1) step = prev[step]
            nextHop[start][dest] = step
        }
    }

    /** Index of the waypoint nearest to a world position. */
    fun nearestWaypoint(pos: Vec3): Int {
        var best = -1
        var bestDist = Float.MAX_VALUE
        val wps = def.waypoints
        for (i in wps.indices) {
            val d = pos.horizontalDistanceTo(wps[i])
            if (d < bestDist) { bestDist = d; best = i }
        }
        return best
    }

    /**
     * The waypoint to walk toward next when heading from [from] to [to].
     * Falls back to the destination itself when the graph has no route.
     */
    fun nextWaypoint(from: Int, to: Int): Int {
        if (from < 0 || to < 0 || from >= nextHop.size) return to
        if (from == to) return to
        val hop = nextHop[from][to]
        return if (hop >= 0) hop else to
    }

    /**
     * True when the navigation graph actually knows a route. [nextWaypoint]
     * deliberately falls back to the destination, so it can never be used to
     * detect an unreachable node — this can.
     */
    fun hasRoute(from: Int, to: Int): Boolean {
        if (from == to) return true
        if (from !in nextHop.indices || to !in nextHop.indices) return false
        return nextHop[from][to] >= 0
    }

    fun waypoint(index: Int): Vec3 = def.waypoints[index]

    val waypointCount: Int get() = def.waypoints.size

    /**
     * Chooses the spawn point that is furthest from any living enemy, so players
     * rarely materialise in someone's crosshair.
     */
    fun pickSpawn(team: Team, enemies: List<GameEntity>): SpawnPoint {
        val candidates = def.spawnsFor(team)
        if (candidates.isEmpty()) return def.spawns.first()
        if (enemies.isEmpty()) return candidates[(Math.random() * candidates.size).toInt().coerceAtMost(candidates.size - 1)]

        var best = candidates[0]
        var bestScore = -1f
        for (sp in candidates) {
            var nearest = Float.MAX_VALUE
            for (e in enemies) {
                if (!e.alive) continue
                val d = sp.position.distanceTo(e.body.position)
                if (d < nearest) nearest = d
            }
            if (nearest == Float.MAX_VALUE) nearest = 1000f
            // Small random tiebreaker so repeated spawns are not identical.
            val score = nearest + (Math.random() * 3.0).toFloat()
            if (score > bestScore) { bestScore = score; best = sp }
        }
        return best
    }

    fun describeGraph(): String {
        val links = neighbours.sumOf { it.size }
        val isolated = neighbours.count { it.isEmpty() }
        return "nav graph: ${def.waypoints.size} nodes, $links links, $isolated isolated"
    }

    companion object {
        /** Waypoints further apart than this are never linked directly. */
        const val MAX_LINK_DISTANCE = 26f
    }
}
