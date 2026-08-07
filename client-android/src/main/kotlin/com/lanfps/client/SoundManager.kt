package com.lanfps.client

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * P1-3: procedural audio — every sound is synthesised into PCM at runtime, so
 * the game ships zero audio assets (consistent with the whole project's
 * "no third-party content" rule).
 *
 * A gunshot is white noise under an exponential decay; a hit is a short tick;
 * damage/death/respawn/match-end are short oscillator sweeps. All are generated
 * into a [ShortArray] and played through one [AudioTrack].
 *
 * Thread safety: every play() call is serialised on [lock] because AudioTrack is
 * not safe to call from multiple threads (NetworkClient's tx thread and the GL
 * thread both fire sounds). All callers must be tolerant of audio being
 * unavailable (silent, never throwing).
 */
object SoundManager {

    private const val SAMPLE_RATE = 22050

    private val lock = Any()
    private var track: AudioTrack? = null

    /** Lazy, defensive AudioTrack creation. Returns null if audio is unavailable. */
    private fun track(): AudioTrack? {
        track?.let { return it }
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
            ).also {
                if (it.state == AudioTrack.STATE_INITIALIZED) {
                    track = it
                } else {
                    it.release()
                    track = null
                }
            }
        } catch (_: Exception) {
            track = null
            null
        }
    }

    /** Plays pre-generated [samples] at [volume] (0..1). Never throws. */
    private fun play(samples: ShortArray, volume: Float) {
        val t = track() ?: return
        if (volume <= 0.01f) return
        val scaled = ShortArray(samples.size)
        for (i in samples.indices) scaled[i] = (samples[i].toInt() * volume).toShort()
        try {
            synchronized(lock) {
                t.write(scaled, 0, scaled.size)
                if (t.playState != AudioTrack.PLAYSTATE_PLAYING) t.play()
            }
        } catch (_: Exception) {
            // Audio unavailable/failed: stay silent, never crash the game.
        }
    }

    /** White noise with an exponential decay envelope. */
    private fun noiseBurst(seconds: Float, decayRate: Float = 40f): ShortArray {
        val n = (SAMPLE_RATE * seconds).toInt().coerceIn(1, 65536)
        val out = ShortArray(n)
        val r = java.util.Random()
        val amp = 0.35f * 32767f
        for (i in 0 until n) {
            val t = i.toFloat() / SAMPLE_RATE
            val env = exp(-decayRate * t)
            val noise = r.nextFloat() * 2f - 1f
            out[i] = (noise * amp * env).toInt().toShort()
        }
        return out
    }

    /** A short oscillator sweep (tone) with an amplitude envelope. */
    private fun tone(seconds: Float, startHz: Float, endHz: Float, amp: Float = 0.30f): ShortArray {
        val n = (SAMPLE_RATE * seconds).toInt().coerceIn(1, 65536)
        val out = ShortArray(n)
        var phase = 0f
        for (i in 0 until n) {
            val t = i.toFloat() / SAMPLE_RATE
            val freq = startHz + (endHz - startHz) * (i.toFloat() / n)
            phase += 2f * PI.toFloat() * freq / SAMPLE_RATE
            val env = exp(-6f * t) // decay envelope
            out[i] = (sin(phase) * amp * env * 32767f).toInt().toShort()
        }
        return out
    }

    // ------------------------------------------------------------- public API

    fun gunshot(volume: Float = 1f) = play(noiseBurst(0.18f, 38f), volume * 0.8f)
    fun hit(volume: Float = 1f) = play(noiseBurst(0.05f, 90f), volume * 0.5f)
    fun damage(volume: Float = 1f) = play(tone(0.16f, 160f, 90f, 0.35f), volume * 0.7f)
    fun death(volume: Float = 1f) = play(tone(0.40f, 220f, 55f, 0.35f), volume * 0.8f)
    fun respawn(volume: Float = 1f) = play(tone(0.25f, 180f, 520f, 0.22f), volume * 0.6f)
    fun matchEnd(volume: Float = 1f) {
        val first = tone(0.12f, 660f, 660f, 0.22f)
        val second = tone(0.20f, 880f, 880f, 0.22f)
        play(first, volume * 0.6f)
        play(second, volume * 0.6f)
    }

    /** Releases the underlying AudioTrack (call from Activity.onDestroy). */
    fun release() {
        synchronized(lock) {
            try {
                track?.stop()
            } catch (_: Exception) {
            }
            try {
                track?.release()
            } catch (_: Exception) {
            }
            track = null
        }
    }
}
