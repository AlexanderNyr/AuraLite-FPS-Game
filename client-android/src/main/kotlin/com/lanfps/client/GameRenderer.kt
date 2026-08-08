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
import com.lanfps.shared.RayMath
import com.lanfps.shared.Team
import com.lanfps.shared.Vec3
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
 * The sky is an inward-facing 24-sided cylinder textured with the generated
 * night panorama, completed in-shader by a sun disc (anchored to the diffuse
 * light direction so shadows and sun agree) and a slowly drifting procedural
 * cloud layer. Shadows come in two cheap layers: a baked contact darkening of
 * floor tiles near static geometry, and per-frame blob quads under every live
 * player that fade with drop height.
 *
 * On top of that sits the post chain ([PostFx]): the whole scene renders into
 * an MSAA off-screen buffer, bright pixels are blurred into a bloom halo and
 * the final fullscreen pass applies exposure tonemapping, split-tone grading
 * and a vignette. Any device unable to host that framebuffer matrix falls
 * back to the exact pre-post direct path. A CPU [ParticleSystem] adds sparks,
 * death shards, foot dust and ambient motes inside the additive effects
 * batch; remote players got articulated legs with a swing that follows
 * smoothed snapshot speed, plus a small forward lean while running.
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
    private var shadowShader: ShaderProgram? = null

    /** Bloom / tonemap / grading pipeline; disables itself on weak drivers. */
    private val postFx = PostFx()
    private var surfW = 0
    private var surfH = 0

    /** One mesh per material id, so each can bind its own texture. */
    private val materialMeshes = HashMap<Int, Mesh>(6)
    private val spawnMesh = Mesh(hasNormals = true)
    // The avatar split so legs can swing while the upper body leans: body mesh
    // holds torso/shoulders/head/rifle; leg mesh is ONE leg with its origin at
    // the hip so a single rotateM in world space swings it like a pendulum.
    private val playerBodyMesh = Mesh(hasNormals = true)
    private val playerLegMesh = Mesh(hasNormals = true)
    private val weaponMesh = Mesh(hasNormals = true)
    // The sky needs UVs but not normals; the lit layout is the only one with
    // UVs, so it piggy-backs on it (a handful of unused floats per vertex).
    private val skyMesh = Mesh(hasNormals = true, drawMode = GLES30.GL_TRIANGLES)
    private val effectMesh = DynamicMesh(hasNormals = false, maxVertices = 6 * 64)
    // Contact shadows under players: rebuilt every frame, one alpha-blended
    // batch. Uses the lit layout purely because that is the one that carries
    // UVs (same trick as the sky mesh).
    private val shadowMesh = Mesh(hasNormals = true)
    private val shadowBuilder = MeshBuilder(withNormals = true, initialCapacity = 4096)

    // ---- textures -----------------------------------------------------------
    private val materialTex = HashMap<Int, Int>(6)
    private var playerTex = 0
    private var weaponTex = 0
    private var skyTex = 0
    private var whiteTex = 0

    // ---- particles ----------------------------------------------------------
    // One additive batch for sparks, death shards, foot dust and ambient motes.
    private val particleMesh = DynamicMesh(hasNormals = false, maxVertices = 6 * ParticleSystem.MAX)
    private val particles = ParticleSystem(particleMesh)

    /** Edge-detect for the local muzzle flash (weapon audio does the same). */
    private var prevMuzzleActive = false

    /** Last-frame alive flags per entity id, for death-burst detection. */
    private val prevAlive = BooleanArray(64)

    /** Emits foot dust at this interval while sprinting. */
    private var footDustTimer = 0f

    /** Smoothed per-player movement state for the lean / leg-swing animation. */
    private class PlayerAnim {
        var px = Float.NaN
        var pz = Float.NaN
        var speed = 0f
        var phase = 0f
        var lean = 0f
    }

    private val playerAnims = HashMap<Int, PlayerAnim>(16)

    private val camera = Camera()
    private val model = FloatArray(16)
    /** Second scratch matrix for the per-leg transforms in drawPlayer. */
    private val legModel = FloatArray(16)
    private val scratch = FloatArray(16)
    private val identity = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    // ---- frame state -------------------------------------------------------
    private val renderEntities = ArrayList<EntityState>(24)
    private val entityPool = ArrayList<EntityState>(24)
    private val tracers = ArrayList<Tracer>(48)

    private var lastFrameNanos = 0L
    private var timeSec = 0f
    private var bobPhase = 0f

    // Scratch for the shadow pass (ray start / ray dir). The renderer is
    // single-threaded, so two reusable Vec3s are plenty.
    private val shadowRay = Vec3()
    private val shadowDir = Vec3(0f, -1f, 0f)
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
        shadowShader = ShaderProgram("shadow", SHADOW_VS, SHADOW_FS)

        loadTextures()
        buildSkyMesh()
        buildArenaMesh()
        buildPlayerMeshes()
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
                "player=${playerBodyMesh.vertexCount} verts",
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
     * UV mapping (v2 — the fix for the "stretched sky" report):
     *  - u spans exactly 0..1 around the full 360°: the art is a 2:1 equirect
     *    panorama, so one wrap is the only non-distorted choice. The previous
     *    12× repeat mosaic was wrong for this texture.
     *  - v is flipped versus the naive bottom-up read: `GLUtils.texImage2D`
     *    stores bitmap row 0 (the image TOP, the dark zenith half) at v=0, so
     *    v=0 is the zenith and v=1 is the image BOTTOM, where the horizon glow
     *    band lives. The cylinder therefore maps its lower rim to v=1 (y just
     *    under the eye, so the glow ring peeks over the far walls exactly at
     *    the horizon line) and its top to v≈0.05; the zenith fan samples the
     *    clamped v=0 edge, i.e. the darkest part of the photo.
     *
     * The sun disc and the drifting cloud strata are procedural — they are
     * evaluated in SKY_FS from the look direction (vDir), not baked into this
     * mesh, so they never stretch regardless of shell geometry.
     */
    private fun buildSkyMesh() {
        val b = MeshBuilder(withNormals = true, initialCapacity = 8192)
        val radius = 110f
        val yTop = 62f
        val yBot = -10f
        val segs = 32
        // v=1 is the image bottom (the horizon glow), v=0 the dark zenith.
        val vBot = 1.0f
        val vTop = 0.045f
        for (i in 0 until segs) {
            val a0 = (i * Math.PI * 2 / segs).toFloat()
            val a1 = ((i + 1) * Math.PI * 2 / segs).toFloat()
            val x0 = cos(a0) * radius; val z0 = sin(a0) * radius
            val x1 = cos(a1) * radius; val z1 = sin(a1) * radius
            // One seamless wrap of the 2:1 panorama around the horizon.
            val u0 = i.toFloat() / segs
            val u1 = (i + 1).toFloat() / segs
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
                u0, vBot,
                u1, vBot,
                u1, vTop,
                u0, vTop,
            )
        }
        // Zenith disc: closes the hole at the top of the cylinder. It samples
        // a hard against the clamped v=0 edge — the darkest, most uniform row
        // of the zenith half, so the cap is effectively flat with no seams.
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
                0.47f, 0.006f,
                0.49f, 0.006f,
                0.49f, 0.030f,
                0.47f, 0.006f,
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
        surfW = width
        surfH = height
        // (Re)build the off-screen pipeline for the new size. On driver trouble
        // the call quietly leaves postFx.ready = false and nothing changes.
        if (surfaceReady) postFx.init(width, height)
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

        // Post pipeline: when up, everything below draws into the off-screen
        // scene buffer and endSceneAndCompose puts it on the display. When the
        // driver declined the FBO matrix, this branch disappears to nothing.
        if (postFx.ready) postFx.beginScene()

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
        sky.setVec3("uEye", camera.x, camera.y, camera.z)
        sky.setVec3("uSunDir", LIGHT_X, LIGHT_Y, LIGHT_Z)
        sky.setFloat("uTime", timeSec)
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
        lit.setFloat("uTime", timeSec)
        lit.setSampler("uTex", 0)
        lit.setMatrix("uModel", identity)
        lit.setVec3("uTint", 1f, 1f, 1f)

        for ((material, mesh) in materialMeshes) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, materialTex[material] ?: whiteTex)
            lit.setFloat("uTexGain", texGain(material))
            // The floor gets the slow scanning shimmer; walls keep still.
            lit.setFloat("uSweep", if (material == Material.FLOOR) 1.0f else 0.0f)
            lit.setFloat("uEmissive", 0.0f)
            mesh.draw()
        }

        // Spawn strips bind the white 1x1: detail layer = 1.0, pure palette.
        // A gentle breathing glow on top — the marker that says "safe room".
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, whiteTex)
        lit.setFloat("uTexGain", 1.0f)
        lit.setFloat("uSweep", 0.0f)
        lit.setFloat("uEmissive", 0.16f + 0.10f * sin(timeSec * 2.4f).coerceAtLeast(0f))
        spawnMesh.draw()
        lit.setFloat("uEmissive", 0.0f)

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

        // ---- player contact shadows -----------------------------------------
        // Soft blob shadows projected down onto whatever is under each visible
        // player (floor, ramp, crate top — one downward ray per body). The
        // blob fades and widens with drop height, which sells jump arcs with
        // zero shadow-mapping machinery. Baked AO on the floor (see
        // floorContactShadow) covers the static geometry, these quads cover
        // the actors.
        buildShadowBlobs(localId, spectatingId, playing)
        if (shadowMesh.vertexCount > 0) {
            val sh = shadowShader ?: return
            sh.use()
            sh.setMatrix("uViewProj", camera.viewProjection)
            GLES30.glEnable(GLES30.GL_BLEND)
            GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
            GLES30.glDepthMask(false)
            GLES30.glDisable(GLES30.GL_CULL_FACE)
            shadowMesh.draw()
            GLES30.glEnable(GLES30.GL_CULL_FACE)
            GLES30.glDepthMask(true)
            GLES30.glDisable(GLES30.GL_BLEND)
        }

        // ---- tracers, flashes & particles (one additive batch) ---------------
        state.collectTracers(nowMs, TRACER_TTL_MS, tracers)
        val muzzleActive = playing && nowMs < state.muzzleFlashUntilMs
        // Spawn muzzle sparks exactly once per shot: rising edge only.
        if (muzzleActive && !prevMuzzleActive) {
            val mx = camera.x + camera.rightX * 0.155f + camera.upX * -0.10f + camera.forwardX * 0.95f
            val my = camera.y + camera.rightY * 0.155f + camera.upY * -0.10f + camera.forwardY * 0.95f
            val mz = camera.z + camera.rightZ * 0.155f + camera.upZ * -0.10f + camera.forwardZ * 0.95f
            particles.muzzleSparks(mx, my, mz, camera.forwardX, camera.forwardY, camera.forwardZ)
        }
        prevMuzzleActive = muzzleActive

        updateParticles(dt, playing)
        buildEffects(nowMs, muzzleActive)
        particles.build(
            camera.rightX, camera.rightY, camera.rightZ,
            camera.upX, camera.upY, camera.upZ,
            camera.x, camera.y, camera.z,
        )
        if (effectMesh.vertexCount > 0 || particleMesh.vertexCount > 0) {
            flat.use()
            flat.setMatrix("uViewProj", camera.viewProjection)
            GLES30.glEnable(GLES30.GL_BLEND)
            GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE)
            GLES30.glDepthMask(false)
            GLES30.glDisable(GLES30.GL_CULL_FACE)
            effectMesh.draw()
            particleMesh.draw()
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

        // Post pipeline: resolve, bloom, grade, present — or a no-op fallback.
        if (postFx.ready) postFx.endSceneAndCompose()
    }

    /**
     * Physics + trigger feed for the particle pool for one rendered frame:
     * advances the pool, keeps the dust volume glued to the camera, notices
     * freshly-dead players (→ shard burst) and sprinkles foot dust under the
     * local sprint. All effects key off [renderEntities] which sampleInto
     * filled at the top of the frame.
     */
    private fun updateParticles(dt: Float, playing: Boolean) {
        particles.update(dt, camera.x, camera.y, camera.z)

        // Per-player movement records for leg swing / forward lean / foot dust.
        // Speed is smoothed exponentially so snapshot hops cannot kick the pose.
        if (playerAnims.size > 64) playerAnims.clear()
        val idleDt = dt.coerceAtLeast(1e-4f)
        for (i in renderEntities.indices) {
            val e = renderEntities[i]
            val a = playerAnims.getOrPut(e.id) { PlayerAnim() }
            var raw = 0f
            if (!a.px.isNaN() && idleDt > 0f) {
                val dx = e.x - a.px
                val dz = e.z - a.pz
                // >2.5 m in one frame is a respawn teleport, not a run.
                if (dx * dx + dz * dz < 2.5f * 2.5f) {
                    raw = kotlin.math.sqrt(dx * dx + dz * dz) / idleDt
                }
            }
            a.speed += (raw.coerceIn(0f, 8f) - a.speed) * (dt * 10f).coerceAtMost(1f)
            a.phase += a.speed * dt * 2.4f
            val target = (a.speed / 4.6f).coerceIn(0f, 1f)
            a.lean += (target - a.lean) * (dt * 8f).coerceAtMost(1f)
            a.px = e.x; a.pz = e.z

            // Death detection piggybacks on the same pass over the entities.
            val id = e.id
            if (id in 0 until prevAlive.size) {
                val was = prevAlive[id]
                if (was && !e.alive && playing) {
                    val (r, g, b) = playerPalette(e)
                    particles.deathBurst(e.x, e.y, e.z, r, g, b)
                }
                prevAlive[id] = e.alive
            }
        }

        // Foot dust under a fast-moving local player.
        if (playing && state.alive) {
            footDustTimer -= dt
            val local = findLocalEntity()
            if (local != null && footDustTimer <= 0f) {
                val anim = playerAnims[local.id]
                // Local anim records are kept for the shadow pass; speed > 2 m/s
                // means an actual run, not a crouch-shuffle.
                if (anim != null && anim.speed > 2.0f) {
                    particles.footDust(local.x, local.y, local.z)
                    footDustTimer = 0.085f
                } else {
                    footDustTimer = 0.02f
                }
            }
        }
    }

    private fun findLocalEntity(): EntityState? {
        val id = state.localPlayerId
        for (i in renderEntities.indices) {
            val e = renderEntities[i]
            if (e.id == id) return e
        }
        return null
    }

    /**
     * Rebuilds the per-frame contact-shadow batch into [shadowMesh].
     *
     * Each visible, alive player gets a radial fan quads-sheet at the floor
     * height under their feet, found with a single downward ray against the
     * collision set (so the shadow lands on crates and ramps during movement,
     * not always on y=0). The fan's centre vertex carries the full shadow
     * alpha in its red channel and the rim vertices carry zero, so the GPU
     * interpolates a soft round falloff — no texture, no extra pass state.
     *
     * The shadow slides slightly *away* from the sun azimuth as the body
     * rises (parallax of a point shadow), and fades + widens with drop
     * height: that is what gives jumps their visual lift.
     */
    /**
     * Baked contact-shadow factor for one floor tile centred at (cx, cz):
     * 1.0 far away from any solid brush, falling off smoothly to 0.55 right
     * against one, over a 1.7 m ramp. This is the workhorse static "shadow":
     * every wall, crate, pillar and piece of cover darkens the floor it
     * stands on, baked once into the arena vertex colours (and therefore
     * free at runtime, and automatically rebuilt on map rotation). The
     * dynamic blob shadows under players are layered on top separately.
     */
    private fun floorContactShadow(cx: Float, cz: Float): Float {
        var nearest = Float.MAX_VALUE
        for (brush in arena.brushes) {
            if (!brush.solid || brush.material == Material.FLOOR) continue
            val b = brush.box
            if (b.minY > 0.25f) continue // floating props cast no floor AO
            val dx = maxOf(b.minX - cx, 0f, cx - b.maxX)
            val dz = maxOf(b.minZ - cz, 0f, cz - b.maxZ)
            val d = kotlin.math.sqrt(dx * dx + dz * dz).toFloat()
            if (d < nearest) nearest = d
        }
        if (nearest == Float.MAX_VALUE) return 1f
        val t = (1f - nearest / 1.7f).coerceIn(0f, 1f)
        return 1f - 0.45f * t * t
    }

    private fun buildShadowBlobs(localId: Int, spectatingId: Int, playing: Boolean) {
        shadowBuilder.clear()
        // Upload of the (possibly empty) batch happens at the bottom so the
        // previous frame's shadows never linger after a state change.
        if (!playing) {
            shadowMesh.upload(shadowBuilder.raw(), 0)
            return
        }
        for (i in renderEntities.indices) {
            val e = renderEntities[i]
            if (!e.alive) continue
            // First-person bodies are not drawn, so neither are their shadows.
            if (e.id == localId || e.id == spectatingId) continue

            shadowRay.set(e.x, e.y + 0.25f, e.z)
            val d = RayMath.raycastArena(shadowRay, shadowDir, SHADOW_MAX_DROP, arena)
            if (d >= SHADOW_MAX_DROP - 0.05f) continue // nothing solid underneath
            val drop = d - 0.25f
            // Slide opposite the sun as the drop grows — a point light drags
            // the shadow sideways; keeps the blob glued to the feet on the
            // ground and trails naturally through a jump.
            val cx = e.x - LIGHT_X * drop * 0.45f
            val cz = e.z - LIGHT_Z * drop * 0.45f
            val floorY = shadowRay.y - d + 0.03f
            val fade = (1f - drop / SHADOW_MAX_DROP).coerceIn(0f, 1f)
            if (fade <= 0.02f) continue
            val alpha = 0.34f * fade + 0.06f
            val radius = 0.55f + drop * 0.055f

            // Radial fan as triangle soup: centre vertex opaque-ish, rim zero —
            // the interpolator does the soft falloff.
            var prevX = cx + radius
            var prevZ = cz
            for (k in 1..SHADOW_SEGMENTS) {
                val a = (k * Math.PI * 2 / SHADOW_SEGMENTS).toFloat()
                val nx = cx + cos(a) * radius
                val nz = cz + sin(a) * radius
                shadowBuilder.vertex(cx, floorY, cz, 0f, 1f, 0f, alpha, 0f, 0f)
                shadowBuilder.vertex(prevX, floorY, prevZ, 0f, 1f, 0f, 0f, 0f, 0f)
                shadowBuilder.vertex(nx, floorY, nz, 0f, 1f, 0f, 0f, 0f, 0f)
                prevX = nx
                prevZ = nz
            }
        }
        shadowMesh.upload(shadowBuilder.raw(), shadowBuilder.floatCount)
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

    /**
     * Pre-health team palette for an entity — shared by the body renderer
     * (which then darkens it by hp) and the death shard burst.
     */
    private fun playerPalette(e: EntityState): Triple<Float, Float, Float> {
        if (state.mode == GameMode.TDM) {
            var (r, g, b) = when (e.teamEnum) {
                Team.RED -> Triple(0.88f, 0.32f, 0.26f)
                Team.BLUE -> Triple(0.30f, 0.56f, 0.92f)
                else -> Triple(0.80f, 0.80f, 0.80f)
            }
            // Teammates are tinted toward green so a glance is enough in a firefight.
            if (e.teamEnum == state.localTeam && e.id != state.localPlayerId) {
                r = r * 0.45f + 0.20f
                g = g * 0.45f + 0.62f
                b = b * 0.45f + 0.30f
            }
            return Triple(r, g, b)
        }
        return if (e.type == EntityType.BOT) {
            Triple(0.86f, 0.47f, 0.20f)
        } else {
            Triple(0.90f, 0.30f, 0.28f)
        }
    }

    /**
     * The avatar as two articulated parts: swinging legs (one mesh drawn twice
     * around each hip) and a leaning upper body. Movement data comes from the
     * [playerAnims] records that updateParticles maintains for every entity,
     * so interpolation already smoothed this out of snapshot hops.
     */
    private fun drawPlayer(lit: ShaderProgram, e: EntityState) {
        val crouchScale = if (e.crouching) {
            GameConstants.PLAYER_CROUCH_HEIGHT / GameConstants.PLAYER_HEIGHT
        } else {
            1f
        }
        val anim = playerAnims[e.id]

        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, e.x, e.y, e.z)
        // Model faces -Z at yaw 0, matching MathUtil.horizontalForward.
        Matrix.rotateM(model, 0, -e.yaw, 0f, 1f, 0f)
        if (crouchScale != 1f) Matrix.scaleM(model, 0, 1f, crouchScale, 1f)

        val (r0, g0, b0) = playerPalette(e)
        // Wounded enemies visibly darken: free feedback that your shots landed.
        val hp = MathUtil.clamp(e.health / GameConstants.MAX_HEALTH.toFloat(), 0f, 1f)
        val k = 0.55f + 0.45f * hp

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, playerTex)
        lit.setFloat("uTexGain", 1.82f)

        // ---- legs -------------------------------------------------------------
        // Swing amplitude grows with speed up to a full run; phase advances with
        // distance travelled so the cadence matches how fast the feet move.
        if (anim != null && !e.crouching) {
            val swingRad = kotlin.math.sin(anim.phase.toDouble()).toFloat() *
                (anim.speed / 4.6f).coerceIn(0f, 1f) * 0.48f
            lit.setVec3("uTint", r0 * k * 0.72f, g0 * k * 0.72f, b0 * k * 0.72f)
            Matrix.setIdentityM(model, 0)
            Matrix.translateM(model, 0, e.x, e.y, e.z)
            Matrix.rotateM(model, 0, -e.yaw, 0f, 1f, 0f)
            // left hip
            System.arraycopy(model, 0, legModel, 0, 16)
            Matrix.translateM(legModel, 0, -0.115f, 0.84f, 0f)
            Matrix.rotateM(legModel, 0, swingRad * 57.29578f, 1f, 0f, 0f)
            lit.setMatrix("uModel", legModel)
            playerLegMesh.draw()
            // right hip, antiphase: rebuild the base again
            System.arraycopy(model, 0, legModel, 0, 16)
            Matrix.translateM(legModel, 0, 0.115f, 0.84f, 0f)
            Matrix.rotateM(legModel, 0, -swingRad * 57.29578f, 1f, 0f, 0f)
            lit.setMatrix("uModel", legModel)
            playerLegMesh.draw()
        } else {
            // Crouching / unknown motion: static legs under the torso.
            lit.setVec3("uTint", r0 * k * 0.72f, g0 * k * 0.72f, b0 * k * 0.72f)
            Matrix.setIdentityM(legModel, 0)
            Matrix.translateM(legModel, 0, e.x, e.y, e.z)
            Matrix.rotateM(legModel, 0, -e.yaw, 0f, 1f, 0f)
            if (crouchScale != 1f) Matrix.scaleM(legModel, 0, 1f, crouchScale, 1f)
            Matrix.translateM(legModel, 0, -0.115f, 0.84f, 0f)
            lit.setMatrix("uModel", legModel)
            playerLegMesh.draw()
            Matrix.translateM(legModel, 0, 0.23f, 0f, 0f)
            lit.setMatrix("uModel", legModel)
            playerLegMesh.draw()
        }

        // ---- upper body --------------------------------------------------------
        // Lean into the run direction: a couple of degrees of forward pitch.
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, e.x, e.y, e.z)
        Matrix.rotateM(model, 0, -e.yaw, 0f, 1f, 0f)
        if (crouchScale != 1f) Matrix.scaleM(model, 0, 1f, crouchScale, 1f)
        if (anim != null) Matrix.rotateM(model, 0, -anim.lean * 7f, 1f, 0f, 0f)
        lit.setMatrix("uModel", model)
        lit.setVec3("uTint", r0 * k, g0 * k, b0 * k)
        playerBodyMesh.draw()

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

            // Little sparks flicking off the bullet's nose while it flies.
            particles.tracerSpark(t.x1, t.y1, t.z1, t.local)
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

        // Tiled floor: still the classic 4 m checker (checker parity is read
        // off the 4 m block grid), but emitted as 2 m quads so the baked
        // contact shadow around walls/crates (floorContactShadow below)
        // shades in a smooth ramp instead of block-wide steps. One texture
        // repeat every 2 metres as before.
        val tile = 2f
        val floorB = builderFor(Material.FLOOR)
        var z = arena.minZ
        while (z < arena.maxZ - 0.001f) {
            val z1 = min(z + tile, arena.maxZ)
            var x = arena.minX
            while (x < arena.maxX - 0.001f) {
                val x1 = min(x + tile, arena.maxX)
                val blockX = ((x - arena.minX + 0.01f).toInt() / 2)
                val blockZ = ((z - arena.minZ + 0.01f).toInt() / 2)
                val dark = ((blockX + blockZ) and 1) == 0
                val c = if (dark) 0.148f else 0.178f
                val sh = floorContactShadow((x + x1) * 0.5f, (z + z1) * 0.5f)
                floorB.floorTile(
                    x, z, x1, z1, 0f,
                    c * sh, c * 1.06f * sh, c * 1.22f * sh,
                    uvScale = 2f,
                )
                x = x1
            }
            z = z1
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
                // Baked bounce of the floor neon onto vertical faces near the
                // ground; tiny on ramps, strongest on walls and pillars.
                glowBottom = when (brush.material) {
                    Material.WALL -> 0.85f
                    Material.PILLAR -> 0.70f
                    Material.COVER, Material.CRATE -> 0.45f
                    else -> 0.15f
                },
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
    private fun buildPlayerMeshes() {
        // Upper body: torso, shoulders, head, visor, rifle.
        val body = MeshBuilder(withNormals = true, initialCapacity = 4096)
        // torso
        body.box(-0.25f, 0.84f, -0.17f, 0.25f, 1.44f, 0.17f, 1.00f, 1.00f, 1.00f)
        // shoulders
        body.box(-0.31f, 1.20f, -0.14f, 0.31f, 1.42f, 0.14f, 0.86f, 0.86f, 0.90f)
        // head
        body.box(-0.125f, 1.44f, -0.125f, 0.125f, 1.71f, 0.125f, 1.12f, 1.12f, 1.12f)
        // visor (front is -Z); extra bright so post bloom picks it up as a glow strip
        body.box(-0.10f, 1.52f, -0.145f, 0.10f, 1.62f, -0.120f, 1.9f, 1.9f, 2.0f)
        // rifle held on the right side
        body.box(0.17f, 1.06f, -0.62f, 0.29f, 1.19f, -0.10f, 0.22f, 0.22f, 0.24f)
        body.box(0.19f, 1.19f, -0.34f, 0.27f, 1.26f, -0.14f, 0.18f, 0.18f, 0.20f)
        playerBodyMesh.upload(body.raw(), body.floatCount)

        // ONE leg with the hip at the origin hanging down -Y: a rotation around
        // the X axis in world space swings it like a pendulum. Drawn twice, one
        // per hip offset, in opposite phase.
        val leg = MeshBuilder(withNormals = true, initialCapacity = 1024)
        leg.box(-0.075f, -0.84f, -0.115f, 0.015f, 0f, 0.035f, 0.68f, 0.68f, 0.72f)
        playerLegMesh.upload(leg.raw(), leg.floatCount)
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
        shadowShader?.dispose()
        shadowShader = null
        for (mesh in materialMeshes.values) mesh.dispose()
        materialMeshes.clear()
        spawnMesh.dispose()
        skyMesh.dispose()
        playerBodyMesh.dispose()
        playerLegMesh.dispose()
        weaponMesh.dispose()
        effectMesh.dispose()
        particleMesh.dispose()
        shadowMesh.dispose()
        textureLoader.dispose()
        postFx.dispose()
    }

    companion object {
        private const val TRACER_TTL_MS = 90L

        /** Radial fan resolution for each player's contact shadow. */
        private const val SHADOW_SEGMENTS = 14

        /** Shadows fade to nothing over this drop (m); also clamps raycasts. */
        private const val SHADOW_MAX_DROP = 5.5f

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
            uniform float uTime;
            // Sweep amplitude of the scanning shimmer running along the floor
            // (1 on floor tiles, 0 on every other material's draw).
            uniform float uSweep;
            // Pure additive glow in vertex-colour space (spawn strip breathing).
            uniform float uEmissive;
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
                // A scanner shimmer sweeping diagonally across the arena floor:
                // one cool band roughly 12 m wide, reappearing every ~8 s.
                if (uSweep > 0.0) {
                    float wave = sin(vWorld.x * 0.42 + vWorld.z * 0.31 - uTime * 1.35);
                    float band = smoothstep(0.72, 0.96, wave) * uSweep;
                    c += vec3(0.05, 0.10, 0.18) * band * (0.4 + 0.6 * detail.g);
                }
                if (uEmissive > 0.0) {
                    c += vColor * uEmissive;
                }
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
         *
         * vDir carries the eye→fragment ray so the fragment shader can layer
         * procedural elements (sun, clouds) on top of the panorama without
         * them being affected by the shell's UV distortion.
         */
        private const val SKY_VS = """#version 300 es
            uniform mat4 uViewProj;
            uniform vec3 uEye;
            in vec3 aPos;
            in vec2 aUv;
            out vec2 vUv;
            out vec3 vDir;
            void main() {
                vUv  = aUv;
                vDir = aPos - uEye;
                gl_Position = uViewProj * vec4(aPos, 1.0);
            }
        """

        /**
         * Panorama + procedural sun + drifting cloud strata:
         *  - Sun: a hot white disc exactly along uSunDir (the same direction
         *    the world lighting uses, so shadows and the sky agree), wrapped
         *    in three rings of warm halo. Clouds dim it a little when they
         *    drift across.
         *  - Clouds: cheap 2-octave value noise sampled in wrap-proof polar
         *    coordinates (cos/sin of the azimuth), faded out toward the
         *    horizon glow and the zenith, scrolled slowly with uTime. They
         *    are ALPHA'd over the photo, not replacing it, so the star field
         *    still glints through the gaps — night-sky clouds, not day.
         */
        private const val SKY_FS = """#version 300 es
            precision mediump float;
            uniform sampler2D uTex;
            uniform vec3  uSunDir;
            uniform float uTime;
            in vec2 vUv;
            in vec3 vDir;
            out vec4 fragColor;

            // Cheap deterministic value noise — one [0,1] cell → 4 hashes.
            float hash(vec2 p) {
                return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453123);
            }
            float vnoise(vec2 p) {
                vec2 i = floor(p);
                vec2 f = fract(p);
                vec2 u = f * f * (3.0 - 2.0 * f);
                float a = hash(i);
                float b = hash(i + vec2(1.0, 0.0));
                float c = hash(i + vec2(0.0, 1.0));
                float d = hash(i + vec2(1.0, 1.0));
                return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
            }

            void main() {
                vec3 col = texture(uTex, vUv).rgb;
                vec3 dir = normalize(vDir);
                float sunDot = dot(dir, normalize(uSunDir));

                // ---- drifting cloud band ---------------------------------
                float cloud = 0.0;
                if (dir.y > 0.01) {
                    float az = atan(dir.z, dir.x);
                    // Polar ring coordinates rhug the dome and wrap at ±π with
                    // no seam (cos/sin are periodic); the radius shrinks toward
                    // the zenith so the pattern hits it with zero area, i.e.
                    // no pinwheel artifact. uTime slides the whole ring.
                    float ringR = 1.8 - clamp(dir.y, 0.0, 1.0) * 1.1;
                    vec2 cuv = vec2(cos(az), sin(az)) * ringR
                             + vec2(uTime * 0.0058, uTime * -0.0035);
                    float n = vnoise(cuv * 1.35) * 0.60
                            + vnoise(cuv * 3.10 + 17.7) * 0.40;
                    float band = smoothstep(0.03, 0.20, dir.y)
                               * (1.0 - smoothstep(0.50, 0.85, dir.y));
                    cloud = smoothstep(0.52, 0.80, n) * band;
                    // Cool, dark cloud body with a warm rim on the sun side:
                    // fits the existing fog palette and the neon horizon.
                    float sunSide = pow(max(sunDot, 0.0), 3.0);
                    vec3 cloudCol = vec3(0.11, 0.15, 0.21)
                                  + vec3(0.40, 0.34, 0.22) * sunSide;
                    col = mix(col, cloudCol, cloud * 0.72);
                }

                // ---- sun --------------------------------------------------
                // Smooth angular falls off from angular thresholds (dot = cos).
                float disc   = smoothstep(0.99935, 0.99978, sunDot);
                float corona = pow(max(sunDot, 0.0), 340.0) * 0.85;
                float halo   = pow(max(sunDot, 0.0), 14.0) * 0.17;
                // White-hot core, warm halo — stands out against the night
                // palette without cold-shifting the arena's teal fog accent.
                vec3 sunCol = vec3(1.00, 0.95, 0.86);
                vec3 sun = sunCol * (disc * 3.4 + corona + halo);
                col += sun * (1.0 - cloud * 0.65);

                fragColor = vec4(col, 1.0);
            }
        """

        /**
         * Contact-shadow pass: a single batch of radial fans (one per visible
         * player). Vertex colour RED carries coverage — 1 at the fan centre,
         * 0 on the rim — so the rasteriser itself makes the smooth round
         * falloff. Drawn with straight alpha blending, no texture unit, right
         * after the player draw loop.
         */
        private const val SHADOW_VS = """#version 300 es
            uniform mat4 uViewProj;
            in vec3 aPos;
            in vec3 aColor;
            out vec3 vColor;
            void main() {
                vColor = aColor;
                gl_Position = uViewProj * vec4(aPos, 1.0);
            }
        """

        private const val SHADOW_FS = """#version 300 es
            precision mediump float;
            in vec3 vColor;
            out vec4 fragColor;
            void main() {
                // Slightly blue-black so shadows read as ambient occlusion in
                // the fog scheme rather than as pure soot.
                fragColor = vec4(0.012, 0.016, 0.030, clamp(vColor.r, 0.0, 1.0));
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
