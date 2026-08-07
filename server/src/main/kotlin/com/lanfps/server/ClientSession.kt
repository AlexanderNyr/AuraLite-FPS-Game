package com.lanfps.server

import com.lanfps.shared.GameConstants
import com.lanfps.shared.InputCommand
import java.net.InetAddress
import java.util.ArrayDeque

/**
 * Everything the server knows about one connected Android client.
 *
 * Input handling is the interesting part. Clients send each command several
 * times (see [GameConstants.INPUT_REDUNDANCY]) so a lost datagram self-heals.
 * The session de-duplicates by sequence number, queues what is new, and the
 * simulation consumes exactly one command per tick — with a token bucket so a
 * tampered client cannot buy extra movement by flooding inputs.
 */
class ClientSession(
    @JvmField val id: Int,
    @JvmField val address: InetAddress,
    @JvmField val port: Int,
    nickname: String,
) {
    @JvmField var nickname: String = nickname

    /** Stable key for the address:port -> session map. */
    val key: String = endpointKey(address, port)

    @JvmField var lastPacketTimeMs: Long = System.currentTimeMillis()
    @JvmField var connectedAtMs: Long = System.currentTimeMillis()

    /** Highest input sequence accepted into the queue (16-bit, wraps). */
    @JvmField var highestInputSeq: Int = -1

    /** Highest input sequence actually applied to the simulation. */
    @JvmField var lastProcessedInputSeq: Int = 0

    /** RTT as measured by the client itself; display only. */
    @JvmField var reportedPingMs: Int = 0

    /** Outgoing snapshot sequence for this client. */
    @JvmField var snapshotSequence: Int = 0

    @JvmField var inputPacketsReceived: Long = 0
    @JvmField var commandsApplied: Long = 0
    @JvmField var commandsDropped: Long = 0

    private val queue = ArrayDeque<InputCommand>()

    /** The last command applied — reused when the queue starves. */
    @JvmField val lastApplied: InputCommand = InputCommand()

    @JvmField var hasAppliedAny: Boolean = false

    /** Consecutive ticks with no fresh input. */
    @JvmField var starvedTicks: Int = 0

    // Token bucket limiting applied commands per second.
    private var tokens: Float = GameConstants.MAX_INPUTS_PER_SECOND.toFloat()

    fun touch(nowMs: Long) {
        lastPacketTimeMs = nowMs
    }

    fun isTimedOut(nowMs: Long): Boolean =
        nowMs - lastPacketTimeMs > GameConstants.SERVER_TIMEOUT_MS

    /**
     * Accepts a batch of (possibly redundant) commands, keeping only ones newer
     * than anything seen before.
     */
    fun enqueueInputs(commands: List<InputCommand>) {
        inputPacketsReceived++
        for (cmd in commands) {
            if (highestInputSeq >= 0 && !InputCommand.sequenceGreaterThan(cmd.sequence, highestInputSeq)) {
                continue // duplicate or out-of-order straggler
            }
            highestInputSeq = cmd.sequence
            if (queue.size >= MAX_QUEUED_INPUTS) {
                // Client is far ahead (lag spike recovery); drop the oldest so we
                // converge on the newest intent instead of replaying stale input.
                queue.pollFirst()
                commandsDropped++
            }
            queue.addLast(cmd.copy().sanitize())
        }
    }

    /** Refills the anti-cheat token bucket. Call once per simulation tick. */
    fun refillTokens(dt: Float) {
        tokens += GameConstants.MAX_INPUTS_PER_SECOND * dt
        if (tokens > GameConstants.MAX_INPUTS_PER_SECOND.toFloat()) {
            tokens = GameConstants.MAX_INPUTS_PER_SECOND.toFloat()
        }
    }

    /**
     * Returns the command to simulate this tick, or null when the client has
     * gone quiet for too long (then the entity simply stands still).
     */
    fun nextCommand(): InputCommand? {
        if (tokens < 1f) return null

        val cmd = queue.pollFirst()
        if (cmd != null) {
            tokens -= 1f
            starvedTicks = 0
            lastApplied.copyFrom(cmd)
            hasAppliedAny = true
            lastProcessedInputSeq = cmd.sequence
            commandsApplied++
            return cmd
        }

        if (!hasAppliedAny) return null

        // Starvation: briefly keep the last intent so a single lost packet does
        // not visibly stutter, then stop moving entirely.
        starvedTicks++
        tokens -= 1f
        return if (starvedTicks <= STARVE_EXTRAPOLATE_TICKS) {
            // Repeat movement but never repeat "fire" or "jump".
            lastApplied.buttons = 0
            lastApplied
        } else {
            lastApplied.buttons = 0
            lastApplied.moveForward = 0f
            lastApplied.moveRight = 0f
            lastApplied
        }
    }

    /** How many commands are waiting — useful for lag diagnostics. */
    fun queuedInputs(): Int = queue.size

    fun clearInputs() = queue.clear()

    override fun toString(): String = "$nickname(#$id @$key)"

    companion object {
        const val MAX_QUEUED_INPUTS = 12
        const val STARVE_EXTRAPOLATE_TICKS = 6

        fun endpointKey(address: InetAddress, port: Int): String =
            address.hostAddress + ":" + port
    }
}
