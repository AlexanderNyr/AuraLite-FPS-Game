package com.lanfps.client

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLES30
import android.opengl.GLUtils
import java.io.InputStream

/**
 * Loads PNG/JPEG textures out of `assets/` into GL textures.
 *
 * Everything is loaded on the GL thread (from [GameRenderer.onSurfaceCreated]),
 * which is also where context-loss recovery re-runs, so a reload here is the
 * whole story — no bookkeeping survives the old context by design.
 *
 * Failure policy: a missing or corrupt asset yields a 1x1 white texture and a
 * log line, so a botched texture can NEVER take the screen to black; the game
 * just looks like the pre-texture flat build for that material.
 */
class TextureLoader(private val assets: AssetManager) {

    private val ids = ArrayList<Int>(16)

    /**
     * Loads `textures/<name>` from assets.
     *
     * @param wrapS horizontal wrap; tiled world materials want GL_REPEAT.
     * @param wrapT vertical wrap; the sky clamps vertically (GL_CLAMP_TO_EDGE)
     *              so the horizon never repeats.
     */
    fun load(
        name: String,
        wrapS: Int = GLES30.GL_REPEAT,
        wrapT: Int = wrapS,
    ): Int {
        var bmp: Bitmap? = null
        try {
            val stream: InputStream = assets.open("textures/$name")
            stream.use { bmp = BitmapFactory.decodeStream(it) }
        } catch (t: Throwable) {
            AndroidLog.e("texture '$name' unreadable: ${t.message}")
        }
        val bitmap = bmp
        val id = if (bitmap != null && bitmap.width > 0) {
            upload(bitmap, wrapS, wrapT).also { bitmap.recycle() }
        } else {
            AndroidLog.e("texture '$name' failed to decode; using white fallback")
            white(wrapS, wrapT)
        }
        AndroidLog.i("texture '$name' -> id $id")
        return id
    }

    private fun upload(bitmap: Bitmap, wrapS: Int, wrapT: Int): Int {
        val id = genTexture(wrapS, wrapT)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER,
            GLES30.GL_LINEAR_MIPMAP_LINEAR,
        )
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        applyAnisotropy()
        return id
    }

    /**
     * P8-4: anisotropic filtering where the driver offers it (the EXT is
     * present on virtually every GLES3 phone). 4x sharpens the floor/walls at
     * grazing angles — the biggest remaining source of blur at sprint speeds.
     * Queried once per GL context, re-queried after context loss.
     */
    private fun applyAnisotropy() {
        val supported = anisoSupported ?: run {
            val exts = try {
                GLES30.glGetString(GL_EXTENSIONS) ?: ""
            } catch (t: Throwable) {
                ""
            }
            exts.contains("GL_EXT_texture_filter_anisotropic").also {
                anisoSupported = it
                AndroidLog.i("anisotropic filtering: ${if (it) "enabled (4x)" else "not supported"}")
            }
        }
        if (!supported) return
        try {
            GLES30.glTexParameterf(GLES30.GL_TEXTURE_2D, TEXTURE_MAX_ANISOTROPY_EXT, 4f)
        } catch (t: Throwable) {
            anisoSupported = false
        }
    }

    /**
     * 1x1 opaque white: multiplying a material colour by it is a no-op, so
     * geometry meant to keep its flat vertex-colour look (spawn strips, or any
     * material whose asset failed) binds this.
     */
    fun white(wrapS: Int = GLES30.GL_REPEAT, wrapT: Int = wrapS): Int {
        val id = genTexture(wrapS, wrapT)
        val px = intArrayOf(-1) // 0xFFFFFFFF ARGB
        val bmp = Bitmap.createBitmap(px, 1, 1, Bitmap.Config.ARGB_8888)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bmp, 0)
        bmp.recycle()
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        return id
    }

    private fun genTexture(wrapS: Int, wrapT: Int): Int {
        val out = IntArray(1)
        GLES30.glGenTextures(1, out, 0)
        ids.add(out[0])
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, out[0])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, wrapS)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, wrapT)
        return out[0]
    }

    /** Deletes every texture this loader produced. Call on the GL thread. */
    fun dispose() {
        // The new context may sit on a different driver: re-probe the EXT.
        anisoSupported = null
        if (ids.isEmpty()) return
        val arr = ids.toIntArray()
        GLES30.glDeleteTextures(arr.size, arr, 0)
        ids.clear()
    }

    companion object {
        /** Per-context EXT probe result; null = not yet queried. */
        @Volatile private var anisoSupported: Boolean? = null
        private const val GL_EXTENSIONS = 0x1F03
        private const val TEXTURE_MAX_ANISOTROPY_EXT = 0x84FE
    }
}
