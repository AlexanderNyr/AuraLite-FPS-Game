package com.lanfps.client

import kotlin.math.cos
import kotlin.math.sin

/**
 * Zero-allocation CPU particle pool rendered as additive, camera-facing
 * quads through a [DynamicMesh]. Additive blending is deliberate: fading a
 * particle is just scaling its colour down, so the 6-float unlit layout
 * (no alpha channel) needs no changes at all.
 *
 * Pool policy: fixed capacity, circular overwrite — spawning more than the
 * budget just retires the oldest particles, so a chaotic firefight degrades
 * into "fewer sparks" instead of frame drops. All work happens on the GL
 * thread; [update] advances physics, [build] rewrites the mesh.
 *
 * The system also owns the ambient dust: [DUST_COUNT] slow motes that drift
 * in a small box glued to the camera and wrap around when they leave it.
 * They nearly double the sense of air volume in an otherwise static arena
 * for the price of one draw call shared with everything else.
 */
class ParticleSystem(
    private val mesh: DynamicMesh,
) {

    // Parallel-array storage beats an object per particle: no allocations,
    // tight cache behaviour on the per-frame update sweep.
    private val px = FloatArray(MAX)
    private val py = FloatArray(MAX)
    private val pz = FloatArray(MAX)
    private val vx = FloatArray(MAX)
    private val vy = FloatArray(MAX)
    private val vz = FloatArray(MAX)
    private val life = FloatArray(MAX)   // remaining seconds; <= 0 = dead slot
    private val ttl = FloatArray(MAX)
    private val size = FloatArray(MAX)
    private val pr = FloatArray(MAX)
    private val pg = FloatArray(MAX)
    private val pb = FloatArray(MAX)
    private val grav = FloatArray(MAX)

    /** Next slot to overwrite; wraps around, oldest-first. */
    private var head = 0

    /** Live-particle upper index for compact iteration. */
    private var used = 0

    private val rng = java.util.Random()

    // ---- ambient dust --------------------------------------------------------
    private var dustInitialised = false

    /** Faint cool backlight so the floating dust never reads as snow. */
    private val dustColor = floatArrayOf(0.030f, 0.052f, 0.085f)

    /**
     * Spawn a single particle. Positive [gravity] pulls it down (world is
     * Y-up); zero gravity with drag makes glittery floating sparks.
     */
    fun spawn(
        x: Float, y: Float, z: Float,
        vx: Float, vy: Float, vz: Float,
        ttl: Float, size: Float,
        r: Float, g: Float, b: Float,
        gravity: Float = 9.8f,
    ) {
        val i = head
        // Dynamic spawns cycle through [0, MAX-DUST_COUNT); the dusty tail
        // [MAX-DUST_COUNT, MAX) belongs to updateDust and is never touched.
        head = (head + 1) % (MAX - DUST_COUNT)
        used = maxOf(used, head)
        px[i] = x; py[i] = y; pz[i] = z
        this.vx[i] = vx; this.vy[i] = vy; this.vz[i] = vz
        this.ttl[i] = ttl; life[i] = ttl
        this.size[i] = size
        pr[i] = r; pg[i] = g; pb[i] = b
        grav[i] = gravity
    }

    /** A cone-shaped burst: sparks fly along (dx,dy,dz) ± [spread] radians. */
    fun burst(
        x: Float, y: Float, z: Float,
        dx: Float, dy: Float, dz: Float,
        count: Int, speed: Float, spread: Float,
        ttl: Float, size: Float,
        r: Float, g: Float, b: Float,
        gravity: Float = 9.8f,
    ) {
        // Orthonormal basis around the burst direction: tangent + bitangent.
        val il = 1f / kotlin.math.sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(1e-6f)
        val nx = dx * il; val ny = dy * il; val nz = dz * il
        val (hx, hy, hz) = if (kotlin.math.abs(ny) < 0.9f) Trip(0f, 1f, 0f) else Trip(1f, 0f, 0f)
        // t1 = dir x helper, t2 = dir x t1
        val t1x = ny * hz - nz * hy; val t1y = nz * hx - nx * hz; val t1z = nx * hy - ny * hx
        val t2x = ny * t1z - nz * t1y; val t2y = nz * t1x - nx * t1z; val t2z = nx * t1y - ny * t1x

        for (k in 0 until count) {
            val a = rng.nextFloat() * (2f * Math.PI.toFloat())
            val tanA = kotlin.math.tan(rng.nextFloat() * spread)
            val ca = cos(a) * tanA
            val sa = sin(a) * tanA
            // velocity dir = normalize(dir + t1*ca + t2*sa)
            var ex = nx + t1x * ca + t2x * sa
            var ey = ny + t1y * ca + t2y * sa
            var ez = nz + t1z * ca + t2z * sa
            val vl = 1f / kotlin.math.sqrt(ex * ex + ey * ey + ez * ez).coerceAtLeast(1e-6f)
            ex *= vl; ey *= vl; ez *= vl
            val s = speed * (0.55f + 0.45f * rng.nextFloat())
            spawn(
                x, y, z, ex * s, ey * s, ez * s,
                ttl * (0.6f + 0.7f * rng.nextFloat()), size * (0.75f + 0.5f * rng.nextFloat()),
                r, g, b, gravity,
            )
        }
    }

    /** Micro-tuple so the helper choice reads nicely without array allocs. */
    private data class Trip(val x: Float, val y: Float, val z: Float)

    /** A dying player pops into a ring of team-coloured shards. */
    fun deathBurst(x: Float, y: Float, z: Float, r: Float, g: Float, b: Float) {
        val n = 22
        for (k in 0 until n) {
            val a = k.toFloat() / n * (2f * Math.PI.toFloat()) + rng.nextFloat() * 0.4f
            val s = 3.2f * (0.6f + 0.4f * rng.nextFloat())
            spawn(
                x, y + 0.9f, z,
                cos(a) * s, 1.6f + 2.2f * rng.nextFloat(), sin(a) * s,
                0.45f + 0.35f * rng.nextFloat(), 0.045f + 0.03f * rng.nextFloat(),
                r * 1.35f, g * 1.35f, b * 1.35f,
                gravity = 7.5f,
            )
        }
    }

    /** Single quiet mote, e.g. under a sprinting player's feet. */
    fun footDust(x: Float, y: Float, z: Float) {
        val a = rng.nextFloat() * (2f * Math.PI.toFloat())
        spawn(
            x + cos(a) * 0.18f, y + 0.03f, z + sin(a) * 0.18f,
            cos(a) * 0.35f, 0.15f, sin(a) * 0.35f,
            0.38f, 0.05f,
            0.055f, 0.075f, 0.115f,
            gravity = -0.25f, // slight buoyancy: dust rises in a sci-fi volume
        )
    }

    /** Spark at a tracer's tip; called once per tracer per frame. */
    fun tracerSpark(x: Float, y: Float, z: Float, local: Boolean) {
        if (rng.nextInt(2) != 0) return // 50% duty cycle keeps the pool roomy
        val jx = rng.nextFloat() - 0.5f
        val jy = rng.nextFloat() - 0.5f
        val jz = rng.nextFloat() - 0.5f
        spawn(
            x, y, z,
            jx * 0.8f, jy * 0.8f, jz * 0.8f,
            0.22f, 0.035f,
            if (local) 0.95f else 1.0f, if (local) 0.85f else 0.55f, 0.30f,
            gravity = 2.5f,
        )
    }

    /**
     * P4-6: a grenade blast. A dense core of fast hot sparks, a handful of
     * heavy embers arcing a bit longer and a few slow dark soot puffs rising —
     * three populations read as "explosion" far better than one lump.
     */
    fun explosion(x: Float, y: Float, z: Float) {
        burst(
            x, y, z, 0f, 1f, 0f,
            count = 26, speed = 7.5f, spread = 1.1f,
            ttl = 0.55f, size = 0.055f,
            r = 1.0f, g = 0.78f, b = 0.42f,
            gravity = 8.5f,
        )
        burst(
            x, y, z, 0f, 1f, 0f,
            count = 12, speed = 3.4f, spread = 1.3f,
            ttl = 0.85f, size = 0.075f,
            r = 0.95f, g = 0.38f, b = 0.16f,
            gravity = 5.5f,
        )
        burst(
            x, y + 0.1f, z, 0f, 1f, 0f,
            count = 6, speed = 1.6f, spread = 0.9f,
            ttl = 1.1f, size = 0.14f,
            r = 0.16f, g = 0.15f, b = 0.15f,
            gravity = -1.8f, // smoke rises
        )
    }

    /** Sparks jetting forward from the local weapon's muzzle, per shot. */
    fun muzzleSparks(
        x: Float, y: Float, z: Float,
        dx: Float, dy: Float, dz: Float,
    ) = burst(
        x, y, z, dx, dy, dz,
        count = 7, speed = 5.5f, spread = 0.6f,
        ttl = 0.16f, size = 0.035f,
        r = 1.0f, g = 0.80f, b = 0.35f,
        gravity = 4.5f,
    )

    /**
     * Advances every live particle and (lazily) the ambient dust box that
     * follows the camera. camX..camZ seed/reseed the dust volume.
     */
    fun update(dt: Float, camX: Float, camY: Float, camZ: Float) {
        for (i in 0 until used) {
            val l = life[i]
            if (l <= 0f) continue
            val nl = l - dt
            life[i] = nl
            if (nl <= 0f) continue
            vy[i] -= grav[i] * dt
            px[i] += vx[i] * dt
            py[i] += vy[i] * dt
            pz[i] += vz[i] * dt
        }
        updateDust(dt, camX, camY, camZ)
    }

    // Dust slots live at the TAIL of the arrays ([MAX-DUST_COUNT, MAX)) and are
    // managed separately: they never die, they just wrap around the camera.
    private fun updateDust(dt: Float, camX: Float, camY: Float, camZ: Float) {
        val base = MAX - DUST_COUNT
        if (!dustInitialised) {
            dustInitialised = true
            for (i in base until MAX) {
                life[i] = 1f; ttl[i] = 1f
                respawnDust(i, camX, camY, camZ)
            }
            // Dust never dies, so the render/update sweeps always cover the tail.
            used = MAX
        }
        for (i in base until MAX) {
            // Slow fall + wobble; the volume is roughly 18m x 6m around the eye.
            px[i] += (vx[i] + sin(time * 0.7f + i.toFloat()) * 0.05f) * dt
            py[i] += vy[i] * dt
            pz[i] += (vz[i] + cos(time * 0.6f + i.toFloat() * 1.7f) * 0.05f) * dt
            if (
                px[i] < camX - DUST_RX || px[i] > camX + DUST_RX ||
                py[i] < camY - DUST_RY || py[i] > camY + DUST_RY ||
                pz[i] < camZ - DUST_RX || pz[i] > camZ + DUST_RX
            ) {
                respawnDust(i, camX, camY, camZ)
            }
        }
        time += dt
    }

    private var time = 0f

    private fun respawnDust(i: Int, camX: Float, camY: Float, camZ: Float) {
        px[i] = camX + (rng.nextFloat() * 2f - 1f) * DUST_RX
        py[i] = camY + (rng.nextFloat() * 2f - 1f) * DUST_RY
        pz[i] = camZ + (rng.nextFloat() * 2f - 1f) * DUST_RX
        vx[i] = (rng.nextFloat() - 0.5f) * 0.10f
        vy[i] = -0.06f - rng.nextFloat() * 0.05f
        vz[i] = (rng.nextFloat() - 0.5f) * 0.10f
        size[i] = 0.010f + rng.nextFloat() * 0.020f
        pr[i] = dustColor[0]; pg[i] = dustColor[1]; pb[i] = dustColor[2]
    }

    /**
     * Rebuilds the render mesh for this frame. rx..uz are the camera's
     * right/up basis vectors; quads are billboarded around the particle
     * centre like the muzzle flash is.
     */
    fun build(
        rx: Float, ry: Float, rz: Float,
        ux: Float, uy: Float, uz: Float,
        camX: Float, camY: Float, camZ: Float,
    ) {
        mesh.begin()
        for (i in 0 until used) {
            val l = life[i]
            if (l <= 0f) continue

            val dustSlot = i >= MAX - DUST_COUNT
            val fade: Float
            val s: Float
            if (dustSlot) {
                // Dust never fades in/out per life; it only dims with distance
                // from the eye so a mote never pops right into your face.
                val ddx = px[i] - camX
                val ddy = py[i] - camY
                val ddz = pz[i] - camZ
                val d = kotlin.math.sqrt(ddx * ddx + ddy * ddy + ddz * ddz)
                fade = ((d - 0.35f) / 0.7f).coerceIn(0f, 1f)
                s = size[i]
            } else {
                val t = l / ttl[i]            // 1 → 0 over the particle's life
                fade = t * t                  // ease-out brightness
                val cin = (1f - t).coerceIn(0f, 1f)
                s = size[i] * (0.7f + 0.5f * cin) // slight growth while dying
            }
            val r = pr[i] * fade
            val g = pg[i] * fade
            val b = pb[i] * fade
            val x = px[i]; val y = py[i]; val z = pz[i]
            val hx = rx * s; val hy = ry * s; val hz = rz * s
            val wx = ux * s; val wy = uy * s; val wz = uz * s

            mesh.vertex(x - hx - wx, y - hy - wy, z - hz - wz, r, g, b)
            mesh.vertex(x + hx - wx, y + hy - wy, z + hz - wz, r, g, b)
            mesh.vertex(x + hx + wx, y + hy + wy, z + hz + wz, r, g, b)

            mesh.vertex(x - hx - wx, y - hy - wy, z - hz - wz, r, g, b)
            mesh.vertex(x + hx + wx, y + hy + wy, z + hz + wz, r, g, b)
            mesh.vertex(x - hx + wx, y - hy + wy, z - hz + wz, r, g, b)
        }
        mesh.end()
    }

    companion object {
        /** Total particle slots; dynamic effects claim head slots, dust owns
         *  the tail. 320 quads ≈ 3.8k tris — invisible to any GLES3 GPU. */
        const val MAX = 320

        /** Ambient motes drifting around the eye, re-wrapped when out of box. */
        const val DUST_COUNT = 48

        /** Half-extent of the dust volume around the camera, metres. */
        const val DUST_RX = 9f
        const val DUST_RY = 3f
    }
}
