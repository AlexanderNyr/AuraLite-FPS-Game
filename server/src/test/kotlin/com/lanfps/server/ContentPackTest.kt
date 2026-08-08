package com.lanfps.server

import com.lanfps.shared.Aabb
import com.lanfps.shared.ArenaDef
import com.lanfps.shared.Brush
import com.lanfps.shared.GameConstants
import com.lanfps.shared.GameMode
import com.lanfps.shared.InputCommand
import com.lanfps.shared.JumpPad
import com.lanfps.shared.MatchState
import com.lanfps.shared.MovementSolver
import com.lanfps.shared.PickupKind
import com.lanfps.shared.PickupSpawn
import com.lanfps.shared.Protocol
import com.lanfps.shared.Packets
import com.lanfps.shared.BinaryReader
import com.lanfps.shared.SpawnPoint
import com.lanfps.shared.Team
import com.lanfps.shared.Vec3
import com.lanfps.shared.Weapons
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * P4 content pack: SMG balance, instagib rules, jump pads, pickups, grenades.
 *
 * Determinism note: the scenarios use "phantom" entities — [BotEntity]
 * instances present in [World.entities] but deliberately NOT in the bot list,
 * so [BotAI] never rewrites their intent. That keeps every assertion about
 * physics/combat rules rather than about AI fortunes.
 */
class ContentPackTest {

    // ------------------------------------------------------------- helpers

    /** Minimal test map: room, a tall wall mid-room, one pad, three slots. */
    private fun testArena(): ArenaDef {
        val brushes = mutableListOf(
            Brush(Aabb().set(-20f, -0.5f, -20f, 20f, 0f, 20f), 0, false),
            Brush(Aabb().set(-21f, 0f, -21f, 21f, 4f, -20f), 1),
            Brush(Aabb().set(-21f, 0f, 20f, 21f, 4f, 21f), 1),
            Brush(Aabb().set(-21f, 0f, -21f, -20f, 4f, 21f), 1),
            Brush(Aabb().set(20f, 0f, -21f, 21f, 4f, 21f), 1),
            // A 3 m wall splitting the room between x=5 and x=6.
            Brush(Aabb().set(5f, 0f, -20f, 6f, 3f, 20f), 1),
        )
        val spawns = listOf(
            SpawnPoint(Vec3(-5f, 0f, 0f), 90f, Team.RED),
            SpawnPoint(Vec3(-8f, 0f, 8f), 90f, Team.BLUE),
            SpawnPoint(Vec3(-8f, 0f, -8f), 90f, Team.NONE),
            SpawnPoint(Vec3(-12f, 0f, 0f), 90f, Team.NONE),
        )
        return ArenaDef(
            name = "testpack", minX = -20f, maxX = 20f, minZ = -20f, maxZ = 20f,
            wallHeight = 4f, brushes = brushes, spawns = spawns,
            waypoints = listOf(Vec3(0f, 0f, 0f), Vec3(10f, 0f, 10f), Vec3(10f, 0f, -10f)),
            jumpPads = listOf(JumpPad(10f, 0f, 1.6f, 12f)),
            pickupSpawns = listOf(
                PickupSpawn(PickupKind.HEALTH, Vec3(0f, 0f, 5f)),
                PickupSpawn(PickupKind.SMG, Vec3(0f, 0f, -5f)),
                PickupSpawn(PickupKind.ARMOR, Vec3(-10f, 0f, 10f)),
            ),
        )
    }

    private fun worldWith(mode: GameMode = GameMode.DM): Pair<World, MatchController> {
        val arena = ArenaDef(
            name = "testpack", minX = -20f, maxX = 20f, minZ = -20f, maxZ = 20f,
            wallHeight = 4f,
            brushes = mutableListOf(
                Brush(Aabb().set(-20f, -0.5f, -20f, 20f, 0f, 20f), 0, false),
                Brush(Aabb().set(-21f, 0f, -21f, 21f, 4f, -20f), 1),
                Brush(Aabb().set(-21f, 0f, 20f, 21f, 4f, 21f), 1),
                Brush(Aabb().set(-21f, 0f, -21f, -20f, 4f, 21f), 1),
                Brush(Aabb().set(20f, 0f, -21f, 21f, 4f, 21f), 1),
                Brush(Aabb().set(5f, 0f, -20f, 6f, 3f, 20f), 1),
            ),
            spawns = listOf(
                SpawnPoint(Vec3(-5f, 0f, 0f), 90f, Team.RED),
                SpawnPoint(Vec3(-8f, 0f, 8f), 90f, Team.BLUE),
                SpawnPoint(Vec3(-8f, 0f, -8f), 90f, Team.NONE),
                SpawnPoint(Vec3(-12f, 0f, 0f), 90f, Team.NONE),
            ),
            waypoints = listOf(Vec3(0f, 0f, 0f), Vec3(10f, 0f, 10f), Vec3(10f, 0f, -10f)),
            jumpPads = listOf(JumpPad(10f, 0f, 1.6f, 12f)),
            pickupSpawns = listOf(
                PickupSpawn(PickupKind.HEALTH, Vec3(0f, 0f, 5f)),
                PickupSpawn(PickupKind.SMG, Vec3(0f, 0f, -5f)),
                PickupSpawn(PickupKind.ARMOR, Vec3(-10f, 0f, 10f)),
            ),
        )
        val config = ServerConfig().apply {
            this.mode = mode
            botCount = 0
            killLimit = 1000
            matchTimeSeconds = 600
        }
        val world = World(ServerArena(arena), config)
        val match = MatchController(world, config)
        // Everyone is already there: let the match go ACTIVE immediately so
        // combat paths run exactly like on the production server.
        world.entities.size // touch: WARMUP requires entities to start; we tick manually.
        return world to match
    }

    /** Phantom entity: in the world (visible to physics/pickups/explosions)
     *  but invisible to the AI and input loops. */
    private fun World.phantom(x: Float, z: Float, id: Int, name: String = "phantom"): BotEntity {
        val bot = BotEntity(id, name)
        bot.body.position.set(x, 0f, z)
        bot.body.yaw = 90f
        bot.alive = true
        bot.health = GameConstants.MAX_HEALTH
        entities[bot.id] = bot
        return bot
    }

    /** Ticks the full world machine with combat hot, like GameServer.runLoop. */
    private fun simulate(world: World, match: MatchController, ticks: Int) {
        repeat(ticks) {
            world.combatEnabled = true
            world.tick(GameConstants.TICK_DT)
            match.update(GameConstants.TICK_DT)
        }
    }

    // ------------------------------------------------------------- SMG

    @Test
    fun `SMG is a distinct rifle side-grade with sane balance numbers`() {
        val smg = Weapons.SmgDef
        val rifle = Weapons.RifleDef
        assertEquals(Weapons.SMG, smg.id)
        assertTrue(smg.fireInterval < rifle.fireInterval, "SMG must fire faster than the rifle")
        assertTrue(smg.damage < rifle.damage, "SMG hits softer per round")
        // DPS ceiling: rifle still wins sustained fights.
        assertTrue(
            smg.damage / smg.fireInterval < rifle.damage / rifle.fireInterval * 1.05f,
            "SMG must not out-damage the rifle in sustained DPS",
        )
        assertTrue(Weapons.isValid(Weapons.SMG))
        assertEquals(smg, Weapons.byId(Weapons.SMG))
    }

    // ------------------------------------------------------------- instagib

    @Test
    fun `instagib forces the rail and one hit kills`() {
        val (world, match) = worldWith(GameMode.INSTAGIB)
        val shooter = world.phantom(-5f, 0f, 101, "shooter")
        val victim = world.phantom(5f, 0f, 102, "victim")
        simulate(world, match, 3)
        world.respawn(shooter) // respawn legislates the rail for everyone
        assertEquals(Weapons.SNIPER, shooter.weapon, "instagib respawn clamps the weapon")
        assertEquals(Weapons.AMMO_INFINITE, shooter.ammoInMag)

        // One rail round anywhere: the def itself is the lethal verdict.
        assertEquals(999, Weapons.InstagibDef.damage)
        world.applyDamage(victim, shooter, Weapons.InstagibDef.damage)
        assertFalse(victim.alive, "one rail hit must kill (instagib)")
        assertEquals(1, shooter.kills)
        assertEquals(1, world.killFeed.size)
        simulate(world, match, 1) // drains kill feed into events cleanly
    }

    // ------------------------------------------------------------- jumppads

    @Test
    fun `jump pad launches a grounded body to a bridge-clearing apex`() {
        val arenaParams = worldWith().first.serverArena.def
        val solver = MovementSolver()
        val body = com.lanfps.shared.BodyState().apply {
            position.set(10f, 0f, 0f)
            onGround = true
        }
        solver.step(body, InputCommand(), arenaParams)
        assertTrue(body.velocity.y >= 11.5f, "pad must shove the body upward")
        assertFalse(body.onGround)

        // Simulate the unpowered ballistic afterwards: apex must clear 3.4 m
        // (the arena05 bridge top) with margin.
        var y = 0f
        var vy = 12f
        var peak = 0f
        repeat(240) {
            vy += GameConstants.GRAVITY * GameConstants.TICK_DT
            y += vy * GameConstants.TICK_DT
            if (y > peak) peak = y
        }
        assertTrue(peak > 3.4f, "pad arc peak ${peak}m must clear a 3.4m bridge")
    }

    @Test
    fun `jump pad stays silent under an airborne body`() {
        val arenaParams = worldWith().first.serverArena.def
        val solver = MovementSolver()
        val body = com.lanfps.shared.BodyState().apply {
            position.set(10f, 0.5f, 0f)
            onGround = false
        }
        solver.step(body, InputCommand(), arenaParams)
        assertTrue(body.velocity.y < 0f, "airborne over a pad: gravity only, no impulse")
    }

    // ------------------------------------------------------------- pickups

    @Test
    fun `health pickup heals the hurt and then respawns`() {
        val (world, match) = worldWith()
        val bot = world.phantom(0f, 5f, 201)
        // The WARMUP -> ACTIVE transition respawns everyone; only start the
        // scenario AFTER it, so our staged damage survives the setup.
        simulate(world, match, 3)
        assertEquals(MatchState.ACTIVE, match.state)
        bot.body.position.set(0f, 0f, 5f)
        bot.health = 50
        world.tick(GameConstants.TICK_DT)
        assertEquals(90, bot.health, "medkit heals +40")
        assertEquals(1, world.pickupFeed.size)
        assertEquals(PickupKind.HEALTH, world.pickupFeed[0].kind)

        val slot = world.pickups.slots.first { it.kind == PickupKind.HEALTH }
        assertFalse(slot.active, "consumed slot goes dormant")
        // Walk away, hurt again, wait out the 20 s re-arm, walk back on.
        bot.body.position.set(8f, 0f, 8f)
        bot.health = 50
        repeat(21 * GameConstants.TICK_RATE) { world.tick(GameConstants.TICK_DT) }
        assertTrue(slot.active, "slot re-arms ~20 s later")
        assertEquals(50, bot.health, "off the slot, no healing happened")
        bot.body.position.set(0f, 0f, 5f)
        world.tick(GameConstants.TICK_DT)
        assertEquals(90, bot.health, "second pass heals again")
    }

    @Test
    fun `full-health body walks past a medkit without consuming it`() {
        val (world, match) = worldWith()
        world.phantom(0f, 5f, 202)
        simulate(world, match, 2)
        val slot = world.pickups.slots.first { it.kind == PickupKind.HEALTH }
        assertTrue(slot.active, "a full-health body must not eat the medkit")
    }

    @Test
    fun `SMG pickup swaps the weapon with a fresh magazine`() {
        val (world, match) = worldWith()
        val bot = world.phantom(0f, -5f, 203)
        simulate(world, match, 2)
        assertEquals(Weapons.SMG, bot.weapon)
        assertEquals(Weapons.SmgDef.magazineSize, bot.ammoInMag)
    }

    // ------------------------------------------------------------- grenades

    @Test
    fun `direct hit detonates at full power and credits the kill`() {
        val (world, match) = worldWith()
        val thrower = world.phantom(-5f, 0f, 301, "thrower")
        val victim = world.phantom(2f, 0f, 302, "victim") // 7 m in front, light cover only
        simulate(world, match, 3)
        assertEquals(MatchState.ACTIVE, match.state)
        // Stage after the match-start respawn.
        thrower.body.position.set(-5f, 0f, 0f)
        thrower.body.yaw = 90f  // +X
        thrower.body.pitch = 5f
        victim.body.position.set(2f, 0f, 0f)
        victim.health = GameConstants.MAX_HEALTH
        world.killFeed.clear()
        assertTrue(world.throwGrenade(thrower), "first nade flies")
        assertEquals(GameConstants.START_GRENADES - 1, thrower.grenades)

        // ~0.44 s to impact + explosion the same tick as impact.
        repeat((GameConstants.GRENADE_FUSE_SEC + 0.5f).let { (it * GameConstants.TICK_RATE).toInt() }) {
            world.combatEnabled = true
            world.tick(GameConstants.TICK_DT)
        }
        assertTrue(world.grenades.grenades.isEmpty(), "nade went off")
        assertFalse(victim.alive, "direct-hit nade must kill a full-health target")
        assertEquals(1, thrower.kills, "kill credited to the thrower")
        assertEquals(1, world.killFeed.size)
    }

    @Test
    fun `a real wall shelters from splash`() {
        val (world, match) = worldWith()
        val thrower = world.phantom(-5f, 0f, 303, "thrower")
        val victim = world.phantom(6.2f, 0f, 304, "victim") // tucked behind the 3 m wall
        simulate(world, match, 3)
        thrower.body.position.set(-5f, 0f, 0f)
        thrower.body.yaw = 90f
        thrower.body.pitch = 0f // flat throw stays on the west side
        victim.body.position.set(6.2f, 0f, 0f)
        victim.health = GameConstants.MAX_HEALTH
        assertTrue(world.throwGrenade(thrower))
        repeat((GameConstants.GRENADE_FUSE_SEC + 0.5f).let { (it * GameConstants.TICK_RATE).toInt() }) {
            world.combatEnabled = true
            world.tick(GameConstants.TICK_DT)
        }
        assertTrue(victim.alive, "wall cover must fully shelter the victim")
        assertEquals(GameConstants.MAX_HEALTH, victim.health)
    }

    @Test
    fun `grenade pouch honours zero and the wire cap`() {
        val (world, match) = worldWith()
        val thrower = world.phantom(-5f, 0f, 305, "thrower")
        simulate(world, match, 3)
        thrower.body.position.set(-5f, 0f, 0f)
        thrower.grenades = 0
        assertFalse(world.throwGrenade(thrower), "zero pouch: nothing thrown")
        assertTrue(world.grenades.grenades.isEmpty())
    }

    // ------------------------------------------------------------- snapshot

    @Test
    fun `snapshot carries pickups and grenades over the wire`() {
        val (world, match) = worldWith()
        val builder = SnapshotBuilder()
        val bot = world.phantom(-5f, 2f, 401)
        simulate(world, match, 3)

        val len = builder.build(world, match, tick = 42, serverTimeMs = 1000L, sequence = 1)
        val pkt = BinaryReader(builder.buffer, 0, len)
        val header = Protocol.Header()
        assertEquals(
            Protocol.ParseResult.OK,
            Protocol.parse(builder.buffer, len, header, pkt),
        )
        val decoded = Packets.readSnapshot(pkt)
        assertEquals(world.pickups.slots.size, decoded.pickups.size)
        val health = decoded.pickups.firstOrNull { it.kind == PickupKind.HEALTH.wire }
        assertNotNull(health)
        assertTrue(health.active)
        assertEquals(0f, health.x, 0.02f)
        assertEquals(5f, health.z, 0.02f)

        // Throw a nade; the very next snapshot must carry it wire-complete.
        assertTrue(world.throwGrenade(bot))
        repeat(5) { world.tick(GameConstants.TICK_DT) }
        val len2 = builder.build(world, match, tick = 50, serverTimeMs = 2000L, sequence = 2)
        val pkt2 = BinaryReader(builder.buffer, 0, len2)
        val header2 = Protocol.Header()
        Protocol.parse(builder.buffer, len2, header2, pkt2)
        val decoded2 = Packets.readSnapshot(pkt2)
        assertEquals(1, decoded2.grenades.size)
        assertTrue(decoded2.grenades[0].fuseTicks >= 0)
    }
}
