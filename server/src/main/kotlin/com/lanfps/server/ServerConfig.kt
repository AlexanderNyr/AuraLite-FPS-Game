package com.lanfps.server

import com.lanfps.shared.GameConstants
import com.lanfps.shared.GameMode
import java.io.File
import java.util.Properties

/**
 * Server settings, resolved in this order (later wins):
 *   1. built-in defaults
 *   2. `server.properties` on the classpath (bundled in the jar)
 *   3. `server.properties` next to the jar / in the working directory
 *   4. command-line arguments, e.g. `--mode=TDM --botCount=6`
 */
class ServerConfig {

    var udpPort: Int = GameConstants.DEFAULT_UDP_PORT
    var bindAddress: String = "0.0.0.0"
    var mode: GameMode = GameMode.DM
    var botCount: Int = GameConstants.DEFAULT_BOT_COUNT
    var maxPlayers: Int = GameConstants.DEFAULT_MAX_PLAYERS
    var matchTimeSeconds: Int = GameConstants.DEFAULT_MATCH_TIME_SEC
    var killLimit: Int = GameConstants.DEFAULT_KILL_LIMIT
    var serverName: String = "LAN FPS Server"

    /** Advertised in the README / client default field. Bind is always 0.0.0.0. */
    var defaultServerIp: String = GameConstants.DEFAULT_SERVER_IP

    var arenaName: String = GameConstants.ARENA_NAME
    var arenaFile: String = "arena01.json"
    var enableDiscovery: Boolean = true

    /** 0..1 — scales bot aim accuracy and reaction speed. */
    var botDifficulty: Float = 0.55f

    // ---- P0-3: connection flood protection -------------------------------
    /** Max simultaneous active sessions per source IP. */
    var maxSessionsPerIp: Int = GameConstants.MAX_SESSIONS_PER_IP
    /** Max brand-new sessions accepted per second, globally. */
    var maxConnectsPerSecond: Int = GameConstants.MAX_CONNECTS_PER_SECOND
    /** Max brand-new sessions accepted per second from one source IP. */
    var maxConnectsPerIpPerSecond: Int = GameConstants.MAX_CONNECTS_PER_IP_SECOND

    /** P0-2: how long a silent session stays a zombie awaiting a reconnect. */
    var zombieTimeoutMs: Long = GameConstants.ZOMBIE_TIMEOUT_MS

    /** How long a session may be silent before it becomes a zombie. Exposed so
     *  operators can tune it (and tests can speed it up). Default 8 s. */
    var serverTimeoutMs: Long = GameConstants.SERVER_TIMEOUT_MS

    /** P1-1: rewinds targets on a shot to what the shooter saw (90 ms + RTT/2,
     *  capped). Can be turned off for direct comparison. */
    var lagCompensation: Boolean = true

    /** P1-2: send FULL snapshots (keyframes) every second and DELTAs between,
     *  instead of a full snapshot every 33 ms. */
    var deltaCompression: Boolean = true

    var logLevel: String = "INFO"

    /** Headless smoke test: run N seconds of simulation then exit. 0 = normal run. */
    var selfTestSeconds: Int = 0

    fun describe(): String = buildString {
        append("mode=").append(mode.name)
        append(" arena=").append(arenaName)
        append(" bots=").append(botCount)
        append(" maxPlayers=").append(maxPlayers)
        append(" matchTime=").append(matchTimeSeconds).append("s")
        append(" killLimit=").append(killLimit)
        append(" discovery=").append(enableDiscovery)
    }

    private fun applyProperties(p: Properties) {
        p.getProperty("udpPort")?.toIntOrNull()?.let { udpPort = it }
        p.getProperty("bindAddress")?.let { bindAddress = it.trim() }
        p.getProperty("mode")?.let { mode = GameMode.parse(it) }
        p.getProperty("botCount")?.toIntOrNull()?.let { botCount = it }
        p.getProperty("maxPlayers")?.toIntOrNull()?.let { maxPlayers = it }
        p.getProperty("matchTimeSeconds")?.toIntOrNull()?.let { matchTimeSeconds = it }
        p.getProperty("killLimit")?.toIntOrNull()?.let { killLimit = it }
        p.getProperty("serverName")?.let { serverName = it.trim() }
        p.getProperty("defaultServerIp")?.let { defaultServerIp = it.trim() }
        p.getProperty("arenaFile")?.let { arenaFile = it.trim() }
        p.getProperty("enableDiscovery")?.let { enableDiscovery = it.trim().toBoolean() }
        p.getProperty("botDifficulty")?.toFloatOrNull()?.let { botDifficulty = it }
        p.getProperty("maxSessionsPerIp")?.toIntOrNull()?.let { maxSessionsPerIp = it }
        p.getProperty("maxConnectsPerSecond")?.toIntOrNull()?.let { maxConnectsPerSecond = it }
        p.getProperty("maxConnectsPerIpPerSecond")?.toIntOrNull()?.let {
            maxConnectsPerIpPerSecond = it
        }
        p.getProperty("zombieTimeoutMs")?.toLongOrNull()?.let { zombieTimeoutMs = it }
        p.getProperty("serverTimeoutMs")?.toLongOrNull()?.let { serverTimeoutMs = it }
        p.getProperty("lagCompensation")?.let { lagCompensation = it.trim().toBoolean() }
        p.getProperty("deltaCompression")?.let { deltaCompression = it.trim().toBoolean() }
        p.getProperty("logLevel")?.let { logLevel = it.trim() }
        p.getProperty("selfTestSeconds")?.toIntOrNull()?.let { selfTestSeconds = it }
    }

    private fun applyArgs(args: Array<String>) {
        val p = Properties()
        for (raw in args) {
            val arg = raw.removePrefix("--")
            val eq = arg.indexOf('=')
            if (eq <= 0) {
                // Bare flags like `--selftest`
                when (arg.lowercase()) {
                    "selftest" -> p.setProperty("selfTestSeconds", "15")
                    "debug" -> p.setProperty("logLevel", "DEBUG")
                    else -> Log.warn("ignoring unrecognised argument '$raw'")
                }
                continue
            }
            p.setProperty(arg.substring(0, eq), arg.substring(eq + 1))
        }
        applyProperties(p)
    }

    fun validate() {
        if (udpPort !in 1..65535) {
            Log.warn("udpPort $udpPort out of range, falling back to ${GameConstants.DEFAULT_UDP_PORT}")
            udpPort = GameConstants.DEFAULT_UDP_PORT
        }
        maxPlayers = maxPlayers.coerceIn(1, 32)
        botCount = botCount.coerceIn(0, 16)
        matchTimeSeconds = matchTimeSeconds.coerceIn(30, 3600)
        killLimit = killLimit.coerceIn(1, 1000)
        botDifficulty = botDifficulty.coerceIn(0f, 1f)
        maxSessionsPerIp = maxSessionsPerIp.coerceIn(1, 32)
        maxConnectsPerSecond = maxConnectsPerSecond.coerceIn(1, 1000)
        maxConnectsPerIpPerSecond = maxConnectsPerIpPerSecond.coerceIn(1, 1000)
        zombieTimeoutMs = zombieTimeoutMs.coerceIn(5_000L, 300_000L)
        serverTimeoutMs = serverTimeoutMs.coerceIn(200L, 300_000L)
    }

    companion object {
        private const val FILE_NAME = "server.properties"

        fun load(args: Array<String>): ServerConfig {
            val cfg = ServerConfig()

            // 1) bundled defaults from the jar
            try {
                ServerConfig::class.java.classLoader
                    .getResourceAsStream(FILE_NAME)?.use { stream ->
                        val p = Properties()
                        p.load(stream)
                        cfg.applyProperties(p)
                        Log.debug("loaded bundled $FILE_NAME")
                    }
            } catch (e: Exception) {
                Log.warn("could not read bundled $FILE_NAME: $e")
            }

            // 2) external file next to the jar (this is the one admins edit)
            val external = File(FILE_NAME)
            if (external.isFile) {
                try {
                    external.inputStream().use { stream ->
                        val p = Properties()
                        p.load(stream)
                        cfg.applyProperties(p)
                    }
                    Log.info("loaded ${external.absolutePath}")
                } catch (e: Exception) {
                    Log.warn("could not read ${external.absolutePath}: $e")
                }
            }

            // 3) command line
            cfg.applyArgs(args)

            Log.setLevel(cfg.logLevel)
            cfg.validate()
            return cfg
        }
    }
}
