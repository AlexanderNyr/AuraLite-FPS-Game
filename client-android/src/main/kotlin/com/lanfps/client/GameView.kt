package com.lanfps.client

import android.content.Context
import android.opengl.GLSurfaceView
import com.lanfps.shared.ArenaDef

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

    val gameRenderer = GameRenderer(state, arena)

    init {
        setEGLContextClientVersion(3)
        // 8/8/8 colour, 16-bit depth, no stencil, no MSAA: the geometry is flat
        // shaded blocks, so anything more is battery burned for nothing.
        setEGLConfigChooser(8, 8, 8, 0, 16, 0)
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
