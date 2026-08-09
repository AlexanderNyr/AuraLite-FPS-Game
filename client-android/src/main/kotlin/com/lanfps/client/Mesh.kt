package com.lanfps.client

import android.opengl.GLES30
import java.nio.FloatBuffer

/**
 * A vertex buffer plus its vertex-array object.
 *
 * Two layouts exist, chosen by [hasNormals]:
 *  - lit    : position(3) normal(3) colour(3) uv(2) = 11 floats / vertex
 *  - unlit  : position(3) colour(3)                 = 6 floats / vertex
 *
 * Lit meshes always carry UVs now (the arena and the models are textured);
 * unlit meshes (tracers, muzzle flash) intentionally do not.
 *
 * Static meshes upload once with GL_STATIC_DRAW; [DynamicMesh] re-uploads every
 * frame with GL_STREAM_DRAW for tracers and muzzle flashes.
 */
open class Mesh(
    val hasNormals: Boolean,
    val drawMode: Int = GLES30.GL_TRIANGLES,
) {
    protected val vao = IntArray(1)
    protected val vbo = IntArray(1)

    var vertexCount: Int = 0
        protected set

    val floatsPerVertex: Int get() = if (hasNormals) MeshBuilder.LIT_FLOATS else MeshBuilder.UNLIT_FLOATS
    val strideBytes: Int get() = floatsPerVertex * GlUtil.FLOAT_BYTES

    protected var created = false

    protected fun ensureCreated() {
        if (created) return
        GLES30.glGenVertexArrays(1, vao, 0)
        GLES30.glGenBuffers(1, vbo, 0)
        created = true
    }

    protected fun configureAttributes() {
        val stride = strideBytes
        GLES30.glEnableVertexAttribArray(ShaderProgram.ATTRIB_POSITION)
        GLES30.glVertexAttribPointer(
            ShaderProgram.ATTRIB_POSITION, 3, GLES30.GL_FLOAT, false, stride, 0,
        )
        if (hasNormals) {
            GLES30.glEnableVertexAttribArray(ShaderProgram.ATTRIB_NORMAL)
            GLES30.glVertexAttribPointer(
                ShaderProgram.ATTRIB_NORMAL, 3, GLES30.GL_FLOAT, false, stride,
                3 * GlUtil.FLOAT_BYTES,
            )
            GLES30.glEnableVertexAttribArray(ShaderProgram.ATTRIB_COLOR)
            GLES30.glVertexAttribPointer(
                ShaderProgram.ATTRIB_COLOR, 3, GLES30.GL_FLOAT, false, stride,
                6 * GlUtil.FLOAT_BYTES,
            )
            GLES30.glEnableVertexAttribArray(ShaderProgram.ATTRIB_UV)
            GLES30.glVertexAttribPointer(
                ShaderProgram.ATTRIB_UV, 2, GLES30.GL_FLOAT, false, stride,
                9 * GlUtil.FLOAT_BYTES,
            )
        } else {
            GLES30.glEnableVertexAttribArray(ShaderProgram.ATTRIB_COLOR)
            GLES30.glVertexAttribPointer(
                ShaderProgram.ATTRIB_COLOR, 3, GLES30.GL_FLOAT, false, stride,
                3 * GlUtil.FLOAT_BYTES,
            )
        }
    }

    /** Uploads [data] (already interleaved) and configures the VAO. */
    fun upload(data: FloatArray, floatCount: Int = data.size) {
        ensureCreated()
        vertexCount = floatCount / floatsPerVertex
        val buffer: FloatBuffer = GlUtil.floatBuffer(data, floatCount)

        GLES30.glBindVertexArray(vao[0])
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[0])
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER,
            floatCount * GlUtil.FLOAT_BYTES,
            buffer,
            GLES30.GL_STATIC_DRAW,
        )
        configureAttributes()
        GLES30.glBindVertexArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    fun draw() {
        if (vertexCount == 0) return
        GLES30.glBindVertexArray(vao[0])
        GLES30.glDrawArrays(drawMode, 0, vertexCount)
        GLES30.glBindVertexArray(0)
    }

    open fun dispose() {
        if (!created) return
        GLES30.glDeleteBuffers(1, vbo, 0)
        GLES30.glDeleteVertexArrays(1, vao, 0)
        created = false
        vertexCount = 0
    }
}

/** A mesh whose contents are rewritten every frame (tracers, muzzle flash). */
class DynamicMesh(
    hasNormals: Boolean,
    private val maxVertices: Int,
    drawMode: Int = GLES30.GL_TRIANGLES,
) : Mesh(hasNormals, drawMode) {

    private val cpu = FloatArray(
        maxVertices * (if (hasNormals) MeshBuilder.LIT_FLOATS else MeshBuilder.UNLIT_FLOATS),
    )
    private var head = 0
    private var allocated = false

    val capacityVertices: Int get() = maxVertices

    fun begin() {
        head = 0
    }

    /** Appends one unlit vertex. Silently ignores overflow. */
    fun vertex(x: Float, y: Float, z: Float, r: Float, g: Float, b: Float) {
        if (head + 6 > cpu.size) return
        cpu[head++] = x; cpu[head++] = y; cpu[head++] = z
        cpu[head++] = r; cpu[head++] = g; cpu[head++] = b
    }

    /** Uploads whatever was appended since [begin]. */
    fun end() {
        ensureCreated()
        vertexCount = head / floatsPerVertex
        if (vertexCount == 0) return

        GLES30.glBindVertexArray(vao[0])
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[0])
        if (!allocated) {
            GLES30.glBufferData(
                GLES30.GL_ARRAY_BUFFER,
                cpu.size * GlUtil.FLOAT_BYTES,
                null,
                GLES30.GL_STREAM_DRAW,
            )
            configureAttributes()
            allocated = true
        }
        GLES30.glBufferSubData(
            GLES30.GL_ARRAY_BUFFER, 0, head * GlUtil.FLOAT_BYTES, GlUtil.floatBuffer(cpu, head),
        )
        GLES30.glBindVertexArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    override fun dispose() {
        super.dispose()
        allocated = false
        head = 0
    }
}

/**
 * P10: dynamic mesh for the soft-smoke system. Interleaved
 * pos(3)/color(3)/alpha(1)/uv(2) = 9 floats per vertex; rewritten every frame
 * with GL_STREAM_DRAW exactly like [DynamicMesh].
 */
class SpriteMesh(private val maxVertices: Int) : Mesh(hasNormals = false) {
    private val cpu = FloatArray(maxVertices * SmokeSystem.FLOATS_PER_VERTEX)
    private var allocated = false
    private var head = 0

    /** Rewrites the VBO from an external scratch — zero per-frame allocation. */
    fun uploadDynamic(src: FloatArray, floatCount: Int) {
        ensureCreated()
        head = floatCount
        vertexCount = floatCount / SmokeSystem.FLOATS_PER_VERTEX
        GLES30.glBindVertexArray(vao[0])
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[0])
        if (!allocated) {
            GLES30.glBufferData(
                GLES30.GL_ARRAY_BUFFER,
                cpu.size * GlUtil.FLOAT_BYTES, null, GLES30.GL_STREAM_DRAW,
            )
            val stride = SmokeSystem.FLOATS_PER_VERTEX * GlUtil.FLOAT_BYTES
            GLES30.glEnableVertexAttribArray(ShaderProgram.ATTRIB_POSITION)
            GLES30.glVertexAttribPointer(
                ShaderProgram.ATTRIB_POSITION, 3, GLES30.GL_FLOAT, false, stride, 0,
            )
            GLES30.glEnableVertexAttribArray(ShaderProgram.ATTRIB_COLOR)
            GLES30.glVertexAttribPointer(
                ShaderProgram.ATTRIB_COLOR, 3, GLES30.GL_FLOAT, false, stride,
                3 * GlUtil.FLOAT_BYTES,
            )
            GLES30.glEnableVertexAttribArray(ShaderProgram.ATTRIB_ALPHA)
            GLES30.glVertexAttribPointer(
                ShaderProgram.ATTRIB_ALPHA, 1, GLES30.GL_FLOAT, false, stride,
                6 * GlUtil.FLOAT_BYTES,
            )
            GLES30.glEnableVertexAttribArray(ShaderProgram.ATTRIB_UV)
            GLES30.glVertexAttribPointer(
                ShaderProgram.ATTRIB_UV, 2, GLES30.GL_FLOAT, false, stride,
                7 * GlUtil.FLOAT_BYTES,
            )
            allocated = true
        }
        if (head > 0) {
            GLES30.glBufferSubData(
                GLES30.GL_ARRAY_BUFFER, 0, head * GlUtil.FLOAT_BYTES,
                GlUtil.floatBuffer(src, head),
            )
        }
        GLES30.glBindVertexArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    override fun dispose() {
        super.dispose()
        allocated = false
        head = 0
    }
}
