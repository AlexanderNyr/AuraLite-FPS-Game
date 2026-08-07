package com.lanfps.server

import java.text.SimpleDateFormat
import java.util.Date

/**
 * Dependency-free console logger.
 *
 * The server is a headless Windows console app, so logging goes to stdout with a
 * timestamp and level. No logging framework is pulled in.
 */
object Log {

    enum class Level(val rank: Int) { DEBUG(0), INFO(1), WARN(2), ERROR(3) }

    @Volatile
    var level: Level = Level.INFO

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS")
    private val lock = Any()

    fun setLevel(name: String) {
        level = when (name.trim().uppercase()) {
            "DEBUG", "TRACE" -> Level.DEBUG
            "WARN", "WARNING" -> Level.WARN
            "ERROR" -> Level.ERROR
            else -> Level.INFO
        }
    }

    fun debug(msg: String) = log(Level.DEBUG, msg)
    fun info(msg: String) = log(Level.INFO, msg)
    fun warn(msg: String) = log(Level.WARN, msg)
    fun error(msg: String) = log(Level.ERROR, msg)

    fun error(msg: String, t: Throwable) {
        log(Level.ERROR, msg + ": " + t)
        if (level.rank <= Level.DEBUG.rank) t.printStackTrace()
    }

    /** Prints without decoration — used for the startup banner. */
    fun raw(msg: String) {
        synchronized(lock) { println(msg) }
    }

    private fun log(l: Level, msg: String) {
        if (l.rank < level.rank) return
        synchronized(lock) {
            val ts = timeFormat.format(Date())
            println("$ts [${l.name}] $msg")
        }
    }
}
