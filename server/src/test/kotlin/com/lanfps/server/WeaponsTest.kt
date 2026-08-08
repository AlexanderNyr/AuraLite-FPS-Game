package com.lanfps.server

import com.lanfps.shared.ArenaDef
import com.lanfps.shared.GameConstants
import com.lanfps.shared.GameMode
import com.lanfps.shared.InputButtons
import com.lanfps.shared.InputCommand
import com.lanfps.shared.Weapons
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P2-1 / P2-2: the weapon catalogue and the magazine/reload rules.
 *
 * The raycasts are tested straight through [ServerRaycast.fireWeapon]; the
 * authoritative ammo bookkeeping is tested through [World.tick] driven by a
 * scripted [ClientSession] — the exact same path a phone takes.
 */
class WeaponsTest {

    private val arenaDef: ArenaDef = ArenaDef.builtinArena01()
    private val arena = ServerArena(arenaDef)
    private val raycast = ServerRaycast(arenaDef)

    private fun entity(id: Int, x: Float, z: Float, yaw: Float = 90f) =
        BotEntity(id, "E$id").apply {
            body.position.set(x, 0f, z)
            body.yaw = yaw
            body.pitch = 0f
            body.onGround = true
            alive = true
        }

    // ---- burst casting ------------------------------------------------------

    @Test
    fun `shotgun stacks every pellet on a point-blank target`() {
        // At 2 m the 5.5 degree cone cannot throw a pellet outside a 0.4 m
        // radius hitbox (max offset tan(5.5deg)*2 ~ 0.19 m), so all seven
        // pellets must land: 7 * 11 = 77 damage in one trigger pull.
        val shooter = entity(1, -10f, -14f)
        val target = entity(2, -8f, -14f)

        val result = raycast.fireWeapon(shooter, Weapons.ShotgunDef, listOf(target))

        assertEquals(
            Weapons.ShotgunDef.damage * Weapons.ShotgunDef.pellets,
            result.damageByEntity[target],
            "point-blank shotgun burst should land every pellet",
        )
    }

    @Test
    fun `sniper deals 90 damage with a single precise shot`() {
        val shooter = entity(1, -10f, -14f)
        val target = entity(2, 0f, -14f)

        val result = raycast.fireWeapon(shooter, Weapons.SniperDef, listOf(target))

        assertEquals(
            Weapons.SniperDef.damage, result.damageByEntity[target],
            "sniper should land its one pellet",
        )
    }

    @Test
    fun `weapon range limits the burst`() {
        // 54 m of clear lane at z=-14: outside the shotgun's 45 m, well inside
        // the sniper's 200 m.
        val shooter = entity(1, -27f, -14f)
        val target = entity(2, 27f, -14f)

        assertNull(
            raycast.fireWeapon(shooter, Weapons.ShotgunDef, listOf(target))
                .damageByEntity[target],
            "a shotgun must not reach across the whole map",
        )
        assertEquals(
            Weapons.SniperDef.damage,
            raycast.fireWeapon(shooter, Weapons.SniperDef, listOf(target))
                .damageByEntity[target],
            "the sniper rifle is the long-range option",
        )
    }

    @Test
    fun `shotgun pellets do not pass through the centre pillar`() {
        // Same geometry argument as RaycastTest: the cone tops out at +-0.77 m
        // at the pillar face, the pillar is +-2 m wide and 2.5 m tall.
        val shooter = entity(1, -10f, 0f)
        val target = entity(2, 10f, 0f)

        val result = raycast.fireWeapon(shooter, Weapons.ShotgunDef, listOf(target))

        assertTrue(
            result.damageByEntity.isEmpty(),
            "the pillar must stop the whole burst, got $result",
        )
    }

    @Test
    fun `rifle burst matches the legacy single-ray behaviour`() {
        val shooter = entity(1, -10f, -14f)
        val target = entity(2, 0f, -14f)

        val burst = raycast.fireWeapon(shooter, Weapons.RifleDef, listOf(target))

        assertEquals(Weapons.RifleDef.damage, burst.damageByEntity[target])
    }

    // ---- world-level ammo rules --------------------------------------------

    private fun makeWorld(infiniteAmmo: Boolean): Pair<World, PlayerEntity> {
        val config = ServerConfig().apply {
            mode = GameMode.DM
            botCount = 0
            this.infiniteAmmo = infiniteAmmo
            matchTimeSeconds = 600
        }
        val world = World(ServerArena(arenaDef), config)
        val session = ClientSession(
            7, InetAddress.getLoopbackAddress(), 40000, "Shooter",
        )
        val player = world.addPlayer(session)
        return world to player
    }

    private fun command(seq: Int, fire: Boolean, weapon: Int = Weapons.RIFLE, reload: Boolean = false) =
        InputCommand().apply {
            sequence = seq
            yaw = 90f
            pitch = 0f
            moveForward = 0f
            moveRight = 0f
            buttons = (if (fire) InputButtons.FIRE else 0) or
                (if (reload) InputButtons.RELOAD else 0)
            this.weapon = weapon
        }

    @Test
    fun `finite ammo decrements per shot and auto-reloads when empty`() {
        val (world, player) = makeWorld(infiniteAmmo = false)
        player.weapon = Weapons.SNIPER
        player.ammoInMag = 1
        val session = player.session

        session.enqueueInputs(listOf(command(1, fire = true, weapon = Weapons.SNIPER)))
        world.tick(GameConstants.TICK_DT)
        assertEquals(0, player.ammoInMag, "the single round should have been spent")
        assertTrue(player.firedThisTick, "the shot went out")

        // Pull the trigger on an empty chamber: the world must kick off the
        // reload itself so nobody is ever stuck dry.
        session.enqueueInputs(listOf(command(2, fire = true, weapon = Weapons.SNIPER)))
        world.tick(GameConstants.TICK_DT)
        assertTrue(player.reloading, "an empty click starts the reload")

        // Ride out the 2.6 s sniper reload (plus slack) with no further input.
        repeat((Weapons.SniperDef.reloadSeconds * GameConstants.TICK_RATE + 10).toInt()) {
            world.tick(GameConstants.TICK_DT)
        }
        assertEquals(
            Weapons.SniperDef.magazineSize, player.ammoInMag,
            "magazine should be refilled after the reload seconds elapsed",
        )
        assertTrue(!player.reloading)
    }

    @Test
    fun `an explicit reload request refills a partial magazine`() {
        val (world, player) = makeWorld(infiniteAmmo = false)
        player.ammoInMag = 3

        player.session.enqueueInputs(listOf(command(1, fire = false, reload = true)))
        world.tick(GameConstants.TICK_DT)
        assertTrue(player.reloading, "RELOAD button starts a reload below a full mag")

        repeat((Weapons.RifleDef.reloadSeconds * GameConstants.TICK_RATE + 10).toInt()) {
            world.tick(GameConstants.TICK_DT)
        }
        assertEquals(Weapons.RifleDef.magazineSize, player.ammoInMag)
    }

    @Test
    fun `switching weapons brings a fresh magazine and a draw delay`() {
        val (world, player) = makeWorld(infiniteAmmo = false)
        player.ammoInMag = 7 // partially spent rifle mag

        player.session.enqueueInputs(listOf(command(1, fire = false, weapon = Weapons.SHOTGUN)))
        world.tick(GameConstants.TICK_DT)

        assertEquals(Weapons.SHOTGUN, player.weapon, "the server adopted the switch")
        assertEquals(
            Weapons.ShotgunDef.magazineSize, player.ammoInMag,
            "the new weapon comes with a full magazine",
        )
        assertTrue(
            player.fireCooldown >= 0.3f,
            "weapon draw takes a moment (fireCooldown=${player.fireCooldown})",
        )

        // The draw delay must actually block firing.
        player.session.enqueueInputs(
            listOf(command(2, fire = true, weapon = Weapons.SHOTGUN)),
        )
        world.tick(GameConstants.TICK_DT)
        assertTrue(!player.firedThisTick, "cannot fire during the weapon draw")
    }

    @Test
    fun `switching weapons cancels an in-flight reload`() {
        val (world, player) = makeWorld(infiniteAmmo = false)
        player.reloadTimer = 1.0f

        player.session.enqueueInputs(listOf(command(1, fire = false, weapon = Weapons.SNIPER)))
        world.tick(GameConstants.TICK_DT)

        assertEquals(Weapons.SNIPER, player.weapon)
        assertTrue(!player.reloading, "the reload died with the weapon swap")
        assertEquals(Weapons.SniperDef.magazineSize, player.ammoInMag)
    }

    @Test
    fun `infinite ammo mode never runs dry`() {
        val (world, player) = makeWorld(infiniteAmmo = true)

        assertEquals(
            Weapons.AMMO_INFINITE, player.ammoInMag,
            "respawn under infiniteAmmo marks the magazine as bottomless",
        )

        var seq = 1
        repeat(20) {
            player.session.enqueueInputs(
                listOf(command(seq++, fire = true, weapon = Weapons.RIFLE)),
            )
            world.tick(GameConstants.TICK_DT)
        }
        assertEquals(Weapons.AMMO_INFINITE, player.ammoInMag, "magazine never depletes")
        assertTrue(!player.reloading, "reloads are a no-op with infinite ammo")
    }

    @Test
    fun `sniper shot through the world deals exactly 90 damage`() {
        val (world, player) = makeWorld(infiniteAmmo = false)
        val victim = entity(50, 0f, -14f)
        world.entities[victim.id] = victim

        // Walk the shooter onto the north lane facing east.
        player.body.position.set(-10f, 0f, -14f)
        player.body.yaw = 90f
        player.weapon = Weapons.SNIPER
        player.ammoInMag = Weapons.SniperDef.magazineSize

        player.session.enqueueInputs(listOf(command(1, fire = true, weapon = Weapons.SNIPER)))
        world.tick(GameConstants.TICK_DT)

        assertEquals(
            GameConstants.MAX_HEALTH - Weapons.SniperDef.damage,
            victim.health,
            "one sniper body shot takes a fresh player to 10 hp",
        )
        assertEquals(player.id, victim.lastAttackerId)
        assertEquals(Weapons.SniperDef.magazineSize - 1, player.ammoInMag)
    }

    @Test
    fun `invalid weapon ids in input are sanitised away`() {
        val (world, player) = makeWorld(infiniteAmmo = false)

        player.session.enqueueInputs(listOf(command(1, fire = false, weapon = 9)))
        world.tick(GameConstants.TICK_DT)

        assertEquals(
            Weapons.DEFAULT, player.weapon,
            "an out-of-catalogue id must not switch the weapon",
        )
    }

    @Test
    fun `weapon catalogue is well formed`() {
        assertEquals(Weapons.COUNT, 3)
        for (id in 0 until Weapons.COUNT) {
            assertTrue(Weapons.isValid(id))
            val def = Weapons.byId(id)
            assertNotNull(def)
            assertTrue(def.damage > 0 && def.magazineSize > 0 && def.range > 0f)
            assertTrue(def.fireInterval > 0f && def.reloadSeconds > 0f)
        }
        assertTrue(!Weapons.isValid(-1) && !Weapons.isValid(Weapons.COUNT))
        assertEquals(Weapons.RifleDef, Weapons.byId(12345), "unknown ids fall back to the rifle")
    }
}
