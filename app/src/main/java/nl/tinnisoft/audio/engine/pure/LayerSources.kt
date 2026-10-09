package nl.tinnisoft.audio.engine.pure

import nl.tinnisoft.audio.dsp.BandPassFilter
import nl.tinnisoft.audio.dsp.Glide
import nl.tinnisoft.audio.dsp.MonoSource
import nl.tinnisoft.audio.dsp.NoiseFactory
import nl.tinnisoft.audio.dsp.NotchFilter
import nl.tinnisoft.audio.dsp.StereoSource
import nl.tinnisoft.audio.dsp.WhiteNoise
import nl.tinnisoft.audio.nature.NatureSynthFactory
import nl.tinnisoft.data.model.LayerConfig
import nl.tinnisoft.data.model.NatureSound
import nl.tinnisoft.data.model.NoiseColor
import nl.tinnisoft.data.model.SourceKind

/** A layer source whose parameters (frequency, width) can be changed while running. */
interface LayerSource : StereoSource {
    fun update(config: LayerConfig) {}
}

/** Decorrelated stereo noise: an independent generator per ear. */
class NoiseLayer(color: NoiseColor, seed: Long, fs: Double) : LayerSource {
    private val l: MonoSource = NoiseFactory.create(color, seed, fs)
    private val r: MonoSource = NoiseFactory.create(color, seed * 31 + 17, fs)

    override fun render(left: FloatArray, right: FloatArray, frames: Int) {
        for (i in 0 until frames) {
            left[i] = l.next()
            right[i] = r.next()
        }
    }
}

/** Colored noise with a band around the tinnitus frequency removed. */
class NotchedLayer(color: NoiseColor, seed: Long, private val fs: Double, config: LayerConfig) : LayerSource {
    private val l: MonoSource = NoiseFactory.create(color, seed, fs)
    private val r: MonoSource = NoiseFactory.create(color, seed * 31 + 17, fs)
    private val fl = NotchFilter(fs)
    private val fr = NotchFilter(fs)
    private val center = Glide(config.centerHz)
    private val width = Glide(config.notchWidthOct)

    init {
        apply()
    }

    override fun update(config: LayerConfig) {
        center.target = config.centerHz
        width.target = config.notchWidthOct
    }

    private fun apply() {
        fl.set(center.value.toDouble(), width.value.toDouble())
        fr.set(center.value.toDouble(), width.value.toDouble())
    }

    override fun render(left: FloatArray, right: FloatArray, frames: Int) {
        val a = center.step()
        val b = width.step()
        if (a || b) apply()
        for (i in 0 until frames) {
            left[i] = fl.process(l.next())
            right[i] = fr.process(r.next())
        }
    }
}

/** Narrow-band noise centered on the tinnitus frequency. */
class NarrowBandLayer(seed: Long, private val fs: Double, center: Float, widthOct: Float) : LayerSource {
    private val l = WhiteNoise(seed)
    private val r = WhiteNoise(seed * 31 + 17)
    private val fl = BandPassFilter(fs)
    private val fr = BandPassFilter(fs)
    private val centerGlide = Glide(center)
    private val widthGlide = Glide(widthOct)

    init {
        apply()
    }

    override fun update(config: LayerConfig) {
        centerGlide.target = config.centerHz
        widthGlide.target = config.bandWidthOct
    }

    /** Used by the frequency matcher: change frequency directly. */
    fun setTarget(centerHz: Float, widthOct: Float) {
        centerGlide.target = centerHz
        widthGlide.target = widthOct
    }

    private fun apply() {
        fl.set(centerGlide.value.toDouble(), widthGlide.value.toDouble())
        fr.set(centerGlide.value.toDouble(), widthGlide.value.toDouble())
    }

    override fun render(left: FloatArray, right: FloatArray, frames: Int) {
        val a = centerGlide.step()
        val b = widthGlide.step()
        if (a || b) apply()
        val g = fl.gainCompensation
        for (i in 0 until frames) {
            left[i] = fl.process(l.next()) * g
            right[i] = fr.process(r.next()) * g
        }
    }
}

class NatureLayer(private val source: StereoSource) : LayerSource {
    override fun render(left: FloatArray, right: FloatArray, frames: Int) = source.render(left, right, frames)
}

/** Supplies a decoded, seamlessly looped recording for a nature sound, or null to synthesize. */
fun interface NatureLoopProvider {
    fun loopFor(sound: NatureSound): StereoSource?
}

object LayerFactory {
    fun create(config: LayerConfig, seed: Long, fs: Double, loops: NatureLoopProvider): LayerSource = when (config.kind) {
        SourceKind.NOISE -> NoiseLayer(config.color, seed, fs)
        SourceKind.NOTCHED -> NotchedLayer(config.color, seed, fs, config)
        SourceKind.NARROWBAND -> NarrowBandLayer(seed, fs, config.centerHz, config.bandWidthOct)
        SourceKind.NATURE -> NatureLayer(loops.loopFor(config.nature) ?: NatureSynthFactory.create(config.nature, seed, fs))
    }
}
