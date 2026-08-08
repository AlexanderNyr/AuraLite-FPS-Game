package com.lanfps.server

import com.lanfps.shared.MovementSolver
import com.lanfps.shared.Team
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import com.lanfps.shared.PickupKind

/**
 * P2-3: the arenas added for map rotation (P4-1 added arena04..06).
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

    /** Every rotation arena, in rotation order. */
    private val arenaNames = listOf(
        "arena01.json", "arena02.json", "arena03.json",
        "arena04.json", "arena05.json", "arena06.json",
    )

    /** Rotation arenas EXCLUDING arena01 (its spawns/waypoints respect the
     *  original built-in layout, which the shared-module tests already pin). */
    private val rotationNames = arenaNames.drop(1)

    @Test
    fun `the rotation arenas load with the expected content`() {
        for (name in rotationNames) {
            val def = loadBundled(name)
            assertEquals(name.removeSuffix(".json"), def.name)
        }

        for (name in rotationNames) {
            val def = loadBundled(name)
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
    fun `all rotation arenas have distinct geometry hashes`() {
        val hashes = arenaNames.map { loadBundled(it).hash() }
        assertEquals(hashes.size, hashes.toSet().size, "arena hashes must be unique")

        // ...and none of the new arenas accidentally collides with arena01.
        for (i in 1 until hashes.size) {
            assertNotEquals(hashes[0], hashes[i], "arena ${arenaNames[i]} duplicates arena01")
        }
    }

    @Test
    fun `every spawn and waypoint of the rotation arenas stands in free space`() {
        for (name in rotationNames) {
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
    fun `the navigation graphs of the rotation arenas are fully connected`() {
        for (name in rotationNames) {
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
        for (name in arenaNames) {
            val bundled = javaClass.classLoader.getResourceAsStream(name)!!.readBytes()
            val asset = File("../client-android/src/main/assets/$name").readBytes()
            assertTrue(
                bundled.contentEquals(asset),
                "$name: the copy bundled with the server differs from the Android asset",
            )
        }
    }

    /** P4-1: the three new arenas carry the content pack's gameplay props. */
    @Test
    fun `new arenas expose jump pads and pickup slots in legal positions`() {
        for (name in listOf("arena04.json", "arena05.json", "arena06.json")) {
            val def = loadBundled(name)
            kotlin.test.assertTrue(
                def.jumpPads.isNotEmpty(),
                "${def.name}: new arenas must have at least one jump pad",
            )
            kotlin.test.assertTrue(
                def.pickupSpawns.isNotEmpty(),
                "${def.name}: new arenas must have pickup slots",
            )
            val kinds = def.pickupSpawns.map { it.kind }.toSet()
            kotlin.test.assertTrue(
                PickupKind.HEALTH in kinds && PickupKind.ARMOR in kinds,
                "${def.name}: needs at least one HEALTH and one ARMOR slot",
            )
            // Every pad centre is inside the arena and stands in free space.
            for (pad in def.jumpPads) {
                kotlin.test.assertTrue(
                    solver.fits(com.lanfps.shared.Vec3(pad.x, 0f, pad.z), def),
                    "${def.name}: pad (${pad.x}, ${pad.z}) is embedded in geometry",
                )
                kotlin.test.assertTrue(
                    pad.x in def.minX + 1f..def.maxX - 1f &&
                        pad.z in def.minZ + 1f..def.maxZ - 1f,
                    "${def.name}: pad (${pad.x}, ${pad.z}) is outside the bounds",
                )
            }
            // Ground pickups sit clear of geometry; elevated ones (on bridges,
            // keeps, crate stacks) snap to the nearest walkable footing.
            for (spawn in def.pickupSpawns) {
                val p = spawn.position
                kotlin.test.assertTrue(
                    solver.fits(p, def),
                    "${def.name}: pickup ${spawn.kind} at $p is embedded in geometry",
                )
            }
        }
    }
}
