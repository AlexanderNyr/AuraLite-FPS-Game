package com.lanfps.server

import com.lanfps.shared.MovementSolver
import com.lanfps.shared.Team
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * P2-3: the two arenas added for map rotation.
 *
 * These are the exact checks a level has to pass before bots and players can
 * trust it: every spawn is legal, every waypoint stands in free space, the
 * navigation graph is one connected component (a disconnected island would
 * make bots grind into a wall forever), and the on-disk copies shipped inside
 * the server jar and the Android APK are byte-identical — the hash check at
 * connect/MATCH_START time can only work if both sides bundle the same map.
 */
class MapsTest {

    private val solver = MovementSolver()

    private fun loadBundled(name: String) = ArenaLoader.load(name)

    @Test
    fun `the rotation arenas load with the expected content`() {
        val a2 = loadBundled("arena02.json")
        val a3 = loadBundled("arena03.json")

        assertEquals("arena02", a2.name)
        assertEquals("arena03", a3.name)

        for (def in listOf(a2, a3)) {
            assertTrue(def.brushes.isNotEmpty(), "${def.name}: no geometry")
            assertTrue(def.waypoints.size >= 12, "${def.name}: too few waypoints for bots")
            assertEquals(
                4, def.spawnsFor(Team.RED).size,
                "${def.name}: TDM needs 4 RED spawns",
            )
            assertEquals(
                4, def.spawnsFor(Team.BLUE).size,
                "${def.name}: TDM needs 4 BLUE spawns",
            )
        }
    }

    @Test
    fun `all three rotation arenas have distinct geometry hashes`() {
        val hashes = listOf("arena01.json", "arena02.json", "arena03.json")
            .map { loadBundled(it).hash() }
        assertEquals(hashes.size, hashes.toSet().size, "arena hashes must be unique")

        // ...and none of them accidentally collide with the compiled-in map.
        assertNotEquals(
            com.lanfps.shared.ArenaDef.builtinArena01().hash(), hashes[1],
        )
        assertNotEquals(
            com.lanfps.shared.ArenaDef.builtinArena01().hash(), hashes[2],
        )
    }

    @Test
    fun `every spawn and waypoint of arena02 and arena03 stands in free space`() {
        for (name in listOf("arena02.json", "arena03.json")) {
            val def = loadBundled(name)
            for (spawn in def.spawns) {
                assertTrue(
                    solver.fits(spawn.position, def),
                    "${def.name}: spawn ${spawn.position} is inside geometry",
                )
            }
            for ((i, wp) in def.waypoints.withIndex()) {
                assertTrue(
                    solver.fits(wp, def),
                    "${def.name}: waypoint $i at $wp is inside geometry",
                )
                assertTrue(
                    wp.x in def.minX + 0.5f..def.maxX - 0.5f &&
                        wp.z in def.minZ + 0.5f..def.maxZ - 0.5f,
                    "${def.name}: waypoint $i is outside the arena bounds",
                )
            }
        }
    }

    @Test
    fun `the navigation graphs of arena02 and arena03 are fully connected`() {
        for (name in listOf("arena02.json", "arena03.json")) {
            val arena = ServerArena(loadBundled(name))
            val n = arena.waypointCount
            for (from in 0 until n) {
                for (to in 0 until n) {
                    assertTrue(
                        arena.hasRoute(from, to),
                        "${arena.def.name}: no route from waypoint $from to $to " +
                            "(${arena.describeGraph()})",
                    )
                }
            }
        }
    }

    @Test
    fun `server resources and android assets of the rotation maps are identical`() {
        // The client hot-loads "<name>.json" from its assets when MATCH_START
        // announces a rotated map; a drift between the two copies would desync
        // prediction vs simulation. The tests run with the server module as
        // working directory, hence the relative path to the Android module.
        for (name in listOf("arena01.json", "arena02.json", "arena03.json")) {
            val bundled = javaClass.classLoader.getResourceAsStream(name)!!.readBytes()
            val asset = File("../client-android/src/main/assets/$name").readBytes()
            assertTrue(
                bundled.contentEquals(asset),
                "$name: the copy bundled with the server differs from the Android asset",
            )
        }
    }
}
