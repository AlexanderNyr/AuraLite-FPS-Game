package com.lanfps.client

import android.opengl.GLES30

/**
 * Post-processing pipeline: the scene renders off-screen and is then drawn
 * back to the display framebuffer through bright-pass bloom, a soft filmic
 * tonemap, gentle split-tone grading (cool shadows / warm highlights) and a
 * vignette. One long-lived object, zero per-frame allocation.
 *
 * Frame flow when [ready]:
 *   1. [beginScene] binds the multisampled scene FBO — all normal draw calls
 *      (sky, world, players, effects, weapon viewmodel) land there untouched;
 *   2. [endSceneAndCompose] resolves MSAA into a regular texture, pulls the
 *      bright areas into a quarter-res target, softens them with two
 *      separable Gaussian sweeps, and draws a single `gl_VertexID` fullscreen
 *      triangle into the default framebuffer with the composite shader.
 *
 * Compatibility contract: every framebuffer is completeness-checked at init;
 * on the first failure (or any exception while creating GL objects) the whole
 * pipeline disables itself and the renderer keeps drawing straight to the
 * screen exactly as it always has — mid-range phones that barf on
 * [GLES30.glRenderbufferStorageMultisample] lose the icing, not the game.
 */
class PostFx {

    // ---- P8 day/night presets --------------------------------------------
    // The GameRenderer pushes these whenever the server-picked lighting
    // preset changes; defaults are the daylight look.
    /** Luminance above which a fragment joins the bloom chain. Day: the sky
     *  band and haze fog sit at 0.7+ luma and must NOT halo — bloom stays
     *  reserved for the sun disc, visors, tracers and flashes. Night: a
     *  darker scene lets neon and muzzle flash bloom from much lower luma. */
    private var bloomThreshold = 0.74f
    /** Pre-tonemap exposure. Day: above 1.0 — the exponential shoulder eats
     *  the mids and a sunlit arena must land bright. Night: well under, so
     *  the same albedos read as moonlit. */
    private var exposure = 1.20f
    /** Corner darkening at the frame edge; lighter in daylight so the frame
     *  reads as airy rather than tunnelled. */
    private var vignette = 0.22f

    /** P8: switches the grading constants between the day and night looks. */
    fun applyPreset(night: Boolean) {
        if (night) {
            bloomThreshold = 0.45f
            exposure = 0.62f
            vignette = 0.30f
        } else {
            bloomThreshold = 0.74f
            exposure = 1.20f
            vignette = 0.22f
        }
    }

    /** True once every FBO validated; the renderer branches on this. */
    var ready = false
        private set

    /** Applied MSAA on the off-screen scene buffer (0 = driver-less path). */
    var samples = 0
        private set

    // ---- sizes --------------------------------------------------------------
    private var width = 0
    private var height = 0
    private var bloomW = 0
    private var bloomH = 0

    // ---- GL object ids -------------------------------------------------------
    private var sceneFbo = 0
    private var msaaFbo = 0
    private var bloomFboA = 0
    private var bloomFboB = 0
    private var sceneTex = 0
    private var bloomTexA = 0
    private var bloomTexB = 0
    private var msaaColorRb = 0
    private var msaaDepthRb = 0
    private var sceneDepthRb = 0
    private var emptyVao = 0

    private var brightProgram: ShaderProgram? = null
    private var blurProgram: ShaderProgram? = null
    private var composeProgram: ShaderProgram? = null

    /** Scratch int buffers reused across calls — the renderer is single-threaded. */
    private val tmp1 = IntArray(1)

    /** (Re)initialises the whole pipeline for surface [w]x[h]. GL thread only. */
    fun init(w: Int, h: Int) {
        dispose()
        if (w < 64 || h < 64) return
        width = w; height = h
        // Quarter-res bloom is soft enough and four times cheaper; clamped so
        // tiny/narrow windows keep a sane blur kernel footprint.
        bloomW = (w / 4).coerceIn(48, 512)
        bloomH = (h / 4).coerceIn(48, 512)

        tmp1[0] = 0
        GLES30.glGetIntegerv(GLES30.GL_MAX_SAMPLES, tmp1, 0)
        // All-or-nothing: 4x when the driver offers it, otherwise draw straight
        // into the resolve texture (still correct, just less smooth edges).
        val wantSamples = 4
        samples = if (tmp1[0] >= wantSamples) wantSamples else 0

        try {
            brightProgram = ShaderProgram("bright", FULLSCREEN_VS, BRIGHT_FS)
            blurProgram = ShaderProgram("blur", FULLSCREEN_VS, BLUR_FS)
            composeProgram = ShaderProgram("compose", FULLSCREEN_VS, COMPOSE_FS)
            createTargets()
            GLES30.glGenVertexArrays(1, tmp1, 0)
            emptyVao = tmp1[0]
        } catch (t: Throwable) {
            AndroidLog.w("post-fx init failed, direct rendering instead: ${t.message}")
            dispose()
            return
        }
        if (!framebuffersComplete()) {
            AndroidLog.w("post-fx: framebuffer incomplete, direct rendering")
            dispose()
            return
        }
        ready = true
        AndroidLog.i(
            "post-fx ready: ${w}x$h, msaa=$samples, bloom chain ${bloomW}x$bloomH",
        )
    }

    /** Binds the off-screen scene buffer. Skips everything when not [ready]. */
    fun beginScene() {
        if (!ready) return
        GLES30.glBindFramebuffer(
            GLES30.GL_FRAMEBUFFER,
            if (samples > 0) msaaFbo else sceneFbo,
        )
        GLES30.glViewport(0, 0, width, height)
    }

    /**
     * Finishes the scene pass and composites to the default framebuffer,
     * restoring the GL state the renderer relies on (depth test on, depth
     * writes on, texture unit 0).
     */
    fun endSceneAndCompose() {
        if (!ready) return
        val bright = brightProgram ?: return
        val blur = blurProgram ?: return
        val compose = composeProgram ?: return

        // 1) resolve MSAA into the plain scene texture
        if (samples > 0) {
            GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, msaaFbo)
            GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, sceneFbo)
            GLES30.glBlitFramebuffer(
                0, 0, width, height, 0, 0, width, height,
                GLES30.GL_COLOR_BUFFER_BIT, GLES30.GL_NEAREST,
            )
            // Binding GL_FRAMEBUFFER resets both READ and DRAW binding points.
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        }

        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glDepthMask(false)
        if (emptyVao != 0) GLES30.glBindVertexArray(emptyVao)

        // 2) bright pass into A
        bindFboViewport(bloomFboA, bloomW, bloomH)
        bright.use()
        bright.setFloat("uThreshold", bloomThreshold)
        bright.setFloat("uKnee", BLOOM_KNEE)
        bright.setSampler("uTex", 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, sceneTex)
        drawTriangle()

        // 3) separable gaussian: H (A->B), V (B->A), H again (A->B) for width
        blur.use()
        blur.setSampler("uTex", 0)
        bindFboViewport(bloomFboB, bloomW, bloomH)
        blur.setVec2("uDir", 1f / bloomW, 0f)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, bloomTexA)
        drawTriangle()

        bindFboViewport(bloomFboA, bloomW, bloomH)
        blur.setVec2("uDir", 0f, 1f / bloomH)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, bloomTexB)
        drawTriangle()

        bindFboViewport(bloomFboB, bloomW, bloomH)
        blur.setVec2("uDir", 1f / bloomW, 0f)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, bloomTexA)
        drawTriangle()

        // 4) composite to the screen: scene + bloom, graded
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, width, height)
        compose.use()
        compose.setSampler("uScene", 0)
        compose.setSampler("uBloom", 1)
        compose.setFloat("uExposure", exposure)
        compose.setFloat("uBloomStrength", BLOOM_STRENGTH)
        compose.setFloat("uVignette", vignette)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, bloomTexB)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, sceneTex)
        drawTriangle()

        // 5) restore what the main pipeline expects after us
        if (emptyVao != 0) GLES30.glBindVertexArray(0)
        GLES30.glDepthMask(true)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
    }

    private fun drawTriangle() = GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)

    private fun bindFboViewport(fbo: Int, w: Int, h: Int) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glViewport(0, 0, w, h)
    }

    private fun createTargets() {
        // Scene resolve target: plain RGBA8 texture + its own depth buffer so we
        // can render directly here when MSAA renderbuffers are unavailable.
        sceneTex = genTexture(width, height)
        sceneFbo = genFbo(sceneTex)
        sceneDepthRb = genDepthRenderbuffer(width, height, 0)
        attachDepth(sceneFbo, sceneDepthRb)

        if (samples > 0) {
            msaaColorRb = genColorRenderbufferMsaa(width, height, samples)
            msaaFbo = genFboWithRenderbuffer(msaaColorRb)
            msaaDepthRb = genDepthRenderbuffer(width, height, samples)
            attachDepth(msaaFbo, msaaDepthRb)
        }

        bloomTexA = genTexture(bloomW, bloomH)
        bloomFboA = genFbo(bloomTexA)
        bloomTexB = genTexture(bloomW, bloomH)
        bloomFboB = genFbo(bloomTexB)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    private fun framebuffersComplete(): Boolean {
        fun ok(fbo: Int): Boolean {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
            val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
            return status == GLES30.GL_FRAMEBUFFER_COMPLETE
        }

        val valid = ok(sceneFbo) && ok(bloomFboA) && ok(bloomFboB) &&
            (samples == 0 || ok(msaaFbo))
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        return valid
    }

    private fun genFboWithRenderbuffer(rb: Int): Int {
        GLES30.glGenFramebuffers(1, tmp1, 0)
        val fbo = tmp1[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glFramebufferRenderbuffer(
            GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_RENDERBUFFER, rb,
        )
        return fbo
    }

    private fun genFbo(tex: Int): Int {
        GLES30.glGenFramebuffers(1, tmp1, 0)
        val fbo = tmp1[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D, tex, 0,
        )
        return fbo
    }

    private fun attachDepth(fbo: Int, rb: Int) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glFramebufferRenderbuffer(
            GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT,
            GLES30.GL_RENDERBUFFER, rb,
        )
    }

    private fun genTexture(w: Int, h: Int): Int {
        GLES30.glGenTextures(1, tmp1, 0)
        val tex = tmp1[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA,
            w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null,
        )
        return tex
    }

    private fun genDepthRenderbuffer(w: Int, h: Int, sampleCount: Int): Int {
        GLES30.glGenRenderbuffers(1, tmp1, 0)
        val rb = tmp1[0]
        GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, rb)
        if (sampleCount > 0) {
            GLES30.glRenderbufferStorageMultisample(
                GLES30.GL_RENDERBUFFER, sampleCount,
                GLES30.GL_DEPTH_COMPONENT16, w, h,
            )
        } else {
            GLES30.glRenderbufferStorage(
                GLES30.GL_RENDERBUFFER, GLES30.GL_DEPTH_COMPONENT16, w, h,
            )
        }
        return rb
    }

    private fun genColorRenderbufferMsaa(w: Int, h: Int, sampleCount: Int): Int {
        GLES30.glGenRenderbuffers(1, tmp1, 0)
        val rb = tmp1[0]
        GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, rb)
        GLES30.glRenderbufferStorageMultisample(
            GLES30.GL_RENDERBUFFER, sampleCount, GLES30.GL_RGBA8, w, h,
        )
        return rb
    }

    /** Deletes every GL object owned by the pipeline. GL thread only. */
    fun dispose() {
        ready = false
        if (sceneFbo != 0 || msaaFbo != 0 || bloomFboA != 0 || bloomFboB != 0) {
            val fbos = intArrayOf(sceneFbo, msaaFbo, bloomFboA, bloomFboB)
            GLES30.glDeleteFramebuffers(4, fbos, 0)
            sceneFbo = 0; msaaFbo = 0; bloomFboA = 0; bloomFboB = 0
        }
        if (sceneTex != 0 || bloomTexA != 0 || bloomTexB != 0) {
            val texs = intArrayOf(sceneTex, bloomTexA, bloomTexB)
            GLES30.glDeleteTextures(3, texs, 0)
            sceneTex = 0; bloomTexA = 0; bloomTexB = 0
        }
        if (msaaColorRb != 0 || msaaDepthRb != 0 || sceneDepthRb != 0) {
            val rbs = intArrayOf(msaaColorRb, msaaDepthRb, sceneDepthRb)
            GLES30.glDeleteRenderbuffers(3, rbs, 0)
            msaaColorRb = 0; msaaDepthRb = 0; sceneDepthRb = 0
        }
        if (emptyVao != 0) {
            val vaos = intArrayOf(emptyVao)
            GLES30.glDeleteVertexArrays(1, vaos, 0)
            emptyVao = 0
        }
        brightProgram?.dispose(); brightProgram = null
        blurProgram?.dispose(); blurProgram = null
        composeProgram?.dispose(); composeProgram = null
    }

    companion object {
        /** Soft knee width: halo grows smoothly instead of clipping. */
        private const val BLOOM_KNEE = 0.55f

        /** Bloom contribution in the composite; 0.4 ≈ visible but not haze. */
        private const val BLOOM_STRENGTH = 0.42f

        /** Attribute-less fullscreen triangle sourced from gl_VertexID. */
        private const val FULLSCREEN_VS = """#version 300 es
            out vec2 vUv;
            void main() {
                // (-1,-1) (3,-1) (-1,3) — covers the screen, no VBO needed.
                vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
                vUv = p;
                gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
            }
        """

        /** Keeps pixels whose luminance clears the soft-knee threshold. */
        private const val BRIGHT_FS = """#version 300 es
            precision mediump float;
            in vec2 vUv;
            uniform sampler2D uTex;
            uniform float uThreshold;
            uniform float uKnee;
            out vec4 fragColor;
            void main() {
                vec3 c = texture(uTex, vUv).rgb;
                float lum = dot(c, vec3(0.2126, 0.7152, 0.0722));
                float w = smoothstep(uThreshold, uThreshold * (1.0 + uKnee), lum);
                fragColor = vec4(c * w, 1.0);
            }
        """

        /** Separable 5-tap/9-weight Gaussian; direction arrives in texels. */
        private const val BLUR_FS = """#version 300 es
            precision mediump float;
            in vec2 vUv;
            uniform sampler2D uTex;
            uniform vec2 uDir;
            out vec4 fragColor;
            void main() {
                vec3 s = texture(uTex, vUv).rgb * 0.227027;
                s += texture(uTex, vUv + uDir * 1.384615).rgb * 0.316216;
                s += texture(uTex, vUv - uDir * 1.384615).rgb * 0.316216;
                s += texture(uTex, vUv + uDir * 3.230769).rgb * 0.070270;
                s += texture(uTex, vUv - uDir * 3.230769).rgb * 0.070270;
                fragColor = vec4(s, 1.0);
            }
        """

        /**
         * Final grade: bloom add, exposure shoulder, split-tone, vignette.
         * The split-tone mixing is deliberately gentle: sky-blue in the
         * shadows, warm parchment in the highlights — a sunlit palette
         * identity that still lets muzzle flashes flare orange.
         */
        private const val COMPOSE_FS = """#version 300 es
            precision mediump float;
            in vec2 vUv;
            uniform sampler2D uScene;
            uniform sampler2D uBloom;
            uniform float uExposure;
            uniform float uBloomStrength;
            uniform float uVignette;
            out vec4 fragColor;
            void main() {
                vec3 scene = texture(uScene, vUv).rgb;
                vec3 bloom = texture(uBloom, vUv).rgb;
                // Slightly warm-weighted bloom: orange flashes and sun glare
                // flare more than cool accents, like film response outdoors.
                vec3 c = scene + bloom * uBloomStrength * vec3(1.0, 0.96, 0.88);
                c *= uExposure;
                // Exponential shoulder: bright never clips hard, mids keep hue.
                c = vec3(1.0) - exp(-c);
                float lum = clamp(dot(c, vec3(0.2126, 0.7152, 0.0722)), 0.0, 1.0);
                vec3 cool = c * vec3(0.94, 1.00, 1.10);
                vec3 warm = c * vec3(1.05, 1.01, 0.94);
                c = mix(cool, warm, smoothstep(0.12, 0.62, lum));
                float d = distance(vUv, vec2(0.5));
                c *= 1.0 - smoothstep(0.40, 0.85, d) * uVignette;
                fragColor = vec4(c, 1.0);
            }
        """
    }
}
