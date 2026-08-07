package com.lanfps.client

import android.util.Log

/**
 * Tiny logging facade.
 *
 * Everything goes to logcat under one tag (`adb logcat -s LANFPS`) and the last
 * [RING_SIZE] lines are kept in memory so the in-game debug overlay and the
 * "connection failed" screen can show what actually happened without the user
 * needing a PC.
 */
object AndroidLog {

    const val TAG = "LANFPS"
    private const val RING_SIZE = 80

    private val ring = ArrayDeque<String>()
    private val lock = Any()

    @Volatile
    var verbose: Boolean = false

    fun d(msg: String) {
        if (verbose) Log.d(TAG, msg)
        push("D $msg")
    }

    fun i(msg: String) {
        Log.i(TAG, msg)
        push("I $msg")
    }

    fun w(msg: String) {
        Log.w(TAG, msg)
        push("W $msg")
    }

    fun e(msg: String, t: Throwable? = null) {
        if (t != null) Log.e(TAG, msg, t) else Log.e(TAG, msg)
        push("E $msg" + if (t != null) " (${t.javaClass.simpleName}: ${t.message})" else "")
    }

    private fun push(line: String) {
        synchronized(lock) {
            ring.addLast(line)
            while (ring.size > RING_SIZE) ring.removeFirst()
        }
    }

    /** Most recent [n] lines, oldest first. Used by the error screen. */
    fun tail(n: Int): List<String> = synchronized(lock) {
        if (ring.size <= n) ring.toList() else ring.toList().subList(ring.size - n, ring.size)
    }
}
