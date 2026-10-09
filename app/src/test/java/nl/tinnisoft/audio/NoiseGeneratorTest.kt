package nl.tinnisoft.audio

import nl.tinnisoft.audio.SpectrumUtil.FS
import nl.tinnisoft.audio.dsp.BandPassFilter
import nl.tinnisoft.audio.dsp.MonoSource
import nl.tinnisoft.audio.dsp.NoiseFactory
import nl.tinnisoft.audio.dsp.NotchFilter
import nl.tinnisoft.audio.dsp.TARGET_RMS
import nl.tinnisoft.audio.dsp.WhiteNoise
import nl.tinnisoft.data.model.NoiseColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class NoiseGeneratorTest {

    private val seconds = 10
    private val skip = 48000 // let filters settle

    private fun render(src: MonoSource, n: Int = (FS * seconds).toInt()) = FloatArray(n) { src.next() }

    private fun slopeOf(color: NoiseColor): Double {
        val x = render(NoiseFactory.create(color, 1234L, FS))
        return SpectrumUtil.slopePerOctave(SpectrumUtil.psd(x, skip), 250.0, 4000.0)
    }

    @Test
    fun whiteIsFlat() = assertEquals(0.0, slopeOf(NoiseColor.WHITE), 0.5)

    @Test
    fun pinkFallsThreeDbPerOctave() = assertEquals(-3.0, slopeOf(NoiseColor.PINK), 0.6)

    @Test
    fun brownFallsSixDbPerOctave() = assertEquals(-6.0, slopeOf(NoiseColor.BROWN), 0.8)

    @Test
    fun blueRisesThreeDbPerOctave() = assertEquals(3.0, slopeOf(NoiseColor.BLUE), 0.8)

    @Test
    fun violetRisesSixDbPerOctave() = assertEquals(6.0, slopeOf(NoiseColor.VIOLET), 0.8)

    @Test
    fun greyFollowsInverseEqualLoudness() {
        val p = SpectrumUtil.psd(render(NoiseFactory.create(NoiseColor.GREY, 7L, FS)), skip)
        val at1k = SpectrumUtil.at(p, 1000.0)
        assertTrue("lows boosted", SpectrumUtil.at(p, 80.0) > at1k + 6)
        assertTrue("3.5 kHz dipped", SpectrumUtil.at(p, 3500.0) < at1k - 3)
        assertTrue("highs lifted", SpectrumUtil.at(p, 14000.0) > SpectrumUtil.at(p, 3500.0) + 6)
    }

    @Test
    fun allColorsHaveTargetLoudnessAndNoClipping() {
        for (c in NoiseColor.entries) {
            val x = render(NoiseFactory.create(c, 99L, FS))
            val rms = SpectrumUtil.rms(x, skip)
            assertEquals("RMS of $c", TARGET_RMS.toDouble(), rms, TARGET_RMS * 0.15)
            val peak = x.drop(skip).maxOf { abs(it) }
            assertTrue("peak of $c = $peak", peak < 0.8f)
        }
    }

    @Test
    fun generatorsDoNotRepeat() {
        // Compare two 1-second windows 5 s apart: correlation must be ~0.
        val x = render(NoiseFactory.create(NoiseColor.WHITE, 5L, FS))
        var dot = 0.0
        var e = 0.0
        for (i in 0 until 48000) {
            dot += x[i] * x[i + 5 * 48000].toDouble()
            e += x[i] * x[i].toDouble()
        }
        assertTrue(abs(dot / e) < 0.05)
    }

    @Test
    fun notchRemovesOneOctaveAroundCenter() {
        val notch = NotchFilter(FS).apply { set(4000.0, 1.0) }
        val white = WhiteNoise(3L)
        val x = render({ notch.process(white.next()) })
        val p = SpectrumUtil.psd(x, skip)
        val pass = SpectrumUtil.at(p, 1000.0)
        assertEquals("pass band low", pass, SpectrumUtil.at(p, 500.0), 1.5)
        assertEquals("pass band high", pass, SpectrumUtil.at(p, 12000.0), 1.5)
        assertTrue("center", SpectrumUtil.bandDb(p, 3780.0, 4230.0) < pass - 25)
        // inner half of the band (±1/4 octave)
        assertTrue("inner band", SpectrumUtil.bandDb(p, 3364.0, 4757.0) < pass - 20)
        // edges of the 1-octave band (2828..5657 Hz) are around -3 dB
        assertTrue(SpectrumUtil.bandDb(p, 2700.0, 2950.0) < pass - 1)
    }

    @Test
    fun notchWorksAtExtremes() {
        for (f in listOf(250.0, 16000.0)) {
            val notch = NotchFilter(FS).apply { set(f, 1.0) }
            val white = WhiteNoise(4L)
            val p = SpectrumUtil.psd(render({ notch.process(white.next()) }), skip)
            val ref = if (f < 1000) SpectrumUtil.at(p, 2000.0) else SpectrumUtil.at(p, 4000.0)
            assertTrue("center $f", SpectrumUtil.at(p, f) < ref - 20)
        }
    }

    @Test
    fun narrowBandConcentratesEnergy() {
        val bp = BandPassFilter(FS).apply { set(6000.0, 0.5) }
        val white = WhiteNoise(8L)
        val x = render({ bp.process(white.next()) * bp.gainCompensation })
        val p = SpectrumUtil.psd(x, skip)
        val center = SpectrumUtil.at(p, 6000.0)
        assertTrue(SpectrumUtil.at(p, 2000.0) < center - 30)
        assertTrue(SpectrumUtil.at(p, 16000.0) < center - 20)
        assertEquals(TARGET_RMS.toDouble(), SpectrumUtil.rms(x, skip), TARGET_RMS * 0.35)
    }
}
