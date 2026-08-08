package com.lanfps.server

import com.lanfps.shared.GameConstants
import com.lanfps.shared.InputCommand
import com.lanfps.shared.Packets
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
    @JvmField var address: InetAddress,
    @JvmField var port: Int,
    nickname: String,
) {
    @JvmField var nickname: String = nickname

    /** Key for the address:port -> session map. `var` so a reconnect that comes
     *  back on a new socket can re-bind the same session under a new key. */
    var key: String = endpointKey(address, port)

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

    /** P0-2: opaque token the client must present to resume this session. */
    @JvmField var resumeToken: Int = 0

    /** P0-2: true while this session is a reconnectable zombie (silent but kept). */
    @JvmField var zombie: Boolean = false

    /** Max silence before the session goes zombie. Defaults to
     *  [GameConstants.SERVER_TIMEOUT_MS]; server config can tune it. */
    @JvmField var serverTimeoutMs: Long = GameConstants.SERVER_TIMEOUT_MS

    /** P0-2: epoch ms after which a zombie session is finally reclaimed. */
    @JvmField var zombieDeadlineMs: Long = 0L

    // ---- P1-1: server-measured RTT (used by lag compensation) -------------
    /** When the server last sent a PING to this client (ms), for RTT timing. */
    @JvmField var lastServerPingSentMs: Long = 0L
    /** Server-measured round-trip time, EMA-smoothed. */
    @JvmField var smoothedRttMs: Double = 0.0

    // ---- P3-3: per-session observability ----------------------------------
    /** Input sequence numbers that never arrived (gaps in the received stream).
     *  A rough one-way loss estimate of the client's uplink. */
    @JvmField var inputSeqGaps: Long = 0
    /** Total new input sequences accepted (denominator for the loss estimate). */
    @JvmField var inputSeqAccepted: Long = 0

    /** Reservoir of recent RTT samples (ms) for percentile reporting. */
    private val rttSamples = DoubleArray(RTT_SAMPLE_CAP)
    private var rttSampleCount: Int = 0

    /** Records one server-measured RTT sample. */
    fun addRttSample(rttMs: Double) {
        rttSamples[(rttSampleCount++) % RTT_SAMPLE_CAP] = rttMs
    }

    /** P95 RTT in ms over the recent reservoir (0 when no samples yet). */
    fun rttP95(): Double {
        val n = minOf(rttSampleCount, RTT_SAMPLE_CAP)
        if (n == 0) return 0.0
        val copy = DoubleArray(n) { rttSamples[it] }
        copy.sort()
        return copy[((n - 1) * 0.95).toInt()]
    }

    /** Estimated uplink packet loss in percent, 0..100. */
    fun inputLossEstimatePct(): Double {
        val total = inputSeqGaps + inputSeqAccepted
        if (total == 0L) return 0.0
        return inputSeqGaps.toDouble() * 100.0 / total.toDouble()
    }

    // ---- P1-4: reliable match events -------------------------------------
    /** Unacknowledged MATCH_EVENTs waiting for this client to ack them. */
    private val pendingEvents = ArrayDeque<Packets.MatchEvent>()

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
        nowMs - lastPacketTimeMs > serverTimeoutMs

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
            // P3-3: a jump in the sequence means the ones in between never made
            // it (UDP loss upstream of us). Out-of-order stragglers within the
            // redundancy window are already filtered by the check above.
            if (highestInputSeq >= 0) {
                val gap = (cmd.sequence - highestInputSeq - 1) and 0xFFFF
                if (gap in 1..32) {
                    inputSeqGaps += gap.toLong()
                }
            }
            inputSeqAccepted++
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

    /**
     * Catch-up variant used when the client got ahead during a lag spike.
     *
     * The normal path consumes exactly one command per tick, so after a 300 ms
     * stall the queue holds ~18 commands and [MAX_QUEUED_INPUTS] starts
     * dropping the oldest — each drop is a guaranteed client/server position
     * divergence, which the client then feels as a correction (a rubber-band
     * or, above its hard-snap threshold, a teleport). Serving a second
     * command per tick while the queue is deep drains the backlog twice as
     * fast as it accumulates, with the token bucket (90/s) still capping the
     * absolute input rate an anti-cheat standpoint.
     *
     * @return the number of commands this tick wants to consume beyond the
     *         first, or 0 when the queue is shallow or tokens are exhausted.
     */
    fun extraCatchUpInputs(): Int {
        if (queue.size < CATCH_UP_QUEUE_MIN) return 0
        val budget = kotlin.math.floor(tokens).toInt() - 1
        if (budget <= 0) return 0
        return minOf(queue.size - 1, budget, CATCH_UP_MAX_PER_TICK)
    }

    /** How many commands are waiting — useful for lag diagnostics. */
    fun queuedInputs(): Int = queue.size

    fun clearInputs() = queue.clear()

    // ---- P1-4: reliable match events -------------------------------------

    /** Queues a new event for delivery (and re-delivery until acked). */
    fun addPendingEvent(ev: Packets.MatchEvent) {
        pendingEvents.addLast(ev)
        while (pendingEvents.size > GameConstants.MAX_PENDING_MATCH_EVENTS) {
            pendingEvents.removeFirst()
        }
    }

    /** Drops every event the client has acknowledged (its ack is the newest
     *  event sequence it has processed). Uses 16-bit wraparound comparison. */
    fun acknowledgeEvents(ackSeq: Int) {
        while (pendingEvents.isNotEmpty() &&
            !InputCommand.sequenceGreaterThan(pendingEvents.first().eventSeq, ackSeq)
        ) {
            pendingEvents.removeFirst()
        }
    }

    /** Snapshot of all currently-unacknowledged events, oldest first. */
    fun drainPendingEvents(): List<Packets.MatchEvent> = pendingEvents.toList()

    val pendingEventCount: Int get() = pendingEvents.size

    override fun toString(): String = "$nickname(#$id @$key)"

    companion object {
        const val MAX_QUEUED_INPUTS = 12
        const val STARVE_EXTRAPOLATE_TICKS = 6

        /** Start catch-up once the client is this many commands ahead (~100 ms). */
        const val CATCH_UP_QUEUE_MIN = 6

        /** Never consume more than 1+2 commands in a single tick. */
        const val CATCH_UP_MAX_PER_TICK = 2
        private const val RTT_SAMPLE_CAP = 48

        fun endpointKey(address: InetAddress, port: Int): String =
            address.hostAddress + ":" + port
    }
}
