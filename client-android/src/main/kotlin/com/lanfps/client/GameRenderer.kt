package com.lanfps.client

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import com.lanfps.shared.ArenaDef
import com.lanfps.shared.EntityState
import com.lanfps.shared.EntityType
import com.lanfps.shared.GameConstants
import com.lanfps.shared.GameMode
import com.lanfps.shared.Material
import com.lanfps.shared.MathUtil
import com.lanfps.shared.Team
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The whole 3D view: arena, players, tracers and the weapon viewmodel.
 *
 * Runs on the GLSurfaceView's own thread and only ever *reads* game state, so
 * the simulation can never be stalled by a slow frame — if rendering drops to
 * 30 fps the network loop still ticks at a rock-steady 60 Hz.
 *
 * Everything is untextured flat-shaded geometry: four draw calls for the world
 * plus one per visible player. That is deliberate — it keeps the frame time low
 * on mid-range phones and means the APK ships no art assets at all.
 */
class GameRenderer(
    private val state: ClientGameState,
    private val arena: ArenaDef,
) : GLSurfaceView.Renderer {

    // ---- GL objects --------------------------------------------------------
    private var litShader: ShaderProgram? = null
    private var flatShader: ShaderProgram? = null

    private val arenaMesh = Mesh(hasNormals = true)
    private val playerMesh = Mesh(hasNormals = true)
    private val weaponMesh = Mesh(hasNormals = true)
    private val effectMesh = DynamicMesh(hasNormals = false, maxVertices = 6 * 64)

    private val camera = Camera()
    private val model = FloatArray(16)
    private val scratch = FloatArray(16)
    private val identity = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    // ---- frame state -------------------------------------------------------
    private val renderEntities = ArrayList<EntityState>(24)
    private val entityPool = ArrayList<EntityState>(24)
    private val tracers = ArrayList<Tracer>(48)

    private var lastFrameNanos = 0L
    private var timeSec = 0f
    private var bobPhase = 0f
    private var weaponKick = 0f
    private var frameCount = 0
    private var fpsAccum = 0f

    /** Menu camera orbit angle. */
    private var orbit = 0f

    @Volatile
    var surfaceReady: Boolean = false
        private set

    // ------------------------------------------------------------- lifecycle

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GlUtil.logContextInfo()

        litShader = ShaderProgram("lit", LIT_VS, LIT_FS)
        flatShader = ShaderProgram("flat", FLAT_VS, FLAT_FS)

        buildArenaMesh()
        buildPlayerMesh()
        buildWeaponMesh()

        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
        GLES30.glCullFace(GLES30.GL_BACK)
        GLES30.glFrontFace(GLES30.GL_CCW)
        GLES30.glClearColor(FOG_R, FOG_G, FOG_B, 1f)
        GLES30.glDisable(GLES30.GL_BLEND)

        lastFrameNanos = System.nanoTime()
        surfaceReady = true
        GlUtil.checkError("onSurfaceCreated")
        AndroidLog.i(
            "renderer ready: arena=${arenaMesh.vertexCount} verts, " +
                "player=${playerMesh.vertexCount} verts",
        )
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        camera.setPerspective(width, height)
        state.viewportWidth = width
        state.viewportHeight = height
        AndroidLog.i("surface ${width}x$height")
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        var dt = (now - lastFrameNanos) / 1_000_000_000f
        lastFrameNanos = now
        if (dt > 0.1f) dt = 0.1f
        if (dt < 0f) dt = 0f
        timeSec += dt

        // FPS meter for the debug overlay.
        frameCount++
        fpsAccum += dt
        if (fpsAccum >= 0.5f) {
            state.fps = frameCount / fpsAccum
            frameCount = 0
            fpsAccum = 0f
        }

        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        val lit = litShader ?: return
        val flat = flatShader ?: return

        val playing = state.phase == Phase.PLAYING || state.phase == Phase.ENDED
        if (playing) updateGameCamera(dt) else updateMenuCamera(dt)
        state.publishViewProj(camera.viewProjection)

        // ---- world ---------------------------------------------------------
        lit.use()
        lit.setMatrix("uViewProj", camera.viewProjection)
        lit.setVec3("uLightDir", LIGHT_X, LIGHT_Y, LIGHT_Z)
        lit.setVec3("uFogColor", FOG_R, FOG_G, FOG_B)
        lit.setFloat("uFogDensity", if (playing) 0.0075f else 0.0045f)
        lit.setVec3("uEye", camera.x, camera.y, camera.z)
        lit.setFloat("uAmbient", 0.55f)

        lit.setMatrix("uModel", identity)
        lit.setVec3("uTint", 1f, 1f, 1f)
        arenaMesh.draw()

        // ---- players --------------------------------------------------------
        val nowMs = System.currentTimeMillis()
        state.snapshots.sampleInto(renderEntities, entityPool, nowMs)
        val localId = state.localPlayerId
        for (i in renderEntities.indices) {
            val e = renderEntities[i]
            if (!e.alive) continue
            if (e.id == localId && playing) continue // first person: no own body
            drawPlayer(lit, e)
        }

        // ---- tracers & flashes ----------------------------------------------
        state.collectTracers(nowMs, TRACER_TTL_MS, tracers)
        val muzzleActive = playing && nowMs < state.muzzleFlashUntilMs
        if (tracers.isNotEmpty() || muzzleActive) {
            buildEffects(nowMs, muzzleActive)
            flat.use()
            flat.setMatrix("uViewProj", camera.viewProjection)
            GLES30.glEnable(GLES30.GL_BLEND)
            GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE)
            GLES30.glDepthMask(false)
            GLES30.glDisable(GLES30.GL_CULL_FACE)
            effectMesh.draw()
            GLES30.glEnable(GLES30.GL_CULL_FACE)
            GLES30.glDepthMask(true)
            GLES30.glDisable(GLES30.GL_BLEND)
        }

        // ---- weapon viewmodel -------------------------------------------------
        if (playing && state.alive) {
            // Clear depth so the gun can never clip through a wall the player is
            // standing against - the classic FPS trick, and much cheaper than a
            // second depth range.
            GLES30.glClear(GLES30.GL_DEPTH_BUFFER_BIT)
            drawWeapon(lit)
        }
    }

    // ------------------------------------------------------------- cameras

    private fun updateGameCamera(dt: Float) {
        // Decay the weapon recoil that the network thread accumulated.
        val recoil = state.recoilPitch
        if (recoil > 0f) {
            state.recoilPitch = (recoil - dt * 9f).coerceAtLeast(0f)
        }
        weaponKick = recoil

        var eyeY = state.eyeY
        // Subtle view bob while running, killed in the air so jumps feel crisp.
        val speed = state.localSpeed
        if (state.localOnGround && speed > 0.4f) {
            bobPhase += dt * (5.2f + speed * 0.9f)
            eyeY += sin(bobPhase * 2f) * 0.022f * min(speed / GameConstants.MOVE_SPEED, 1f)
        } else {
            bobPhase += dt * 1.5f
        }

        camera.setPose(state.eyeX, eyeY, state.eyeZ, state.viewYaw, state.viewPitch)
    }

    /** Slow orbit over the arena, shown behind the menus. */
    private fun updateMenuCamera(dt: Float) {
        orbit += dt * 5.5f
        val r = 34f
        val a = orbit * MathUtil.DEG_TO_RAD
        val cx = cos(a) * r
        val cz = sin(a) * r
        val cy = 13f
        // Look at the arena centre: derive yaw/pitch from the offset so the shared
        // angle convention is used here too.
        val dx = -cx
        val dz = -cz
        val yaw = Math.toDegrees(kotlin.math.atan2(dx.toDouble(), -dz.toDouble())).toFloat()
        val horiz = kotlin.math.sqrt(dx * dx + dz * dz)
        val pitch = Math.toDegrees(kotlin.math.atan2(-cy.toDouble(), horiz.toDouble())).toFloat()
        camera.setPose(cx, cy, cz, yaw, pitch)
    }

    // --------------------------------------------------------------- drawing

    private fun drawPlayer(lit: ShaderProgram, e: EntityState) {
        val crouchScale = if (e.crouching) {
            GameConstants.PLAYER_CROUCH_HEIGHT / GameConstants.PLAYER_HEIGHT
        } else {
            1f
        }

        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, e.x, e.y, e.z)
        // Model faces -Z at yaw 0, matching MathUtil.horizontalForward.
        Matrix.rotateM(model, 0, -e.yaw, 0f, 1f, 0f)
        if (crouchScale != 1f) Matrix.scaleM(model, 0, 1f, crouchScale, 1f)

        lit.setMatrix("uModel", model)

        var r: Float
        var g: Float
        var b: Float
        if (state.mode == GameMode.TDM) {
            when (e.teamEnum) {
                Team.RED -> { r = 0.88f; g = 0.32f; b = 0.26f }
                Team.BLUE -> { r = 0.30f; g = 0.56f; b = 0.92f }
                else -> { r = 0.80f; g = 0.80f; b = 0.80f }
            }
            // Teammates are tinted toward green so a glance is enough in a firefight.
            if (e.teamEnum == state.localTeam && e.id != state.localPlayerId) {
                r = r * 0.45f + 0.20f
                g = g * 0.45f + 0.62f
                b = b * 0.45f + 0.30f
            }
        } else if (e.type == EntityType.BOT) {
            r = 0.86f; g = 0.47f; b = 0.20f
        } else {
            r = 0.90f; g = 0.30f; b = 0.28f
        }

        // Wounded enemies visibly darken: free feedback that your shots landed.
        val hp = MathUtil.clamp(e.health / GameConstants.MAX_HEALTH.toFloat(), 0f, 1f)
        val k = 0.55f + 0.45f * hp
        lit.setVec3("uTint", r * k, g * k, b * k)
        playerMesh.draw()
    }

    private fun drawWeapon(lit: ShaderProgram) {
        val speed = state.localSpeed
        val moveT = min(speed / GameConstants.MOVE_SPEED, 1f)
        val bobX = sin(bobPhase) * 0.012f * moveT
        val bobY = -abs(cos(bobPhase)) * 0.014f * moveT

        // Recoil pushes the gun back and up.
        val kick = weaponKick
        val offZ = -kick * 0.035f
        val offY = kick * 0.010f

        camera.viewModelMatrix(model, 0.155f + bobX, -0.135f + bobY + offY, offZ)
        // Pitch the muzzle up slightly with recoil.
        Matrix.setIdentityM(scratch, 0)
        Matrix.rotateM(scratch, 0, kick * 2.2f, 1f, 0f, 0f)
        Matrix.multiplyMM(scratch, 0, model, 0, scratch, 0)

        lit.setMatrix("uModel", scratch)
        lit.setVec3("uTint", 1f, 1f, 1f)
        lit.setFloat("uFogDensity", 0f)
        weaponMesh.draw()
        lit.setFloat("uFogDensity", 0.0075f)
    }

    /** Builds camera-facing quads for tracers and the muzzle flash. */
    private fun buildEffects(nowMs: Long, muzzleActive: Boolean) {
        effectMesh.begin()

        for (i in tracers.indices) {
            val t = tracers[i]
            val age = (nowMs - t.bornMs).toFloat() / TRACER_TTL_MS
            val fade = (1f - age).coerceIn(0f, 1f)
            if (fade <= 0f) continue

            // Perpendicular to both the tracer and the view direction gives a
            // ribbon that is always visible, whatever angle you shoot from.
            var dx = t.x1 - t.x0
            var dy = t.y1 - t.y0
            var dz = t.z1 - t.z0
            val len = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
            if (len < 0.05f) continue
            dx /= len; dy /= len; dz /= len

            // side = dir x viewForward
            var sx = dy * camera.forwardZ - dz * camera.forwardY
            var sy = dz * camera.forwardX - dx * camera.forwardZ
            var sz = dx * camera.forwardY - dy * camera.forwardX
            val sl = kotlin.math.sqrt(sx * sx + sy * sy + sz * sz)
            if (sl < 1e-4f) continue
            val half = (if (t.local) 0.020f else 0.026f) * fade
            sx = sx / sl * half; sy = sy / sl * half; sz = sz / sl * half

            val r: Float
            val g: Float
            val b: Float
            if (t.local) {
                r = 1.0f * fade; g = 0.92f * fade; b = 0.55f * fade
            } else {
                r = 1.0f * fade; g = 0.55f * fade; b = 0.25f * fade
            }

            // Two triangles forming the ribbon.
            effectMesh.vertex(t.x0 - sx, t.y0 - sy, t.z0 - sz, r, g, b)
            effectMesh.vertex(t.x1 - sx, t.y1 - sy, t.z1 - sz, r, g, b)
            effectMesh.vertex(t.x1 + sx, t.y1 + sy, t.z1 + sz, r, g, b)

            effectMesh.vertex(t.x0 - sx, t.y0 - sy, t.z0 - sz, r, g, b)
            effectMesh.vertex(t.x1 + sx, t.y1 + sy, t.z1 + sz, r, g, b)
            effectMesh.vertex(t.x0 + sx, t.y0 + sy, t.z0 + sz, r, g, b)
        }

        if (muzzleActive) {
            // A small billboard just in front of the muzzle.
            val mx = camera.x + camera.rightX * 0.155f + camera.upX * -0.10f + camera.forwardX * 0.95f
            val my = camera.y + camera.rightY * 0.155f + camera.upY * -0.10f + camera.forwardY * 0.95f
            val mz = camera.z + camera.rightZ * 0.155f + camera.upZ * -0.10f + camera.forwardZ * 0.95f
            val s = 0.085f
            val rx = camera.rightX * s; val ry = camera.rightY * s; val rz = camera.rightZ * s
            val ux = camera.upX * s; val uy = camera.upY * s; val uz = camera.upZ * s
            val r = 1.0f; val g = 0.86f; val b = 0.45f

            effectMesh.vertex(mx - rx - ux, my - ry - uy, mz - rz - uz, r, g, b)
            effectMesh.vertex(mx + rx - ux, my + ry - uy, mz + rz - uz, r, g, b)
            effectMesh.vertex(mx + rx + ux, my + ry + uy, mz + rz + uz, r, g, b)

            effectMesh.vertex(mx - rx - ux, my - ry - uy, mz - rz - uz, r, g, b)
            effectMesh.vertex(mx + rx + ux, my + ry + uy, mz + rz + uz, r, g, b)
            effectMesh.vertex(mx - rx + ux, my - ry + uy, mz - rz + uz, r, g, b)
        }

        effectMesh.end()
    }

    // ------------------------------------------------------------ mesh build

    private fun buildArenaMesh() {
        val b = MeshBuilder(withNormals = true, initialCapacity = 1 shl 16)

        // Tiled floor: a checker of 4 m tiles reads as distance far better than a
        // single flat quad, and costs one extra draw call of nothing.
        val tile = 4f
        var z = arena.minZ
        var row = 0
        while (z < arena.maxZ - 0.001f) {
            val z1 = min(z + tile, arena.maxZ)
            var x = arena.minX
            var col = 0
            while (x < arena.maxX - 0.001f) {
                val x1 = min(x + tile, arena.maxX)
                val dark = ((row + col) and 1) == 0
                val c = if (dark) 0.148f else 0.178f
                b.floorTile(x, z, x1, z1, 0f, c, c * 1.06f, c * 1.22f)
                x = x1
                col++
            }
            z = z1
            row++
        }

        for (brush in arena.brushes) {
            // The floor brush is replaced by the tiled grid above.
            if (brush.material == Material.FLOOR) continue
            val (r, g, bb) = materialColour(brush.material)
            b.box(brush.box, r, g, bb, shadeBottom = brush.box.sizeY > 1.2f)
        }

        // Coloured strips on the ground in front of each spawn: a readable,
        // asset-free way to show whose side of the map you are on.
        for (s in arena.spawns) {
            val (r, g, bb) = when (s.team) {
                Team.RED -> Triple(0.42f, 0.14f, 0.12f)
                Team.BLUE -> Triple(0.12f, 0.20f, 0.44f)
                else -> Triple(0.24f, 0.24f, 0.26f)
            }
            b.box(
                s.position.x - 1.1f, 0.001f, s.position.z - 1.1f,
                s.position.x + 1.1f, 0.02f, s.position.z + 1.1f,
                r, g, bb,
            )
        }

        arenaMesh.upload(b.raw(), b.floatCount)
        AndroidLog.i("arena mesh: ${arenaMesh.vertexCount} vertices")
    }

    private fun materialColour(material: Int): Triple<Float, Float, Float> = when (material) {
        Material.WALL -> Triple(0.335f, 0.352f, 0.400f)
        Material.CRATE -> Triple(0.520f, 0.390f, 0.220f)
        Material.PILLAR -> Triple(0.300f, 0.330f, 0.420f)
        Material.COVER -> Triple(0.235f, 0.375f, 0.330f)
        Material.RAMP -> Triple(0.380f, 0.380f, 0.400f)
        else -> Triple(0.400f, 0.400f, 0.420f)
    }

    /**
     * The player avatar: legs, torso, head, a visor that shows which way the
     * model is facing and a stubby rifle. Vertex colours here are *multipliers*
     * on the per-entity team tint, so one mesh serves every player.
     */
    private fun buildPlayerMesh() {
        val b = MeshBuilder(withNormals = true, initialCapacity = 4096)

        // legs
        b.box(-0.17f, 0.00f, -0.14f, 0.17f, 0.84f, 0.14f, 0.68f, 0.68f, 0.72f)
        // torso
        b.box(-0.25f, 0.84f, -0.17f, 0.25f, 1.44f, 0.17f, 1.00f, 1.00f, 1.00f)
        // shoulders
        b.box(-0.31f, 1.20f, -0.14f, 0.31f, 1.42f, 0.14f, 0.86f, 0.86f, 0.90f)
        // head
        b.box(-0.125f, 1.44f, -0.125f, 0.125f, 1.71f, 0.125f, 1.12f, 1.12f, 1.12f)
        // visor (front is -Z)
        b.box(-0.10f, 1.52f, -0.145f, 0.10f, 1.62f, -0.120f, 1.9f, 1.9f, 2.0f)
        // rifle held on the right side
        b.box(0.17f, 1.06f, -0.62f, 0.29f, 1.19f, -0.10f, 0.22f, 0.22f, 0.24f)
        b.box(0.19f, 1.19f, -0.34f, 0.27f, 1.26f, -0.14f, 0.18f, 0.18f, 0.20f)

        playerMesh.upload(b.raw(), b.floatCount)
    }

    /** First-person weapon, modelled in view space (-Z points where you aim). */
    private fun buildWeaponMesh() {
        val b = MeshBuilder(withNormals = true, initialCapacity = 4096)
        val d = 0.30f   // dark metal
        val m = 0.42f   // mid
        val l = 0.20f   // barrel

        // receiver
        b.box(-0.048f, -0.052f, -0.62f, 0.048f, 0.050f, -0.14f, d, d, d * 1.08f)
        // top rail
        b.box(-0.030f, 0.050f, -0.52f, 0.030f, 0.072f, -0.20f, m * 0.7f, m * 0.7f, m * 0.75f)
        // front sight
        b.box(-0.010f, 0.072f, -0.56f, 0.010f, 0.105f, -0.52f, m, m, m)
        // rear sight
        b.box(-0.022f, 0.072f, -0.26f, 0.022f, 0.098f, -0.23f, m, m, m)
        // barrel
        b.box(-0.019f, -0.012f, -0.95f, 0.019f, 0.026f, -0.60f, l, l, l * 1.1f)
        // muzzle
        b.box(-0.026f, -0.020f, -1.00f, 0.026f, 0.034f, -0.93f, 0.15f, 0.15f, 0.16f)
        // magazine
        b.box(-0.030f, -0.215f, -0.44f, 0.030f, -0.050f, -0.32f, d * 0.8f, d * 0.8f, d * 0.85f)
        // grip
        b.box(-0.034f, -0.225f, -0.26f, 0.034f, -0.050f, -0.15f, d * 0.7f, d * 0.7f, d * 0.75f)
        // stock
        b.box(-0.036f, -0.030f, -0.14f, 0.036f, 0.046f, 0.10f, d * 0.9f, d * 0.9f, d * 0.95f)
        // hand hint
        b.box(-0.052f, -0.150f, -0.30f, 0.052f, -0.060f, -0.20f, 0.55f, 0.42f, 0.34f)

        weaponMesh.upload(b.raw(), b.floatCount)
    }

    fun dispose() {
        surfaceReady = false
        litShader?.dispose()
        flatShader?.dispose()
        arenaMesh.dispose()
        playerMesh.dispose()
        weaponMesh.dispose()
        effectMesh.dispose()
    }

    companion object {
        private const val TRACER_TTL_MS = 90L

        // Fog / clear colour: a cool dark blue-grey.
        private const val FOG_R = 0.055f
        private const val FOG_G = 0.070f
        private const val FOG_B = 0.092f

        // Key light direction (pointing from the surface toward the light).
        private const val LIGHT_X = 0.38f
        private const val LIGHT_Y = 0.86f
        private const val LIGHT_Z = 0.34f

        private const val LIT_VS = """#version 300 es
            uniform mat4 uViewProj;
            uniform mat4 uModel;
            in vec3 aPos;
            in vec3 aNormal;
            in vec3 aColor;
            out vec3 vNormal;
            out vec3 vColor;
            out vec3 vWorld;
            void main() {
                vec4 world = uModel * vec4(aPos, 1.0);
                vWorld  = world.xyz;
                vNormal = mat3(uModel) * aNormal;
                vColor  = aColor;
                gl_Position = uViewProj * world;
            }
        """

        private const val LIT_FS = """#version 300 es
            precision mediump float;
            uniform vec3  uLightDir;
            uniform vec3  uTint;
            uniform float uAmbient;
            uniform vec3  uFogColor;
            uniform float uFogDensity;
            uniform vec3  uEye;
            in vec3 vNormal;
            in vec3 vColor;
            in vec3 vWorld;
            out vec4 fragColor;
            void main() {
                vec3 n = normalize(vNormal);
                float lambert = max(dot(n, normalize(uLightDir)), 0.0);
                // Hemisphere term: sky above, bounce below. Keeps vertical faces
                // readable without a second light or any shadow mapping.
                float hemi = 0.55 + 0.45 * n.y;
                float light = uAmbient * hemi + (1.0 - uAmbient) * lambert;
                vec3 c = vColor * uTint * light;
                float dist = length(vWorld - uEye);
                float fog = 1.0 - exp(-uFogDensity * dist);
                c = mix(c, uFogColor, clamp(fog, 0.0, 1.0));
                fragColor = vec4(c, 1.0);
            }
        """

        private const val FLAT_VS = """#version 300 es
            uniform mat4 uViewProj;
            in vec3 aPos;
            in vec3 aColor;
            out vec3 vColor;
            void main() {
                vColor = aColor;
                gl_Position = uViewProj * vec4(aPos, 1.0);
            }
        """

        private const val FLAT_FS = """#version 300 es
            precision mediump float;
            in vec3 vColor;
            out vec4 fragColor;
            void main() {
                fragColor = vec4(vColor, 1.0);
            }
        """
    }
}
