package com.lanfps.shared

/**
 * P4-5: what the glowing cubes scattered around the newer arenas do when a
 * body runs through them. The wire id goes into MATCH_EVENT(PICKUP).extra and
 * into the pickup section of every snapshot.
 */
enum class PickupKind(val wire: Int) {
    /** +40 health, capped; only consumed when actually hurt. */
    HEALTH(1),

    /** +50 armor, capped; only consumed when the pool has room. */
    ARMOR(2),

    /** Force-switch to the SMG with a fresh magazine (shortcut pickup). */
    SMG(3),

    /** +2 grenades, capped. */
    GRENADES(4);

    companion object {
        fun fromWire(v: Int): PickupKind? = entries.firstOrNull { it.wire == v }

        fun fromName(s: String): PickupKind? = when (s.trim().lowercase()) {
            "health", "hp", "medkit", "med" -> HEALTH
            "armor", "armour", "shield" -> ARMOR
            "smg", "weapon" -> SMG
            "grenades", "grenade", "nades", "nade" -> GRENADES
            else -> null
        }
    }
}

/**
 * One pickup slot as it appears in a snapshot. Position is quantised to a
 * centimetre (i16, ±327 m): enough for a render marker the server owns anyway.
 */
class PickupState {
    @JvmField var kind: Int = 0

    /** Bit 0: currently lying in the world (false = waiting to respawn). */
    @JvmField var flags: Int = 1

    @JvmField var x: Float = 0f
    @JvmField var y: Float = 0f
    @JvmField var z: Float = 0f

    var active: Boolean
        get() = (flags and 1) != 0
        set(v) { flags = if (v) flags or 1 else flags and 1.inv() }

    fun copyFrom(o: PickupState): PickupState {
        kind = o.kind; flags = o.flags
        x = o.x; y = o.y; z = o.z
        return this
    }

    fun write(w: BinaryWriter) {
        w.writeU8(kind)
        w.writeU8(flags)
        w.writeI16(MathUtil.clamp((x * 100f).toInt(), -32768, 32767))
        w.writeI16(MathUtil.clamp((y * 100f).toInt(), -32768, 32767))
        w.writeI16(MathUtil.clamp((z * 100f).toInt(), -32768, 32767))
    }

    fun read(r: BinaryReader): PickupState {
        kind = r.readU8()
        flags = r.readU8()
        x = r.readI16() / 100f
        y = r.readI16() / 100f
        z = r.readI16() / 100f
        return this
    }

    companion object {
        /** u8 kind + u8 flags + 3xi16 position. */
        const val WIRE_SIZE: Int = 8
    }
}

/**
 * A live hand grenade as replicated to clients. Clients draw it as a dark
 * blinking ball and need no velocity: 30 Hz updates are plenty to follow a
 * thrown arc, and the explosion is signalled by the id *leaving* the list.
 */
class GrenadeState {
    /** Unique within a match, assigned by the server as it spawns each nade. */
    @JvmField var id: Int = 0

    /** Seconds of fuse already burned, in 1/60 s ticks (for the blink rate). */
    @JvmField var fuseTicks: Int = 0

    @JvmField var x: Float = 0f
    @JvmField var y: Float = 0f
    @JvmField var z: Float = 0f

    fun copyFrom(o: GrenadeState): GrenadeState {
        id = o.id; fuseTicks = o.fuseTicks
        x = o.x; y = o.y; z = o.z
        return this
    }

    fun write(w: BinaryWriter) {
        w.writeU8(id and 0xFF)
        w.writeU8(fuseTicks and 0xFF)
        w.writeF32(x); w.writeF32(y); w.writeF32(z)
    }

    fun read(r: BinaryReader): GrenadeState {
        id = r.readU8()
        fuseTicks = r.readU8()
        x = r.readF32(); y = r.readF32(); z = r.readF32()
        return this
    }

    companion object {
        /** u8 id + u8 fuse + 3xf32 position. */
        const val WIRE_SIZE: Int = 14
    }
}
