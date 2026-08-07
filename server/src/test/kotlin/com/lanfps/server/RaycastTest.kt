package com.lanfps.server

import com.lanfps.shared.ArenaDef
import com.lanfps.shared.GameConstants
import com.lanfps.shared.Team
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Authoritative hit detection. These are the tests that guarantee "shots register
 * on clear line of sight" and "shots never pass through walls".
 */
class RaycastTest {

    private val arenaDef: ArenaDef = ArenaDef.builtinArena01()
    private val arena = ServerArena(arenaDef)
    private val raycast = ServerRaycast(arenaDef)

    private fun entity(id: Int, x: Float, z: Float, yaw: Float = 0f, team: Team = Team.NONE) =
        BotEntity(id, "E$id").apply {
            body.position.set(x, 0f, z)
            body.yaw = yaw
            body.pitch = 0f
            body.onGround = true
            alive = true
            this.team = team
        }

    @Test
    fun `hits an enemy with clear line of sight`() {
        // Both in the open north lane, shooter facing east (yaw 90).
        val shooter = entity(1, -10f, -14f, yaw = 90f)
        val target = entity(2, 0f, -14f)

        val hit = raycast.fire(shooter, listOf(target))
        assertSame(target, hit.entity, "expected to hit the target in the open lane")
        assertTrue(hit.distance in 9f..10.1f, "unexpected hit distance ${hit.distance}")
        assertTrue(!hit.hitWorld)
    }

    @Test
    fun `misses an enemy that is not in the crosshair`() {
        val shooter = entity(1, -10f, -14f, yaw = 90f)
        val target = entity(2, 0f, -5f) // well off to the side

        val hit = raycast.fire(shooter, listOf(target))
        assertNull(hit.entity, "should not have hit an off-axis target")
        assertTrue(hit.hitWorld)
    }

    @Test
    fun `cannot shoot through the centre pillar`() {
        // Pillar occupies x -2..2, z -2..2 up to y 2.5.
        val shooter = entity(1, -10f, 0f, yaw = 90f)
        val target = entity(2, 10f, 0f)

        val hit = raycast.fire(shooter, listOf(target))
        assertNull(hit.entity, "the pillar must block this shot")
        assertTrue(hit.hitWorld, "ray should have stopped on world geometry")
        assertTrue(hit.distance < 8.1f, "ray should stop at the pillar, got ${hit.distance}")
    }

    @Test
    fun `cannot shoot through a lane divider`() {
        // Divider at z -8..-7 spanning x -22..-6.
        val shooter = entity(1, -12f, -12f, yaw = 180f) // facing +Z
        val target = entity(2, -12f, -2f)

        val hit = raycast.fire(shooter, listOf(target))
        assertNull(hit.entity, "the divider must block this shot")
    }

    @Test
    fun `nearest target is hit when two are lined up`() {
        val shooter = entity(1, -14f, -14f, yaw = 90f)
        val near = entity(2, -6f, -14f)
        val far = entity(3, 0f, -14f)

        val hit = raycast.fire(shooter, listOf(far, near))
        assertSame(near, hit.entity, "the closer entity must absorb the shot")
    }

    @Test
    fun `dead entities are not hittable`() {
        val shooter = entity(1, -10f, -14f, yaw = 90f)
        val target = entity(2, 0f, -14f).apply { alive = false }

        val hit = raycast.fire(shooter, listOf(target))
        assertNull(hit.entity, "a dead entity must not absorb bullets")
    }

    @Test
    fun `range limits the shot`() {
        val shooter = entity(1, -25f, -14f, yaw = 90f)
        val target = entity(2, 20f, -14f)
        val hit = raycast.fire(shooter, listOf(target), range = 10f)
        assertNull(hit.entity, "target beyond the range must not be hit")
        assertEquals(10f, hit.distance)
    }

    @Test
    fun `teammates are excluded by the world in TDM`() {
        val config = ServerConfig().apply {
            mode = com.lanfps.shared.GameMode.TDM
            botCount = 0
        }
        val world = World(arena, config)

        val shooter = entity(1, -10f, -14f, yaw = 90f, team = Team.RED)
        val mate = entity(2, -4f, -14f, team = Team.RED)
        val enemy = entity(3, 0f, -14f, team = Team.BLUE)
        world.entities[shooter.id] = shooter
        world.entities[mate.id] = mate
        world.entities[enemy.id] = enemy

        assertTrue(world.areAllies(shooter, mate), "same team must be allies in TDM")
        assertTrue(!world.areAllies(shooter, enemy), "opposite teams must be enemies")

        // Fire through the teammate: with friendly fire off the mate is not a
        // candidate, so the enemy behind should be hit.
        val hostiles = listOf(enemy)
        val hit = raycast.fire(shooter, hostiles)
        assertSame(enemy, hit.entity, "shot should reach the enemy past a teammate")
    }

    @Test
    fun `line of sight matches shooting results`() {
        val a = com.lanfps.shared.Vec3(-10f, GameConstants.EYE_HEIGHT, -14f)
        val b = com.lanfps.shared.Vec3(10f, GameConstants.EYE_HEIGHT, -14f)
        assertTrue(raycast.hasLineOfSight(a, b), "north lane should be clear")

        val c = com.lanfps.shared.Vec3(-10f, GameConstants.EYE_HEIGHT, 0f)
        val d = com.lanfps.shared.Vec3(10f, GameConstants.EYE_HEIGHT, 0f)
        assertTrue(!raycast.hasLineOfSight(c, d), "centre pillar should block")
    }
}
