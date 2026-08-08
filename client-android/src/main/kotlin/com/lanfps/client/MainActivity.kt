package com.lanfps.client

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import android.net.wifi.WifiManager
import com.lanfps.shared.ArenaDef
import com.lanfps.shared.GameConstants
import com.lanfps.shared.GameMode
import com.lanfps.shared.Team

/**
 * The single Activity: owns the view stack, the phase state machine and the
 * network client's lifetime.
 *
 * View stack, bottom to top:
 *   GameView (GL)  ->  HudView  ->  TouchControlsView  ->  scoreboard
 *   ->  menu / lobby / end-match / pause panels
 *
 * The GL surface is never removed, only covered. Recreating an EGL surface
 * every time a menu opens is a reliable way to produce black screens and
 * context-loss bugs on real phones, so we simply keep it alive and draw a slow
 * camera orbit of the arena behind the menus.
 */
class MainActivity : Activity(), NetworkClient.Listener {

    private lateinit var prefs: SharedPreferences
    private lateinit var arena: ArenaDef
    private lateinit var state: ClientGameState
    private lateinit var input: InputController

    private lateinit var root: FrameLayout
    private lateinit var gameView: GameView
    private lateinit var hud: HudView
    private lateinit var controls: TouchControlsView
    private lateinit var menu: MenuView
    private lateinit var lobby: LobbyView
    private lateinit var scoreboard: ScoreboardView
    private lateinit var endMatch: EndMatchView
    private lateinit var pausePanel: LinearLayout

    private var net: NetworkClient? = null

    /**
     * P7-3: held for the whole session. Without it the phone's Wi-Fi stack is
     * free to doze between beacon intervals and the AP buffers our downstream
     * packets until the next DTIM — that is the standard reason a LAN game
     * shows 60–200 ms of "physically impossible" ping on a phone. LOW_LATENCY
     * mode (API 29+) instructs the firmware to disable that power save;
     * HIGH_PERF is the legacy equivalent below 29.
     */
    private var wifiLock: WifiManager.WifiLock? = null

    private val ui = Handler(Looper.getMainLooper())
    private var lastPhase: Phase? = null
    private var paused = false
    private var scoreboardVisible = false
    private var panelTick = 0

    /** Last damage timestamp we already vibrated for (the state field is a
     *  monotonically-updated edge, we must buzz exactly once per hit). */
    private var lastVibratedDamageMs = 0L

    private var frameCallback: Choreographer.FrameCallback? = null

    // ------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        prefs = getSharedPreferences("lanfps", Context.MODE_PRIVATE)
        arena = loadArena()
        AndroidLog.i(arena.describe())

        state = ClientGameState(arena)
        state.prediction = Prediction(arena)
        input = InputController()

        // Restore the last used settings so a play session is one tap away.
        state.nickname = prefs.getString("nick", defaultNickname()) ?: defaultNickname()
        state.serverIp = prefs.getString("ip", GameConstants.DEFAULT_SERVER_IP)
            ?: GameConstants.DEFAULT_SERVER_IP
        state.serverPort = prefs.getInt("port", GameConstants.DEFAULT_UDP_PORT)
        state.password = prefs.getString("pw", "") ?: ""
        input.sensitivity = prefs.getFloat("sens", InputController.DEFAULT_SENSITIVITY)
        input.invertY = prefs.getBoolean("invertY", false)

        buildViews()
        applyPhase(Phase.MENU)
        startUiTicker()
    }

    private fun buildViews() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        gameView = GameView(this, state, arena)
        root.addView(gameView, matchParent())

        hud = HudView(this, state)
        root.addView(hud, matchParent())

        controls = TouchControlsView(this, input).apply {
            onMenu = { showPause(true) }
            onScoreboard = { down -> setScoreboardVisible(down) }
        }
        root.addView(controls, matchParent())

        scoreboard = ScoreboardView(this, state).apply { visibility = View.GONE }
        root.addView(scoreboard, matchParent())

        endMatch = EndMatchView(
            this, state,
            onStay = {
                // Wait for the server to start the next round; drop back to the
                // lobby so the player is not shooting during the results screen.
                state.phase = Phase.LOBBY
            },
            onLeave = { leaveServer() },
        ).apply { visibility = View.GONE }
        root.addView(endMatch, matchParent())

        lobby = LobbyView(
            this, state,
            onEnterMatch = {
                input.releaseAll()
                controls.resetAll()
                state.phase = Phase.PLAYING
            },
            onLeave = { leaveServer() },
            onVote = { voteMode -> net?.voteMode(voteMode) },
        ).apply { visibility = View.GONE }
        root.addView(lobby, matchParent())

        menu = MenuView(
            this, state, input,
            onConnect = { ip, port, nick, password -> connect(ip, port, nick, password) },
            onScan = { scanLan() },
            onQuit = { finishAndRemoveTaskCompat() },
        )
        root.addView(menu, matchParent())

        pausePanel = buildPausePanel().apply { visibility = View.GONE }
        root.addView(pausePanel, matchParent())

        setContentView(root)
    }

    private fun buildPausePanel(): LinearLayout {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.argb(206, 6, 9, 14))
            isClickable = true
        }
        val card = UiKit.panel(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                UiKit.dp(this@MainActivity, 320f),
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        card.addView(UiKit.title(this, "PAUSED", 22f))
        card.addView(
            UiKit.label(
                this,
                "The match keeps running on the server while this is open — " +
                    "you are still in the world and can still be shot.",
                11f,
            ),
        )
        card.addView(UiKit.divider(this))
        card.addView(
            UiKit.button(this, "Resume", primary = true) { showPause(false) }.apply {
                layoutParams = UiKit.lp(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            },
        )
        card.addView(
            UiKit.button(this, "Toggle net/debug overlay") {
                hud.showDebug = !hud.showDebug
                showPause(false)
            }.apply {
                layoutParams = UiKit.lp(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    margins = intArrayOf(0, UiKit.dp(this@MainActivity, 8f), 0, 0),
                )
            },
        )
        card.addView(
            UiKit.button(this, "Disconnect") { leaveServer() }.apply {
                layoutParams = UiKit.lp(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    margins = intArrayOf(0, UiKit.dp(this@MainActivity, 8f), 0, 0),
                )
            },
        )
        outer.addView(card)
        return outer
    }

    private fun matchParent() = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
    )

    override fun onResume() {
        super.onResume()
        goImmersive()
        gameView.onResume()
    }

    override fun onPause() {
        super.onPause()
        // Never leave the stick or the trigger stuck down when the app loses focus.
        input.releaseAll()
        controls.resetAll()
        gameView.onPause()
    }

    override fun onDestroy() {
        stopUiTicker()
        net?.stopNow()
        net = null
        setLowLatencyWifi(false)
        SoundManager.release() // P1-3: free the AudioTrack
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goImmersive()
    }

    @Suppress("DEPRECATION")
    private fun goImmersive() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        when (state.phase) {
            Phase.MENU -> super.onBackPressed()
            Phase.PLAYING -> if (pausePanel.visibility == View.VISIBLE) {
                showPause(false)
            } else {
                showPause(true)
            }
            else -> leaveServer()
        }
    }

    // ------------------------------------------------------------ UI ticker

    /**
     * One Choreographer callback drives every 2D redraw.
     *
     * The HUD is invalidated every frame (it has to track the camera), while the
     * heavier panels rebuild four times a second — rebuilding a dozen TextViews
     * at 60 Hz would cost more CPU than the entire renderer.
     */
    private fun startUiTicker() {
        val cb = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                tickUi()
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
        frameCallback = cb
        Choreographer.getInstance().postFrameCallback(cb)
    }

    private fun stopUiTicker() {
        frameCallback?.let { Choreographer.getInstance().removeFrameCallback(it) }
        frameCallback = null
    }

    private fun tickUi() {
        // P2-3: the network thread parks map-rotation requests in the state;
        // only this thread may touch the APK assets, so the swap happens here.
        val pending = state.pendingArenaName
        if (pending != null) {
            state.pendingArenaName = null
            hotSwapArena(pending, state.pendingArenaHash)
        }

        // A short buzz on every hit taken (VIBRATE permission is declared; the
        // edge detector makes sure one hit = one buzz, even at 60 Hz redraws).
        if (state.lastDamageTakenMs != lastVibratedDamageMs) {
            lastVibratedDamageMs = state.lastDamageTakenMs
            vibrateShort()
        }

        val phase = state.phase
        if (phase != lastPhase) applyPhase(phase)

        if (phase == Phase.PLAYING || phase == Phase.ENDED) {
            hud.invalidate()
            controls.invalidate()
        }

        panelTick++
        if (panelTick % 15 == 0) {
            when (phase) {
                Phase.LOBBY -> lobby.refresh()
                Phase.ENDED -> endMatch.refresh()
                Phase.CONNECTING -> menu.setStatus(state.statusText)
                else -> Unit
            }
            if (scoreboardVisible) scoreboard.refresh()
        }
    }

    /**
     * P2-3: loads "<name>.json" from the APK assets and swaps every subsystem
     * onto it (prediction physics, renderer mesh, tracer raycasts). The hash
     * sent by the server is verified first when available; on any failure the
     * old geometry stays and the mismatch banner keeps warning - a wrong map
     * silently applied would be far worse than no map applied.
     */
    private fun hotSwapArena(name: String, expectedHash: Int) {
        try {
            val file = if (name.endsWith(".json")) name else "$name.json"
            assets.open(file).use { stream ->
                val text = stream.readBytes().toString(Charsets.UTF_8)
                val newArena = ArenaDef.fromJson(text)
                if (expectedHash != 0 && newArena.hash() != expectedHash) {
                    AndroidLog.w(
                        "refusing arena hot-swap of $file: hash 0x%08X != server's 0x%08X"
                            .format(newArena.hash(), expectedHash),
                    )
                    state.arenaMismatch = true
                    return
                }
                arena = newArena
                state.arena = newArena
                state.prediction?.setArena(newArena)
                net?.updateArena(newArena)
                gameView.runOnGl { gameView.gameRenderer.setArena(newArena) }
                state.arenaMismatch = false
                AndroidLog.i("arena hot-swapped to ${newArena.name}")
            }
        } catch (e: Exception) {
            AndroidLog.w(
                "this APK has no map for the server's current arena '$name' " +
                    "(${e.message}); keeping the old geometry",
            )
            state.arenaMismatch = true
        }
    }

    @Suppress("DEPRECATION")
    private fun vibrateShort() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= 23) {
                getSystemService(Vibrator::class.java)
            } else {
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            } ?: return
            if (Build.VERSION.SDK_INT >= 26) {
                vibrator.vibrate(
                    VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE),
                )
            } else {
                vibrator.vibrate(35)
            }
        } catch (_: Exception) {
        }
    }

    // --------------------------------------------------------------- phases

    private fun applyPhase(phase: Phase) {
        lastPhase = phase
        AndroidLog.i("phase -> $phase")

        menu.visibility = if (phase == Phase.MENU || phase == Phase.CONNECTING ||
            phase == Phase.DISCONNECTED || phase == Phase.RECONNECTING
        ) View.VISIBLE else View.GONE

        lobby.visibility = if (phase == Phase.LOBBY) View.VISIBLE else View.GONE
        endMatch.visibility = if (phase == Phase.ENDED) View.VISIBLE else View.GONE
        controls.visibility = if (phase == Phase.PLAYING) View.VISIBLE else View.GONE
        hud.visibility = if (phase == Phase.PLAYING || phase == Phase.ENDED) {
            View.VISIBLE
        } else {
            View.GONE
        }

        if (phase != Phase.PLAYING) {
            input.releaseAll()
            setScoreboardVisible(false)
            showPause(false)
        }

        when (phase) {
            Phase.MENU -> {
                menu.setStatus("Ready.")
                menu.refreshLocalIp()
                menu.refreshServers()
                hideKeyboard()
            }

            Phase.CONNECTING -> menu.setStatus(state.statusText)

            Phase.RECONNECTING -> menu.setStatus(state.statusText)

            Phase.DISCONNECTED -> {
                menu.setStatus(state.errorText.ifEmpty { "Disconnected." }, error = true)
                menu.refreshLocalIp()
            }

            Phase.LOBBY -> {
                hideKeyboard()
                lobby.refresh()
            }

            Phase.ENDED -> endMatch.refresh()

            Phase.PLAYING -> {
                controls.resetAll()
                goImmersive()
            }
        }
    }

    private fun setScoreboardVisible(visible: Boolean) {
        scoreboardVisible = visible
        scoreboard.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) scoreboard.refresh()
    }

    private fun showPause(show: Boolean) {
        pausePanel.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) goImmersive()
    }

    // ------------------------------------------------------------ networking

    /**
     * P7-3: holds/releases the low-latency Wi-Fi lock. Every failure here is
     * deliberately non-fatal: the game must run exactly as before on a device
     * whose firmware refuses the lock.
     */
    @Synchronized
    @Suppress("DEPRECATION")
    private fun setLowLatencyWifi(active: Boolean) {
        try {
            if (active) {
                if (wifiLock == null) {
                    val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                    val mode = if (Build.VERSION.SDK_INT >= 29) {
                        WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                    } else {
                        WifiManager.WIFI_MODE_FULL_HIGH_PERF
                    }
                    wifiLock = wm.createWifiLock(mode, "lanfps:game").apply {
                        setReferenceCounted(false)
                    }
                }
                if (wifiLock?.isHeld == false) {
                    wifiLock?.acquire()
                    AndroidLog.i("low-latency Wi-Fi lock acquired")
                }
            } else if (wifiLock?.isHeld == true) {
                wifiLock?.release()
                AndroidLog.i("low-latency Wi-Fi lock released")
            }
        } catch (e: Exception) {
            AndroidLog.w("wifi lock toggle failed: ${e.message}")
        }
    }

    private fun connect(ip: String, port: Int, nick: String, password: String) {
        prefs.edit()
            .putString("nick", nick)
            .putString("ip", ip)
            .putInt("port", port)
            .putString("pw", password)
            .putFloat("sens", input.sensitivity)
            .putBoolean("invertY", input.invertY)
            .apply()

        hideKeyboard()
        state.resetForNewSession()
        state.nickname = nick
        state.password = password
        input.setAngles(0f, 0f)

        net?.stopNow()
        val client = NetworkClient(state, input, arena, this)
        net = client
        setLowLatencyWifi(true)
        client.start(ip, port, nick)
    }

    private fun leaveServer() {
        AndroidLog.i("leaving server")
        net?.stop()
        net = null
        setLowLatencyWifi(false)
        state.resetForNewSession()
        state.phase = Phase.MENU
        ui.post {
            menu.setStatus("Disconnected.")
            applyPhase(Phase.MENU)
        }
    }

    private fun scanLan() {
        state.clearDiscovered()
        menu.setStatus("Scanning the local network for servers ...")
        val port = state.serverPort
        val scanner = net ?: NetworkClient(state, input, arena, this)
        scanner.discover(port, 1400) { found ->
            ui.post {
                for (s in found) state.addDiscovered(s)
                menu.refreshServers()
                menu.setStatus(
                    if (found.isEmpty()) {
                        "No servers answered the broadcast. Type the IP by hand — " +
                            "that works even when the router blocks broadcast."
                    } else {
                        "Found ${found.size} server(s). Tap one to fill in its address."
                    },
                    error = found.isEmpty(),
                )
            }
        }
    }

    // ---- NetworkClient.Listener (called on the network thread) --------------

    override fun onConnected(playerId: Int, team: Team, mode: GameMode) {
        ui.post {
            Toast.makeText(
                this,
                "Connected as ${state.nickname}" +
                    if (mode == GameMode.TDM) "  ·  team ${team.name}" else "",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    override fun onRejected(reason: String) {
        setLowLatencyWifi(false)
        ui.post { menu.setStatus("Server refused the connection: $reason", error = true) }
    }

    override fun onDisconnected(reason: String, wasError: Boolean) {
        setLowLatencyWifi(false)
        ui.post {
            menu.setStatus(reason, error = wasError)
            if (wasError) {
                Toast.makeText(this, reason, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onMatchStateChanged(newState: Int, winningTeam: Int) {
        ui.post {
            if (newState == com.lanfps.shared.MatchState.ENDED) endMatch.refresh()
        }
    }

    // ----------------------------------------------------------------- utils

    /**
     * Loads `arena01.json` from the APK assets, falling back to the built-in
     * geometry. The fallback is not decoration: it guarantees the client always
     * has a map whose hash the server can check, even if the asset were somehow
     * stripped from the package.
     */
    private fun loadArena(): ArenaDef {
        try {
            assets.open("arena01.json").use { stream ->
                val text = stream.readBytes().toString(Charsets.UTF_8)
                val a = ArenaDef.fromJson(text)
                AndroidLog.i("arena loaded from assets/arena01.json")
                return a
            }
        } catch (e: Exception) {
            AndroidLog.w("could not load assets/arena01.json (${e.message}); using built-in arena")
        }
        return ArenaDef.builtinArena01()
    }

    private fun defaultNickname(): String {
        val model = android.os.Build.MODEL?.filter { it.isLetterOrDigit() } ?: "Player"
        return ("P-" + model).take(GameConstants.MAX_NICKNAME_LENGTH)
    }

    private fun hideKeyboard() {
        try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(root.windowToken, 0)
        } catch (_: Exception) {
        }
    }

    private fun finishAndRemoveTaskCompat() {
        net?.stopNow()
        finish()
    }
}
