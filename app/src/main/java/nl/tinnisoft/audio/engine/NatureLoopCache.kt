package nl.tinnisoft.audio.engine

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import nl.tinnisoft.audio.dsp.LoopPlayer
import nl.tinnisoft.audio.dsp.SeamlessLoop
import nl.tinnisoft.audio.dsp.StereoSource
import nl.tinnisoft.audio.dsp.TARGET_RMS
import nl.tinnisoft.audio.engine.pure.NatureLoopProvider
import nl.tinnisoft.data.model.MixConfig
import nl.tinnisoft.data.model.NatureSound
import nl.tinnisoft.data.model.SourceKind
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt

/**
 * Optional recorded nature sounds. Put a CC0 recording in `assets/nature/<name>.ogg`
 * (e.g. `rain.ogg`) and it replaces the synthesized version. The file is decoded once,
 * resampled to 48 kHz, normalized and turned into a seamless loop with a 3 s equal-power
 * crossfade. Without files every nature sound is synthesized in real time.
 */
class NatureLoopCache(private val context: Context) : NatureLoopProvider {

    private class Loop(val left: FloatArray, val right: FloatArray, val gain: Float)

    /** Decoded loops, or [NONE] when no recording exists. Read lock-free by the audio thread. */
    private val cache = ConcurrentHashMap<NatureSound, Any>()

    @Synchronized
    fun preload(mix: MixConfig) {
        for (l in mix.layers) if (l.kind == SourceKind.NATURE) load(l.nature)
    }

    /** Only returns already-decoded loops; never decodes (or blocks) on the audio thread. */
    override fun loopFor(sound: NatureSound): StereoSource? {
        val loop = cache[sound] as? Loop ?: return null
        return LoopPlayer(loop.left, loop.right, loop.gain)
    }

    private fun load(sound: NatureSound) {
        if (cache.containsKey(sound)) return
        cache[sound] = runCatching { decode(sound) }.onFailure { Log.w(TAG, "decode $sound failed", it) }.getOrNull() ?: NONE
    }

    private fun assetName(sound: NatureSound): String? {
        val base = sound.name.lowercase()
        val files = runCatching { context.assets.list("nature")?.toList() }.getOrNull() ?: return null
        return files.firstOrNull { it.substringBeforeLast('.') == base }?.let { "nature/$it" }
    }

    private fun decode(sound: NatureSound): Loop? {
        val name = assetName(sound) ?: return null
        val afd = context.assets.openFd(name)
        val extractor = MediaExtractor()
        extractor.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
        afd.close()
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: return null
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()
        var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val pcm = ShortArrayBuilder()
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        while (!outputDone) {
            if (!inputDone) {
                val inIdx = codec.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    val buf = codec.getInputBuffer(inIdx)!!
                    val size = extractor.readSampleData(buf, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val outIdx = codec.dequeueOutputBuffer(info, 10_000)
            if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                sampleRate = codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                channels = codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else if (outIdx >= 0) {
                val out = codec.getOutputBuffer(outIdx)!!.order(ByteOrder.nativeOrder()).asShortBuffer()
                while (out.hasRemaining()) pcm.add(out.get())
                codec.releaseOutputBuffer(outIdx, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
            }
        }
        codec.stop()
        codec.release()
        extractor.release()

        val frames = pcm.size / channels
        if (frames < sampleRate * 6) return null // too short to loop nicely
        val l = FloatArray(frames)
        val r = FloatArray(frames)
        for (i in 0 until frames) {
            l[i] = pcm[i * channels] / 32768f
            r[i] = pcm[i * channels + (if (channels > 1) 1 else 0)] / 32768f
        }
        val rl = resample(l, sampleRate, AudioEngine.SAMPLE_RATE)
        val rr = resample(r, sampleRate, AudioEngine.SAMPLE_RATE)
        val xf = AudioEngine.SAMPLE_RATE * 3
        val loopL = SeamlessLoop.build(rl, xf)
        val loopR = SeamlessLoop.build(rr, xf)
        var s = 0.0
        for (i in loopL.indices) s += (loopL[i] * loopL[i] + loopR[i] * loopR[i]).toDouble()
        val rms = sqrt(s / (2 * loopL.size)).toFloat().coerceAtLeast(1e-4f)
        return Loop(loopL, loopR, TARGET_RMS / rms)
    }

    private fun resample(x: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to) return x
        val n = (x.size.toLong() * to / from).toInt()
        val ratio = from.toDouble() / to
        return FloatArray(n) { i ->
            val pos = i * ratio
            val k = pos.toInt().coerceAtMost(x.size - 2)
            val f = (pos - k).toFloat()
            x[k] * (1 - f) + x[k + 1] * f
        }
    }

    private class ShortArrayBuilder {
        private var data = ShortArray(1 shl 16)
        var size = 0
            private set

        fun add(v: Short) {
            if (size == data.size) data = data.copyOf(size * 2)
            data[size++] = v
        }

        operator fun get(i: Int) = data[i]
    }

    private companion object {
        const val TAG = "NatureLoopCache"
        val NONE = Any()
    }
}
