package com.lanfps.client

import android.opengl.GLES30

/**
 * A compiled and linked GLSL ES 3.00 program with cached uniform locations.
 *
 * Compilation failures are fatal and loud: a silent shader error produces a
 * black screen that is very hard to diagnose on a phone, so we throw with the
 * full info log instead.
 */
class ShaderProgram(name: String, vertexSrc: String, fragmentSrc: String) {

    val id: Int
    private val uniforms = HashMap<String, Int>()

    init {
        val vs = compile(GLES30.GL_VERTEX_SHADER, vertexSrc, "$name.vert")
        val fs = compile(GLES30.GL_FRAGMENT_SHADER, fragmentSrc, "$name.frag")
        val program = GLES30.glCreateProgram()
        if (program == 0) throw RuntimeException("glCreateProgram failed for $name")

        GLES30.glAttachShader(program, vs)
        GLES30.glAttachShader(program, fs)
        // Fixed attribute slots so every Mesh can bind the same way.
        GLES30.glBindAttribLocation(program, ATTRIB_POSITION, "aPos")
        GLES30.glBindAttribLocation(program, ATTRIB_NORMAL, "aNormal")
        GLES30.glBindAttribLocation(program, ATTRIB_COLOR, "aColor")
        GLES30.glBindAttribLocation(program, ATTRIB_UV, "aUv")
        GLES30.glLinkProgram(program)

        val status = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(program)
            GLES30.glDeleteProgram(program)
            throw RuntimeException("link failed for $name:\n$log")
        }

        // The shader objects are no longer needed once linked.
        GLES30.glDetachShader(program, vs)
        GLES30.glDetachShader(program, fs)
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)

        id = program
        AndroidLog.i("shader '$name' linked (id=$program)")
    }

    fun use() = GLES30.glUseProgram(id)

    fun uniform(name: String): Int = uniforms.getOrPut(name) {
        val loc = GLES30.glGetUniformLocation(id, name)
        if (loc < 0) AndroidLog.d("uniform '$name' not found (optimised out?)")
        loc
    }

    fun setMatrix(name: String, m: FloatArray) {
        val loc = uniform(name)
        if (loc >= 0) GLES30.glUniformMatrix4fv(loc, 1, false, m, 0)
    }

    fun setVec3(name: String, x: Float, y: Float, z: Float) {
        val loc = uniform(name)
        if (loc >= 0) GLES30.glUniform3f(loc, x, y, z)
    }

    fun setVec2(name: String, x: Float, y: Float) {
        val loc = uniform(name)
        if (loc >= 0) GLES30.glUniform2f(loc, x, y)
    }

    fun setFloat(name: String, v: Float) {
        val loc = uniform(name)
        if (loc >= 0) GLES30.glUniform1f(loc, v)
    }

    fun dispose() {
        if (id != 0) GLES30.glDeleteProgram(id)
    }

    private fun compile(type: Int, src: String, label: String): Int {
        val shader = GLES30.glCreateShader(type)
        if (shader == 0) throw RuntimeException("glCreateShader failed for $label")
        GLES30.glShaderSource(shader, src)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(shader)
            GLES30.glDeleteShader(shader)
            throw RuntimeException("compile failed for $label:\n$log\n--- source ---\n$src")
        }
        return shader
    }

    /** Sampler slot: binds a texture unit index (e.g. 0) to a sampler uniform. */
    fun setSampler(name: String, unit: Int) {
        val loc = uniform(name)
        if (loc >= 0) GLES30.glUniform1i(loc, unit)
    }

    companion object {
        const val ATTRIB_POSITION = 0
        const val ATTRIB_NORMAL = 1
        const val ATTRIB_COLOR = 2
        const val ATTRIB_UV = 3
    }
}
