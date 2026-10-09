package nl.tinnisoft.audio.engine.pure

import nl.tinnisoft.audio.dsp.Equalizer
import nl.tinnisoft.audio.dsp.Modulator
import nl.tinnisoft.audio.dsp.Ramp
import nl.tinnisoft.audio.dsp.SoftLimiter
import nl.tinnisoft.data.model.LayerConfig
import nl.tinnisoft.data.model.MixConfig
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/** Tone used by the frequency matcher. */
data class ToneParams(
    val frequencyHz: Float,
    /** false = pure sine, true = narrow band of noise (1/6 octave). */
    val narrowBand: Boolean,
    /** 0..1, same scale as the main volume (mapped through [VolumeLimiter.levelToGain]). */
    val level: Float,
)

/**
 * Renders the complete mix (all layers, EQ, modulation, balance, matcher tone, gain and
 * fades) to interleaved stereo floats. Pure Kotlin; must be used from one thread only
 * (the audio thread). Every change is ramped so nothing ever clicks.
 */
class MixRenderer(
    private val fs: Double,
    private val loops: NatureLoopProvider = NatureLoopProvider { null },
) {
    private class ActiveLayer(var config: LayerConfig, val source: LayerSource) {
        val gain = Ramp(0f)
        var retiring = false
    }

    private val layers = ArrayList<ActiveLayer>(6)
    private val eqL = Equalizer(fs, MixConfig.EQ_BANDS_HZ)
    private val eqR = Equalizer(fs, MixConfig.EQ_BANDS_HZ)
    private val modulator = Modulator(fs)
    private val balL = Ramp(1f)
    private val balR = Ramp(1f)

    private val gain = Ramp(0f)
    private var maxGain = 1f
    private val fade = Ramp(0f)
    private val mixBus = Ramp(1f)
    private val toneBus = Ramp(0f)

    private var tone: ToneParams? = null
    private var tonePhase = 0.0
    private var toneFreq = 1000.0
    private val toneGain = Ramp(0f)
    private var toneNoise: NarrowBandLayer? = null
    private var toneNarrow = false

    private val tmpL = FloatArray(BLOCK)
    private val tmpR = FloatArray(BLOCK)
    private val sumL = FloatArray(BLOCK)
    private val sumR = FloatArray(BLOCK)
    private var seedCounter = System.nanoTime()

    private fun ms(m: Int) = (fs * m / 1000).toInt()

    // ---- control (audio thread) ----

    fun applyMix(mix: MixConfig) {
        val wanted = mix.normalized().layers
        val keep = HashSet<ActiveLayer>()
        val newLayers = ArrayList<ActiveLayer>()
        for (cfg in wanted) {
            val existing = layers.firstOrNull { !it.retiring && it !in keep && it.config.identity == cfg.identity }
            if (existing != null) {
                keep += existing
                existing.config = cfg
                existing.source.update(cfg)
                existing.gain.setTarget(layerGain(cfg.volume), ms(LAYER_RAMP_MS))
                newLayers += existing
            } else {
                val src = LayerFactory.create(cfg, seedCounter++, fs, loops)
                newLayers += ActiveLayer(cfg, src).also { it.gain.setTarget(layerGain(cfg.volume), ms(CROSSFADE_MS)) }
            }
        }
        // Layers that are no longer wanted fade out and are removed when silent.
        for (old in layers) {
            if (old in keep) continue
            old.retiring = true
            old.gain.setTarget(0f, ms(CROSSFADE_MS))
            newLayers += old
        }
        layers.clear()
        layers += newLayers

        eqL.setGains(mix.eqGainsDb)
        eqR.setGains(mix.eqGainsDb)
        modulator.set(mix.modulationEnabled, mix.modulationDepth, mix.modulationPeriodSec)
        val b = mix.balance.coerceIn(-1f, 1f)
        balL.setTarget(if (b > 0) 1f - b else 1f, ms(LAYER_RAMP_MS))
        balR.setTarget(if (b < 0) 1f + b else 1f, ms(LAYER_RAMP_MS))
    }

    fun setTone(params: ToneParams?) {
        tone = params
        if (params == null) {
            toneBus.setTarget(0f, ms(CROSSFADE_MS))
            mixBus.setTarget(1f, ms(CROSSFADE_MS))
            return
        }
        mixBus.setTarget(0f, ms(CROSSFADE_MS))
        toneBus.setTarget(1f, ms(CROSSFADE_MS))
        if (params.narrowBand != toneNarrow) {
            // switch type: dip the tone briefly to avoid a click
            toneGain.jumpTo(0f)
            toneNarrow = params.narrowBand
        }
        if (params.narrowBand) {
            val n = toneNoise ?: NarrowBandLayer(seedCounter++, fs, params.frequencyHz, TONE_BAND_OCT).also { toneNoise = it }
            n.setTarget(params.frequencyHz, TONE_BAND_OCT)
        }
        // the matcher always starts softly: ramps take 1.5 s upward, 100 ms downward
        val target = VolumeLimiter.levelToGain(params.level)
        val up = target > toneGain.value
        toneGain.setTarget(target, if (up) ms(1500) else ms(100))
    }

    /** Final app gain (already limited by the caller). [maxGain] is a hard ceiling on top. */
    fun setGain(linear: Float, rampMs: Int = 80) {
        gain.setTarget(linear.coerceIn(0f, maxGain), ms(rampMs))
    }

    fun setMaxGain(linear: Float) {
        maxGain = linear.coerceIn(0f, 1f)
        if (gain.target > maxGain) gain.setTarget(maxGain, ms(80))
    }

    /** Fade the master to [target] (0 or 1) over [seconds]. */
    fun fadeTo(target: Float, seconds: Float) {
        fade.setTarget(target, (fs * seconds.coerceAtLeast(0.02f)).toInt())
    }

    val fadeValue: Float get() = fade.value
    val isFadingOut: Boolean get() = fade.target == 0f && fade.isRamping
    val isSilent: Boolean get() = fade.target == 0f && !fade.isRamping

    // ---- rendering ----

    /** Render [frames] stereo frames into [out] (interleaved L, R). */
    fun render(out: FloatArray, frames: Int) {
        var done = 0
        while (done < frames) {
            val n = minOf(BLOCK, frames - done)
            renderBlock(out, done, n)
            done += n
        }
        layers.removeAll { it.retiring && !it.gain.isRamping && it.gain.value == 0f }
    }

    private fun renderBlock(out: FloatArray, offset: Int, n: Int) {
        java.util.Arrays.fill(sumL, 0, n, 0f)
        java.util.Arrays.fill(sumR, 0, n, 0f)
        val mixAudible = mixBus.value > 0f || mixBus.isRamping
        if (mixAudible) {
            for (layer in layers) {
                if (layer.gain.value == 0f && !layer.gain.isRamping) continue
                layer.source.render(tmpL, tmpR, n)
                for (i in 0 until n) {
                    val g = layer.gain.next()
                    sumL[i] += tmpL[i] * g
                    sumR[i] += tmpR[i] * g
                }
            }
        }
        val toneActive = toneBus.value > 0f || toneBus.isRamping
        val noise = toneNoise
        if (toneActive && toneNarrow && noise != null) noise.render(tmpL, tmpR, n)
        val toneTarget = tone?.frequencyHz?.toDouble() ?: toneFreq

        var o = offset * 2
        for (i in 0 until n) {
            val mb = mixBus.next()
            var l = eqL.process(sumL[i]) * mb
            var r = eqR.process(sumR[i]) * mb
            val m = modulator.next()
            l *= m
            r *= m
            if (toneActive) {
                val tb = toneBus.next() * toneGain.next()
                val s = if (toneNarrow && noise != null) {
                    (tmpL[i] + tmpR[i]) * 0.5f
                } else {
                    toneFreq += (toneTarget - toneFreq) * 0.0005
                    tonePhase += 2 * PI * toneFreq / fs
                    if (tonePhase > 2 * PI) tonePhase -= 2 * PI
                    // sine at the same RMS as the noise sources
                    sin(tonePhase).toFloat() * TONE_SINE_PEAK
                }
                l += s * tb
                r += s * tb
            }
            val f = fade.next()
            val master = gain.next() * f * f
            out[o++] = SoftLimiter.process(l * balL.next() * master)
            out[o++] = SoftLimiter.process(r * balR.next() * master)
        }
    }

    private fun layerGain(volume: Float): Float {
        if (volume <= 0f) return 0f
        // 40 dB range for layer faders, perceptually even
        return 10f.pow((volume.coerceAtMost(1f) - 1f) * 2f) * LAYER_HEADROOM
    }

    companion object {
        const val BLOCK = 256
        const val CROSSFADE_MS = 400
        const val LAYER_RAMP_MS = 60
        const val TONE_BAND_OCT = 1f / 6f
        private const val TONE_SINE_PEAK = 0.1414f
        /** Sources run at 0.1 RMS; x2 brings one layer to ~-14 dBFS, three layers stay < 0 dBFS. */
        private const val LAYER_HEADROOM = 2f
    }
}
