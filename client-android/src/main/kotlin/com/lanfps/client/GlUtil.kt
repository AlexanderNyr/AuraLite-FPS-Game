package com.lanfps.client

import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** Small helpers shared by the GL classes. Everything here runs on the GL thread. */
object GlUtil {

    const val FLOAT_BYTES = 4

    /** Allocates a direct, native-order float buffer filled from [data]. */
    fun floatBuffer(data: FloatArray, count: Int = data.size): FloatBuffer =
        ByteBuffer.allocateDirect(count * FLOAT_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(data, 0, count)
                position(0)
            }

    /**
     * Logs any pending GL error.
     *
     * Called after set-up steps only: polling glGetError every frame would
     * force a pipeline flush and destroy performance for no benefit.
     */
    fun checkError(where: String) {
        var err = GLES30.glGetError()
        while (err != GLES30.GL_NO_ERROR) {
            AndroidLog.e("GL error 0x${Integer.toHexString(err)} at $where")
            err = GLES30.glGetError()
        }
    }

    fun logContextInfo() {
        AndroidLog.i("GL_VERSION  : ${GLES30.glGetString(GLES30.GL_VERSION)}")
        AndroidLog.i("GL_RENDERER : ${GLES30.glGetString(GLES30.GL_RENDERER)}")
        AndroidLog.i("GL_VENDOR   : ${GLES30.glGetString(GLES30.GL_VENDOR)}")
        AndroidLog.i("GLSL        : ${GLES30.glGetString(GLES30.GL_SHADING_LANGUAGE_VERSION)}")
    }
}
