package com.lanfps.client

/**
 * P10-7: volumetric-feeling smoke — big soft billboard puffs with proper
 * alpha blending (unlike the additive spark pool) and per-pixel depth fading
 * against the scene (the soft-particle depth compare happens in the shader;
 * this class only simulates and builds the geometry).
 *
 * Sources: grenade/explosion clouds, heavy landing dust. Puffs grow over
 * their lifetime, drift upward along a slight buoy, lighten from dark blast
 * core to neutral dust grey, spin slowly around the view axis and fade both
 * in and out so nothing pops.
 *
 * CPU pool, zero per-frame allocation: parallel FloatArrays + one vertex
 * scratch reused every build.
 */
class SmokeSystem {

    companion object {
        /** Max live puffs; fresh bursts steal the oldest (shortest-lived). */
        const val MAX = 64

        /** Interleaved vertex: pos(3) color(3) alpha(1) uv(2) = 9 floats. */
        const val FLOATS_PER_VERTEX = 9
        const val VERTS_PER_SPRITE = 6
        const val FLOATS_PER_SPRITE = FLOATS_PER_VERTEX * VERTS_PER_SPRITE
    }

    private val rng = java.util.Random(7)

    private val px = FloatArray(MAX)
    private val py = FloatArray(MAX)
    private val pz = FloatArray(MAX)
    private val vx = FloatArray(MAX)
    private val vy = FloatArray(MAX)
    private val vz = FloatArray(MAX)
    private val life0 = FloatArray(MAX)  // ttl at spawn
    private val life = FloatArray(MAX)   // remaining
    private val size0 = FloatArray(MAX)
    private val grow = FloatArray(MAX)   // radius growth per second
    private val spin = FloatArray(MAX)   // rad/s around the view axis
    private val phase = FloatArray(MAX)  // accumulated spin angle
    private val cr = FloatArray(MAX)
    private val cg = FloatArray(MAX)
    private val cb = FloatArray(MAX)
    private val r1 = FloatArray(MAX)
    private val g1 = FloatArray(MAX)
    private val b1 = FloatArray(MAX)
    private val alpha0 = FloatArray(MAX)

    private var aliveCount = 0

    private fun findSlot(): Int {
        var best = -1
        var worst = Float.MAX_VALUE
        for (i in 0 until MAX) {
            if (life[i] <= 0f) return i
            if (life[i] < worst) {
                worst = life[i]
                best = i
            }
        }
        return best
    }

    fun spawn(
        x: Float, y: Float, z: Float,
        dx: Float, dy: Float, dz: Float,
        ttl: Float, startSize: Float, growPerSec: Float,
        rFrom: Float, gFrom: Float, bFrom: Float,
        rTo: Float, gTo: Float, bTo: Float,
        alpha: Float,
    ) {
        val i = findSlot()
        if (i < 0) return
        px[i] = x; py[i] = y; pz[i] = z
        vx[i] = dx; vy[i] = dy; vz[i] = dz
        life0[i] = ttl; life[i] = ttl
        size0[i] = startSize; grow[i] = growPerSec
        cr[i] = rFrom; cg[i] = gFrom; cb[i] = bFrom
        r1[i] = rTo; g1[i] = gTo; b1[i] = bTo
        alpha0[i] = alpha
        spin[i] = (rng.nextFloat() - 0.5f) * 2.4f
        phase[i] = rng.nextFloat() * 6.283f
        aliveCount++
    }

    /** Grenade blast: dark core puffs lightening to dust grey. */
    fun explosion(x: Float, y: Float, z: Float) {
        val n = 11
        for (k in 0 until n) {
            val a = (k.toFloat() / n) * Math.PI.toFloat() * 2f
            val up = rng.nextFloat() * 1.6f
            spawn(
                x + Math.cos(a.toDouble()).toFloat() * 0.15f,
                y + 0.12f + up * 0.05f,
                z + Math.sin(a.toDouble()).toFloat() * 0.15f,
                Math.cos(a.toDouble()).toFloat() * (0.55f + rng.nextFloat() * 0.7f),
                0.55f + up,
                Math.sin(a.toDouble()).toFloat() * (0.55f + rng.nextFloat() * 0.7f),
                ttl = 1.15f + rng.nextFloat() * 0.7f,
                startSize = 0.34f + rng.nextFloat() * 0.18f,
                growPerSec = 1.5f,
                rFrom = 0.16f, gFrom = 0.13f, bFrom = 0.10f,
                rTo = 0.42f, gTo = 0.40f, bTo = 0.36f,
                alpha = 0.62f,
            )
        }
    }

    /** Heavy landing: a short pale ring of ground dust. */
    fun landingDust(x: Float, y: Float, z: Float, intensity: Float) {
        val n = 5
        for (k in 0 until n) {
            val a = (k.toFloat() / n) * Math.PI.toFloat() * 2f + rng.nextFloat() * 0.6f
            spawn(
                x, y + 0.05f, z,
                Math.cos(a.toDouble()).toFloat() * 1.3f * intensity,
                0.25f, Math.sin(a.toDouble()).toFloat() * 1.3f * intensity,
                ttl = 0.55f + rng.nextFloat() * 0.3f,
                startSize = 0.16f + rng.nextFloat() * 0.08f,
                growPerSec = 2.2f,
                rFrom = 0.50f, gFrom = 0.48f, bFrom = 0.44f,
                rTo = 0.55f, gTo = 0.54f, bTo = 0.50f,
                alpha = 0.45f * intensity.coerceIn(0.4f, 1.4f),
            )
        }
    }

    fun update(dt: Float) {
        if (aliveCount <= 0) return
        var alive = 0
        for (i in 0 until MAX) {
            if (life[i] <= 0f) continue
            life[i] -= dt
            if (life[i] <= 0f) continue
            alive++
            px[i] += vx[i] * dt
            py[i] += vy[i] * dt
            pz[i] += vz[i] * dt
            // Outward drift dampens; the buoyant rise keeps a floor velocity.
            val damp = 1f - (1.4f * dt).coerceAtMost(0.9f)
            vx[i] *= damp
            vz[i] *= damp
            vy[i] = (vy[i] - 0.12f * dt).coerceAtLeast(0.15f)
            phase[i] += spin[i] * dt
        }
        aliveCount = alive
    }

    /**
     * Builds camera-facing quads into [out] (pos/color/alpha/uv per vertex)
     * and returns the float count actually written. The quad spins around the
     * view axis — cheap motion that reads as volume churn.
     */
    fun build(
        out: FloatArray,
        rightX: Float, rightY: Float, rightZ: Float,
        upX: Float, upY: Float, upZ: Float,
    ): Int {
        var head = 0
        if (aliveCount <= 0) return 0
        for (i in 0 until MAX) {
            if (life[i] <= 0f) continue
            val t = 1f - life[i] / life0[i]                 // 0 fresh -> 1 dead
            val cin = (t * 3f).coerceAtMost(1f)              // fade in fast
            val cout = ((1f - t) * 2.2f).coerceAtMost(1f)    // fade out tail
            val age = life0[i] - life[i]
            val s = size0[i] * (1f + grow[i] * age) * 0.5f

            val ca = kotlin.math.cos(phase[i])
            val sa = kotlin.math.sin(phase[i])
            val rrx = rightX * ca + upX * sa
            val rry = rightY * ca + upY * sa
            val rrz = rightZ * ca + upZ * sa
            val uux = upX * ca - rightX * sa
            val uuy = upY * ca - rightY * sa
            val uuz = upZ * ca - rightZ * sa

            val a = alpha0[i] * cin * cout
            val rr = cr[i] + (r1[i] - cr[i]) * t
            val gg = cg[i] + (g1[i] - cg[i]) * t
            val bb = cb[i] + (b1[i] - cb[i]) * t

            fun vert(sgnR: Float, sgnU: Float) {
                out[head++] = px[i] + (rrx * sgnR + uux * sgnU) * s
                out[head++] = py[i] + (rry * sgnR + uuy * sgnU) * s
                out[head++] = pz[i] + (rrz * sgnR + uuz * sgnU) * s
                out[head++] = rr
                out[head++] = gg
                out[head++] = bb
                out[head++] = a
                out[head++] = sgnR
                out[head++] = sgnU
            }

            vert(-1f, 1f); vert(1f, -1f); vert(1f, 1f)
            vert(-1f, 1f); vert(-1f, -1f); vert(1f, -1f)
        }
        return head
    }

    /** Current live-sprite budget check for log/preview purposes. */
    fun alive(): Int = aliveCount
}
