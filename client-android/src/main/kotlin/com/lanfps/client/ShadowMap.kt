package com.lanfps.client

import android.opengl.GLES30

/**
 * P10-1: single-cascade sun shadow map (GLES 3.0 core, no extensions).
 *
 * The game's sun is a fixed directional light over a fixed arena, so ONE
 * static orthographic volume (built once per arena) covers every possible
 * caster — no cascades, no per-frame fitting. The depth pass renders into a
 * DEPTH_COMPONENT16 texture through [DEPTH_PROGRAM]; the lit shader then
 * samples it as `sampler2DShadow` with the hardware compare unit (compare
 * mode L/R + LINEAR filter gives free bilinear PCF on virtually every GLES3
 * GPU), plus a 5-tap cross on top for extra softness.
 *
 * Fallback contract exactly like [PostFx]: any FBO/texture failure logs and
 * leaves [ready] false; the renderer then keeps uShadowOn=0 and the scene
 * looks exactly as it did before this feature existed.
 */
class ShadowMap {

    /** 1024 px is plenty for volumes up to ~70 m (arena max is 64 m). */
    var size = 0
        private set

    var ready = false
        private set

    /** Final matrix applied to world position in the lit shader. */
    val lightMatrix = FloatArray(16)

    private var fbo = 0
    private var depthTex = 0
    private var program: ShaderProgram? = null

    companion object {
        // GL spec constants not re-exported by android.opengl.GLES30.
        private const val GL_TEXTURE_COMPARE_MODE = 0x884C
        private const val GL_TEXTURE_COMPARE_FUNC = 0x884D
        private const val GL_COMPARE_R_TO_TEXTURE = 0x884E

        private const val DEPTH_VS = """#version 300 es
            uniform mat4 uMvp;
            in vec3 aPos;
            void main() { gl_Position = uMvp * vec4(aPos, 1.0); }
        """
        private const val DEPTH_FS = """#version 300 es
            precision mediump float;
            void main() { } /* depth-only: nothing to write */
        """
    }

    private val tmp1 = IntArray(1)

    /**
     * Builds the FBO/texture and the static light volume. Called on the GL
     * thread whenever a new arena mesh was built ([ArenaDef] gives the bounds).
     */
    fun init(arena: com.lanfps.shared.ArenaDef, sunX: Float, sunY: Float, sunZ: Float) {
        dispose()
        size = 1024
        try {
            program = ShaderProgram("shadow-depth", DEPTH_VS, DEPTH_FS)

            GLES30.glGenTextures(1, tmp1, 0)
            depthTex = tmp1[0]
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, depthTex)
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D, 0, GLES30.GL_DEPTH_COMPONENT16,
                size, size, 0, GLES30.GL_DEPTH_COMPONENT, GLES30.GL_UNSIGNED_SHORT,
                null,
            )
            GLES30.glTexParameteri(
                GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR,
            )
            GLES30.glTexParameteri(
                GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR,
            )
            GLES30.glTexParameteri(
                GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE,
            )
            GLES30.glTexParameteri(
                GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE,
            )
            // HW compare: sampling returns depth-test result, filter = PCF.
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GL_TEXTURE_COMPARE_MODE, GL_COMPARE_R_TO_TEXTURE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GL_TEXTURE_COMPARE_FUNC, GLES30.GL_LEQUAL)

            GLES30.glGenFramebuffers(1, tmp1, 0)
            fbo = tmp1[0]
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
            GLES30.glFramebufferTexture2D(
                GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT,
                GLES30.GL_TEXTURE_2D, depthTex, 0,
            )
            // Depth-only pass: explicitly no colour buffer (GLES 3.0 rule).
            GLES30.glDrawBuffers(1, intArrayOf(GLES30.GL_NONE), 0)
            GLES30.glReadBuffer(GLES30.GL_NONE)
            val ok = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) ==
                GLES30.GL_FRAMEBUFFER_COMPLETE
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            if (!ok) {
                AndroidLog.w("shadow map: FBO incomplete, shadows disabled")
                dispose()
                return
            }
        } catch (t: Throwable) {
            AndroidLog.w("shadow map init failed: ${t.message}")
            dispose()
            return
        }

        // Static sun volume: ortho box centred on the arena, aligned with the
        // view along the sun direction. Half extent covers the full diagonal,
        // far plane covers the arena height with headroom.
        val cx = (arena.minX + arena.maxX) * 0.5f
        val cz = (arena.minZ + arena.maxZ) * 0.5f
        val ex = (arena.maxX - arena.minX) * 0.5f
        val ez = (arena.maxZ - arena.minZ) * 0.5f
        // Half extent must cover the worst-case in-plane span of the arena:
        // half-diagonal·cos(sun-elevation) + wall-height·sin(elevation); for
        // the 64 m map @ 59° sun that is ~26.7 m — 32·0.78+3 ≈ 28 with pad.
        val half = (kotlin.math.max(ex, ez) * 0.78f) + 3f

        val lx = cx + sunX * 60f
        val ly = sunY * 60f
        val lz = cz + sunZ * 60f
        val view = FloatArray(16)
        android.opengl.Matrix.setLookAtM(
            view, 0,
            lx, ly, lz,
            cx, 0f, cz,
            0f, 1f, 0f,
        )
        val proj = FloatArray(16)
        android.opengl.Matrix.orthoM(proj, 0, -half, half, -half, half, 1f, 120f)

        // bias transform: clip [-1,1] -> tex uvs [0,1] (and depth compare [0,1])
        val bias = floatArrayOf(
            0.5f, 0f, 0f, 0f,
            0f, 0.5f, 0f, 0f,
            0f, 0f, 0.5f, 0f,
            0.5f, 0.5f, 0.5f, 1f,
        )
        val tmp = FloatArray(16)
        android.opengl.Matrix.multiplyMM(tmp, 0, proj, 0, view, 0)
        android.opengl.Matrix.multiplyMM(lightMatrix, 0, bias, 0, tmp, 0)

        ready = true
        AndroidLog.i("shadow map ready: ${size}px, half=$half")
    }

    /** Runs a depth-pass draw: caller issues mesh.draw() between callbacks. */
    fun beginDepthPass() {
        val p = program ?: return
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glViewport(0, 0, size, size)
        GLES30.glClear(GLES30.GL_DEPTH_BUFFER_BIT)
        // Push shadow-side faces back a hair: kills most surface acne.
        GLES30.glEnable(GLES30.GL_POLYGON_OFFSET_FILL)
        GLES30.glPolygonOffset(2.0f, 4.0f)
        GLES30.glCullFace(GLES30.GL_BACK)
        p.use()
    }

    fun endDepthPass() {
        GLES30.glDisable(GLES30.GL_POLYGON_OFFSET_FILL)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    private val mvpScratch = FloatArray(16)

    /** Pushes uMvp = (bias·P·V) · model for one depth-pass draw — no allocation. */
    fun setMvp(model: FloatArray) {
        val p = program ?: return
        android.opengl.Matrix.multiplyMM(mvpScratch, 0, lightMatrix, 0, model, 0)
        p.setMatrix("uMvp", mvpScratch)
    }

    fun depthTexture(): Int = depthTex

    /** Deletes GL objects. Called on context loss (re-init on next arena). */
    fun dispose() {
        ready = false
        if (fbo != 0) {
            GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
            fbo = 0
        }
        if (depthTex != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(depthTex), 0)
            depthTex = 0
        }
        program?.dispose()
        program = null
        size = 0
    }
}
