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
import com.lanfps.shared.Weapons
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
 * Geometry is still flat-shaded boxes, but every surface now carries a
 * generated sci-fi texture (see `assets/textures/`): the texture acts as a
 * detail layer multiplied by the original palette colour, so the look is a
 * strictly-upgraded version of the old flat one. Each material is its own
 * small mesh + draw call (~8 world draws total), still nothing for a GPU.
 * The sky is a fullscreen triangle ray-cast against a panorama texture.
 */
class GameRenderer(
    private val state: ClientGameState,
    @Volatile private var arena: ArenaDef,
    private val textureLoader: TextureLoader,
) : GLSurfaceView.Renderer {

    /**
     * P2-3: a map queued by [setArena], applied on the GL thread at the start of
     * the next frame (rebuilding the mesh must happen where the GL context
     * lives). Never touched by the network or UI threads beyond the volatile
     * hand-off.
     */
    @Volatile private var pendingArena: ArenaDef? = null

    /** P2-3: asks the renderer to switch to a rotated map. Any thread may call. */
    fun setArena(newArena: ArenaDef) {
        pendingArena = newArena
    }

    // ---- GL objects --------------------------------------------------------
    private var litShader: ShaderProgram? = null
    private var flatShader: ShaderProgram? = null
    private var skyShader: ShaderProgram? = null

    /** One mesh per material id, so each can bind its own texture. */
    private val materialMeshes = HashMap<Int, Mesh>(6)
    private val spawnMesh = Mesh(hasNormals = true)
    private val playerMesh = Mesh(hasNormals = true)
    private val weaponMesh = Mesh(hasNormals = true)
    // The sky needs UVs but not normals; the lit layout is the only one with
    // UVs, so it piggy-backs on it (a handful of unused floats per vertex).
    private val skyMesh = Mesh(hasNormals = true, drawMode = GLES30.GL_TRIANGLES)
    private val effectMesh = DynamicMesh(hasNormals = false, maxVertices = 6 * 64)

    // ---- textures -----------------------------------------------------------
    private val materialTex = HashMap<Int, Int>(6)
    private var playerTex = 0
    private var weaponTex = 0
    private var skyTex = 0
    private var whiteTex = 0

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
        skyShader = ShaderProgram("sky", SKY_VS, SKY_FS)

        loadTextures()
        buildSkyMesh()
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
            "renderer ready: arena=${materialMeshes.values.sumOf { it.vertexCount }} verts, " +
                "player=${playerMesh.vertexCount} verts",
        )
    }

    /**
     * One texture per material. The gain column compensates each texture's
     * baked-in mean brightness so the classic palette is preserved verbatim —
     * textures only add *detail*, never a global colour shift. The numbers are
     * 1/mean-luma measured when the PNGs were processed for this APK.
     */
    private fun loadTextures() {
        textureLoader.dispose() // harmless on first run, recovers after context loss
        whiteTex = textureLoader.white()
        materialTex[Material.FLOOR] = textureLoader.load("floor.png")
        materialTex[Material.WALL] = textureLoader.load("wall.png")
        materialTex[Material.CRATE] = textureLoader.load("crate.png", GLES30.GL_CLAMP_TO_EDGE)
        materialTex[Material.PILLAR] = textureLoader.load("pillar.png")
        materialTex[Material.COVER] = textureLoader.load("cover.png", GLES30.GL_CLAMP_TO_EDGE)
        materialTex[Material.RAMP] = textureLoader.load("ramp.png")
        playerTex = textureLoader.load("player.png")
        weaponTex = textureLoader.load("weapon.png")
        skyTex = textureLoader.load("sky.jpg", GLES30.GL_REPEAT, GLES30.GL_CLAMP_TO_EDGE)
    }

    private fun texGain(material: Int): Float = when (material) {
        Material.FLOOR -> 2.00f  // mean luma 0.50
        Material.WALL -> 1.92f   // 0.52
        Material.CRATE -> 1.92f  // 0.52
        Material.PILLAR -> 1.92f // 0.52
        Material.COVER -> 2.00f  // 0.50
        Material.RAMP -> 1.92f   // 0.52
        else -> 1.0f
    }

    /**
     * A huge inward-facing cylinder plus a zenith fan, built once. Stars are
     * NOT fogged and NOT lit; the texture's left/right edges were blended to
     * wrap seamlessly. Radius 110 sits well inside the 260 m far plane, and the
     * shell is drawn with depth-writes off so the world always occludes it.
     *
     * UV note: u spans 0..12 around the equator (12 horizontal repeats densify
     * the star field), v runs bottom-up; v=0 is clamped to the horizon glow.
     */
    private fun buildSkyMesh() {
        val b = MeshBuilder(withNormals = true, initialCapacity = 8192)
        val radius = 110f
        val yTop = 60f
        val yBot = -34f
        val segs = 24
        val repeats = 12f
        for (i in 0 until segs) {
            val a0 = (i * Math.PI * 2 / segs).toFloat()
            val a1 = ((i + 1) * Math.PI * 2 / segs).toFloat()
            val x0 = cos(a0) * radius; val z0 = sin(a0) * radius
            val x1 = cos(a1) * radius; val z1 = sin(a1) * radius
            val u0 = i * repeats / segs
            val u1 = (i + 1) * repeats / segs
            // Winding is CCW *as seen from inside* (the camera is always
            // inside the shell), so the front face survives backface culling.
            val nx = -(x0 + x1); val nz = -(z0 + z1)
            b.quad(
                x0, yBot, z0,
                x1, yBot, z1,
                x1, yTop, z1,
                x0, yTop, z0,
                nx, 0f, nz,
                1f, 1f, 1f,
                u0, 0f,
                u1, 0f,
                u1, 1f,
                u0, 1f,
            )
        }
        // Zenith disc: closes the hole at the top of the cylinder. It samples
        // a small patch near the texture's top edge — dim, uniform sky.
        for (i in 0 until segs) {
            val a0 = (i * Math.PI * 2 / segs).toFloat()
            val a1 = ((i + 1) * Math.PI * 2 / segs).toFloat()
            val x0 = cos(a0) * radius; val z0 = sin(a0) * radius
            val x1 = cos(a1) * radius; val z1 = sin(a1) * radius
            b.quad(
                0f, yTop, 0f,
                x0, yTop, z0,
                x1, yTop, z1,
                0f, yTop, 0f,
                0f, -1f, 0f,
                1f, 1f, 1f,
                0f, 0.86f,
                0.10f, 0.86f,
                0.10f, 0.92f,
                0f, 0.86f,
            )
        }
        skyMesh.upload(b.raw(), b.floatCount)
        AndroidLog.i("sky mesh: ${skyMesh.vertexCount} verts")
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        camera.setPerspective(width, height)
        state.viewportWidth = width
        state.viewportHeight = height
        AndroidLog.i("surface ${width}x$height")
    }

    override fun onDrawFrame(gl: GL10?) {
        // P2-3: consume a pending map rotation before anything is drawn. Doing
        // it here (rather than in setArena) means the VBO is rebuilt on the
        // thread that owns the GL context.
        pendingArena?.let {
            pendingArena = null
            if (it != arena) {
                arena = it
                buildArenaMesh()
                AndroidLog.i("arena mesh rebuilt for ${it.name} (map rotation)")
            }
        }

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
        val sky = skyShader ?: return

        val playing = state.phase == Phase.PLAYING || state.phase == Phase.ENDED
        val nowMs = System.currentTimeMillis()
        state.snapshots.sampleInto(renderEntities, entityPool, nowMs)

        if (playing) updateGameCamera(dt) else updateMenuCamera(dt)
        // P2-5: while dead, the camera borrows the killer's eyes.
        if (playing && !state.alive) applySpectatorCamera()
        state.publishViewProj(camera.viewProjection)

        // ---- sky ------------------------------------------------------------
        // Drawn first with depth writes off: the world overwrites it wherever
        // geometry exists, so the shell acts as a pure backdrop. Not fogged and
        // not lit — see SKY_FS.
        sky.use()
        sky.setMatrix("uViewProj", camera.viewProjection)
        sky.setSampler("uTex", 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, skyTex)
        GLES30.glDepthMask(false)
        skyMesh.draw()
        GLES30.glDepthMask(true)

        // ---- world ---------------------------------------------------------
        lit.use()
        lit.setMatrix("uViewProj", camera.viewProjection)
        lit.setVec3("uLightDir", LIGHT_X, LIGHT_Y, LIGHT_Z)
        lit.setVec3("uFogColor", FOG_R, FOG_G, FOG_B)
        lit.setFloat("uFogDensity", if (playing) 0.0075f else 0.0045f)
        lit.setVec3("uEye", camera.x, camera.y, camera.z)
        lit.setFloat("uAmbient", 0.55f)
        lit.setSampler("uTex", 0)
        lit.setMatrix("uModel", identity)
        lit.setVec3("uTint", 1f, 1f, 1f)

        for ((material, mesh) in materialMeshes) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, materialTex[material] ?: whiteTex)
            lit.setFloat("uTexGain", texGain(material))
            mesh.draw()
        }

        // Spawn strips bind the white 1x1: detail layer = 1.0, pure palette.
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, whiteTex)
        lit.setFloat("uTexGain", 1.0f)
        spawnMesh.draw()

        // ---- players --------------------------------------------------------
        // P2-5: the body we are spectating must BE drawn - first-person for
        // ourselves, third-person view of our killer, so skip whoever the
        // camera is inside of.
        val localId = state.localPlayerId
        val spectatingId = if (playing && !state.alive) state.spectateId else -1
        for (i in renderEntities.indices) {
            val e = renderEntities[i]
            if (!e.alive) continue
            if (e.id == localId && playing) continue // first person: no own body
            if (e.id == spectatingId) continue // camera is inside this body
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

    /**
     * P2-5: spectate the killer while waiting out the respawn. The camera sits
     * at the victim-view entity's eye and looks where it looks; when the target
     * cannot be found (left the server, also died) we keep the body camera the
     * network thread published.
     */
    private fun applySpectatorCamera() {
        val id = state.spectateId
        if (id < 0) return
        for (i in renderEntities.indices) {
            val e = renderEntities[i]
            if (e.id != id) continue
            if (!e.alive) return
            // No bob, no recoil - a calm chase-cam read of someone else's fight.
            val eyeH = if (e.crouching) {
                GameConstants.EYE_HEIGHT_CROUCH
            } else {
                GameConstants.EYE_HEIGHT
            }
            camera.setPose(e.x, e.y + eyeH, e.z, e.yaw, e.pitch)
            return
        }
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
        // Light armour plating, tinted by team colour; gain = 1/0.55 luma.
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, playerTex)
        lit.setFloat("uTexGain", 1.82f)
        playerMesh.draw()
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, whiteTex)
        lit.setFloat("uTexGain", 1.0f)
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

        // P2-1: silhouette and tint change with the weapon the server says we
        // hold, so the viewmodel confirms what the HUD claims. The shotgun is
        // stubby and warm, the sniper long and cold.
        var tintR = 1f
        var tintG = 1f
        var tintB = 1f
        var scaleX = 1f
        var scaleY = 1f
        var scaleZ = 1f
        when (state.localWeapon) {
            Weapons.SHOTGUN -> {
                tintR = 1.08f; tintG = 0.95f; tintB = 0.78f
                scaleX = 1.22f; scaleY = 1.10f; scaleZ = 0.80f
            }
            Weapons.SNIPER -> {
                tintR = 0.82f; tintG = 0.95f; tintB = 1.12f
                scaleX = 0.90f; scaleY = 0.90f; scaleZ = 1.32f
            }
        }

        camera.viewModelMatrix(model, 0.155f + bobX, -0.135f + bobY + offY, offZ)
        // Pitch the muzzle up slightly with recoil.
        Matrix.setIdentityM(scratch, 0)
        Matrix.rotateM(scratch, 0, kick * 2.2f, 1f, 0f, 0f)
        Matrix.scaleM(scratch, 0, scaleX, scaleY, scaleZ)
        Matrix.multiplyMM(scratch, 0, model, 0, scratch, 0)

        lit.setMatrix("uModel", scratch)
        lit.setVec3("uTint", tintR, tintG, tintB)
        lit.setFloat("uFogDensity", 0f)
        // Gunmetal detail layer; gain = 1/0.45 luma (weapon texture is
        // deliberately darker so the small readout dots pop).
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, weaponTex)
        lit.setFloat("uTexGain", 2.22f)
        weaponMesh.draw()
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, whiteTex)
        lit.setFloat("uTexGain", 1.0f)
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

    /**
     * What UV treatment a material gets: FIT stretches one texture over each
     * face (crates and cover, assets generated with a coherent border), TILED
     * repeats in world space every [uvScale] metres. Scales are chosen so the
     * panel pitch looks natural on this project's boxy geometry.
     */
    private class MatStyle(val mode: MeshBuilder.UvMode, val uvScale: Float)

    private fun matStyle(material: Int): MatStyle = when (material) {
        Material.WALL -> MatStyle(MeshBuilder.UvMode.TILED, 3f)   // 3 m panels
        Material.CRATE -> MatStyle(MeshBuilder.UvMode.FIT, 1f)
        Material.PILLAR -> MatStyle(MeshBuilder.UvMode.TILED, 2f)
        Material.COVER -> MatStyle(MeshBuilder.UvMode.FIT, 1f)
        Material.RAMP -> MatStyle(MeshBuilder.UvMode.TILED, 2f)
        else -> MatStyle(MeshBuilder.UvMode.TILED, 3f)
    }

    private fun buildArenaMesh() {
        // One builder per material id; keeps each texture's geometry together
        // so the draw loop is a simple per-material bind+draw.
        val builders = HashMap<Int, MeshBuilder>(6)
        fun builderFor(material: Int) = builders.getOrPut(material) {
            MeshBuilder(withNormals = true, initialCapacity = 1 shl 13)
        }

        // Tiled floor: still a 4 m checker (the colour alternation survives as
        // a tint over the metal texture, which reads great) but each tile now
        // gets tiled UVs: one texture repeat every 2 metres.
        val tile = 4f
        val floorB = builderFor(Material.FLOOR)
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
                floorB.floorTile(x, z, x1, z1, 0f, c, c * 1.06f, c * 1.22f, uvScale = 2f)
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
            val style = matStyle(brush.material)
            builderFor(brush.material).box(
                brush.box, r, g, bb,
                shadeBottom = brush.box.sizeY > 1.2f,
                uvMode = style.mode,
                uvScale = style.uvScale,
            )
        }

        // Upload per-material meshes, recycling old ones (a map rotation
        // rebuild must not leak VBOs).
        for (old in materialMeshes.values) old.dispose()
        materialMeshes.clear()
        for ((material, builder) in builders) {
            val mesh = Mesh(hasNormals = true)
            mesh.upload(builder.raw(), builder.floatCount)
            materialMeshes[material] = mesh
        }

        // Coloured strips on the ground in front of each spawn: kept flat and
        // untextured (they bind the white 1x1) so team colours stay pure and
        // readable from spawn-room doorways.
        val sb = MeshBuilder(withNormals = true, initialCapacity = 2048)
        for (s in arena.spawns) {
            val (r, g, bb) = when (s.team) {
                Team.RED -> Triple(0.42f, 0.14f, 0.12f)
                Team.BLUE -> Triple(0.12f, 0.20f, 0.44f)
                else -> Triple(0.24f, 0.24f, 0.26f)
            }
            sb.box(
                s.position.x - 1.1f, 0.001f, s.position.z - 1.1f,
                s.position.x + 1.1f, 0.02f, s.position.z + 1.1f,
                r, g, bb,
            )
        }
        spawnMesh.upload(sb.raw(), sb.floatCount)
        AndroidLog.i(
            "arena mesh: ${materialMeshes.values.sumOf { it.vertexCount }} verts " +
                "in ${materialMeshes.size} material meshes + spawn strips",
        )
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
        skyShader?.dispose()
        for (mesh in materialMeshes.values) mesh.dispose()
        materialMeshes.clear()
        spawnMesh.dispose()
        skyMesh.dispose()
        playerMesh.dispose()
        weaponMesh.dispose()
        effectMesh.dispose()
        textureLoader.dispose()
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
            in vec2 aUv;
            out vec3 vNormal;
            out vec3 vColor;
            out vec3 vWorld;
            out vec2 vUv;
            void main() {
                vec4 world = uModel * vec4(aPos, 1.0);
                vWorld  = world.xyz;
                vNormal = mat3(uModel) * aNormal;
                vColor  = aColor;
                vUv     = aUv;
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
            uniform sampler2D uTex;
            uniform float uTexGain;
            in vec3 vNormal;
            in vec3 vColor;
            in vec3 vWorld;
            in vec2 vUv;
            out vec4 fragColor;
            void main() {
                vec3 n = normalize(vNormal);
                float lambert = max(dot(n, normalize(uLightDir)), 0.0);
                // Hemisphere term: sky above, bounce below. Keeps vertical faces
                // readable without a second light or any shadow mapping.
                float hemi = 0.55 + 0.45 * n.y;
                float light = uAmbient * hemi + (1.0 - uAmbient) * lambert;
                // The texture is a greyscale-ish detail layer: uTexGain restores
                // the palette brightness (1/mean-luma), so the original vertex
                // colours survive texturing and a white 1x1 fallback texture is
                // a silent no-op returning the pre-texture look.
                vec3 detail = texture(uTex, vUv).rgb * uTexGain;
                vec3 c = vColor * detail * uTint * light;
                float dist = length(vWorld - uEye);
                float fog = 1.0 - exp(-uFogDensity * dist);
                c = mix(c, uFogColor, clamp(fog, 0.0, 1.0));
                fragColor = vec4(c, 1.0);
            }
        """

        /**
         * Sky shell: one textured inward-facing cylinder around the arena, drawn
         * first with depth writes off. Unlit and unfogged on purpose — fogging
         * the sky would crush the stars into murk; the fog-tinted world already
         * blends toward the same dark palette at distance.
         */
        private const val SKY_VS = """#version 300 es
            uniform mat4 uViewProj;
            in vec3 aPos;
            in vec2 aUv;
            out vec2 vUv;
            void main() {
                vUv = aUv;
                gl_Position = uViewProj * vec4(aPos, 1.0);
            }
        """

        private const val SKY_FS = """#version 300 es
            precision mediump float;
            uniform sampler2D uTex;
            in vec2 vUv;
            out vec4 fragColor;
            void main() {
                fragColor = vec4(texture(uTex, vUv).rgb, 1.0);
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
