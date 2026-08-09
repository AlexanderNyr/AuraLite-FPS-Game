package com.lanfps.client

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import com.lanfps.shared.ArenaDef
import com.lanfps.shared.EntityState
import com.lanfps.shared.EntityType
import com.lanfps.shared.GrenadeState
import com.lanfps.shared.PickupKind
import com.lanfps.shared.PickupState
import com.lanfps.shared.GameConstants
import com.lanfps.shared.GameMode
import com.lanfps.shared.Material
import com.lanfps.shared.MathUtil
import com.lanfps.shared.RayMath
import com.lanfps.shared.Team
import com.lanfps.shared.TimeOfDay
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
 * The sky is an inward-facing cylinder textured with the generated panorama
 * (daylight gradient by default; a starfield night panorama ships alongside
 * for the server-picked night preset — see below), completed in-shader by a
 * sun disc (anchored to the diffuse light direction so shadows and sun
 * agree) and a slowly drifting procedural cloud layer. Shadows come in three
 * cheap layers: baked contact AO of floor tiles near static geometry, baked
 * SUN shadows (a ray from every floor tile toward the sun, AABB-tested and
 * blurred across tiles), and per-frame blob quads under live players that
 * stretch along the sun azimuth and fade with drop height.
 *
 * The lit shader carries a Blinn sun-specular (per-material strength) and a
 * dynamic point-light hook fed by muzzle flashes and grenade blasts, so
 * gunfire briefly lights up nearby walls. Static neon trim ridges cap the
 * tall walls and pillars. A depth-tested sun flare billboard (+ two ghosts)
 * rides the additive effects batch.
 *
 * P8: the SERVER picks the lighting preset (timeOfDay=day|night). The client
 * hot-swaps sky texture, fog, ambient, cloud/sun tints and the PostFx
 * grading constants when the value in [ClientGameState.timeOfDay] changes.
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
    // P4 content visuals: pads (static ring per arena), pickup cube +
    // grenade ball (reused meshes drawn per item), plus client-side blast
    // detection that turns a vanishing grenade into particles and sound.
    private val padMesh = Mesh(hasNormals = true)
    private val pickupCube = Mesh(hasNormals = true)
    private val grenodeMesh = Mesh(hasNormals = true)
    /** P8-6: static neon trim ridges capping tall walls and pillars. */
    private val trimMesh = Mesh(hasNormals = true)
    private val prevGrenades = HashMap<Int, FloatArray>(8)
    private var pickupYaw = 0f
    // The avatar split so legs can swing while the upper body leans: body mesh
    // holds torso/shoulders/head/rifle; leg mesh is ONE leg with its origin at
    // the hip so a single rotateM in world space swings it like a pendulum.
    private val playerBodyMesh = Mesh(hasNormals = true)
    private val playerLegMesh = Mesh(hasNormals = true)
    private val weaponMesh = Mesh(hasNormals = true)
    // The sky needs UVs but not normals; the lit layout is the only one with
    // UVs, so it piggy-backs on it (a handful of unused floats per vertex).
    private val skyMesh = Mesh(hasNormals = true, drawMode = GLES30.GL_TRIANGLES)
    // P8-7: bumped +96 verts so the sun-flare fans always fit beside a full
    // tracer burst (DynamicMesh silently drops overflow).
    private val effectMesh = DynamicMesh(hasNormals = false, maxVertices = 6 * 80)
    // Contact shadows under players: rebuilt every frame, one alpha-blended
    // batch. Uses the lit layout purely because that is the one that carries
    // UVs (same trick as the sky mesh).
    private val shadowMesh = Mesh(hasNormals = true)
    private val shadowBuilder = MeshBuilder(withNormals = true, initialCapacity = 4096)

    // ---- textures -----------------------------------------------------------
    private val materialTex = HashMap<Int, Int>(6)
    private var playerTex = 0
    private var weaponTex = 0
    private var grenadeTex = 0
    private var skyTexDay = 0
    private var skyTexNight = 0
    private var whiteTex = 0

    // ---- P8 lighting presets (day default; server can pick night) ----------
    /** Which preset the uniforms currently hold. */
    private var presetNight = false
    private var fogR = 0.780f
    private var fogG = 0.845f
    private var fogB = 0.930f
    private var fogDensityPlay = 0.0075f
    private var fogDensityMenu = 0.0045f
    private var ambientLight = 0.66f
    private var trimEmissive = 0.30f
    /** Night scenes let gunfire light the world harder — contrast sells it. */
    private var pointGainScale = 1.0f
    private var sunTintR = 1.0f
    private var sunTintG = 0.96f
    private var sunTintB = 0.88f
    private var sunGain = 1.0f
    private var cloudR = 0.93f
    private var cloudG = 0.95f
    private var cloudB = 1.00f

    // ---- P8-3 dynamic point light (muzzle flash / grenade blast) -----------
    private var blastFromMs = Long.MIN_VALUE
    private var blastX = 0f
    private var blastY = 0f
    private var blastZ = 0f

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
    /** Scratch for the P8-1 baked sun-shadow rays (build-time only). */
    private val sunRay = Vec3()
    private val sunDirVec = Vec3()
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
        GLES30.glClearColor(fogR, fogG, fogB, 1f)
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
        // P9: pineapple-shell texture for the spherical grenade.
        grenadeTex = textureLoader.load("grenade.png")
        // P8: day is the default; the night panorama ships alongside for the
        // server-picked night preset (both are tiny generated panoramas).
        skyTexDay = textureLoader.load("sky.jpg", GLES30.GL_REPEAT, GLES30.GL_CLAMP_TO_EDGE)
        skyTexNight = textureLoader.load("sky_night.jpg", GLES30.GL_REPEAT, GLES30.GL_CLAMP_TO_EDGE)
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
     * A huge inward-facing cylinder plus a zenith fan, built once. The sky is
     * NOT fogged and NOT lit; the panorama is a constant-along-u daylight
     * gradient, so it wraps seamlessly by construction. Radius 110 sits well
     * inside the 260 m far plane, and the shell is drawn with depth-writes
     * off so the world always occludes it.
     *
     * UV mapping (v2 — the fix for the "stretched sky" report):
     *  - u spans exactly 0..1 around the full 360°: the art is a 2:1 equirect
     *    panorama, so one wrap is the only non-distorted choice. The previous
     *    12× repeat mosaic was wrong for this texture.
     *  - v is flipped versus the naive bottom-up read: `GLUtils.texImage2D`
     *    stores bitmap row 0 (the image TOP, the deep-blue zenith) at v=0, so
     *    v=0 is the zenith and v=1 is the image BOTTOM, where the pale horizon
     *    haze band lives. The cylinder therefore maps its lower rim to v=1 (y
     *    just under the eye, so the haze ring meets the far walls exactly at
     *    the horizon line — and it is precisely the world fog colour, so the
     *    blend is invisible); the zenith fan samples the clamped v≈0 edge,
     *    the flat deep-blue cap.
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
        // v=1 is the image bottom (the horizon haze), v=0 the blue zenith.
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
        // hard against the clamped v=0 edge, which the generator left
        // perfectly flat, so the cap is uniform with no seams.
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

    /**
     * P8: hot-swap between the day and night lighting presets. Touches only
     * uniform-level state (fog/ambient/sun/cloud tints, trim glow, grading
     * constants) — meshes and their baked albedos/AO stay shared, which is
     * exactly why the swap costs nothing per vertex.
     */
    private fun applyLightPreset(night: Boolean) {
        if (night) {
            fogR = 0.045f; fogG = 0.062f; fogB = 0.085f
            fogDensityPlay = 0.0090f; fogDensityMenu = 0.0055f
            ambientLight = 0.22f
            trimEmissive = 0.55f
            pointGainScale = 1.6f
            sunTintR = 0.72f; sunTintG = 0.82f; sunTintB = 1.05f
            sunGain = 0.5f
            cloudR = 0.085f; cloudG = 0.115f; cloudB = 0.170f
        } else {
            fogR = 0.780f; fogG = 0.845f; fogB = 0.930f
            fogDensityPlay = 0.0075f; fogDensityMenu = 0.0045f
            ambientLight = 0.66f
            trimEmissive = 0.30f
            pointGainScale = 1.0f
            sunTintR = 1.0f; sunTintG = 0.96f; sunTintB = 0.88f
            sunGain = 1.0f
            cloudR = 0.93f; cloudG = 0.95f; cloudB = 1.0f
        }
        postFx.applyPreset(night)
        GLES30.glClearColor(fogR, fogG, fogB, 1f)
        AndroidLog.i("lighting preset -> ${if (night) "NIGHT" else "DAY"}")
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

        // P8: the server can flip the lighting preset at any time (lobby
        // broadcast) — catch the change here, on the GL thread.
        val wantNight = state.timeOfDay == TimeOfDay.NIGHT
        if (wantNight != presetNight) {
            presetNight = wantNight
            applyLightPreset(wantNight)
        }

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
        sky.setVec3("uSunTint", sunTintR, sunTintG, sunTintB)
        sky.setFloat("uSunGain", sunGain)
        sky.setVec3("uCloudCol", cloudR, cloudG, cloudB)
        sky.setFloat("uTime", timeSec)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, if (presetNight) skyTexNight else skyTexDay)
        GLES30.glDepthMask(false)
        skyMesh.draw()
        GLES30.glDepthMask(true)

        // ---- world ---------------------------------------------------------
        lit.use()
        lit.setMatrix("uViewProj", camera.viewProjection)
        lit.setVec3("uLightDir", LIGHT_X, LIGHT_Y, LIGHT_Z)
        lit.setVec3("uFogColor", fogR, fogG, fogB)
        lit.setFloat("uFogDensity", if (playing) fogDensityPlay else fogDensityMenu)
        lit.setVec3("uEye", camera.x, camera.y, camera.z)
        // Midday scattering: the hemisphere term carries most of the light,
        // the directional sun adds contrast on the lit faces. (Night preset
        // drops this hard — same albedos then read as moonlit.)
        lit.setFloat("uAmbient", ambientLight)
        lit.setSampler("uTex", 0)
        lit.setMatrix("uModel", identity)
        lit.setVec3("uTint", 1f, 1f, 1f)
        updatePointLight(lit, nowMs, playing)

        for ((material, mesh) in materialMeshes) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, materialTex[material] ?: whiteTex)
            lit.setFloat("uTexGain", texGain(material))
            lit.setFloat("uSpec", specFor(material))
            lit.setFloat("uEmissive", 0.0f)
            mesh.draw()
        }

        // Spawn strips bind the white 1x1: detail layer = 1.0, pure palette.
        // P8: static glow (the pulsing "breathing" read as light waves).
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, whiteTex)
        lit.setFloat("uTexGain", 1.0f)
        lit.setFloat("uSpec", 0.0f)
        lit.setFloat("uEmissive", 0.22f)
        spawnMesh.draw()

        // P8-6: static neon trim ridges on tall walls/pillars. Constant glow
        // (no pulsing): bright cyan by day, a neon skyline by night.
        if (trimMesh.vertexCount > 0) {
            lit.setFloat("uSpec", 0.0f)
            lit.setFloat("uEmissive", trimEmissive)
            trimMesh.draw()
            lit.setFloat("uEmissive", 0.0f)
        } else {
            lit.setFloat("uEmissive", 0.0f)
        }

        // P4 content trio, drawn only while we're actually in a match world:
        // powered pads with a breathing pulse, spinning pickup cubes and the
        // in-flight grenades. Menu/backdrop views keep the scene calm.
        if (playing) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, whiteTex)
            lit.setFloat("uTexGain", 1.0f)
            lit.setVec3("uTint", 1f, 1f, 1f)

            // Pads: static ring, constant powered glow (no pulse waves).
            if (padMesh.vertexCount > 0) {
                lit.setFloat("uSpec", 0.25f)
                lit.setFloat("uEmissive", 0.42f)
                padMesh.draw()
                lit.setFloat("uEmissive", 0.0f)
                lit.setFloat("uSpec", 0.0f)
            }

            drawPickups(lit, playing, dt)
            drawGrenades(lit, nowMs)
        }

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

        // Post pipeline: resolve, bloom, grade, present — or a no-op fallback.
        if (postFx.ready) postFx.endSceneAndCompose()

        // ---- weapon viewmodel: its own final pass straight to the screen ----
        // P9: the gun no longer renders into the offscreen MSAA buffer but as a
        // dedicated pass AFTER the post pipeline (and, without post, right after
        // the effects batch). That removes the whole flicker class: the
        // viewmodel cannot be eaten by an MSAA resolve hiccup, the cleared
        // depth stops it sinking into nearby walls, and NaN-guarded inputs in
        // updateGameCamera/drawWeapon keep one bad float from dropping the
        // whole matrix for a frame.
        if (playing && state.alive) {
            GLES30.glEnable(GLES30.GL_DEPTH_TEST)
            GLES30.glDisable(GLES30.GL_BLEND)
            GLES30.glEnable(GLES30.GL_CULL_FACE)
            GLES30.glDepthMask(true)
            GLES30.glClear(GLES30.GL_DEPTH_BUFFER_BIT)
            drawWeapon(lit)
        }
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
            // Sunlit contact shadows need more contrast than the old night
            // ones: a bright floor swallows a faint blob whole.
            val alpha = 0.40f * fade + 0.07f
            val radius = 0.55f + drop * 0.055f

            // P8-5: ellipse stretched along the sun azimuth — a standing body
            // throws an oblong shadow away from the sun, not a coin. The fan
            // centre is nudged the same way so the feet sit inside the shadow.
            val ecx = cx + SHADOW_DIR_X * 0.10f
            val ecz = cz + SHADOW_DIR_Z * 0.10f
            val rAlong = radius * 1.22f
            val rAcross = radius * 0.88f

            // Radial fan as triangle soup: centre vertex opaque-ish, rim zero —
            // the interpolator does the soft falloff.
            var prevX = ecx + SHADOW_PERP_X * rAcross
            var prevZ = ecz + SHADOW_PERP_Z * rAcross
            for (k in 1..SHADOW_SEGMENTS) {
                val a = (k * Math.PI * 2 / SHADOW_SEGMENTS).toFloat()
                val cA = cos(a) * rAcross
                val sA = sin(a) * rAlong
                val nx = ecx + SHADOW_PERP_X * cA + SHADOW_DIR_X * sA
                val nz = ecz + SHADOW_PERP_Z * cA + SHADOW_DIR_Z * sA
                shadowBuilder.vertex(ecx, floorY, ecz, 0f, 1f, 0f, alpha, 0f, 0f)
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

    // ---- P4 worlds: pickups, grenades and their blasts ------------------

    /**
     * Draws every live pickup as a slowly spinning, hovering glow cube tinted
     * by kind. The list travels fully with each snapshot — no interpolation:
     * the markers are static except for spin/bob, done client-side.
     */
    private fun drawPickups(lit: ShaderProgram, playing: Boolean, dt: Float) {
        val snap = state.snapshots.latest ?: return
        val pickups = snap.pickups
        if (pickups.isEmpty()) return
        pickupYaw += dt * 70f

        lit.setMatrix("uModel", identity)
        lit.setFloat("uSpec", 0.35f)
        var any = false
        for (p in pickups) {
            if (!p.active) continue
            any = true
            val (r, g, b) = pickupTint(p.kind)
            lit.setVec3("uTint", r, g, b)
            lit.setFloat("uEmissive", 0.52f)

            val bob = 0.42f + 0.09f * sin(timeSec * 2.1f + p.z).toFloat()
            Matrix.setIdentityM(model, 0)
            Matrix.translateM(model, 0, p.x, (p.y + bob), p.z)
            Matrix.rotateM(model, 0, pickupYaw, 0f, 1f, 0f)
            lit.setMatrix("uModel", model)
            pickupCube.draw()
        }
        if (any) {
            lit.setFloat("uEmissive", 0.0f)
            lit.setFloat("uSpec", 0.0f)
            lit.setVec3("uTint", 1f, 1f, 1f)
            lit.setMatrix("uModel", identity)
        }
    }

    private fun pickupTint(kindWire: Int): Triple<Float, Float, Float> {
        return when (PickupKind.fromWire(kindWire)) {
            PickupKind.HEALTH -> Triple(0.34f, 0.90f, 0.46f)
            PickupKind.ARMOR -> Triple(0.32f, 0.66f, 0.98f)
            PickupKind.SMG -> Triple(0.98f, 0.80f, 0.30f)
            PickupKind.GRENADES -> Triple(0.98f, 0.58f, 0.26f)
            null -> Triple(0.7f, 0.7f, 0.7f)
        }
    }

    /**
     * Draws the live grenades and turns every grenade that *vanished* since the
     * last frame into a blast: particles + an engine shove of a boom sound.
     * 30 Hz positions suffice to track the arc; the explosion point is the
     * last place the ball was seen, matching where it actually went off.
     */
    private fun drawGrenades(lit: ShaderProgram, nowMs: Long) {
        val snap = state.snapshots.latest
        val list = snap?.grenades ?: emptyList<GrenadeState>()

        // P9: segmented olive shell on a real sphere. texGain = 1/0.2975 (the
        // texture's mean luma) keeps the palette brightness pre-texture; the
        // green body and the hot fuse blink still come from uTint.
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, grenadeTex)
        lit.setFloat("uTexGain", 3.36f)
        lit.setFloat("uSpec", 0.35f)
        for (g in list) {
            val blink = g.fuseTicks > 30 && ((nowMs / 90L) and 1L) == 0L
            if (blink) {
                lit.setVec3("uTint", 0.85f, 0.22f, 0.12f)
                lit.setFloat("uEmissive", 0.9f)
            } else {
                lit.setVec3("uTint", 0.14f, 0.18f, 0.12f)
                lit.setFloat("uEmissive", 0.12f)
            }
            Matrix.setIdentityM(model, 0)
            Matrix.translateM(model, 0, g.x, g.y, g.z)
            lit.setMatrix("uModel", model)
            grenodeMesh.draw()

            val slot = prevGrenades.getOrPut(g.id) { FloatArray(3) }
            slot[0] = g.x; slot[1] = g.y; slot[2] = g.z
        }
        lit.setFloat("uEmissive", 0.0f)
        lit.setFloat("uSpec", 0.0f)
        lit.setFloat("uTexGain", 1.0f)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, whiteTex)
        lit.setVec3("uTint", 1f, 1f, 1f)
        lit.setMatrix("uModel", identity)

        // Vanished ids => boom right there (server already did the damage).
        if (prevGrenades.isNotEmpty()) {
            val iter = prevGrenades.entries.iterator()
            while (iter.hasNext()) {
                val (id, pos) = iter.next()
                var alive = false
                for (g in list) if (g.id == id) { alive = true; break }
                if (!alive) {
                    iter.remove()
                    particles.explosion(pos[0], pos[1], pos[2])
                    // P8-3: the blast feeds the dynamic point light for a
                    // quarter second — nearby walls flash with the boom.
                    blastFromMs = nowMs
                    blastX = pos[0]; blastY = pos[1] + 0.25f; blastZ = pos[2]
                    val ddx = pos[0] - camera.x
                    val ddz = pos[2] - camera.z
                    val dist = kotlin.math.sqrt(ddx * ddx + ddz * ddz)
                    val vol = (1f - dist / 60f).coerceIn(0f, 1f)
                    if (vol > 0.03f) SoundManager.explosion(vol)
                }
            }
        }
    }


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
        lit.setFloat("uSpec", 0.28f)

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

        // Recoil pushes the gun back and up. (NaN-guarded: one poisoned float
        // must never zero the whole viewmodel matrix for a frame.)
        val rawKick = weaponKick
        val kick = if (rawKick.isNaN() || rawKick.isInfinite()) 0f else rawKick
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
            // P4-1: compact high-rate body; cool forest-green tint.
            Weapons.SMG -> {
                tintR = 0.88f; tintG = 1.06f; tintB = 0.86f
                scaleX = 0.92f; scaleY = 0.92f; scaleZ = 0.80f
            }
        }

        camera.viewModelMatrix(model, 0.155f + bobX, -0.135f + bobY + offY, offZ)
        // Pitch the muzzle up slightly with recoil.
        Matrix.setIdentityM(scratch, 0)
        Matrix.rotateM(scratch, 0, kick * 2.2f, 1f, 0f, 0f)
        Matrix.scaleM(scratch, 0, scaleX, scaleY, scaleZ)
        Matrix.multiplyMM(scratch, 0, model, 0, scratch, 0)

        // P9: drawn after the post pipeline, so no world uniforms are live —
        // rebind the lit program and re-push the frame constants it relies on.
        lit.use()
        lit.setMatrix("uViewProj", camera.viewProjection)
        lit.setVec3("uLightDir", LIGHT_X, LIGHT_Y, LIGHT_Z)
        lit.setVec3("uFogColor", fogR, fogG, fogB)
        lit.setVec3("uEye", camera.x, camera.y, camera.z)
        lit.setFloat("uAmbient", ambientLight)
        lit.setSampler("uTex", 0)
        // Muzzle/explosion light still plays over the viewmodel in this pass.
        val playingNow = state.phase == Phase.PLAYING || state.phase == Phase.ENDED
        updatePointLight(lit, System.currentTimeMillis(), playingNow)
        lit.setMatrix("uModel", scratch)
        lit.setVec3("uTint", tintR, tintG, tintB)
        lit.setFloat("uFogDensity", 0f)
        // Gunmetal detail layer; gain = 1/0.45 luma (weapon texture is
        // deliberately darker so the small readout dots pop).
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, weaponTex)
        lit.setFloat("uTexGain", 2.22f)
        lit.setFloat("uSpec", 0.55f)
        weaponMesh.draw()
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, whiteTex)
        lit.setFloat("uTexGain", 1.0f)
        lit.setFloat("uSpec", 0.0f)
        lit.setFloat("uFogDensity", fogDensityPlay)
    }

    /**
     * P8-3: one dynamic point light per frame, strongest event wins — the
     * local muzzle flash, or a grenade blast for its first 240 ms. Set on the
     * LIT program once per frame; every lit draw (world, players, weapon)
     * picks it up, which is what makes gunfire light the room.
     */
    private fun updatePointLight(lit: ShaderProgram, nowMs: Long, playing: Boolean) {
        var px = 0f
        var py = 0f
        var pz = 0f
        var gain = 0f
        var cr = 1.0f
        var cg = 0.72f
        var cb = 0.42f

        if (playing && nowMs < state.muzzleFlashUntilMs && state.alive) {
            px = camera.x + camera.rightX * 0.155f + camera.upX * -0.10f + camera.forwardX * 0.35f
            py = camera.y + camera.rightY * 0.155f + camera.upY * -0.10f + camera.forwardY * 0.35f
            pz = camera.z + camera.rightZ * 0.155f + camera.upZ * -0.10f + camera.forwardZ * 0.35f
            gain = 2.4f
        }

        val blastAge = (nowMs - blastFromMs).toFloat()
        if (blastAge in 0f..240f) {
            val g = 7.0f * (1f - blastAge / 240f)
            if (g > gain) {
                gain = g
                px = blastX; py = blastY; pz = blastZ
                cr = 1.0f; cg = 0.50f; cb = 0.24f
            }
        }

        lit.setVec3("uPtPos", px, py, pz)
        lit.setVec3("uPtColor", cr, cg, cb)
        lit.setFloat("uPtGain", gain * pointGainScale)
    }

    /**
     * P8-2: Blinn sun-specular strength per material (0 = matte). Concrete
     * and metal get a sunny glint; wood stays dry.
     */
    private fun specFor(material: Int): Float = when (material) {
        Material.FLOOR -> 0.30f
        Material.WALL -> 0.20f
        Material.PILLAR -> 0.30f
        Material.CRATE -> 0.10f
        Material.COVER -> 0.16f
        Material.RAMP -> 0.14f
        else -> 0.20f
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

        buildSunFlare()
        effectMesh.end()
    }

    /**
     * P8-7: sun flare — a soft radial billboard at the far end of the sun ray
     * plus two ghosts mirrored about the view axis (classic streak flare).
     * Everything is a triangle-fan "sprite": bright centre vertex, zero rim,
     * so the additive blend feathers the falloff with no texture. The batch
     * is depth-tested, so arena walls occlude the flare exactly like the real
     * sun; it only lives where a slice of sky is visible that way. Fades in
     * while the look direction approaches the sun azimuth.
     */
    private fun buildSunFlare() {
        val vis = camera.forwardX * SUN_X + camera.forwardY * SUN_Y + camera.forwardZ * SUN_Z
        if (vis <= FLARE_MIN_VIS) return
        val t = ((vis - FLARE_MIN_VIS) / (1f - FLARE_MIN_VIS)).coerceIn(0f, 1f)
        // Dimmer at night (the disc there is a low moon, not the sun).
        val fade = t * t * (0.35f + 0.65f * sunGain)

        // Main disc: ~2.5 degrees across at the flare's 160 m anchor depth.
        addFlareFan(
            camera.x + SUN_X * FLARE_DIST, camera.y + SUN_Y * FLARE_DIST, camera.z + SUN_Z * FLARE_DIST,
            7.0f,
            1.0f * 0.55f * fade, 0.92f * 0.55f * fade, 0.75f * 0.55f * fade,
        )

        // Ghost 1: halfway between the sun and the frame centre, cool blue.
        val g1x = SUN_X * 0.45f + camera.forwardX * 0.55f
        val g1y = SUN_Y * 0.45f + camera.forwardY * 0.55f
        val g1z = SUN_Z * 0.45f + camera.forwardZ * 0.55f
        val g1l = kotlin.math.sqrt(g1x * g1x + g1y * g1y + g1z * g1z)
        if (g1l > 1e-4f && (g1x * camera.forwardX + g1y * camera.forwardY + g1z * camera.forwardZ) / g1l > 0.25f) {
            addFlareFan(
                camera.x + g1x / g1l * FLARE_DIST, camera.y + g1y / g1l * FLARE_DIST, camera.z + g1z / g1l * FLARE_DIST,
                2.6f,
                0.55f * 0.28f * fade, 0.72f * 0.28f * fade, 1.0f * 0.28f * fade,
            )
        }

        // Ghost 2: the sun direction mirrored through the view axis, warm.
        val dot2 = 2f * vis
        val rx = dot2 * camera.forwardX - SUN_X
        val ry = dot2 * camera.forwardY - SUN_Y
        val rz = dot2 * camera.forwardZ - SUN_Z
        val g2x = rx * 0.6f + camera.forwardX * 0.4f
        val g2y = ry * 0.6f + camera.forwardY * 0.4f
        val g2z = rz * 0.6f + camera.forwardZ * 0.4f
        val g2l = kotlin.math.sqrt(g2x * g2x + g2y * g2y + g2z * g2z)
        if (g2l > 1e-4f && (g2x * camera.forwardX + g2y * camera.forwardY + g2z * camera.forwardZ) / g2l > 0.25f) {
            addFlareFan(
                camera.x + g2x / g2l * FLARE_DIST, camera.y + g2y / g2l * FLARE_DIST, camera.z + g2z / g2l * FLARE_DIST,
                1.7f,
                0.95f * 0.20f * fade, 0.70f * 0.20f * fade, 0.45f * 0.20f * fade,
            )
        }
    }

    /** One camera-facing radial fan: bright centre vertex, zero rim. */
    private fun addFlareFan(cx: Float, cy: Float, cz: Float, radius: Float, r: Float, g: Float, b: Float) {
        var prevX = cx + camera.rightX * radius
        var prevY = cy + camera.rightY * radius
        var prevZ = cz + camera.rightZ * radius
        for (k in 1..FLARE_SEGMENTS) {
            val a = (k * Math.PI * 2 / FLARE_SEGMENTS).toFloat()
            val ca = cos(a); val sa = sin(a)
            // rim point on the camera plane
            val nx = cx + camera.rightX * radius * ca + camera.upX * radius * sa
            val ny = cy + camera.rightY * radius * ca + camera.upY * radius * sa
            val nz = cz + camera.rightZ * radius * ca + camera.upZ * radius * sa
            effectMesh.vertex(cx, cy, cz, r, g, b)
            effectMesh.vertex(prevX, prevY, prevZ, 0f, 0f, 0f)
            effectMesh.vertex(nx, ny, nz, 0f, 0f, 0f)
            prevX = nx; prevY = ny; prevZ = nz
        }
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

        // ---- P8-1: baked sun shadows ---------------------------------------
        // For every 2 m tile, four corner-jittered rays march from just above
        // the tile centre toward the sun. A hit on any solid brush (wall,
        // pillar, crate...) means the tile is in shade; the jitter fraction
        // gives a 0.5 m penumbra band and two 3x3 blur passes widen it into a
        // soft edge. Built once per arena — zero per-frame cost, and the
        // result multiplies the old contact AO so near-wall shading keeps
        // working inside real sun shadows.
        val gw = ((arena.maxX - arena.minX) / tile).toInt() + 1
        val gh = ((arena.maxZ - arena.minZ) / tile).toInt() + 1
        var sunGrid = FloatArray(gw * gh) { 1f }
        run {
            val sunLen = kotlin.math.sqrt(
                (LIGHT_X * LIGHT_X + LIGHT_Y * LIGHT_Y + LIGHT_Z * LIGHT_Z).toDouble(),
            ).toFloat()
            sunDirVec.set(LIGHT_X / sunLen, LIGHT_Y / sunLen, LIGHT_Z / sunLen)
            var gi = 0
            var gz = arena.minZ
            while (gz < arena.maxZ - 0.001f) {
                var gx = arena.minX
                while (gx < arena.maxX - 0.001f) {
                    var blocked = 0
                    for (j in SUN_JITTER) {
                        sunRay.set(gx + tile * 0.5f + j[0], 0.06f, gz + tile * 0.5f + j[1])
                        val d = RayMath.raycastArena(sunRay, sunDirVec, SUN_SHADOW_DIST, arena)
                        if (d < SUN_SHADOW_DIST - 0.01f) blocked++
                    }
                    sunGrid[gi++] = 1f - 0.50f * blocked / SUN_JITTER.size
                    gx += tile
                }
                gz += tile
            }
        }
        repeat(2) {
            val src = sunGrid
            val dst = FloatArray(gw * gh)
            for (iz in 0 until gh) {
                for (ix in 0 until gw) {
                    var acc = 0f
                    var cnt = 0
                    for (oz in -1..1) {
                        for (ox in -1..1) {
                            val xx = ix + ox
                            val zz = iz + oz
                            if (xx in 0 until gw && zz in 0 until gh) {
                                acc += src[zz * gw + xx]
                                cnt++
                            }
                        }
                    }
                    dst[iz * gw + ix] = acc / cnt
                }
            }
            sunGrid = dst
        }

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
                // Daylight palette: a bright concrete checker with a cool
                // (sky-tinted) B/G lift applied below.
                val c = if (dark) 0.340f else 0.420f
                val sh = floorContactShadow((x + x1) * 0.5f, (z + z1) * 0.5f)
                val tx = ((x - arena.minX) / tile).toInt().coerceIn(0, gw - 1)
                val tz = ((z - arena.minZ) / tile).toInt().coerceIn(0, gh - 1)
                val sh2 = sh * sunGrid[tz * gw + tx]
                floorB.floorTile(
                    x, z, x1, z1, 0f,
                    c * sh2, c * 1.06f * sh2, c * 1.22f * sh2,
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
                Team.RED -> Triple(0.52f, 0.18f, 0.16f)
                Team.BLUE -> Triple(0.16f, 0.26f, 0.54f)
                else -> Triple(0.34f, 0.34f, 0.36f)
            }
            sb.box(
                s.position.x - 1.1f, 0.001f, s.position.z - 1.1f,
                s.position.x + 1.1f, 0.02f, s.position.z + 1.1f,
                r, g, bb,
            )
        }
        spawnMesh.upload(sb.raw(), sb.floatCount)

        // P4-4: jump pads — a dodecagon ring of glowing wedge bricks. Static
        // geometry, bright vertex colours: with the uEmissive hook they read
        // as powered launch tiles even before bloom sees them.
        val pb = MeshBuilder(withNormals = true, initialCapacity = 4096)
        for (pad in arena.jumpPads) {
            buildPadRing(pb, pad.x, pad.z, pad.radius)
        }
        padMesh.upload(pb.raw(), pb.floatCount)

        // ---- P8-6: neon trim ridges capping tall walls & pillars -----------
        // A slim cyan ridge floated a hair above every tall solid's top face:
        // the sci-fi accent that reads as cool metal edging by day and as a
        // neon skyline in the night preset (uEmissive comes from the preset).
        val tb = MeshBuilder(withNormals = true, initialCapacity = 2048)
        for (brush in arena.brushes) {
            if (!brush.solid) continue
            if (brush.material != Material.WALL && brush.material != Material.PILLAR) continue
            val b = brush.box
            if (b.sizeY < 1.6f) continue
            if (b.maxX - b.minX < 0.2f || b.maxZ - b.minZ < 0.2f) continue
            tb.box(
                b.minX + 0.07f, b.maxY + 0.004f, b.minZ + 0.07f,
                b.maxX - 0.07f, b.maxY + 0.05f, b.maxZ - 0.07f,
                0.30f, 0.75f, 0.85f,
            )
        }
        trimMesh.upload(tb.raw(), tb.floatCount)

        prevGrenades.clear() // a new map invalidates blast tracking
        AndroidLog.i(
            "arena mesh: ${materialMeshes.values.sumOf { it.vertexCount }} verts " +
                "in ${materialMeshes.size} material meshes + spawn strips",
        )
    }

    /** 12 wedge-ish bricks forming one glowing launch ring around a pad. */
    private fun buildPadRing(b: MeshBuilder, cx: Float, cz: Float, radius: Float) {
        val n = 12
        for (i in 0 until n) {
            val a0 = i * (2f * Math.PI.toFloat()) / n
            val a1 = (i + 1) * (2f * Math.PI.toFloat()) / n
            val x0 = cx + cos(a0) * radius
            val z0 = cz + sin(a0) * radius
            val x1 = cx + cos(a1) * radius
            val z1 = cz + sin(a1) * radius
            // Chunky warm slab between the two arc points; thickness scales
            // with the ring so impulse-12 pads don't dwarf the 1.6 default.
            val thick = (radius * 2f * Math.PI.toFloat() / n) * 0.62f
            val xA = minOf(x0, x1) - thick * 0.18f
            val xB = maxOf(x0, x1) + thick * 0.18f
            val zA = minOf(z0, z1) - thick * 0.18f
            val zB = maxOf(z0, z1) + thick * 0.18f
            b.box(xA, 0.001f, zA, xB, 0.16f, zB, 1.0f, 0.62f, 0.22f)
        }
        // Centre plate: the actual pad face, dimmer than the ring.
        b.box(
            cx - radius * 0.55f, 0.0f, cz - radius * 0.55f,
            cx + radius * 0.55f, 0.10f, cz + radius * 0.55f,
            0.30f, 0.22f, 0.14f,
        )
    }

    /**
     * Daylight albedos: light cool metals with warm wooden crates as the
     * contrast accent. The texture detail layer multiplies on top exactly as
     * before — these are simply the old night values re-exposed for a sunlit
     * arena so the fog tonemap lands in the bright half of its shoulder.
     */
    private fun materialColour(material: Int): Triple<Float, Float, Float> = when (material) {
        Material.WALL -> Triple(0.585f, 0.605f, 0.655f)
        Material.CRATE -> Triple(0.660f, 0.520f, 0.320f)
        Material.PILLAR -> Triple(0.540f, 0.580f, 0.675f)
        Material.COVER -> Triple(0.460f, 0.590f, 0.545f)
        Material.RAMP -> Triple(0.600f, 0.600f, 0.630f)
        else -> Triple(0.600f, 0.600f, 0.630f)
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

        buildContentPackMeshes()

        // ONE leg with the hip at the origin hanging down -Y: a rotation around
        // the X axis in world space swings it like a pendulum. Drawn twice, one
        // per hip offset, in opposite phase.
        val leg = MeshBuilder(withNormals = true, initialCapacity = 1024)
        leg.box(-0.075f, -0.84f, -0.115f, 0.015f, 0f, 0.035f, 0.68f, 0.68f, 0.72f)
        playerLegMesh.upload(leg.raw(), leg.floatCount)
    }

    /**
     * Reusable unit geometry for the P4 set: a 0.34 m pickup cube (tinted per
     * kind at draw time) and a proper UV-SPHERE grenade (P9 — replaces the
     * old box): 16 longitude segments x 10 latitude rings so the silhouette
     * stays round even in close-up, radial normals for correct lighting, and
     * full UVs for the pineapple-shell texture. Vertex colours are neutral
     * multipliers — uTint does the real work.
     */
    private fun buildContentPackMeshes() {
        val cube = MeshBuilder(withNormals = true, initialCapacity = 512)
        cube.box(-0.17f, -0.17f, -0.17f, 0.17f, 0.17f, 0.17f, 1f, 1f, 1f)
        pickupCube.upload(cube.raw(), cube.floatCount)

        val ball = MeshBuilder(withNormals = true, initialCapacity = 1024)
        buildUnitSphere(ball, 0.10f, 16, 10)
        grenodeMesh.upload(ball.raw(), ball.floatCount)
    }

    /**
     * P9: lat/long UV sphere at the origin. Rings run pole to pole; each quad
     * is split into two triangles with radial normals, so specular and the
     * hemisphere term roll over the ball exactly like on a real round body.
     * UVs wrap the shell once (the texture is seam-safe horizontally).
     */
    private fun buildUnitSphere(b: MeshBuilder, radius: Float, segs: Int, rings: Int) {
        for (r in 0 until rings) {
            val v0 = r.toFloat() / rings
            val v1 = (r + 1).toFloat() / rings
            val th0 = v0 * Math.PI.toFloat()        // 0 (top) .. PI (bottom)
            val th1 = v1 * Math.PI.toFloat()
            val sy0 = cos(th0); val rr0 = sin(th0)
            val sy1 = cos(th1); val rr1 = sin(th1)
            for (s in 0 until segs) {
                val u0 = s.toFloat() / segs
                val u1 = (s + 1).toFloat() / segs
                val ph0 = u0 * (2f * Math.PI.toFloat())
                val ph1 = u1 * (2f * Math.PI.toFloat())

                // corners: (ring i, seg j)
                val n00x = rr0 * cos(ph0); val n00z = rr0 * sin(ph0)
                val n01x = rr0 * cos(ph1); val n01z = rr0 * sin(ph1)
                val n10x = rr1 * cos(ph0); val n10z = rr1 * sin(ph0)
                val n11x = rr1 * cos(ph1); val n11z = rr1 * sin(ph1)

                fun vert(nx: Float, ny: Float, nz: Float, uu: Float, vv: Float) {
                    b.vertex(
                        nx * radius, ny * radius, nz * radius,
                        nx, ny, nz,
                        1f, 1f, 1f,
                        uu, vv,
                    )
                }

                // CCW seen from OUTSIDE (verified numerically: cross points
                // outward; GL glFrontFace CCW + cull-back).
                // triangle 1: (00),(11),(10)
                vert(n00x, sy0, n00z, u0, v0)
                vert(n11x, sy1, n11z, u1, v1)
                vert(n10x, sy1, n10z, u0, v1)
                // triangle 2: (00),(01),(11)
                vert(n00x, sy0, n00z, u0, v0)
                vert(n01x, sy0, n01z, u1, v0)
                vert(n11x, sy1, n11z, u1, v1)
            }
        }
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
        padMesh.dispose()
        trimMesh.dispose()
        pickupCube.dispose()
        grenodeMesh.dispose()
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

        // Key light direction (pointing from the surface toward the light).
        // Kept un-normalized historically; shaders normalize per fragment.
        private const val LIGHT_X = 0.38f
        private const val LIGHT_Y = 0.86f
        private const val LIGHT_Z = 0.34f

        // Normalized sun direction for CPU-side work (flare, blob ellipse).
        private val SUN_LEN = kotlin.math.sqrt((LIGHT_X * LIGHT_X + LIGHT_Y * LIGHT_Y + LIGHT_Z * LIGHT_Z).toDouble()).toFloat()
        private val SUN_X = LIGHT_X / SUN_LEN
        private val SUN_Y = LIGHT_Y / SUN_LEN
        private val SUN_Z = LIGHT_Z / SUN_LEN

        /** Horizontal direction a cast shadow stretches (away from the sun). */
        private val SUN_XZ = kotlin.math.sqrt((LIGHT_X * LIGHT_X + LIGHT_Z * LIGHT_Z).toDouble()).toFloat()
        private val SHADOW_DIR_X = -LIGHT_X / SUN_XZ
        private val SHADOW_DIR_Z = -LIGHT_Z / SUN_XZ
        private val SHADOW_PERP_X = -SHADOW_DIR_Z
        private val SHADOW_PERP_Z = SHADOW_DIR_X

        // P8-7: sun flare billboard anchor distance / edge fade / fan detail.
        private const val FLARE_DIST = 160f
        private const val FLARE_MIN_VIS = 0.90f
        private const val FLARE_SEGMENTS = 10

        // P8-1: baked sun shadows — ray length (covers 4–5 m walls at the 59°
        // sun elevation) and the 0.45 m corner jitter that makes the penumbra.
        private const val SUN_SHADOW_DIST = 9f
        private val SUN_JITTER = arrayOf(
            floatArrayOf(0.45f, 0.45f),
            floatArrayOf(0.45f, -0.45f),
            floatArrayOf(-0.45f, 0.45f),
            floatArrayOf(-0.45f, -0.45f),
        )

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
            // Pure additive glow in vertex-colour space (spawn strips, trim).
            uniform float uEmissive;
            // P8-2: Blinn specular strength toward the sun (0 = matte).
            uniform float uSpec;
            // P8-3: one dynamic point light (muzzle flash / grenade blast).
            uniform vec3  uPtPos;
            uniform vec3  uPtColor;
            uniform float uPtGain;
            in vec3 vNormal;
            in vec3 vColor;
            in vec3 vWorld;
            in vec2 vUv;
            out vec4 fragColor;
            void main() {
                vec3 n = normalize(vNormal);
                vec3 ld = normalize(uLightDir);
                float lambert = max(dot(n, ld), 0.0);
                // Hemisphere term: sky above, bounce below. The daylight base
                // is high (the whole dome is a light source), so vertical
                // walls stay readable without a second light or shadow maps.
                float hemi = 0.65 + 0.35 * n.y;
                float light = uAmbient * hemi + (1.0 - uAmbient) * lambert;
                // The texture is a greyscale-ish detail layer: uTexGain restores
                // the palette brightness (1/mean-luma), so the original vertex
                // colours survive texturing and a white 1x1 fallback texture is
                // a silent no-op returning the pre-texture look.
                vec3 detail = texture(uTex, vUv).rgb * uTexGain;
                vec3 surf = vColor * detail * uTint;
                vec3 c = surf * light;
                // P8-3: the gunfire/boom light. Quadratic-ish rolloff over
                // ~14 m keeps it a local event, never a second sun.
                if (uPtGain > 0.0) {
                    vec3 dv = uPtPos - vWorld;
                    float d = length(dv);
                    float att = clamp(1.0 - d / 14.0, 0.0, 1.0);
                    att *= att;
                    float ndl = max(dot(n, dv / max(d, 0.001)), 0.0);
                    c += surf * uPtColor * (att * ndl * uPtGain);
                }
                // P8-2: Blinn glint toward the sun, gated by lambert so only
                // the sun-facing sides sparkle. It is what makes the concrete
                // read as sunlit when the camera moves.
                if (uSpec > 0.0) {
                    vec3 v = normalize(uEye - vWorld);
                    vec3 h = normalize(v + ld);
                    float spec = pow(max(dot(n, h), 0.0), 48.0) * uSpec;
                    c += vec3(1.0, 0.98, 0.92) * spec * (0.25 + 0.75 * lambert);
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
         * first with depth writes off. Unlit and unfogged on purpose — the sky
         * IS the far background; the fog-tinted world dissolves into the same
         * haze colour at distance, so the junction stays invisible.
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
         *    horizon and the zenith, scrolled slowly with uTime. White
         *    midday cumulus with a warm rim on the sun side, alpha'd over
         *    the blue panorama so the haze band keeps breathing through
         *    their lower edges.
         */
        private const val SKY_FS = """#version 300 es
            precision mediump float;
            uniform sampler2D uTex;
            uniform vec3  uSunDir;
            uniform vec3  uSunTint;   // preset: warm white by day, cool moon by night
            uniform float uSunGain;   // preset: 1 day / 0.5 night
            uniform vec3  uCloudCol;  // preset: white cumulus / dark stratus
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
                    // Polar ring coordinates hug the dome and wrap at ±π with
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
                    // Scattered cover, not overcast: with the threshold at
                    // 0.60+ only the densest noise cells become cumulus and
                    // most of the blue dome stays visible. (The night value
                    // 0.50 looked fine on dark clouds; on white ones it
                    // whitens the entire sky.)
                    cloud = smoothstep(0.60, 0.85, n) * band;
                    // Cloud body from the active preset, warming toward the
                    // sun side — the silver-lining rim that sells a lit cloud.
                    float sunSide = pow(max(sunDot, 0.0), 3.0) * uSunGain;
                    vec3 cloudCol = uCloudCol
                                  + vec3(0.12, 0.06, -0.06) * sunSide;
                    // Slight grey-blue underside keeps the puffs 3D against
                    // the pale horizon band.
                    cloudCol *= 1.0 - vec3(0.16, 0.13, 0.08) * (1.0 - dir.y);
                    col = mix(col, cloudCol, cloud * 0.80);
                }

                // ---- sun --------------------------------------------------
                // Smooth angular falls off from angular thresholds (dot = cos).
                float disc   = smoothstep(0.99935, 0.99978, sunDot);
                float corona = pow(max(sunDot, 0.0), 340.0) * 1.05;
                float halo   = pow(max(sunDot, 0.0), 14.0) * 0.26;
                // White-hot core with a wide daylight glare; the preset's
                // uSunGain dims the whole term into a soft moon at night.
                vec3 sun = uSunTint * uSunGain * (disc * 3.6 + corona + halo);
                col += sun * (1.0 - cloud * 0.60);

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
                // Daylight shadow = the sky's ambient arriving where the sun
                // cannot: a mid blue-grey, not soot. On the bright floor this
                // sits one clearly visible notch below the unshadowed tiles.
                fragColor = vec4(0.065, 0.090, 0.160, clamp(vColor.r, 0.0, 1.0));
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
