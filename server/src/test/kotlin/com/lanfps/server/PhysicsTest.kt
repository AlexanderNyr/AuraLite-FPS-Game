package com.lanfps.server

import com.lanfps.shared.ArenaDef
import com.lanfps.shared.GameConstants
import com.lanfps.shared.InputCommand
import com.lanfps.shared.Team
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Server-side physics: movement is applied through the shared solver, entities
 * stay inside the world, and spawn selection never puts anyone inside geometry.
 */
class PhysicsTest {

    private val arenaDef: ArenaDef = ArenaDef.builtinArena01()
    private val arena = ServerArena(arenaDef)
    private val physics = ServerPhysics(arena)

    private fun bot(id: Int, x: Float, z: Float): BotEntity =
        BotEntity(id, "T$id").apply {
            body.position.set(x, 0f, z)
            body.onGround = true
            alive = true
        }

    private fun input(forward: Float, yaw: Float) = InputCommand().apply {
        moveForward = forward
        this.yaw = yaw
    }

    @Test
    fun `entity cannot walk through a perimeter wall`() {
        val e = bot(1, 24f, -14f)
        val cmd = input(1f, 90f) // run east
        repeat(600) { physics.step(e, cmd) }
        assertTrue(
            e.body.position.x <= arenaDef.maxX,
            "entity escaped east wall at x=${e.body.position.x}",
        )
        assertTrue(physics.fits(e.body.position), "entity finished inside geometry")
    }

    @Test
    fun `entity cannot walk through a lane divider`() {
        // Divider spans x -22..-6 at z -8..-7. Start north of it, walk south.
        val e = bot(2, -12f, -12f)
        val cmd = input(1f, 180f) // yaw 180 => +Z
        repeat(300) { physics.step(e, cmd) }
        assertTrue(
            e.body.position.z < -8f + GameConstants.PLAYER_RADIUS + 0.1f,
            "entity passed through the divider, z=${e.body.position.z}",
        )
    }

    @Test
    fun `entity walks through the gap in the divider`() {
        // The centre gap is |x| < 6, so at x = 0 the entity should get through.
        val e = bot(3, 0f, -12f)
        val cmd = input(1f, 180f)
        repeat(300) { physics.step(e, cmd) }
        assertTrue(
            e.body.position.z > -6f,
            "entity failed to use the centre gap, z=${e.body.position.z}",
        )
    }

    @Test
    fun `positions never become NaN`() {
        val e = bot(4, 0f, 0f)
        var yaw = 0f
        repeat(1200) {
            yaw += 7f
            physics.step(e, input(1f, yaw))
        }
        assertTrue(e.body.position.x.isFinite(), "x became ${e.body.position.x}")
        assertTrue(e.body.position.y.isFinite(), "y became ${e.body.position.y}")
        assertTrue(e.body.position.z.isFinite(), "z became ${e.body.position.z}")
    }

    @Test
    fun `spawn selection always returns free space`() {
        val enemies = listOf(bot(10, 0f, 0f), bot(11, 20f, 5f))
        repeat(60) {
            for (team in listOf(Team.NONE, Team.RED, Team.BLUE)) {
                val spawn = arena.pickSpawn(team, enemies)
                assertTrue(
                    physics.fits(spawn.position),
                    "picked spawn ${spawn.position} is inside geometry",
                )
                if (team != Team.NONE) {
                    assertTrue(
                        spawn.team == team,
                        "TDM spawn for $team returned a ${spawn.team} point",
                    )
                }
            }
        }
    }

    @Test
    fun `spawn selection prefers points away from enemies`() {
        // An enemy camping the west side should push RED spawns... RED only owns
        // the west, so instead verify the chosen point is the furthest available.
        val camper = bot(12, -27f, -12f)
        var pickedFarSpawn = 0
        repeat(40) {
            val spawn = arena.pickSpawn(Team.RED, listOf(camper))
            if (spawn.position.distanceTo(camper.body.position) > 5f) pickedFarSpawn++
        }
        assertTrue(
            pickedFarSpawn >= 35,
            "spawn picker kept choosing the camped point ($pickedFarSpawn/40 were safe)",
        )
    }

    @Test
    fun `bot separation pushes overlapping bots apart`() {
        val a = bot(20, 0f, -14f)
        val b = bot(21, 0.05f, -14f)
        repeat(120) { physics.separateBots(listOf(a, b), GameConstants.TICK_DT) }
        val dist = a.body.position.horizontalDistanceTo(b.body.position)
        assertTrue(dist > 0.2f, "bots stayed stacked, distance=$dist")
    }

    @Test
    fun `navigation graph connects every waypoint`() {
        val isolated = arena.neighbours.count { it.isEmpty() }
        assertTrue(isolated == 0, "$isolated waypoints have no neighbours")
        // Every node must be able to route to every other node.
        for (from in 0 until arena.waypointCount) {
            for (to in 0 until arena.waypointCount) {
                if (from == to) continue
                assertTrue(
                    arena.hasRoute(from, to),
                    "navigation graph has no route from waypoint $from " +
                        "${arena.waypoint(from)} to $to ${arena.waypoint(to)}",
                )
            }
        }
    }
}
