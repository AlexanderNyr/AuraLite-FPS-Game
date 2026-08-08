package com.lanfps.server

import com.lanfps.shared.GameConstants
import com.lanfps.shared.PickupKind
import com.lanfps.shared.PickupState
import com.lanfps.shared.Weapons

/** A pickup that someone ran through: broadcast as MATCH_EVENT(PICKUP). */
class PickupEvent(
    @JvmField val playerId: Int,
    @JvmField val playerName: String,
    @JvmField val kind: PickupKind,
)

/**
 * P4-5: the live state machine behind the map's pickup slots.
 *
 * The arena definition only carries *markers* (`pickupSpawns`); this manager
 * turns them into consumable slots with respawn timers. Physics rules the
 * intake: a pickup is consumed when an alive body's feet come close enough —
 * no button press, exactly the way arena shooters always worked.
 *
 * Fairness rule: a slot that would do nothing (full health, full armor,
 * already holding the SMG, full grenade pouch) is *not* consumed — it stays
 * on the floor for someone who needs it. This is what keeps small maps from
 * degenerating into pickup-hoarding.
 */
class PickupManager(world: World) {

    class Slot(
        @JvmField val kind: PickupKind,
        @JvmField val x: Float,
        @JvmField val y: Float,
        @JvmField val z: Float,
    ) {
        @JvmField var active: Boolean = true
        @JvmField var respawnTimer: Float = 0f
    }

    @JvmField val slots: ArrayList<Slot> = ArrayList()

    private val world: World = world

    init {
        setArena()
    }

    /** Rebuilds slots from the current arena (map rotation / boot). */
    fun setArena() {
        slots.clear()
        for (spawn in world.serverArena.def.pickupSpawns) {
            slots.add(Slot(spawn.kind, spawn.position.x, spawn.position.y, spawn.position.z))
        }
    }

    /** Rearms every slot — new match, fresh economy. */
    fun resetAll() {
        for (slot in slots) {
            slot.active = true
            slot.respawnTimer = 0f
        }
    }

    /** Advances timers and consumes whatever an alive body is standing on. */
    fun tick(dt: Float, players: Collection<GameEntity>) {
        for (slot in slots) {
            if (!slot.active) {
                slot.respawnTimer -= dt
                if (slot.respawnTimer <= 0f) {
                    slot.respawnTimer = 0f
                    slot.active = true
                }
                continue
            }
            // A scan over 8-20 entities x up to ~40 slots is trivial at 60 Hz.
            for (e in players) {
                if (!e.alive) continue
                val dx = e.body.position.x - slot.x
                val dz = e.body.position.z - slot.z
                if (dx * dx + dz * dz > TRIGGER_RADIUS_SQ) continue
                val dy = e.body.position.y - slot.y
                if (dy < -TRIGGER_Y || dy > TRIGGER_Y) continue
                if (!wouldConsume(e, slot.kind)) continue
                consume(e, slot.kind)
                slot.active = false
                slot.respawnTimer = respawnSeconds(slot.kind)
                world.pickupFeed.add(PickupEvent(e.id, e.name, slot.kind))
                break // one consumer per tick per slot
            }
        }
    }

    /** True when taking this pickup would actually change the entity. */
    private fun wouldConsume(e: GameEntity, kind: PickupKind): Boolean = when (kind) {
        PickupKind.HEALTH -> e.health < GameConstants.MAX_HEALTH
        PickupKind.ARMOR -> e.armor < GameConstants.MAX_ARMOR
        PickupKind.SMG -> e.weapon != Weapons.SMG
        PickupKind.GRENADES -> e.grenades < GameConstants.MAX_GRENADES
    }

    private fun consume(e: GameEntity, kind: PickupKind) {
        when (kind) {
            PickupKind.HEALTH -> e.health = minOf(
                GameConstants.MAX_HEALTH, e.health + HEALTH_PACK,
            )
            PickupKind.ARMOR -> e.armor = minOf(
                GameConstants.MAX_ARMOR, e.armor + ARMOR_PACK,
            )
            PickupKind.SMG -> {
                e.weapon = Weapons.SMG
                e.ammoInMag = if (world.config.infiniteAmmo) {
                    Weapons.AMMO_INFINITE
                } else {
                    Weapons.SmgDef.magazineSize
                }
                e.reloadTimer = 0f
            }
            PickupKind.GRENADES -> e.grenades = minOf(
                GameConstants.MAX_GRENADES, e.grenades + GRENADE_PACK,
            )
        }
    }

    private fun respawnSeconds(kind: PickupKind): Float = when (kind) {
        PickupKind.HEALTH -> 20f
        PickupKind.ARMOR -> 30f
        PickupKind.SMG -> 30f
        PickupKind.GRENADES -> 30f
    }

    /** Copies the live slots into wire form for [com.lanfps.shared.Snapshot]. */
    fun snapshotTo(out: ArrayList<PickupState>, pool: ArrayList<PickupState>) {
        out.clear()
        for (i in slots.indices) {
            val slot = slots[i]
            while (pool.size <= i) pool.add(PickupState())
            val dst = pool[i]
            dst.kind = slot.kind.wire
            dst.flags = 0
            dst.active = slot.active
            dst.x = slot.x; dst.y = slot.y; dst.z = slot.z
            out.add(dst)
        }
    }

    companion object {
        /** Pickup trigger: horizontal reach from the body's centre. */
        private const val TRIGGER_RADIUS: Float = 1.0f
        private const val TRIGGER_RADIUS_SQ: Float = TRIGGER_RADIUS * TRIGGER_RADIUS
        private const val TRIGGER_Y: Float = 1.7f

        /** Health restocked by one medkit. */
        private const val HEALTH_PACK: Int = 40
        /** Armor granted by one shield cube. */
        private const val ARMOR_PACK: Int = 50
        /** Grenades granted by one ammo pouch. */
        private const val GRENADE_PACK: Int = 2
    }
}
