package com.lanfps.server

import com.lanfps.shared.GameConstants
import com.lanfps.shared.GrenadeState
import com.lanfps.shared.MathUtil
import com.lanfps.shared.Vec3
import kotlin.math.max

/**
 * P4-6: server-side hand grenades.
 *
 * A thrown grenade is a tiny ballistic body: it bounces off level geometry
 * with [GameConstants.GRENADE_RESTITUTION], ignores the thrower for a split
 * second (grace — otherwise you blow yourself up mid-throw), detonates early
 * on a direct hit against an enemy and *always* on fuse timeout.
 *
 * Explosions reuse [World.applyDamage], so armor absorption, friendly-fire
 * rules, scoring and the kill feed stay consistent with bullets: no special
 * paths. Splash requires line of sight from the blast point — a crate is
 * still a perfectly good shelter, which is both fair and readable.
 *
 * Prediction-exempt by design: like every other world object, only the
 * server's version matters; clients render replicates.
 */
class GrenadeTracker(private val world: World) {

    /** One live grenade. Reused instances alive only inside this class. */
    class Grenade {
        @JvmField var id: Int = 0
        @JvmField var thrower: GameEntity? = null
        @JvmField var x: Float = 0f
        @JvmField var y: Float = 0f
        @JvmField var z: Float = 0f
        @JvmField var vx: Float = 0f
        @JvmField var vy: Float = 0f
        @JvmField var vz: Float = 0f
        @JvmField var fuse: Float = 0f
        @JvmField var age: Float = 0f
    }

    @JvmField val grenades: ArrayList<Grenade> = ArrayList()

    private var nextId = 1

    private val dir = Vec3()
    private val eye = Vec3()

    /** Spawns a grenade from [thrower]'s view direction. Caller decremented
     *  the pouch already. */
    fun throwFrom(thrower: GameEntity) {
        val g = Grenade()
        g.id = nextId and 0xFF
        nextId++
        if (nextId > 255) nextId = 1
        g.thrower = thrower
        thrower.eyePosition(eye)
        MathUtil.forwardFromAngles(thrower.body.yaw, thrower.body.pitch, dir)
        // Spawn one step in front of the eye so the ball never instantly
        // bounces off the thrower's own hitbox or a wall they're hugging.
        g.x = eye.x + dir.x * 0.6f
        g.y = eye.y + dir.y * 0.6f - 0.1f
        g.z = eye.z + dir.z * 0.6f
        g.vx = dir.x * GameConstants.GRENADE_THROW_SPEED
        g.vy = dir.y * GameConstants.GRENADE_THROW_SPEED + 1.2f // slight lob
        g.vz = dir.z * GameConstants.GRENADE_THROW_SPEED
        g.fuse = GameConstants.GRENADE_FUSE_SEC
        g.age = 0f
        grenades.add(g)
    }

    /** Advances every live grenade by one fixed step, detonating as needed. */
    fun tick(dt: Float) {
        var i = 0
        while (i < grenades.size) {
            val g = grenades[i]
            g.fuse -= dt
            g.age += dt
            integrate(g, dt)

            val impactVictim = findImpactVictim(g)
            if (impactVictim != null || g.fuse <= 0f) {
                grenades.removeAt(i)
                explode(g, impactVictim)
            } else {
                i++
            }
        }
    }

    /** Euler integration + per-axis AABB bounce (ball radius [BALL_RADIUS]). */
    private fun integrate(g: Grenade, dt: Float) {
        g.vy += GameConstants.GRAVITY * dt
        if (g.vy < -60f) g.vy = -60f

        // X axis
        g.x += g.vx * dt
        if (hitsWorld(g)) { g.x -= g.vx * dt; g.vx = -g.vx * GameConstants.GRENADE_RESTITUTION }
        // Z axis
        g.z += g.vz * dt
        if (hitsWorld(g)) { g.z -= g.vz * dt; g.vz = -g.vz * GameConstants.GRENADE_RESTITUTION }
        // Y axis
        g.y += g.vy * dt
        if (hitsWorld(g)) {
            g.y -= g.vy * dt
            g.vy = -g.vy * GameConstants.GRENADE_RESTITUTION
            if (g.vy < 0.8f && g.vy > -0.8f) g.vy = 0f // rest on top
        }
        // Analytic floor.
        if (g.y < BALL_RADIUS) {
            g.y = BALL_RADIUS
            if (g.vy < 0f) g.vy = -g.vy * GameConstants.GRENADE_RESTITUTION
            if (g.vy > -0.8f && g.vy < 0.8f) g.vy = 0f
        }

        // Arena bounds.
        val def = world.serverArena.def
        g.x = MathUtil.clamp(g.x, def.minX + BALL_RADIUS, def.maxX - BALL_RADIUS)
        g.z = MathUtil.clamp(g.z, def.minZ + BALL_RADIUS, def.maxZ - BALL_RADIUS)

        // Rolling on the ground bleeds speed fast; the nade lands to rest.
        if (g.vy == 0f && g.y <= BALL_RADIUS + 0.01f) {
            g.vx *= 1f - 4f * dt
            g.vz *= 1f - 4f * dt
            if (g.vx * g.vx + g.vz * g.vz < 0.01f) { g.vx = 0f; g.vz = 0f }
        }
    }

    /** True when the ball (small AABB) overlaps any solid brush. */
    private fun hitsWorld(g: Grenade): Boolean {
        val def = world.serverArena.def
        val boxes = def.collision
        val minX = g.x - BALL_RADIUS
        val maxX = g.x + BALL_RADIUS
        val minY = g.y - BALL_RADIUS
        val maxY = g.y + BALL_RADIUS
        val minZ = g.z - BALL_RADIUS
        val maxZ = g.z + BALL_RADIUS
        for (idx in boxes.indices) {
            val b = boxes[idx]
            if (maxX <= b.minX || b.maxX <= minX) continue
            if (maxY <= b.minY || b.maxY <= minY) continue
            if (maxZ <= b.minZ || b.maxZ <= minZ) continue
            return true
        }
        return false
    }

    /** An enemy close enough to the ball that a direct-hit boom is fairer
     *  than letting the fuse run. The thrower is exempt during the grace
     *  window so a point-blank pan-pan throw doesn't gib its owner. */
    private fun findImpactVictim(g: Grenade): GameEntity? {
        for (e in world.entities.values) {
            if (!e.alive) continue
            if (e === g.thrower && g.age < GameConstants.GRENADE_SELF_GRACE_SEC) continue
            if (g.thrower != null && world.areAllies(g.thrower!!, e)) continue
            val dx = e.body.position.x - g.x
            val dz = e.body.position.z - g.z
            val bodyMid = e.body.position.y + 0.9f - g.y
            val rr = GameConstants.GRENADE_IMPACT_RADIUS
            if (dx * dx + dz * dz + bodyMid * bodyMid < rr * rr) return e
        }
        return null
    }

    /** Blasts [g], applying radial falloff damage with a line-of-sight check. */
    private fun explode(g: Grenade, directHit: GameEntity?) {
        val thrower = g.thrower
        val blastX = g.x; val blastY = g.y + 0.15f; val blastZ = g.z
        val radius = GameConstants.GRENADE_RADIUS
        for (e in world.entities.values) {
            if (!e.alive) continue
            // Splash never skips the thrower beyond the grace window — being
            // careless with your own nade is the way of the arena.
            val dx = e.body.position.x - blastX
            val dz = e.body.position.z - blastZ
            val bodyMid = e.body.position.y + 0.9f - blastY
            val d = kotlin.math.sqrt(dx * dx + dz * dz + bodyMid * bodyMid)
            if (d > radius) continue
            // Cover blocks the boom: the raycast IS the locomotive truth, and
            // the attacker list stays empty when a wall shields the body.
            blastEye.setQuery(blastX, blastY, blastZ)
            e.eyePosition(eye)
            if (!world.raycast.hasLineOfSight(blastEye.vec, eye)) continue

            val falloff = 1f - (d / radius)
            var damage = (GameConstants.GRENADE_MAX_DAMAGE * falloff).toInt()
            if (damage < GameConstants.GRENADE_EDGE_DAMAGE) {
                damage = GameConstants.GRENADE_EDGE_DAMAGE
            }
            if (e === directHit) damage = max(damage, GameConstants.GRENADE_MAX_DAMAGE)
            if (thrower != null) {
                world.applyDamage(e, thrower, damage)
            }
        }
    }

    /** Tiny non-allocating Vec wrapper for the LOS query above. */
    private class QueryVec {
        val vec = Vec3()
        fun setQuery(x: Float, y: Float, z: Float) { vec.set(x, y, z) }
    }

    private val blastEye = QueryVec()

    /** Copies the live grenades into wire form. */
    fun snapshotTo(out: ArrayList<GrenadeState>, pool: ArrayList<GrenadeState>) {
        out.clear()
        for (i in grenades.indices) {
            val g = grenades[i]
            while (pool.size <= i) pool.add(GrenadeState())
            val dst = pool[i]
            dst.id = g.id and 0xFF
            dst.fuseTicks = (GameConstants.GRENADE_FUSE_SEC - g.fuse).coerceAtLeast(0f)
                .let { (it * 60f).toInt() and 0xFF }
            dst.x = g.x; dst.y = g.y; dst.z = g.z
            out.add(dst)
        }
    }

    companion object {
        /** Half-extent of the grenade's collision cube, metres. */
        private const val BALL_RADIUS: Float = 0.12f
    }
}
