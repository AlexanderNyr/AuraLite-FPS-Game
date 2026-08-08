package com.lanfps.client

import com.lanfps.shared.Aabb
import kotlin.math.abs

/**
 * Builds interleaved triangle soup on the CPU.
 *
 * Everything in this game is boxes and quads, so there is no model format, no
 * loader and no index buffer: 36 vertices per box is a few hundred kilobytes for
 * the whole arena and uploads in one call.
 *
 * Winding is counter-clockwise as seen from outside the box, which matches
 * `glFrontFace(GL_CCW)` + `glCullFace(GL_BACK)`.
 *
 * Two ways of generating texture coordinates exist, picked per box:
 *  - [UvMode.FIT] stretches the whole texture over each face — right for
 *    crates, cover plates and model parts that want one coherent picture.
 *  - [UvMode.TILED] maps world metres to texture repeats (`uv = coord /
 *    uvScale`) and relies on GL_REPEAT — right for floors, walls and ramps,
 *    where one texture repeat must look the same on every surface.
 *
 * Meshes built with [withNormals] = false ignore UVs entirely (tracers etc.
 * stay unlit and untextured on a 6-float layout).
 */
class MeshBuilder(private val withNormals: Boolean = true, initialCapacity: Int = 4096) {

    private var data = FloatArray(initialCapacity)
    private var size = 0

    private val floatsPerVertex: Int get() = if (withNormals) LIT_FLOATS else UNLIT_FLOATS

    val floatCount: Int get() = size
    val vertexCount: Int get() = size / floatsPerVertex

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

    /** How UVs are produced for a face; see the class doc. */
    enum class UvMode { FIT, TILED }

    fun vertex(
        x: Float, y: Float, z: Float,
        nx: Float, ny: Float, nz: Float,
        r: Float, g: Float, b: Float,
        u: Float = 0f, v: Float = 0f,
    ) {
        ensure(floatsPerVertex)
        data[size++] = x; data[size++] = y; data[size++] = z
        if (withNormals) {
            data[size++] = nx; data[size++] = ny; data[size++] = nz
        }
        data[size++] = r; data[size++] = g; data[size++] = b
        if (withNormals) {
            data[size++] = u; data[size++] = v
        }
    }

    /**
     * Emits the two triangles of a planar quad given in CCW order.
     *
     * The four (u,v) pairs belong to the four corners in the same order as the
     * positions, so a caller can build billboards, sky shells and tiled floors
     * without going through [box].
     */
    fun quad(
        x0: Float, y0: Float, z0: Float,
        x1: Float, y1: Float, z1: Float,
        x2: Float, y2: Float, z2: Float,
        x3: Float, y3: Float, z3: Float,
        nx: Float, ny: Float, nz: Float,
        r: Float, g: Float, b: Float,
        u0: Float = 0f, v0: Float = 0f,
        u1: Float = 0f, v1: Float = 0f,
        u2: Float = 1f, v2: Float = 1f,
        u3: Float = 1f, v3: Float = 0f,
    ) {
        vertex(x0, y0, z0, nx, ny, nz, r, g, b, u0, v0)
        vertex(x1, y1, z1, nx, ny, nz, r, g, b, u1, v1)
        vertex(x2, y2, z2, nx, ny, nz, r, g, b, u2, v2)

        vertex(x0, y0, z0, nx, ny, nz, r, g, b, u0, v0)
        vertex(x2, y2, z2, nx, ny, nz, r, g, b, u2, v2)
        vertex(x3, y3, z3, nx, ny, nz, r, g, b, u3, v3)
    }

    /**
     * Adds an axis-aligned box.
     *
     * @param shadeBottom when true the lower part of tall geometry is darkened,
     *        which fakes ambient occlusion and stops big walls looking like flat
     *        cardboard. Costs nothing at runtime because it is baked in here.
     * @param uvMode [UvMode.FIT] stretches the texture once over every face;
     *        [UvMode.TILED] repeats it every [uvScale] metres along the face.
     * @param uvScale world metres per texture repeat when [uvMode] is TILED;
     *        ignored for FIT. Must be > 0 for TILED to make sense.
     */
    fun box(
        x0: Float, y0: Float, z0: Float,
        x1: Float, y1: Float, z1: Float,
        r: Float, g: Float, b: Float,
        shadeBottom: Boolean = false,
        uvMode: UvMode = UvMode.FIT,
        uvScale: Float = 4f,
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

        // Maps a position on a face to (u,v). The face's normal decides which
        // two world axes the texture spans; FIT normalises to the box extents
        // on those axes, TILED just scales world metres.
        fun uv(nx: Float, ny: Float, nz: Float, x: Float, y: Float, z: Float): Pair<Float, Float> {
            val u: Float
            val v: Float
            val ax = abs(nx)
            val ay = abs(ny)
            if (ax > 0.5f) {
                u = z; v = y
                return if (uvMode == UvMode.TILED) {
                    u / uvScale to v / uvScale
                } else {
                    (u - z0) / span(z1 - z0) to (v - y0) / span(y1 - y0)
                }
            } else if (ay > 0.5f) {
                u = x; v = z
                return if (uvMode == UvMode.TILED) {
                    u / uvScale to v / uvScale
                } else {
                    (u - x0) / span(x1 - x0) to (v - z0) / span(z1 - z0)
                }
            } else {
                u = x; v = y
                return if (uvMode == UvMode.TILED) {
                    u / uvScale to v / uvScale
                } else {
                    (u - x0) / span(x1 - x0) to (v - y0) / span(y1 - y0)
                }
            }
        }

        // Vertical faces get the gradient; horizontal faces use a flat tint.
        fun v(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float) {
            val s = shade(y)
            val (uu, vv) = uv(nx, ny, nz, x, y, z)
            vertex(x, y, z, nx, ny, nz, r * s, g * s, b * s, uu, vv)
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

    private fun span(d: Float): Float = if (abs(d) < 1e-5f) 1e-5f else d

    fun box(
        aabb: Aabb, r: Float, g: Float, b: Float,
        shadeBottom: Boolean = false,
        uvMode: UvMode = UvMode.FIT,
        uvScale: Float = 4f,
    ) = box(
        aabb.minX, aabb.minY, aabb.minZ, aabb.maxX, aabb.maxY, aabb.maxZ,
        r, g, b, shadeBottom, uvMode, uvScale,
    )

    /**
     * A horizontal, upward-facing quad — used for the floor tiles.
     * [uvScale] maps world metres to texture repeats (TILED); pass 0 to leave
     * UVs at zero (untextured caller).
     */
    fun floorTile(
        x0: Float, z0: Float, x1: Float, z1: Float, y: Float,
        r: Float, g: Float, b: Float,
        uvScale: Float = 0f,
    ) {
        if (uvScale > 0f) {
            quad(
                x0, y, z0,
                x0, y, z1,
                x1, y, z1,
                x1, y, z0,
                0f, 1f, 0f,
                r, g, b,
                x0 / uvScale, z0 / uvScale,
                x0 / uvScale, z1 / uvScale,
                x1 / uvScale, z1 / uvScale,
                x1 / uvScale, z0 / uvScale,
            )
        } else {
            quad(
                x0, y, z0,
                x0, y, z1,
                x1, y, z1,
                x1, y, z0,
                0f, 1f, 0f,
                r, g, b,
            )
        }
    }

    /** The finished interleaved array (a copy sized exactly to the content). */
    fun toFloatArray(): FloatArray = data.copyOf(size)

    /** Direct access to the backing array, valid for [floatCount] floats. */
    fun raw(): FloatArray = data

    companion object {
        /** position(3) normal(3) colour(3) uv(2). */
        const val LIT_FLOATS = 11

        /** position(3) colour(3). */
        const val UNLIT_FLOATS = 6
    }
}
