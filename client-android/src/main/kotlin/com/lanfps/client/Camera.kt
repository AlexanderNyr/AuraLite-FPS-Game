package com.lanfps.client

import android.opengl.Matrix
import com.lanfps.shared.MathUtil
import kotlin.math.cos
import kotlin.math.sin

/**
 * First-person camera.
 *
 * The angle convention is the project-wide one, defined in
 * [com.lanfps.shared.MathUtil.forwardFromAngles]: yaw 0 looks down -Z,
 * increasing yaw turns toward +X, positive pitch looks up. Using the shared
 * helper here (rather than a second hand-rolled copy) is what keeps the camera
 * pointing exactly where the server thinks the player is aiming.
 */
class Camera {

    val projection = FloatArray(16)
    val view = FloatArray(16)
    val viewProjection = FloatArray(16)

    var x = 0f; private set
    var y = 0f; private set
    var z = 0f; private set
    var yaw = 0f; private set
    var pitch = 0f; private set

    /** Camera basis vectors in world space, recomputed by [setPose]. */
    val forwardX get() = fx
    val forwardY get() = fy
    val forwardZ get() = fz
    val rightX get() = rx
    val rightY get() = ry
    val rightZ get() = rz
    val upX get() = ux
    val upY get() = uy
    val upZ get() = uz

    private var fx = 0f; private var fy = 0f; private var fz = -1f
    private var rx = 1f; private var ry = 0f; private var rz = 0f
    private var ux = 0f; private var uy = 1f; private var uz = 0f

    var fovYDegrees: Float = 68f
    var near: Float = 0.06f
    var far: Float = 260f

    fun setPerspective(widthPx: Int, heightPx: Int) {
        val aspect = if (heightPx <= 0) 1.6f else widthPx.toFloat() / heightPx.toFloat()
        Matrix.perspectiveM(projection, 0, fovYDegrees, aspect, near, far)
    }

    fun setPose(px: Float, py: Float, pz: Float, yawDeg: Float, pitchDeg: Float) {
        x = px; y = py; z = pz
        yaw = yawDeg
        pitch = MathUtil.clamp(pitchDeg, -89.9f, 89.9f)

        val yr = yaw * MathUtil.DEG_TO_RAD
        val pr = pitch * MathUtil.DEG_TO_RAD
        val cp = cos(pr)

        fx = sin(yr) * cp
        fy = sin(pr)
        fz = -cos(yr) * cp

        // Right is the horizontal strafe axis: independent of pitch, so looking up
        // never rolls the view.
        rx = cos(yr); ry = 0f; rz = sin(yr)

        // up = right x forward
        ux = ry * fz - rz * fy
        uy = rz * fx - rx * fz
        uz = rx * fy - ry * fx

        Matrix.setLookAtM(
            view, 0,
            x, y, z,
            x + fx, y + fy, z + fz,
            ux, uy, uz,
        )
        Matrix.multiplyMM(viewProjection, 0, projection, 0, view, 0)
    }

    /**
     * Builds a model matrix that places view-space geometry (the weapon) in the
     * world, so the viewmodel can be drawn with the same lit shader as
     * everything else.
     *
     * Column-major, OpenGL order: [right | up | -forward | eye].
     */
    fun viewModelMatrix(out: FloatArray, offsetX: Float, offsetY: Float, offsetZ: Float) {
        out[0] = rx; out[1] = ry; out[2] = rz; out[3] = 0f
        out[4] = ux; out[5] = uy; out[6] = uz; out[7] = 0f
        out[8] = -fx; out[9] = -fy; out[10] = -fz; out[11] = 0f
        out[12] = x + rx * offsetX + ux * offsetY - fx * offsetZ
        out[13] = y + ry * offsetX + uy * offsetY - fy * offsetZ
        out[14] = z + rz * offsetX + uz * offsetY - fz * offsetZ
        out[15] = 1f
    }
}
