package com.lanfps.client

import com.lanfps.shared.EntityState
import com.lanfps.shared.GameConstants
import com.lanfps.shared.MathUtil
import com.lanfps.shared.Snapshot

/**
 * Entity interpolation buffer.
 *
 * Remote entities are **never** drawn at the position of the newest snapshot.
 * They are drawn [GameConstants.INTERPOLATION_DELAY_MS] in the past, which
 * guarantees that two snapshots always bracket the render time, so motion is
 * smooth even though snapshots only arrive 30 times per second and may arrive
 * late, out of order or not at all.
 *
 * Server and client clocks are unrelated, so we keep a smoothed estimate of
 * `serverTime - localTime` and convert the render time into the server
 * timeline.
 *
 * Thread safety: [add] runs on the network thread, [sampleInto] on the GL
 * thread; both take the same monitor. The critical sections are a few
 * microseconds long.
 */
class SnapshotBuffer {

    /** Enough for ~half a second of history at 30 Hz. */
    private val capacity = 24

    private val lock = Any()
    private val items = ArrayList<Snapshot>(capacity)

    /** serverTimeMs - localTimeMs, exponentially smoothed. */
    private var clockOffsetMs: Double = 0.0
    private var haveOffset = false

    /** Newest snapshot received, for HUD / scoreboard / match state. */
    @Volatile
    var latest: Snapshot? = null
        private set

    @Volatile
    var receivedCount: Int = 0
        private set

    /** Set when a snapshot arrives older than one we already have. */
    @Volatile
    var outOfOrderCount: Int = 0
        private set

    fun add(snapshot: Snapshot, localRecvMs: Long) {
        synchronized(lock) {
            receivedCount++

            val newest = items.lastOrNull()
            val inOrder = newest == null || snapshot.serverTick > newest.serverTick

            // Only in-order arrivals are allowed to move the clock estimate.
            // A snapshot that overtook an older one on the wire, or a duplicate
            // of one we already hold, is by definition *late*: its apparent
            // offset is too small and would drag the whole render timeline
            // backwards, causing a visible hitch. Ignore those samples.
            if (inOrder) {
                val sample = (snapshot.serverTimeMs - localRecvMs).toDouble()
                if (!haveOffset) {
                    clockOffsetMs = sample
                    haveOffset = true
                } else {
                    // A packet can only ever be *delayed*, never early, so the
                    // largest observed offset is the most accurate one. Snap up
                    // immediately, drift down slowly - this tracks real clock
                    // skew without letting one late packet rewind the timeline.
                    clockOffsetMs = if (sample > clockOffsetMs) {
                        sample
                    } else {
                        clockOffsetMs + (sample - clockOffsetMs) * 0.02
                    }
                }
            }

            if (!inOrder) {
                // Out of order or duplicate: keep the buffer sorted, drop duplicates.
                outOfOrderCount++
                if (items.any { it.serverTick == snapshot.serverTick }) return
                var i = items.size - 1
                while (i >= 0 && items[i].serverTick > snapshot.serverTick) i--
                items.add(i + 1, snapshot)
            } else {
                items.add(snapshot)
            }

            while (items.size > capacity) items.removeAt(0)
            latest = items.last()
        }
    }

    fun clear() {
        synchronized(lock) {
            items.clear()
            latest = null
            haveOffset = false
            clockOffsetMs = 0.0
            receivedCount = 0
            outOfOrderCount = 0
        }
    }

    /** Server-timeline instant that should be on screen right now. */
    fun renderServerTime(localNowMs: Long): Long =
        (localNowMs + clockOffsetMs).toLong() - GameConstants.INTERPOLATION_DELAY_MS

    /**
     * Fills [out] with the interpolated state of every entity at the render
     * time. Instances in [out] are reused between calls, so the caller must not
     * hold on to them past the next call.
     *
     * @return true when at least one entity was produced.
     */
    fun sampleInto(out: ArrayList<EntityState>, pool: ArrayList<EntityState>, localNowMs: Long): Boolean {
        synchronized(lock) {
            out.clear()
            if (items.isEmpty()) return false

            val target = renderServerTime(localNowMs)

            // Locate the pair (a, b) with a.time <= target <= b.time.
            var a: Snapshot? = null
            var b: Snapshot? = null
            for (i in items.indices) {
                val s = items[i]
                if (s.serverTimeMs <= target) {
                    a = s
                } else {
                    b = s
                    break
                }
            }

            if (a == null) {
                // Render time is older than everything we have (just connected):
                // show the oldest snapshot rather than nothing.
                a = items.first()
                b = null
            }
            if (b == null) {
                // Render time is newer than the newest snapshot (packet loss or a
                // stalled server): freeze on the newest state instead of
                // extrapolating into walls.
                copyAll(a, out, pool)
                return out.isNotEmpty()
            }

            val span = (b.serverTimeMs - a.serverTimeMs).toFloat()
            val t = if (span <= 0.001f) 0f else {
                MathUtil.clamp((target - a.serverTimeMs).toFloat() / span, 0f, 1f)
            }

            var used = 0
            for (i in b.entities.indices) {
                val nb = b.entities[i]
                val na = a.findEntity(nb.id)
                val dst = borrow(pool, used++)
                if (na == null) {
                    // Entity appeared between the two snapshots: pop it in at its
                    // first known position rather than sliding it from the origin.
                    dst.copyFrom(nb)
                } else {
                    dst.copyFrom(nb)
                    dst.x = MathUtil.lerp(na.x, nb.x, t)
                    dst.y = MathUtil.lerp(na.y, nb.y, t)
                    dst.z = MathUtil.lerp(na.z, nb.z, t)
                    dst.yaw = MathUtil.wrapDegrees(MathUtil.lerpAngleDeg(na.yaw, nb.yaw, t))
                    dst.pitch = MathUtil.lerp(na.pitch, nb.pitch, t)
                    // Firing is a one-tick pulse: keep it if either end has it so a
                    // muzzle flash is never swallowed by interpolation.
                    if (na.firing) dst.firing = true
                }
                out.add(dst)
            }
            return out.isNotEmpty()
        }
    }

    private fun copyAll(s: Snapshot, out: ArrayList<EntityState>, pool: ArrayList<EntityState>) {
        var used = 0
        for (i in s.entities.indices) {
            out.add(borrow(pool, used++).copyFrom(s.entities[i]))
        }
    }

    private fun borrow(pool: ArrayList<EntityState>, index: Int): EntityState {
        while (pool.size <= index) pool.add(EntityState())
        return pool[index]
    }
}
