package com.lanfps.shared

/**
 * One replicated entity inside a snapshot (a human player or a server bot).
 *
 * Nicknames travel with every snapshot. At 16 entities that costs well under 1 KB
 * per snapshot, which is free on a LAN, and it removes an entire class of
 * "roster desync" bugs where a late-joining client shows blank name tags.
 */
class EntityState {
    @JvmField var id: Int = 0
    @JvmField var type: Int = EntityType.PLAYER
    @JvmField var team: Int = Team.NONE.wire

    @JvmField var x: Float = 0f
    @JvmField var y: Float = 0f
    @JvmField var z: Float = 0f

    @JvmField var yaw: Float = 0f
    @JvmField var pitch: Float = 0f

    @JvmField var vx: Float = 0f
    @JvmField var vy: Float = 0f
    @JvmField var vz: Float = 0f

    @JvmField var health: Int = GameConstants.MAX_HEALTH
    @JvmField var kills: Int = 0
    @JvmField var deaths: Int = 0

    /** Current weapon (id from [Weapons]); drives the client view model and HUD. */
    @JvmField var weapon: Int = Weapons.DEFAULT

    /** Rounds left in the magazine, or [Weapons.AMMO_INFINITE] (255) when the
     *  server runs with infinite ammo. Display-only for remote entities. */
    @JvmField var ammo: Int = Weapons.AMMO_INFINITE

    @JvmField var flags: Int = 0

    /**
     * Local-only label. NOT serialized on the wire since the P0-1 change: sending
     * the nickname with every entity in every 30 Hz snapshot could push a full
     * server past the UDP MTU (especially with 2-byte-per-char names) and cause
     * IP fragmentation. Names now travel once, in LOBBY_STATE, and the client
     * joins them to entities by id via a roster map.
     */
    @JvmField var name: String = ""

    var alive: Boolean
        get() = (flags and FLAG_ALIVE) != 0
        set(v) { flags = if (v) flags or FLAG_ALIVE else flags and FLAG_ALIVE.inv() }

    /** True on the tick the entity fired; drives the client muzzle flash. */
    var firing: Boolean
        get() = (flags and FLAG_FIRING) != 0
        set(v) { flags = if (v) flags or FLAG_FIRING else flags and FLAG_FIRING.inv() }

    var crouching: Boolean
        get() = (flags and FLAG_CROUCH) != 0
        set(v) { flags = if (v) flags or FLAG_CROUCH else flags and FLAG_CROUCH.inv() }

    val teamEnum: Team get() = Team.fromWire(team)

    fun copyFrom(o: EntityState): EntityState {
        id = o.id; type = o.type; team = o.team
        x = o.x; y = o.y; z = o.z
        yaw = o.yaw; pitch = o.pitch
        vx = o.vx; vy = o.vy; vz = o.vz
        health = o.health; kills = o.kills; deaths = o.deaths
        weapon = o.weapon; ammo = o.ammo
        flags = o.flags; name = o.name
        return this
    }

    fun copy(): EntityState = EntityState().copyFrom(this)

    fun write(w: BinaryWriter) {
        w.writeU16(id)
        w.writeU8(type)
        w.writeU8(team)
        w.writeU8(flags)
        w.writeF32(x); w.writeF32(y); w.writeF32(z)
        w.writeF32(yaw); w.writeF32(pitch)
        // Velocity is quantised to 1/100 m/s in a signed 16-bit field: plenty for
        // dead-reckoning between snapshots and 6 bytes cheaper than three floats.
        w.writeI16(quantiseVelocity(vx))
        w.writeI16(quantiseVelocity(vy))
        w.writeI16(quantiseVelocity(vz))
        w.writeU8(MathUtil.clamp(health, 0, 255))
        w.writeU16(MathUtil.clamp(kills, 0, 65535))
        w.writeU16(MathUtil.clamp(deaths, 0, 65535))
        w.writeU8(MathUtil.clamp(weapon, 0, 255))
        w.writeU8(MathUtil.clamp(ammo, 0, 255))
        // NB: no name. See the field comment — names moved to LOBBY_STATE.
    }

    fun read(r: BinaryReader): EntityState {
        id = r.readU16()
        type = r.readU8()
        team = r.readU8()
        flags = r.readU8()
        x = r.readF32(); y = r.readF32(); z = r.readF32()
        yaw = r.readF32(); pitch = r.readF32()
        vx = r.readI16() / 100f
        vy = r.readI16() / 100f
        vz = r.readI16() / 100f
        health = r.readU8()
        kills = r.readU16()
        deaths = r.readU16()
        weapon = r.readU8()
        ammo = r.readU8()
        // NB: no name on the wire anymore (see the field comment). The client
        // fills it from the LOBBY_STATE roster.
        return this
    }

    override fun toString(): String =
        "Entity(#$id '$name' ${if (type == EntityType.BOT) "BOT" else "PLR"} " +
            "pos=(%.1f,%.1f,%.1f) hp=$health k=$kills d=$deaths alive=$alive)".format(x, y, z)

    companion object {
        const val FLAG_ALIVE: Int = 1 shl 0
        const val FLAG_FIRING: Int = 1 shl 1
        const val FLAG_CROUCH: Int = 1 shl 2

        /** Fixed part of the wire size (no nickname on the wire since P0-1). */
        const val WIRE_SIZE_FIXED: Int = 2 + 1 + 1 + 1 + 12 + 8 + 6 + 1 + 2 + 2 + 1 + 1

        private fun quantiseVelocity(v: Float): Int =
            MathUtil.clamp((v * 100f).toInt(), -32768, 32767)
    }
}
