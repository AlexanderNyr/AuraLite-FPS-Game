package com.lanfps.client

import android.content.Context
import android.opengl.GLSurfaceView
import com.lanfps.shared.ArenaDef
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay

/**
 * Hosts the GL thread.
 *
 * `setEGLContextClientVersion(3)` asks the driver for the highest OpenGL ES 3.x
 * context it supports, which is 3.2 on any recent phone. The shaders are written
 * against GLSL ES 3.00 core so the same APK also runs on ES 3.0 hardware — a
 * strictly-3.2-only build would refuse to install on perfectly capable devices
 * for no gain.
 */
class GameView(
    context: Context,
    state: ClientGameState,
    arena: ArenaDef,
) : GLSurfaceView(context) {

    // TextureLoader gets nothing but the AssetManager: it only opens streams,
    // actual GL work is deferred to onSurfaceCreated on this view's GL thread.
    val gameRenderer = GameRenderer(state, arena, TextureLoader(context.assets))

    init {
        setEGLContextClientVersion(3)
        // 8/8/8/8 colour, 16-bit depth, no stencil, 4x MSAA: box edges are the
        // whole arena, and supersampled edges are the single cheapest "made on
        // a console" upgrade for this geometry. Devices without 4x get a plain
        // config — GameRenderer's PostFx still multisamples off-screen when it
        // can, so this only matters for the direct-render fallback path.
        setEGLConfigChooser(object : GLSurfaceView.EGLConfigChooser {
            override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig {
                // First try: the full-fat config.
                pickConfig(egl, display, rgba = intArrayOf(8, 8, 8, 8), depth = 16, samples = 4)?.let { return it }
                // Second try: full fat minus MSAA (very old drivers).
                pickConfig(egl, display, rgba = intArrayOf(8, 8, 8, 8), depth = 16, samples = 0)?.let { return it }
                // Last resort: let the driver pick anything close.
                pickConfig(egl, display, rgba = intArrayOf(5, 6, 5, 0), depth = 16, samples = 0)?.let { return it }
                throw IllegalArgumentException("no usable EGL configs")
            }

            private fun pickConfig(
                egl: EGL10, display: EGLDisplay,
                rgba: IntArray, depth: Int, samples: Int,
            ): EGLConfig? {
                val attrs = intArrayOf(
                    EGL10.EGL_RED_SIZE, rgba[0],
                    EGL10.EGL_GREEN_SIZE, rgba[1],
                    EGL10.EGL_BLUE_SIZE, rgba[2],
                    EGL10.EGL_ALPHA_SIZE, rgba[3],
                    EGL10.EGL_DEPTH_SIZE, depth,
                    EGL10.EGL_STENCIL_SIZE, 0,
                    EGL10.EGL_SAMPLE_BUFFERS, if (samples > 0) 1 else 0,
                    EGL10.EGL_SAMPLES, samples,
                    EGL10.EGL_RENDERABLE_TYPE, 4, // EGL_OPENGL_ES2_BIT; ES3 contexts are a superset
                    EGL10.EGL_NONE,
                )
                val num = IntArray(1)
                if (!egl.eglChooseConfig(display, attrs, null, 0, num) || num[0] <= 0) return null
                val configs = arrayOfNulls<EGLConfig>(num[0])
                egl.eglChooseConfig(display, attrs, configs, num[0], num)
                return configs.firstOrNull()
            }
        })
        preserveEGLContextOnPause = true
        setRenderer(gameRenderer)
        renderMode = RENDERMODE_CONTINUOUSLY
        // The overlay Views (HUD, controls, menus) are drawn on top of this
        // surface by the normal View hierarchy, so it must stay behind them.
        setZOrderMediaOverlay(false)
    }

    /** Queues work on the GL thread; safe to call from the UI thread. */
    fun runOnGl(action: () -> Unit) = queueEvent(action)
}
