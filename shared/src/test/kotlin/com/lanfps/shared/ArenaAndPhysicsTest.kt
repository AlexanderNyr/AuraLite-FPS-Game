package com.lanfps.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Validates the arena data itself and the shared movement/raycast model.
 * These are the checks that guarantee "you cannot walk through a wall" and
 * "you cannot shoot through a wall" before any networking is involved.
 */
class ArenaAndPhysicsTest {

    private val arena = ArenaDef.builtinArena01()
    private val solver = MovementSolver()

    private fun cmd(
        forward: Float = 0f,
        right: Float = 0f,
        yaw: Float = 0f,
        buttons: Int = 0,
    ) = InputCommand().apply {
        moveForward = forward; moveRight = right; this.yaw = yaw; this.buttons = buttons
    }

    private fun bodyAt(x: Float, y: Float, z: Float) = BodyState().apply {
        position.set(x, y, z)
        onGround = true
    }

    // ---- arena data -------------------------------------------------------

    @Test
    fun `arena has enough spawn points for both modes`() {
        assertTrue(arena.spawns.size >= 4, "DM needs at least 4 spawns")
        assertTrue(arena.spawnsFor(Team.RED).size >= 2, "TDM needs >= 2 RED spawns")
        assertTrue(arena.spawnsFor(Team.BLUE).size >= 2, "TDM needs >= 2 BLUE spawns")
    }

    @Test
    fun `every spawn point is in free space`() {
        for (s in arena.spawns) {
            assertTrue(
                solver.fits(s.position, arena),
                "spawn ${s.position} (${s.team}) is embedded in geometry",
            )
            assertTrue(
                s.position.x > arena.minX && s.position.x < arena.maxX &&
                    s.position.z > arena.minZ && s.position.z < arena.maxZ,
                "spawn ${s.position} is outside the arena bounds",
            )
        }
    }

    @Test
    fun `every bot waypoint is in free space`() {
        for (w in arena.waypoints) {
            assertTrue(solver.fits(w, arena), "waypoint $w is embedded in geometry")
        }
    }

    @Test
    fun `json round trip preserves the arena exactly`() {
        val json = arena.toJson()
        val reloaded = ArenaDef.fromJson(json)
        assertEquals(arena.hash(), reloaded.hash(), "arena hash changed after JSON round trip")
        assertEquals(arena.brushes.size, reloaded.brushes.size)
        assertEquals(arena.spawns.size, reloaded.spawns.size)
        assertEquals(arena.waypoints.size, reloaded.waypoints.size)
        assertEquals(arena.collision.size, reloaded.collision.size)
    }

    // ---- collision --------------------------------------------------------

    @Test
    fun `player cannot walk through the centre pillar`() {
        // Start west of the pillar (which spans x -2..2, z -2..2) and run east.
        val body = bodyAt(-6f, 0f, 0f)
        val c = cmd(forward = 1f, yaw = 90f) // yaw 90 => facing +X
        repeat(240) { solver.step(body, c, arena) }

        assertTrue(
            body.position.x < -2f + GameConstants.PLAYER_RADIUS + 0.05f,
            "player tunnelled into/through the pillar: x=${body.position.x}",
        )
    }

    @Test
    fun `player cannot leave the arena through the east wall`() {
        val body = bodyAt(20f, 0f, 12f)
        val c = cmd(forward = 1f, yaw = 90f)
        repeat(600) { solver.step(body, c, arena) }
        assertTrue(
            body.position.x <= arena.maxX,
            "player escaped the arena: x=${body.position.x}",
        )
        assertTrue(solver.fits(body.position, arena), "player ended up inside geometry")
    }

    @Test
    fun `player slides along a wall instead of sticking`() {
        // Run diagonally into the north perimeter wall; the Z component should be
        // cancelled while X keeps moving.
        val body = bodyAt(0f, 0f, -18f)
        val c = cmd(forward = 1f, right = 1f, yaw = 0f).also { it.sanitize() }
        val startX = body.position.x
        repeat(120) { solver.step(body, c, arena) }
        assertTrue(
            kotlin.math.abs(body.position.x - startX) > 1f,
            "player stuck on the wall instead of sliding (dx=${body.position.x - startX})",
        )
    }

    @Test
    fun `gravity settles the player onto the floor`() {
        val body = bodyAt(0f, 10f, -14f)
        body.onGround = false
        val c = cmd()
        repeat(180) { solver.step(body, c, arena) }
        assertTrue(body.onGround, "player never landed")
        assertTrue(
            kotlin.math.abs(body.position.y) < 0.01f,
            "player did not settle on y=0, got ${body.position.y}",
        )
    }

    @Test
    fun `player can stand on top of a one metre crate`() {
        // Crate spans x -8..-6, z -6..-4, height 1.
        val body = bodyAt(-7f, 5f, -5f)
        body.onGround = false
        repeat(180) { solver.step(body, cmd(), arena) }
        assertTrue(body.onGround, "player never landed on the crate")
        assertTrue(
            kotlin.math.abs(body.position.y - 1f) < 0.02f,
            "expected to rest on the crate at y=1, got ${body.position.y}",
        )
    }

    @Test
    fun `movement speed never exceeds the configured maximum`() {
        val body = bodyAt(0f, 0f, -14f)
        val c = cmd(forward = 1f, right = 1f, yaw = 45f).also { it.sanitize() }
        var maxSeen = 0f
        repeat(300) {
            solver.step(body, c, arena)
            maxSeen = maxOf(maxSeen, body.velocity.horizontalLength())
        }
        assertTrue(
            maxSeen <= GameConstants.MOVE_SPEED * 1.02f,
            "speed exploit: reached $maxSeen m/s, cap is ${GameConstants.MOVE_SPEED}",
        )
    }

    // ---- raycasting -------------------------------------------------------

    @Test
    fun `raycast reaches a target with clear line of sight`() {
        // Straight down the north lane, which is open.
        val from = Vec3(-20f, GameConstants.EYE_HEIGHT, -14f)
        val to = Vec3(20f, GameConstants.EYE_HEIGHT, -14f)
        assertTrue(RayMath.hasLineOfSight(from, to, arena), "north lane should be open")
    }

    @Test
    fun `raycast is blocked by the centre pillar`() {
        val from = Vec3(-10f, GameConstants.EYE_HEIGHT, 0f)
        val to = Vec3(10f, GameConstants.EYE_HEIGHT, 0f)
        assertTrue(
            !RayMath.hasLineOfSight(from, to, arena),
            "the centre pillar must block the spawn-to-spawn sight line",
        )
    }

    @Test
    fun `raycast is blocked by a lane divider`() {
        // Across the divider at z = -8..-7 (x = -12 is inside the -22..-6 span).
        val from = Vec3(-12f, GameConstants.EYE_HEIGHT, -14f)
        val to = Vec3(-12f, GameConstants.EYE_HEIGHT, 0f)
        assertTrue(!RayMath.hasLineOfSight(from, to, arena), "lane divider must block LOS")
    }

    @Test
    fun `ray hits an entity hitbox at the expected distance`() {
        val target = Aabb().setFromBody(
            Vec3(0f, 0f, -14f), GameConstants.PLAYER_RADIUS, GameConstants.PLAYER_HEIGHT,
        )
        val origin = Vec3(-10f, GameConstants.EYE_HEIGHT, -14f)
        val dir = Vec3(1f, 0f, 0f)
        val t = RayMath.rayAabb(origin, dir, target, Weapons.RifleDef.range)
        assertTrue(t > 0f, "ray should hit the target box")
        assertTrue(
            kotlin.math.abs(t - (10f - GameConstants.PLAYER_RADIUS)) < 0.05f,
            "unexpected hit distance $t",
        )
    }

    @Test
    fun `ray misses an entity that is off to the side`() {
        val target = Aabb().setFromBody(
            Vec3(0f, 0f, -10f), GameConstants.PLAYER_RADIUS, GameConstants.PLAYER_HEIGHT,
        )
        val origin = Vec3(-10f, GameConstants.EYE_HEIGHT, -14f)
        val dir = Vec3(1f, 0f, 0f)
        assertEquals(
            RayMath.NO_HIT,
            RayMath.rayAabb(origin, dir, target, Weapons.RifleDef.range),
        )
    }

    @Test
    fun `forward vector matches the yaw convention`() {
        val out = Vec3()
        MathUtil.forwardFromAngles(0f, 0f, out)
        assertTrue(out.z < -0.99f, "yaw 0 must look toward -Z, got $out")
        MathUtil.forwardFromAngles(90f, 0f, out)
        assertTrue(out.x > 0.99f, "yaw 90 must look toward +X, got $out")
    }
}
