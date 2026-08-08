package com.lanfps.client

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import java.util.EnumMap
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * P1-3: procedural audio — every sound is synthesised into PCM at runtime, so
 * the game ships zero audio assets (consistent with the whole project's
 * "no third-party content" rule).
 *
 * A gunshot is white noise under an exponential decay; a hit is a short tick;
 * damage/death/respawn/match-end are short oscillator sweeps.
 *
 * Threading model (this used to be a real bug):
 *
 * `AudioTrack.write()` in MODE_STREAM **blocks the caller until the stream
 * buffer drains**, and the longest clips here (death = 0.40 s) are more than
 * twice the size of that buffer. The sound API is called from NetworkClient's
 * tx thread — the same thread that drains snapshots, reconciles prediction and
 * sends input. A blocking write there stalled the whole netcode for up to
 * half a second: inputs stopped flowing, the server started extrapolating, the
 * incoming snapshots piled up, and the user saw a freeze followed by a violent
 * correction jump exactly when a damage/death packet arrived.
 *
 * So now all game threads ever do is a non-blocking `offer` into a bounded
 * queue; a single dedicated audio thread owns the AudioTrack and is the only
 * thread ever allowed to block on it. Sounds are also synthesised ONCE and
 * cached, instead of being regenerated per call (remote gunshots used to pay
 * for a 4 000-sample noise burst on every firing snapshot).
 *
 * When the queue is full, droppable requests (gunshots — the next one is 100 ms
 * away anyway) are discarded; one-shot feedback (death, match end) is kept by
 * evicting the oldest droppable request. A lost sound is invisible; a lost
 * frame is not.
 *
 * All callers must be tolerant of audio being unavailable (silent, never
 * throwing) — that contract is unchanged.
 */
object SoundManager {

    private const val SAMPLE_RATE = 22050

    /** Deep enough for a skirmish; when full, gunshots get dropped. */
    private const val QUEUE_CAPACITY = 8

    /** Longest clip is death (0.40 s); the writer's scratch buffer fits any. */
    private val MAX_CLIP_SAMPLES = (SAMPLE_RATE * 0.45f).toInt()

    private enum class Clip { GUNSHOT, HIT, DAMAGE, DEATH, RESPAWN, MATCH_END_A, MATCH_END_B }

    private class Request(
        @JvmField val clip: Clip,
        @JvmField val volume: Float,
        @JvmField val droppable: Boolean,
    )

    private val requests = ArrayBlockingQueue<Request>(QUEUE_CAPACITY)
    private val running = AtomicBoolean(false)
    private val startLock = Any()

    @Volatile
    private var writer: Thread? = null

    // ---- clip cache ---------------------------------------------------------
    // Synthesised lazily on first use, on the writer thread, exactly once.
    private val clipCache = EnumMap<Clip, ShortArray>(Clip::class.java)
    private val cacheLock = Any()

    @Volatile
    private var cacheReady = false

    private fun clip(kind: Clip): ShortArray? {
        if (!cacheReady) {
            synchronized(cacheLock) {
                if (!cacheReady) {
                    try {
                        clipCache[Clip.GUNSHOT] = noiseBurst(0.18f, 38f)
                        clipCache[Clip.HIT] = noiseBurst(0.05f, 90f)
                        clipCache[Clip.DAMAGE] = tone(0.16f, 160f, 90f, 0.35f)
                        clipCache[Clip.DEATH] = tone(0.40f, 220f, 55f, 0.35f)
                        clipCache[Clip.RESPAWN] = tone(0.25f, 180f, 520f, 0.22f)
                        clipCache[Clip.MATCH_END_A] = tone(0.12f, 660f, 660f, 0.22f)
                        clipCache[Clip.MATCH_END_B] = tone(0.20f, 880f, 880f, 0.22f)
                        cacheReady = true
                    } catch (_: Exception) {
                        return null // audio init failed: stay silent
                    }
                }
            }
        }
        return clipCache[kind]
    }

    // ------------------------------------------------------------- public API
    // Every call below is O(1) and never blocks: either the request fits in the
    // queue, or it is dropped. Safe to invoke from any thread at any rate.

    fun gunshot(volume: Float = 1f) = enqueue(Clip.GUNSHOT, volume * 0.8f, droppable = true)
    fun hit(volume: Float = 1f) = enqueue(Clip.HIT, volume * 0.5f, droppable = false)
    fun damage(volume: Float = 1f) = enqueue(Clip.DAMAGE, volume * 0.7f, droppable = false)
    fun death(volume: Float = 1f) = enqueue(Clip.DEATH, volume * 0.8f, droppable = false)
    fun respawn(volume: Float = 1f) = enqueue(Clip.RESPAWN, volume * 0.6f, droppable = false)

    fun matchEnd(volume: Float = 1f) {
        enqueue(Clip.MATCH_END_A, volume * 0.6f, droppable = false)
        enqueue(Clip.MATCH_END_B, volume * 0.6f, droppable = false)
    }

    private fun enqueue(clip: Clip, volume: Float, droppable: Boolean) {
        if (volume <= 0.01f) return
        if (!running.get()) startWriter()

        if (requests.offer(Request(clip, volume, droppable))) return

        // Queue full. Gunshots are lost constantly in a firefight without anyone
        // noticing; one-shot feedback (death, match end) must survive, so it
        // evicts the oldest queued gunshot for a slot.
        if (!droppable) {
            val it = requests.iterator()
            while (it.hasNext()) {
                if (it.next().droppable) {
                    it.remove()
                    break
                }
            }
            requests.offer(Request(clip, volume, droppable))
        }
        // A dropped droppable sound is silently lost. That is the intent.
    }

    // ------------------------------------------------------------- writer

    private fun startWriter() {
        synchronized(startLock) {
            if (running.get()) return
            running.set(true)
            writer = Thread({ writerLoop() }, "lanfps-audio").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY - 1
                start()
            }
        }
    }

    /** The ONLY thread that may touch (and block on) the AudioTrack. */
    private fun writerLoop() {
        val track = createTrack()
        if (track == null) {
            running.set(false)
            return
        }
        // Reused for every clip: game threads must never allocate here either.
        val scratch = ShortArray(MAX_CLIP_SAMPLES)

        try {
            track.play()
        } catch (_: Exception) {
            try {
                track.release()
            } catch (_: Exception) {
            }
            running.set(false)
            return
        }

        try {
            while (running.get()) {
                val req = try {
                    requests.take()
                } catch (_: InterruptedException) {
                    break // release() was called
                }
                val pcm = clip(req.clip) ?: continue
                val n = min(pcm.size, scratch.size)
                val volume = req.volume
                for (i in 0 until n) {
                    scratch[i] = (pcm[i].toInt() * volume).toInt()
                        .coerceIn(-32768, 32767).toShort()
                }
                var offset = 0
                // Blocking on purpose — but only THIS thread can be blocked.
                while (offset < n && running.get()) {
                    val written = try {
                        track.write(scratch, offset, n - offset)
                    } catch (_: Exception) {
                        -1
                    }
                    if (written <= 0) break
                    offset += written
                }
            }
        } finally {
            try {
                track.stop()
            } catch (_: Exception) {
            }
            try {
                track.release()
            } catch (_: Exception) {
            }
            running.set(false)
        }
    }

    /** Lazy, defensive AudioTrack creation. Returns null if audio is unavailable. */
    private fun createTrack(): AudioTrack? {
        val minBuf = try {
            AudioTrack.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
            )
        } catch (_: Exception) {
            4096
        }
        return try {
            AudioTrack(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
                maxOf(minBuf, 8192),
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE,
            ).also {
                if (it.state != AudioTrack.STATE_INITIALIZED) {
                    it.release()
                    return null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Releases the audio thread and its AudioTrack (call from Activity.onDestroy). */
    fun release() {
        running.set(false)
        requests.clear()
        val thread = writer
        thread?.interrupt() // wakes take() out of the idle wait
        try {
            // A blocking write in flight is at most ~0.45 s of clip; give the
            // writer room to exit cleanly, never hang the UI thread on it.
            thread?.join(700)
        } catch (_: InterruptedException) {
        }
        writer = null
        // The writer's finally-block stops/releases the AudioTrack.
    }

    // ---- synthesis (runs once, at first use) --------------------------------

    /** White noise with an exponential decay envelope. */
    private fun noiseBurst(seconds: Float, decayRate: Float = 40f): ShortArray {
        val n = (SAMPLE_RATE * seconds).toInt().coerceIn(1, MAX_CLIP_SAMPLES)
        val out = ShortArray(n)
        // Fixed seed: the same clip every run, so behaviour is reproducible.
        val r = java.util.Random(0x5EED)
        val amp = 0.35f * 32767f
        for (i in 0 until n) {
            val t = i.toFloat() / SAMPLE_RATE
            val env = exp(-decayRate * t)
            out[i] = ((r.nextFloat() * 2f - 1f) * amp * env).toInt().toShort()
        }
        return out
    }

    /** A short oscillator sweep (tone) with an amplitude envelope. */
    private fun tone(seconds: Float, startHz: Float, endHz: Float, amp: Float = 0.30f): ShortArray {
        val n = (SAMPLE_RATE * seconds).toInt().coerceIn(1, MAX_CLIP_SAMPLES)
        val out = ShortArray(n)
        var phase = 0f
        for (i in 0 until n) {
            val t = i.toFloat() / SAMPLE_RATE
            val freq = startHz + (endHz - startHz) * (i.toFloat() / n)
            phase += 2f * PI.toFloat() * freq / SAMPLE_RATE
            val env = exp(-6f * t)
            out[i] = (sin(phase) * amp * env * 32767f).toInt().toShort()
        }
        return out
    }
}
