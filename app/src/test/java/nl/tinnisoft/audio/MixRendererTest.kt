package nl.tinnisoft.audio

import nl.tinnisoft.audio.engine.pure.MixRenderer
import nl.tinnisoft.audio.engine.pure.ToneParams
import nl.tinnisoft.data.model.LayerConfig
import nl.tinnisoft.data.model.MixConfig
import nl.tinnisoft.data.model.NatureSound
import nl.tinnisoft.data.model.NoiseColor
import nl.tinnisoft.data.model.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class MixRendererTest {
    private val fs = 48000.0

    private fun MixRenderer.renderSeconds(s: Double, sink: MutableList<Float>) {
        val frames = (fs * s).toInt()
        val buf = FloatArray(frames * 2)
        render(buf, frames)
        for (i in 0 until frames) sink += (buf[2 * i] + buf[2 * i + 1]) * 0.5f
    }

    /** Ratios between the RMS of adjacent 5 ms windows; a click or jump shows as a big ratio. */
    private fun maxEnvelopeJump(x: List<Float>, floor: Double = 2e-3): Double {
        val w = 240
        var prev = -1.0
        var worst = 1.0
        var i = 0
        while (i + w <= x.size) {
            var s = 0.0
            for (k in i until i + w) s += x[k] * x[k].toDouble()
            val rms = sqrt(s / w)
            if (prev > floor && rms > floor) worst = maxOf(worst, rms / prev, prev / rms)
            prev = rms
            i += w
        }
        return worst
    }

    private fun white(vol: Float = 0.8f) = LayerConfig(SourceKind.NOISE, NoiseColor.WHITE, volume = vol)

    @Test
    fun startStopAndSwitchingAreClickFree() {
        val r = MixRenderer(fs)
        val out = ArrayList<Float>()
        r.setGain(0.5f)
        r.applyMix(MixConfig(listOf(white())))
        r.fadeTo(1f, 0.5f)
        r.renderSeconds(1.0, out)
        // preset switch to a different source
        r.applyMix(MixConfig(listOf(LayerConfig(SourceKind.NOISE, NoiseColor.VIOLET, volume = 0.8f))))
        r.renderSeconds(1.0, out)
        // volume jump
        r.setGain(0.25f)
        r.renderSeconds(0.5, out)
        // notch frequency sweep
        r.applyMix(MixConfig(listOf(LayerConfig(SourceKind.NOTCHED, NoiseColor.WHITE, centerHz = 2000f))))
        r.renderSeconds(0.5, out)
        r.applyMix(MixConfig(listOf(LayerConfig(SourceKind.NOTCHED, NoiseColor.WHITE, centerHz = 6000f))))
        r.renderSeconds(0.5, out)
        r.fadeTo(0f, 0.5f)
        r.renderSeconds(1.0, out)
        assertTrue(r.isSilent)
        assertEquals(0f, out.last(), 0f)
        val jump = maxEnvelopeJump(out)
        assertTrue("envelope jump ratio $jump", jump < 1.6)
        // and no sample-level spikes
        val peak = out.maxOf { abs(it) }
        assertTrue(peak < 0.5f)
    }

    @Test
    fun startsFromSilence() {
        val r = MixRenderer(fs)
        r.setGain(1f, 0)
        r.applyMix(MixConfig(listOf(white())))
        r.fadeTo(1f, 5f)
        val out = ArrayList<Float>()
        r.renderSeconds(0.01, out)
        // after 10 ms of a 5 s fade-in the level is still tiny
        assertTrue(out.maxOf { abs(it) } < 0.001f)
    }

    @Test
    fun hardCeilingWinsOverRequestedGain() {
        fun level(maxGain: Float, gain: Float): Double {
            val r = MixRenderer(fs)
            r.setMaxGain(maxGain)
            r.setGain(gain, 0)
            r.applyMix(MixConfig(listOf(white(1f))))
            r.fadeTo(1f, 0.02f)
            val out = ArrayList<Float>()
            r.renderSeconds(2.0, out)
            return SpectrumUtil.rms(out.drop(48000).toFloatArray())
        }
        val reference = level(1f, 1f)
        val capped = level(0.25f, 1f)
        assertEquals(0.25, capped / reference, 0.02)
    }

    @Test
    fun threeLayersNeverClip() {
        val r = MixRenderer(fs)
        r.setGain(1f, 0)
        r.applyMix(
            MixConfig(
                listOf(
                    LayerConfig(SourceKind.NOISE, NoiseColor.BROWN, volume = 1f),
                    LayerConfig(SourceKind.NATURE, nature = NatureSound.WIND, volume = 1f),
                    LayerConfig(SourceKind.NOISE, NoiseColor.WHITE, volume = 1f),
                ),
                eqGainsDb = List(8) { 12f },
            ),
        )
        r.fadeTo(1f, 0.02f)
        val buf = FloatArray(48000 * 2 * 5)
        r.render(buf, 48000 * 5)
        assertTrue(buf.all { abs(it) <= 1f })
    }

    @Test
    fun matcherToneStartsSoftAndReplacesTheMix() {
        val r = MixRenderer(fs)
        r.setGain(1f, 0)
        r.applyMix(MixConfig(listOf(white())))
        r.fadeTo(1f, 0.02f)
        r.setTone(ToneParams(4000f, narrowBand = false, level = 0.5f))
        val out = ArrayList<Float>()
        r.renderSeconds(0.05, out)
        val early = SpectrumUtil.rms(out.takeLast(480).toFloatArray())
        r.renderSeconds(3.0, out)
        val p = SpectrumUtil.psd(out.takeLast(48000).toFloatArray())
        // energy concentrated at 4 kHz, the noise mix is gone
        assertTrue(SpectrumUtil.at(p, 4000.0) > SpectrumUtil.at(p, 1000.0) + 40)
        assertTrue(early > 0)
    }

    @Test
    fun matcherToneRampsUpSlowly() {
        val r = MixRenderer(fs)
        r.setGain(1f, 0)
        r.applyMix(MixConfig(layers = emptyList()))
        r.fadeTo(1f, 0.02f)
        r.setTone(ToneParams(1000f, narrowBand = true, level = 0.6f))
        val out = ArrayList<Float>()
        r.renderSeconds(0.1, out)
        val early = SpectrumUtil.rms(out.takeLast(960).toFloatArray())
        r.renderSeconds(2.0, out)
        val late = SpectrumUtil.rms(out.takeLast(4800).toFloatArray())
        assertTrue("tone ramps up: early=$early late=$late", early < late * 0.15)
    }
}
