package com.lanfps.shared

/**
 * P1-2: delta compression for snapshots.
 *
 * A full snapshot lists every entity; a delta lists only the entities that
 * changed since the client's last keyframe, plus a list of removed ids. Because
 * names left the snapshot format (P0-1) an unchanged entity is only ~36 bytes,
 * so when most entities are idle the delta is a fraction of a full snapshot.
 *
 * The pure logic (compute / apply / serialise) lives in `shared` so it can be
 * unit-tested on the JVM and used by both the server (to build deltas) and the
 * client (to reconstruct full snapshots for interpolation).
 */
class SnapshotDelta {

    /** Full states of entities that are new or whose state changed. */
    val changed = ArrayList<EntityState>()

    /** Entity ids present in the base but gone from the current snapshot. */
    val removed = ArrayList<Int>()

    fun clear() {
        changed.clear()
        removed.clear()
    }

    val isEmpty: Boolean get() = changed.isEmpty() && removed.isEmpty()

    // ------------------------------------------------------------------ logic

    companion object {

        /** Field-by-field equality, ignoring the local-only [EntityState.name]. */
        fun sameState(a: EntityState, b: EntityState): Boolean {
            if (a.id != b.id || a.type != b.type || a.team != b.team) return false
            if (a.flags != b.flags) return false
            if (a.x != b.x || a.y != b.y || a.z != b.z) return false
            if (a.yaw != b.yaw || a.pitch != b.pitch) return false
            if (a.vx != b.vx || a.vy != b.vy || a.vz != b.vz) return false
            if (a.health != b.health || a.kills != b.kills || a.deaths != b.deaths) return false
            // P4: weapon/armor/grenade changes without a position change used to
            // pass "unchanged" and skip the client entirely for one keyframe.
            if (a.weapon != b.weapon || a.ammo != b.ammo) return false
            if (a.armor != b.armor || a.grenades != b.grenades) return false
            return true
        }

        /**
         * Diffs [current] against the [base] keyed by entity id, filling [out].
         * [base] is the last full state the recipient has.
         */
        fun compute(base: Map<Int, EntityState>, current: List<EntityState>, out: SnapshotDelta): SnapshotDelta {
            out.clear()
            val seen = HashSet<Int>(current.size + 4)
            for (e in current) {
                seen.add(e.id)
                val old = base[e.id]
                if (old == null || !sameState(old, e)) out.changed.add(e)
            }
            for (id in base.keys) {
                if (!seen.contains(id)) out.removed.add(id)
            }
            return out
        }

        /**
         * Applies [delta] on top of [base] (the recipient's last keyframe),
         * producing a full state in [out]. Base entities that are removed are
         * dropped; changed/new entities replace or append by id.
         */
        fun apply(
            base: List<EntityState>,
            delta: SnapshotDelta,
            out: ArrayList<EntityState>,
        ): ArrayList<EntityState> {
            out.clear()
            val byId = HashMap<Int, EntityState>(base.size + delta.changed.size + 4)
            for (e in base) byId[e.id] = e
            for (c in delta.changed) byId[c.id] = c
            for (id in delta.removed) byId.remove(id)

            for (e in base) {
                val cur = byId[e.id] ?: continue // removed
                out.add(cur)
            }
            // Brand-new entities that weren't in the base, preserving delta order.
            for (c in delta.changed) {
                if (out.none { it.id == c.id }) out.add(c)
            }
            return out
        }
    }

    // -------------------------------------------------------------- serialise

    fun write(w: BinaryWriter) {
        val n = if (changed.size > 255) 255 else changed.size
        w.writeU8(n)
        for (i in 0 until n) changed[i].write(w)
        val m = if (removed.size > 255) 255 else removed.size
        w.writeU8(m)
        for (i in 0 until m) w.writeU16(removed[i])
    }

    fun read(r: BinaryReader): SnapshotDelta {
        clear()
        val n = r.readU8()
        for (i in 0 until n) changed.add(EntityState().read(r))
        val m = r.readU8()
        for (i in 0 until m) removed.add(r.readU16())
        return this
    }
}
