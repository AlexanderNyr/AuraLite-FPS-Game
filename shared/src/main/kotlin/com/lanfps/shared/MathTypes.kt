package com.lanfps.shared

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Deliberately mutable 3-component vector. The server simulation reuses instances
 * inside its hot loop so it does not allocate per tick.
 */
class Vec3(
    @JvmField var x: Float = 0f,
    @JvmField var y: Float = 0f,
    @JvmField var z: Float = 0f,
) {
    fun set(nx: Float, ny: Float, nz: Float): Vec3 { x = nx; y = ny; z = nz; return this }
    fun set(o: Vec3): Vec3 { x = o.x; y = o.y; z = o.z; return this }
    fun zero(): Vec3 = set(0f, 0f, 0f)

    fun add(o: Vec3): Vec3 { x += o.x; y += o.y; z += o.z; return this }
    fun add(nx: Float, ny: Float, nz: Float): Vec3 { x += nx; y += ny; z += nz; return this }
    fun sub(o: Vec3): Vec3 { x -= o.x; y -= o.y; z -= o.z; return this }
    fun scale(s: Float): Vec3 { x *= s; y *= s; z *= s; return this }
    fun addScaled(o: Vec3, s: Float): Vec3 { x += o.x * s; y += o.y * s; z += o.z * s; return this }

    fun lengthSquared(): Float = x * x + y * y + z * z
    fun length(): Float = sqrt(lengthSquared())

    fun horizontalLength(): Float = sqrt(x * x + z * z)

    fun normalize(): Vec3 {
        val len = length()
        if (len > 1e-6f) scale(1f / len)
        return this
    }

    fun dot(o: Vec3): Float = x * o.x + y * o.y + z * o.z

    fun distanceTo(o: Vec3): Float {
        val dx = x - o.x; val dy = y - o.y; val dz = z - o.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    fun horizontalDistanceTo(o: Vec3): Float {
        val dx = x - o.x; val dz = z - o.z
        return sqrt(dx * dx + dz * dz)
    }

    fun copy(): Vec3 = Vec3(x, y, z)

    override fun toString(): String = "(%.2f, %.2f, %.2f)".format(x, y, z)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Vec3) return false
        return x == other.x && y == other.y && z == other.z
    }

    override fun hashCode(): Int = (31 * (31 * x.toRawBits() + y.toRawBits())) + z.toRawBits()
}

/**
 * Axis-aligned bounding box. Used for both level geometry and entity hitboxes so
 * collision and hit detection share one code path.
 */
class Aabb(
    @JvmField var minX: Float = 0f,
    @JvmField var minY: Float = 0f,
    @JvmField var minZ: Float = 0f,
    @JvmField var maxX: Float = 0f,
    @JvmField var maxY: Float = 0f,
    @JvmField var maxZ: Float = 0f,
) {
    fun set(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float): Aabb {
        minX = min(x0, x1); minY = min(y0, y1); minZ = min(z0, z1)
        maxX = max(x0, x1); maxY = max(y0, y1); maxZ = max(z0, z1)
        return this
    }

    fun set(o: Aabb): Aabb = set(o.minX, o.minY, o.minZ, o.maxX, o.maxY, o.maxZ)

    /** Builds the box occupied by an upright capsule-ish body standing at [pos]. */
    fun setFromBody(pos: Vec3, radius: Float, height: Float): Aabb = set(
        pos.x - radius, pos.y, pos.z - radius,
        pos.x + radius, pos.y + height, pos.z + radius,
    )

    fun intersects(o: Aabb): Boolean =
        minX < o.maxX && maxX > o.minX &&
        minY < o.maxY && maxY > o.minY &&
        minZ < o.maxZ && maxZ > o.minZ

    fun contains(px: Float, py: Float, pz: Float): Boolean =
        px in minX..maxX && py in minY..maxY && pz in minZ..maxZ

    fun expanded(r: Float, out: Aabb = Aabb()): Aabb = out.set(
        minX - r, minY - r, minZ - r,
        maxX + r, maxY + r, maxZ + r,
    )

    val centerX: Float get() = (minX + maxX) * 0.5f
    val centerY: Float get() = (minY + maxY) * 0.5f
    val centerZ: Float get() = (minZ + maxZ) * 0.5f

    val sizeX: Float get() = maxX - minX
    val sizeY: Float get() = maxY - minY
    val sizeZ: Float get() = maxZ - minZ

    fun copy(): Aabb = Aabb(minX, minY, minZ, maxX, maxY, maxZ)

    override fun toString(): String =
        "Aabb[(%.1f,%.1f,%.1f)-(%.1f,%.1f,%.1f)]".format(minX, minY, minZ, maxX, maxY, maxZ)
}

object MathUtil {
    const val DEG_TO_RAD = 0.017453292f
    const val RAD_TO_DEG = 57.295776f

    fun clamp(v: Float, lo: Float, hi: Float): Float = if (v < lo) lo else if (v > hi) hi else v
    fun clamp(v: Int, lo: Int, hi: Int): Int = if (v < lo) lo else if (v > hi) hi else v

    fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    /** Wraps degrees into (-180, 180]. */
    fun wrapDegrees(deg: Float): Float {
        var d = deg % 360f
        if (d > 180f) d -= 360f
        if (d <= -180f) d += 360f
        return d
    }

    /** Shortest-path angular interpolation, in degrees. */
    fun lerpAngleDeg(a: Float, b: Float, t: Float): Float =
        a + wrapDegrees(b - a) * t

    /** Signed shortest delta from [a] to [b], in degrees. */
    fun angleDeltaDeg(a: Float, b: Float): Float = wrapDegrees(b - a)

    /**
     * Converts yaw/pitch (degrees) into a unit forward vector.
     * Convention used everywhere in this project:
     *  - yaw 0 looks down -Z, increasing yaw turns right (toward +X)
     *  - positive pitch looks up
     */
    fun forwardFromAngles(yawDeg: Float, pitchDeg: Float, out: Vec3): Vec3 {
        val y = yawDeg * DEG_TO_RAD
        val p = pitchDeg * DEG_TO_RAD
        val cp = cos(p)
        return out.set(sin(y) * cp, sin(p), -cos(y) * cp)
    }

    /** Horizontal forward (movement) direction for a yaw, ignoring pitch. */
    fun horizontalForward(yawDeg: Float, out: Vec3): Vec3 {
        val y = yawDeg * DEG_TO_RAD
        return out.set(sin(y), 0f, -cos(y))
    }

    /** Horizontal right-hand strafe direction for a yaw. */
    fun horizontalRight(yawDeg: Float, out: Vec3): Vec3 {
        val y = yawDeg * DEG_TO_RAD
        return out.set(cos(y), 0f, sin(y))
    }

    fun approxEquals(a: Float, b: Float, eps: Float = 1e-4f): Boolean = abs(a - b) <= eps
}
