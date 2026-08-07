package com.lanfps.client

import com.lanfps.shared.Aabb

/**
 * Builds interleaved triangle soup on the CPU.
 *
 * Everything in this game is boxes and quads, so there is no model format, no
 * loader and no index buffer: 36 vertices per box is a few hundred kilobytes for
 * the whole arena and uploads in one call.
 *
 * Winding is counter-clockwise as seen from outside the box, which matches
 * `glFrontFace(GL_CCW)` + `glCullFace(GL_BACK)`.
 */
class MeshBuilder(private val withNormals: Boolean = true, initialCapacity: Int = 4096) {

    private var data = FloatArray(initialCapacity)
    private var size = 0

    val floatCount: Int get() = size
    val vertexCount: Int get() = size / (if (withNormals) 9 else 6)

    fun clear(): MeshBuilder {
        size = 0
        return this
    }

    private fun ensure(extra: Int) {
        if (size + extra <= data.size) return
        var n = data.size
        while (n < size + extra) n = n shl 1
        data = data.copyOf(n)
    }

    fun vertex(
        x: Float, y: Float, z: Float,
        nx: Float, ny: Float, nz: Float,
        r: Float, g: Float, b: Float,
    ) {
        ensure(if (withNormals) 9 else 6)
        data[size++] = x; data[size++] = y; data[size++] = z
        if (withNormals) {
            data[size++] = nx; data[size++] = ny; data[size++] = nz
        }
        data[size++] = r; data[size++] = g; data[size++] = b
    }

    /** Emits the two triangles of a planar quad given in CCW order. */
    fun quad(
        x0: Float, y0: Float, z0: Float,
        x1: Float, y1: Float, z1: Float,
        x2: Float, y2: Float, z2: Float,
        x3: Float, y3: Float, z3: Float,
        nx: Float, ny: Float, nz: Float,
        r: Float, g: Float, b: Float,
    ) {
        vertex(x0, y0, z0, nx, ny, nz, r, g, b)
        vertex(x1, y1, z1, nx, ny, nz, r, g, b)
        vertex(x2, y2, z2, nx, ny, nz, r, g, b)

        vertex(x0, y0, z0, nx, ny, nz, r, g, b)
        vertex(x2, y2, z2, nx, ny, nz, r, g, b)
        vertex(x3, y3, z3, nx, ny, nz, r, g, b)
    }

    /**
     * Adds an axis-aligned box.
     *
     * @param shadeBottom when true the lower part of tall geometry is darkened,
     *        which fakes ambient occlusion and stops big walls looking like flat
     *        cardboard. Costs nothing at runtime because it is baked in here.
     */
    fun box(
        x0: Float, y0: Float, z0: Float,
        x1: Float, y1: Float, z1: Float,
        r: Float, g: Float, b: Float,
        shadeBottom: Boolean = false,
    ) {
        val lo = if (shadeBottom) 0.72f else 1f
        val hi = 1f

        fun shade(y: Float): Float {
            if (!shadeBottom) return 1f
            val h = (y1 - y0)
            if (h <= 0.0001f) return hi
            val t = ((y - y0) / h).coerceIn(0f, 1f)
            return lo + (hi - lo) * t
        }

        // Vertical faces get the gradient; horizontal faces use a flat tint.
        fun v(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float) {
            val s = shade(y)
            vertex(x, y, z, nx, ny, nz, r * s, g * s, b * s)
        }

        fun face(
            ax: Float, ay: Float, az: Float,
            bx: Float, by: Float, bz: Float,
            cx: Float, cy: Float, cz: Float,
            dx: Float, dy: Float, dz: Float,
            nx: Float, ny: Float, nz: Float,
        ) {
            v(ax, ay, az, nx, ny, nz); v(bx, by, bz, nx, ny, nz); v(cx, cy, cz, nx, ny, nz)
            v(ax, ay, az, nx, ny, nz); v(cx, cy, cz, nx, ny, nz); v(dx, dy, dz, nx, ny, nz)
        }

        // +X
        face(x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1, 1f, 0f, 0f)
        // -X
        face(x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, -1f, 0f, 0f)
        // +Y (top)
        face(x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0, 0f, 1f, 0f)
        // -Y (bottom)
        face(x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, 0f, -1f, 0f)
        // +Z
        face(x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, 0f, 0f, 1f)
        // -Z
        face(x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0, 0f, 0f, -1f)
    }

    fun box(aabb: Aabb, r: Float, g: Float, b: Float, shadeBottom: Boolean = false) =
        box(aabb.minX, aabb.minY, aabb.minZ, aabb.maxX, aabb.maxY, aabb.maxZ, r, g, b, shadeBottom)

    /** A horizontal, upward-facing quad — used for the floor tiles. */
    fun floorTile(x0: Float, z0: Float, x1: Float, z1: Float, y: Float, r: Float, g: Float, b: Float) {
        quad(
            x0, y, z0,
            x0, y, z1,
            x1, y, z1,
            x1, y, z0,
            0f, 1f, 0f,
            r, g, b,
        )
    }

    /** The finished interleaved array (a copy sized exactly to the content). */
    fun toFloatArray(): FloatArray = data.copyOf(size)

    /** Direct access to the backing array, valid for [floatCount] floats. */
    fun raw(): FloatArray = data
}
