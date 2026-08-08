package com.lanfps.shared

/**
 * Authoritative world state broadcast at [GameConstants.SNAPSHOT_RATE] Hz.
 *
 * The list of [entities] is shared for every recipient; only
 * [lastProcessedInputSeq] is per-recipient (it is patched in at send time so each
 * client can reconcile its own prediction), so the builder writes the common part
 * once and back-patches the two per-client fields.
 */
class Snapshot {
    /** Server simulation tick this snapshot represents. */
    @JvmField var serverTick: Int = 0

    /** Server monotonic time (ms) — clients use it to time-order snapshots. */
    @JvmField var serverTimeMs: Long = 0

    /** Recipient-specific: highest input sequence the server has applied. */
    @JvmField var lastProcessedInputSeq: Int = 0

    @JvmField var mode: Int = GameMode.DM.wire
    @JvmField var matchState: Int = MatchState.WARMUP
    @JvmField var matchTimeRemaining: Float = 0f

    /** For TDM scoreboards. */
    @JvmField var redScore: Int = 0
    @JvmField var blueScore: Int = 0

    /** P1-2: FULL (0) serialises [entities]; DELTA (1) serialises the delta. */
    @JvmField var kind: Int = SnapshotKind.FULL

    /** Full entity list (FULL snapshots, and the reconstructed result). */
    @JvmField var entities: ArrayList<EntityState> = ArrayList()

    /** P1-2: changed/new entities carried by a DELTA snapshot. */
    @JvmField var deltaChanged: ArrayList<EntityState> = ArrayList()
    /** P1-2: ids removed since the recipient's last keyframe. */
    @JvmField var deltaRemoved: ArrayList<Int> = ArrayList()

    /** P4-5: pickup markers. Always fully serialised (never delta-compressed;
     *  at ~8 B per slot the section is small and its churn is resend-only). */
    @JvmField var pickups: ArrayList<PickupState> = ArrayList()

    /** P4-6: live grenades. Fully serialised; an explosion is the id vanishing
     *  from the list, which the client turns into particles+sound. */
    @JvmField var grenades: ArrayList<GrenadeState> = ArrayList()

    val modeEnum: GameMode get() = GameMode.fromWire(mode)

    fun findEntity(id: Int): EntityState? {
        for (i in entities.indices) if (entities[i].id == id) return entities[i]
        return null
    }

    fun clear(): Snapshot {
        serverTick = 0
        serverTimeMs = 0
        lastProcessedInputSeq = 0
        redScore = 0
        blueScore = 0
        kind = SnapshotKind.FULL
        entities.clear()
        deltaChanged.clear()
        deltaRemoved.clear()
        pickups.clear()
        grenades.clear()
        return this
    }
}

/** P1-2: snapshot encoding kind, in wire order. */
object SnapshotKind {
    const val FULL: Int = 0
    const val DELTA: Int = 1
}
