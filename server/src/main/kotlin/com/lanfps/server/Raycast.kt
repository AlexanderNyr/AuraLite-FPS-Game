package com.lanfps.server

import com.lanfps.shared.Aabb
import com.lanfps.shared.ArenaDef
import com.lanfps.shared.GameConstants
import com.lanfps.shared.MathUtil
import com.lanfps.shared.RayMath
import com.lanfps.shared.Vec3

/**
 * Authoritative hitscan resolution.
 *
 * The client never says "I hit someone". It sends only a FIRE button; the server
 * casts the ray from the shooter's own authoritative eye position and view
 * angles, and decides. Level geometry is tested first, so a shot can never pass
 * through a wall to reach a target standing behind it.
 */
class ServerRaycast(private val arena: ArenaDef) {

    /** Reusable result — the tick loop is single-threaded. */
    class Hit {
        @JvmField var entity: GameEntity? = null
        @JvmField var distance: Float = 0f
        @JvmField val point: Vec3 = Vec3()
        /** True when the ray stopped on level geometry rather than an entity. */
        @JvmField var hitWorld: Boolean = false

        fun reset(): Hit {
            entity = null
            distance = 0f
            point.zero()
            hitWorld = false
            return this
        }
    }

    private val result = Hit()
    private val origin = Vec3()
    private val dir = Vec3()
    private val box = Aabb()

    /**
     * Casts the shooter's weapon ray.
     *
     * @param candidates every entity that could be hit (the caller filters out
     *        the shooter itself and, in TDM, teammates when friendly fire is off).
     */
    fun fire(
        shooter: GameEntity,
        candidates: Collection<GameEntity>,
        range: Float = GameConstants.WEAPON_RANGE,
    ): Hit {
        result.reset()

        shooter.eyePosition(origin)
        MathUtil.forwardFromAngles(shooter.body.yaw, shooter.body.pitch, dir)

        // 1) How far can the bullet travel before it hits the level?
        val wallDistance = RayMath.raycastArena(origin, dir, range, arena)

        // 2) Nearest entity in front of that wall.
        var nearest = wallDistance
        var hitEntity: GameEntity? = null
        for (e in candidates) {
            if (e === shooter || !e.alive) continue
            e.hitbox(box)
            val t = RayMath.rayAabb(origin, dir, box, nearest)
            if (t != RayMath.NO_HIT && t < nearest) {
                nearest = t
                hitEntity = e
            }
        }

        result.entity = hitEntity
        result.distance = nearest
        result.hitWorld = hitEntity == null
        result.point.set(
            origin.x + dir.x * nearest,
            origin.y + dir.y * nearest,
            origin.z + dir.z * nearest,
        )
        return result
    }

    /** Line-of-sight between two eye positions; used by bot vision. */
    fun hasLineOfSight(from: Vec3, to: Vec3): Boolean =
        RayMath.hasLineOfSight(from, to, arena, dir)
}
