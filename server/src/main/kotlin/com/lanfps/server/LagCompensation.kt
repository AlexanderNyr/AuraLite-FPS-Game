package com.lanfps.server

import com.lanfps.shared.Vec3
import java.util.ArrayDeque

/**
 * P1-1: ring buffer of past entity positions, used for lag compensation.
 *
 * Players see opponents ~90 ms in the past (client interpolation buffer) plus
 * half their RTT, so firing at "present" positions makes a strafing target a
 * visible miss. On a shot the server rewinds each target to where the shooter
 * actually saw it, casts the ray against those positions, then moves on.
 *
 * One frame is recorded every simulation tick (60 Hz); [maxFrames] covers
 * [GameConstants.MAX_LAG_COMP_MS] plus margin. Only alive entities are kept.
 */
class PositionHistory(private val maxFrames: Int = 24) {

    private val frames = ArrayDeque<Frame>()

    /** One tick's worth of entity id -> feet position. */
    class Frame(val positions: HashMap<Int, Vec3>)

    /** Records the current positions of every live entity. Call once per tick. */
    fun record(entities: Collection<GameEntity>) {
        val positions = HashMap<Int, Vec3>(entities.size + 4)
        for (e in entities) {
            if (!e.alive) continue
            positions[e.id] = Vec3(e.body.position.x, e.body.position.y, e.body.position.z)
        }
        frames.addLast(Frame(positions))
        while (frames.size > maxFrames) frames.removeFirst()
    }

    /**
     * The positions as they were [ticksAgo] simulation ticks in the past, or
     * null when the buffer has no frames yet. Clamps to the oldest frame.
     */
    fun positionsAtTicksAgo(ticksAgo: Int): HashMap<Int, Vec3>? {
        if (frames.isEmpty()) return null
        var idx = frames.size - 1 - ticksAgo
        if (idx < 0) idx = 0
        return frames.elementAt(idx).positions
    }

    fun clear() = frames.clear()

    val frameCount: Int get() = frames.size
}
