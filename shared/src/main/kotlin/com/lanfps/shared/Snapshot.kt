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

    @JvmField var entities: ArrayList<EntityState> = ArrayList()

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
        entities.clear()
        return this
    }
}
