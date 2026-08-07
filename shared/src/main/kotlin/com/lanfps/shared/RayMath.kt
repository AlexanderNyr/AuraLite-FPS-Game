package com.lanfps.shared

import kotlin.math.abs

/**
 * Ray / AABB intersection used for hitscan weapons, bot line-of-sight and
 * client-side tracer effects.
 *
 * Lives in `shared` so the client can draw a tracer that ends exactly where the
 * server decided the bullet stopped.
 */
object RayMath {

    /** Returned by [rayAabb] when the ray misses. */
    const val NO_HIT: Float = -1f

    /**
     * Slab-method ray/box intersection.
     *
     * @return distance along the ray to the entry point (0 if the origin is
     *         already inside the box), or [NO_HIT] when there is no intersection
     *         within [maxDist]. Direction must be normalised.
     */
    fun rayAabb(
        ox: Float, oy: Float, oz: Float,
        dx: Float, dy: Float, dz: Float,
        box: Aabb,
        maxDist: Float,
    ): Float {
        var tMin = 0f
        var tMax = maxDist

        // X slab
        if (abs(dx) < 1e-7f) {
            if (ox < box.minX || ox > box.maxX) return NO_HIT
        } else {
            val inv = 1f / dx
            var t1 = (box.minX - ox) * inv
            var t2 = (box.maxX - ox) * inv
            if (t1 > t2) { val t = t1; t1 = t2; t2 = t }
            if (t1 > tMin) tMin = t1
            if (t2 < tMax) tMax = t2
            if (tMin > tMax) return NO_HIT
        }

        // Y slab
        if (abs(dy) < 1e-7f) {
            if (oy < box.minY || oy > box.maxY) return NO_HIT
        } else {
            val inv = 1f / dy
            var t1 = (box.minY - oy) * inv
            var t2 = (box.maxY - oy) * inv
            if (t1 > t2) { val t = t1; t1 = t2; t2 = t }
            if (t1 > tMin) tMin = t1
            if (t2 < tMax) tMax = t2
            if (tMin > tMax) return NO_HIT
        }

        // Z slab
        if (abs(dz) < 1e-7f) {
            if (oz < box.minZ || oz > box.maxZ) return NO_HIT
        } else {
            val inv = 1f / dz
            var t1 = (box.minZ - oz) * inv
            var t2 = (box.maxZ - oz) * inv
            if (t1 > t2) { val t = t1; t1 = t2; t2 = t }
            if (t1 > tMin) tMin = t1
            if (t2 < tMax) tMax = t2
            if (tMin > tMax) return NO_HIT
        }

        return tMin
    }

    fun rayAabb(origin: Vec3, dir: Vec3, box: Aabb, maxDist: Float): Float =
        rayAabb(origin.x, origin.y, origin.z, dir.x, dir.y, dir.z, box, maxDist)

    /**
     * Nearest distance at which the ray hits solid level geometry.
     * @return the hit distance, or [maxDist] when the ray reaches that far freely.
     */
    fun raycastArena(origin: Vec3, dir: Vec3, maxDist: Float, arena: ArenaDef): Float {
        var nearest = maxDist
        val boxes = arena.collision
        for (i in boxes.indices) {
            val t = rayAabb(origin, dir, boxes[i], nearest)
            if (t != NO_HIT && t < nearest) nearest = t
        }
        return nearest
    }

    /**
     * True when nothing solid blocks the straight line between two world points.
     * This is the single line-of-sight test used by both shooting and bot vision,
     * which is why bots can never "see" or shoot through a wall.
     */
    fun hasLineOfSight(from: Vec3, to: Vec3, arena: ArenaDef, scratchDir: Vec3 = Vec3()): Boolean {
        scratchDir.set(to.x - from.x, to.y - from.y, to.z - from.z)
        val dist = scratchDir.length()
        if (dist < 1e-4f) return true
        scratchDir.scale(1f / dist)
        // Stop a hair short so a target standing flush against a wall is still visible.
        val blocked = raycastArena(from, scratchDir, dist - 0.02f, arena)
        return blocked >= dist - 0.03f
    }
}
