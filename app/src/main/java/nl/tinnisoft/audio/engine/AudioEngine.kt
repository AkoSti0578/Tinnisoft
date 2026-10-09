package nl.tinnisoft.audio.engine

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import nl.tinnisoft.audio.engine.pure.MixRenderer
import nl.tinnisoft.audio.engine.pure.ToneParams
import nl.tinnisoft.data.model.MixConfig
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * Real-time audio output. Generates the mix with [MixRenderer] on a dedicated
 * high-priority thread and streams it to an [AudioTrack] (PCM float, 48 kHz, stereo).
 *
 * All public methods are thread-safe: they post the latest value into atomic slots that
 * the audio thread picks up at the start of every block. The thread only runs while
 * sound is audible, so a paused engine costs no battery.
 */
class AudioEngine(private val loopCache: NatureLoopCache) {

    enum class State { STOPPED, PLAYING, FADING_OUT }

    private val _state = MutableStateFlow(State.STOPPED)
    val state: StateFlow<State> = _state

    /** Fraction of real time the audio thread spends rendering (0..1). */
    private val _cpuLoad = MutableStateFlow(0f)
    val cpuLoad: StateFlow<Float> = _cpuLoad

    private val pendingMix = AtomicReference<MixConfig?>(null)
    private val pendingTone = AtomicReference<Any?>(null) // ToneParams or NO_TONE
    private val pendingFade = AtomicReference<FloatArray?>(null) // [target, seconds]

    @Volatile private var gain = 0f
    @Volatile private var maxGain = 1f
    @Volatile private var relaxedLatency = false
    @Volatile private var thread: Thread? = null
    @Volatile private var lastMix: MixConfig = MixConfig()
    @Volatile private var onSilent: (() -> Unit)? = null

    private val loader = Executors.newSingleThreadExecutor { r -> Thread(r, "tinnisoft-loader") }
    private val lock = Any()

    fun setMix(mix: MixConfig) {
        lastMix = mix
        // Decoding recorded loops can take a moment: do it off the audio thread.
        loader.execute {
            loopCache.preload(mix)
            pendingMix.set(mix)
        }
    }

    fun setTone(tone: ToneParams?) {
        pendingTone.set(tone ?: NO_TONE)
    }

    fun setGain(linear: Float) {
        gain = linear
    }

    fun setMaxGain(linear: Float) {
        maxGain = linear
    }

    /** Larger buffers and power-saving mode: more latency, fewer wake-ups, less battery. */
    fun setRelaxedLatency(relaxed: Boolean) {
        if (relaxed == relaxedLatency) return
        relaxedLatency = relaxed
        // takes effect at the next start
    }

    /** Start (or keep) playing, fading in over [fadeInSec]. */
    fun start(fadeInSec: Float) {
        synchronized(lock) {
            pendingFade.set(floatArrayOf(1f, fadeInSec))
            onSilent = null
            if (thread == null) {
                val t = Thread({ runLoop() }, "tinnisoft-audio")
                thread = t
                _state.value = State.PLAYING
                t.start()
            } else {
                _state.value = State.PLAYING
            }
        }
    }

    /** Fade out over [fadeOutSec] and then stop the thread; [onStopped] runs on the audio thread. */
    fun stop(fadeOutSec: Float, onStopped: (() -> Unit)? = null) {
        synchronized(lock) {
            if (thread == null) {
                onStopped?.invoke()
                return
            }
            onSilent = onStopped
            pendingFade.set(floatArrayOf(0f, fadeOutSec))
            _state.value = State.FADING_OUT
        }
    }

    private fun buildTrack(): Pair<AudioTrack, Int> {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build()
        val minBytes = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
            .coerceAtLeast(4096)
        val relaxed = relaxedLatency
        // relaxed: ~400 ms buffer, rendered in ~85 ms chunks; normal: ~2x minimum, ~10 ms chunks
        val bytes = if (relaxed) maxOf(minBytes * 4, SAMPLE_RATE * 8 * 2 / 5) else minBytes * 2
        val chunkFrames = if (relaxed) 4096 else 512
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(format)
            .setBufferSizeInBytes(bytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(
                if (relaxed) AudioTrack.PERFORMANCE_MODE_POWER_SAVING else AudioTrack.PERFORMANCE_MODE_NONE,
            )
            .build()
        return track to chunkFrames
    }

    private fun runLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val renderer = MixRenderer(SAMPLE_RATE.toDouble(), loopCache)
        renderer.setMaxGain(maxGain)
        renderer.setGain(gain, 0)
        loopCache.preload(lastMix)
        renderer.applyMix(pendingMix.getAndSet(null) ?: lastMix)
        var track: AudioTrack? = null
        try {
            val (t, chunk) = buildTrack()
            track = t
            val buf = FloatArray(chunk * 2)
            t.play()
            var appliedGain = Float.NaN
            var appliedMax = Float.NaN
            var busyNs = 0L
            var windowStart = SystemClock.elapsedRealtimeNanos()
            while (true) {
                pendingMix.getAndSet(null)?.let { renderer.applyMix(it) }
                pendingTone.getAndSet(null)?.let { renderer.setTone(it as? ToneParams) }
                pendingFade.getAndSet(null)?.let { renderer.fadeTo(it[0], it[1]) }
                if (maxGain != appliedMax) {
                    appliedMax = maxGain
                    renderer.setMaxGain(appliedMax)
                }
                if (gain != appliedGain) {
                    appliedGain = gain
                    renderer.setGain(appliedGain)
                }
                val t0 = SystemClock.elapsedRealtimeNanos()
                renderer.render(buf, chunk)
                busyNs += SystemClock.elapsedRealtimeNanos() - t0
                val written = t.write(buf, 0, buf.size, AudioTrack.WRITE_BLOCKING)
                if (written < 0) {
                    Log.w(TAG, "AudioTrack write error $written")
                    break
                }
                val now = SystemClock.elapsedRealtimeNanos()
                if (now - windowStart > 5_000_000_000L) {
                    _cpuLoad.value = busyNs.toFloat() / (now - windowStart)
                    busyNs = 0
                    windowStart = now
                }
                synchronized(lock) {
                    if (renderer.isSilent && pendingFade.get() == null && _state.value == State.FADING_OUT) {
                        thread = null
                        _state.value = State.STOPPED
                    }
                }
                if (thread !== Thread.currentThread()) break
            }
        } catch (e: Exception) {
            Log.e(TAG, "audio thread failed", e)
            synchronized(lock) {
                if (thread === Thread.currentThread()) thread = null
                _state.value = State.STOPPED
            }
        } finally {
            try {
                track?.pause()
                track?.flush()
                track?.release()
            } catch (_: Exception) {
            }
            val cb = onSilent
            onSilent = null
            cb?.invoke()
        }
    }

    companion object {
        const val SAMPLE_RATE = 48000
        private const val TAG = "AudioEngine"
        private val NO_TONE = Any()
    }
}
