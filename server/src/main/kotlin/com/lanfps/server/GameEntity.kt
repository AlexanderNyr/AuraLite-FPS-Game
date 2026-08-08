package com.lanfps.server

import com.lanfps.shared.Aabb
import com.lanfps.shared.BodyState
import com.lanfps.shared.EntityState
import com.lanfps.shared.EntityType
import com.lanfps.shared.GameConstants
import com.lanfps.shared.SpawnPoint
import com.lanfps.shared.Team
import com.lanfps.shared.Vec3
import com.lanfps.shared.Weapons

/**
 * Anything that can move, be shot, die and respawn. Human players and bots share
 * this base so the simulation, hit detection and snapshot code treat them
 * identically — bots are literally just entities without a network session.
 */
abstract class GameEntity(@JvmField val id: Int) {

    @JvmField var name: String = ""
    @JvmField var team: Team = Team.NONE

    @JvmField val body: BodyState = BodyState()

    @JvmField var health: Int = GameConstants.MAX_HEALTH
    @JvmField var alive: Boolean = false

    @JvmField var kills: Int = 0
    @JvmField var deaths: Int = 0

    /** Seconds until this entity respawns (only meaningful while dead). */
    @JvmField var respawnTimer: Float = 0f

    /** Seconds until the weapon can fire again. */
    @JvmField var fireCooldown: Float = 0f

    /** Set for the single tick on which the entity fired; drives muzzle flash. */
    @JvmField var firedThisTick: Boolean = false

    @JvmField var lastAttackerId: Int = 0

    // ---- P2-1/P2-2: per-entity weapon state --------------------------------
    /** Current weapon, an id from [Weapons]. Kept across respawns. */
    @JvmField var weapon: Int = Weapons.DEFAULT

    /** Rounds left in the magazine, or [Weapons.AMMO_INFINITE] when the server
     *  runs with `infiniteAmmo=true`. */
    @JvmField var ammoInMag: Int = Weapons.byId(Weapons.DEFAULT).magazineSize

    /** >0 while a reload is in progress; counts down to the refill tick. */
    @JvmField var reloadTimer: Float = 0f

    /** P4-5: armor pool; absorbs 2/3 of incoming damage until depleted. */
    @JvmField var armor: Int = 0

    /** P4-6: grenades left in the pouch. */
    @JvmField var grenades: Int = GameConstants.START_GRENADES

    /** P4-6: rising-edge memory for the GRENADE button (one throw per press). */
    @JvmField var prevGrenadePressed: Boolean = false

    val reloading: Boolean get() = reloadTimer > 0f

    abstract val entityType: Int

    val isBot: Boolean get() = entityType == EntityType.BOT

    fun eyePosition(out: Vec3): Vec3 = body.eyePosition(out)

    fun hitbox(out: Aabb): Aabb =
        out.setFromBody(body.position, GameConstants.PLAYER_RADIUS, body.height)

    /** Places the entity at a spawn point with full health. */
    open fun spawnAt(spawn: SpawnPoint) {
        body.reset()
        body.position.set(spawn.position)
        body.yaw = spawn.yaw
        body.pitch = 0f
        body.onGround = true
        health = GameConstants.MAX_HEALTH
        alive = true
        respawnTimer = 0f
        fireCooldown = 0f
        firedThisTick = false
        lastAttackerId = 0
        // P4 fields reset per respawn: you drop your armor, keep a fresh nade.
        armor = 0
        grenades = GameConstants.START_GRENADES
        // Weapon identity survives death; the magazine is refilled
        // (World.respawn upgrades this to AMMO_INFINITE when configured).
        ammoInMag = Weapons.byId(weapon).magazineSize
        reloadTimer = 0f
    }

    open fun kill() {
        alive = false
        health = 0
        deaths++
        respawnTimer = GameConstants.RESPAWN_DELAY_SEC
        body.velocity.zero()
    }

    /** Copies simulation state into the wire representation. */
    fun writeTo(dst: EntityState) {
        dst.id = id
        dst.type = entityType
        dst.team = team.wire
        dst.x = body.position.x
        dst.y = body.position.y
        dst.z = body.position.z
        dst.yaw = body.yaw
        dst.pitch = body.pitch
        dst.vx = body.velocity.x
        dst.vy = body.velocity.y
        dst.vz = body.velocity.z
        dst.health = health
        dst.kills = kills
        dst.deaths = deaths
        dst.weapon = weapon
        dst.ammo = ammoInMag
        dst.armor = armor
        dst.grenades = grenades
        dst.flags = 0
        dst.alive = alive
        dst.firing = firedThisTick
        dst.crouching = body.crouching
        // P0-1: names no longer go into snapshots (they move via LOBBY_STATE).
    }

    override fun toString(): String = "#$id '$name'"
}
