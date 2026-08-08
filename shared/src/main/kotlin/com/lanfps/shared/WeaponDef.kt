package com.lanfps.shared

/**
 * Static weapon catalogue (P2-1 of docs/IMPROVEMENT_PLAN.md).
 *
 * The `weapon` field of [InputCommand] existed from day one but was unused;
 * these definitions give it meaning. Everything about a weapon — damage,
 * pellets, spread cone, rate of fire, magazine, reload time, range and recoil —
 * lives here once, so the server resolves shots and the client draws
 * tracers/recoil/HUD from the same numbers.
 *
 * All weapons stay hitscan: multi-pellet spread (the shotgun) is rolled on the
 * server inside [com.lanfps.shared.RayMath]-based casts, never by the client.
 */
class WeaponDef(
    /** Wire/world id. Stable: ids go into snapshots and input commands. */
    @JvmField val id: Int,
    /** Display name for the HUD and view model. */
    @JvmField val displayName: String,
    /** Short label for the weapon-switch button. */
    @JvmField val shortName: String,
    /** Hitscan damage per pellet. */
    @JvmField val damage: Int,
    /** Projectiles per shot; >1 only for the shotgun. */
    @JvmField val pellets: Int,
    /** Random cone half-angle in degrees applied to every pellet. */
    @JvmField val spreadDeg: Float,
    /** Seconds between shots. */
    @JvmField val fireInterval: Float,
    /** Rounds per magazine (used when the server runs with finite ammo). */
    @JvmField val magazineSize: Int,
    /** Seconds a reload takes. */
    @JvmField val reloadSeconds: Float,
    /** Maximum ray length in metres. */
    @JvmField val range: Float,
    /** Upward camera kick per shot, in degrees. */
    @JvmField val recoilPitchDeg: Float,
)

object Weapons {
    const val RIFLE: Int = 0
    const val SHOTGUN: Int = 1
    const val SNIPER: Int = 2
    const val COUNT: Int = 3

    /** Default weapon every entity spawns with (and the first id clients cycle). */
    const val DEFAULT: Int = RIFLE

    /**
     * Magic ammo value replicated in snapshots meaning "bottomless magazine"
     * (server runs with `infiniteAmmo=true`). The HUD renders it as ∞.
     */
    const val AMMO_INFINITE: Int = 255

    @JvmField
    val RifleDef: WeaponDef = WeaponDef(
        id = RIFLE, displayName = "RIFLE", shortName = "RFL",
        damage = 25, pellets = 1, spreadDeg = 0.4f,
        fireInterval = 0.125f, magazineSize = 30, reloadSeconds = 1.6f,
        range = 120f, recoilPitchDeg = 0.55f,
    )

    @JvmField
    val ShotgunDef: WeaponDef = WeaponDef(
        id = SHOTGUN, displayName = "SHOTGUN", shortName = "SG",
        damage = 11, pellets = 7, spreadDeg = 5.5f,
        fireInterval = 0.85f, magazineSize = 6, reloadSeconds = 2.2f,
        range = 45f, recoilPitchDeg = 1.7f,
    )

    @JvmField
    val SniperDef: WeaponDef = WeaponDef(
        id = SNIPER, displayName = "SNIPER", shortName = "SNP",
        damage = 90, pellets = 1, spreadDeg = 0.05f,
        fireInterval = 1.4f, magazineSize = 5, reloadSeconds = 2.6f,
        range = 200f, recoilPitchDeg = 2.4f,
    )

    fun byId(id: Int): WeaponDef = when (id) {
        SHOTGUN -> ShotgunDef
        SNIPER -> SniperDef
        else -> RifleDef
    }

    fun isValid(id: Int): Boolean = id in 0 until COUNT
}
