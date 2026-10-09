package nl.tinnisoft.audio.nature

import nl.tinnisoft.audio.dsp.Biquad
import nl.tinnisoft.audio.dsp.BrownNoise
import nl.tinnisoft.audio.dsp.FastRandom
import nl.tinnisoft.audio.dsp.OnePole
import nl.tinnisoft.audio.dsp.PinkNoise
import nl.tinnisoft.audio.dsp.SmoothRandom
import nl.tinnisoft.audio.dsp.StereoSource
import nl.tinnisoft.audio.dsp.WhiteNoise
import nl.tinnisoft.data.model.NatureSound
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/*
 * Procedural nature sounds. They are synthesized in real time from random processes,
 * so there is no loop at all and nothing ever repeats. All are normalized to ~0.1 RMS
 * (same as the noise colors). Recorded CC0 loops in assets/nature/ take precedence
 * when present, see [NatureFactory].
 */

/** Pool of decaying two-pole resonators, used for rain drops and bubbles. */
private class ResonatorPool(size: Int, private val fs: Double) {
    private val c1 = FloatArray(size)
    private val c2 = FloatArray(size)
    private val y1 = FloatArray(size)
    private val y2 = FloatArray(size)
    private val pending = FloatArray(size)
    private val sweep = FloatArray(size)
    private val w = FloatArray(size)
    private val r = FloatArray(size)
    private val life = IntArray(size)
    private var nextSlot = 0

    /** [sweepPerSample] > 1 makes the pitch rise while ringing (bubbles). */
    fun trigger(freqHz: Double, decaySec: Double, amp: Float, sweepPerSample: Float = 1f) {
        val i = nextSlot
        nextSlot = (nextSlot + 1) % c1.size
        w[i] = (2 * PI * freqHz / fs).toFloat()
        r[i] = exp(-1.0 / (decaySec * fs)).toFloat()
        sweep[i] = sweepPerSample
        update(i)
        y1[i] = 0f
        y2[i] = 0f
        pending[i] = amp
        // ~-70 dB after 8 time constants
        life[i] = (decaySec * fs * 8).toInt()
    }

    private fun update(i: Int) {
        c1[i] = 2f * r[i] * cos(w[i].toDouble()).toFloat()
        c2[i] = -r[i] * r[i]
    }

    fun next(): Float {
        var sum = 0f
        for (i in c1.indices) {
            if (life[i] <= 0) continue
            life[i]--
            val y = c1[i] * y1[i] + c2[i] * y2[i] + pending[i]
            pending[i] = 0f
            y2[i] = y1[i]
            y1[i] = y
            if (sweep[i] != 1f && (life[i] and 15) == 0) {
                w[i] = (w[i] * sweep[i].pow16()).coerceAtMost(2.8f)
                update(i)
            }
            sum += y
        }
        return sum
    }

    private fun Float.pow16(): Float {
        var v = this
        repeat(4) { v *= v }
        return v
    }
}

class RainSynth(seed: Long, private val fs: Double) : StereoSource {
    private val chans = Array(2) { ch -> Channel(seed + ch * 7919) }

    private inner class Channel(seed: Long) {
        val rnd = FastRandom(seed)
        val pink = PinkNoise(seed + 1)
        val hp = Biquad().apply { setHighPass(fs, 450.0, 0.7) }
        val lp = Biquad().apply { setLowPass(fs, 9000.0, 0.7) }
        val flutter = SmoothRandom(seed + 2, 0.6, fs)
        val drops = ResonatorPool(10, fs)
        val dropHp = Biquad().apply { setHighPass(fs, 900.0, 0.7) }
        val ratePerSample = 45.0 / fs

        fun next(): Float {
            val bed = lp.process(hp.process(pink.next())) * (0.75f + 0.25f * flutter.next())
            if (rnd.nextUnit() < ratePerSample) {
                val f = 1800.0 + rnd.nextUnit() * 4200.0
                val amp = 0.002f + 0.012f * rnd.nextUnit() * rnd.nextUnit()
                drops.trigger(f, 0.002 + rnd.nextUnit() * 0.006, amp)
            }
            return bed * 1.7f + dropHp.process(drops.next()) * 1.0f
        }
    }

    override fun render(left: FloatArray, right: FloatArray, frames: Int) {
        for (i in 0 until frames) {
            left[i] = chans[0].next() * GAIN
            right[i] = chans[1].next() * GAIN
        }
    }

    private companion object {
        const val GAIN = 1.2f
    }
}

class WavesSynth(seed: Long, private val fs: Double) : StereoSource {
    private val rnd = FastRandom(seed)
    private val noiseL = BrownNoise(seed + 1, fs)
    private val noiseR = BrownNoise(seed + 2, fs)
    private val hissL = PinkNoise(seed + 3)
    private val hissR = PinkNoise(seed + 4)
    private val lpL1 = OnePole()
    private val lpL2 = OnePole()
    private val lpR1 = OnePole()
    private val lpR2 = OnePole()
    private val envSmoothL = OnePole().apply { setCutoff(fs, 3.0) }
    private val envSmoothR = OnePole().apply { setCutoff(fs, 3.0) }
    private var phase = 0.0
    private var periodSamples = newPeriod()
    private var strength = 0.7f + 0.3f * rnd.nextUnit()
    private var counter = 0

    private fun newPeriod() = (fs * (7.0 + rnd.nextUnit() * 6.0))

    private fun envelope(p: Double): Float {
        val e = if (p < 0.4) {
            val t = p / 0.4
            t * t * (3 - 2 * t)
        } else {
            exp(-(p - 0.4) * 4.5)
        }
        return (0.18 + 0.82 * e).toFloat()
    }

    override fun render(left: FloatArray, right: FloatArray, frames: Int) {
        for (i in 0 until frames) {
            phase += 1.0 / periodSamples
            if (phase >= 1.0) {
                phase -= 1.0
                periodSamples = newPeriod()
                strength = 0.6f + 0.4f * rnd.nextUnit()
            }
            val eL = envSmoothL.process(envelope(phase) * strength)
            val eR = envSmoothR.process(envelope((phase + 0.04) % 1.0) * strength)
            if (counter-- <= 0) {
                counter = 32
                lpL1.setCutoff(fs, 250.0 + 2600.0 * eL * eL)
                lpL2.coefficient = lpL1.coefficient
                lpR1.setCutoff(fs, 250.0 + 2600.0 * eR * eR)
                lpR2.coefficient = lpR1.coefficient
            }
            val l = lpL2.process(lpL1.process(noiseL.next() + hissL.next() * 0.8f))
            val r = lpR2.process(lpR1.process(noiseR.next() + hissR.next() * 0.8f))
            left[i] = l * eL * GAIN
            right[i] = r * eR * GAIN
        }
    }

    private companion object {
        const val GAIN = 1.6f
    }
}

class BrookSynth(seed: Long, private val fs: Double) : StereoSource {
    private val chans = Array(2) { Channel(seed + it * 104729) }

    private inner class Channel(seed: Long) {
        val rnd = FastRandom(seed)
        val white = WhiteNoise(seed + 1)
        val bp = Biquad().apply { setBandPass(fs, 2200.0, 0.6) }
        val m1 = SmoothRandom(seed + 2, 3.0, fs)
        val m2 = SmoothRandom(seed + 3, 7.0, fs)
        val m3 = SmoothRandom(seed + 4, 13.0, fs)
        val bubbles = ResonatorPool(6, fs)
        val ratePerSample = 9.0 / fs

        fun next(): Float {
            val mod = 0.35f + 0.65f * (m1.next() * 0.5f + m2.next() * 0.3f + m3.next() * 0.2f)
            val bed = bp.process(white.next()) * mod * 2.2f
            if (rnd.nextUnit() < ratePerSample) {
                val f = 450.0 + rnd.nextUnit() * 900.0
                // rising pitch, typical for bubbles
                bubbles.trigger(f, 0.012 + rnd.nextUnit() * 0.02, 0.004f + 0.01f * rnd.nextUnit(), 1.00006f)
            }
            return bed + bubbles.next()
        }
    }

    override fun render(left: FloatArray, right: FloatArray, frames: Int) {
        for (i in 0 until frames) {
            left[i] = chans[0].next() * GAIN
            right[i] = chans[1].next() * GAIN
        }
    }

    private companion object {
        const val GAIN = 1.4f
    }
}

class WindSynth(seed: Long, private val fs: Double) : StereoSource {
    private val chans = Array(2) { Channel(seed + it * 15485863) }

    private inner class Channel(seed: Long) {
        val white = WhiteNoise(seed)
        val brown = BrownNoise(seed + 1, fs)
        val bp = Biquad()
        val center = SmoothRandom(seed + 2, 0.15, fs)
        val gust = SmoothRandom(seed + 3, 0.08, fs)
        val q = SmoothRandom(seed + 4, 0.2, fs)
        var counter = 0
        var g = 0.5f

        fun next(): Float {
            if (counter-- <= 0) {
                counter = 64
                val c = center.advance(64)
                g = 0.25f + 0.75f * gust.advance(64)
                bp.setBandPass(fs, 220.0 + 750.0 * c * (0.6 + 0.4 * g), 1.2 + 2.5 * q.advance(64))
            }
            return (bp.process(white.next()) * 3.2f + brown.next() * 0.35f) * g
        }
    }

    override fun render(left: FloatArray, right: FloatArray, frames: Int) {
        for (i in 0 until frames) {
            left[i] = chans[0].next() * GAIN
            right[i] = chans[1].next() * GAIN
        }
    }

    private companion object {
        const val GAIN = 4.6f
    }
}

class CricketsSynth(seed: Long, private val fs: Double) : StereoSource {
    private class Cricket(val freq: Double, val pan: Float, val amp: Float, val periodSec: Double, val pulses: Int) {
        var phase = 0.0
        var t = 0.0 // seconds inside current chirp period
        var jitter = 0.0
    }

    private val rnd = FastRandom(seed)
    private val crickets = Array(4) {
        Cricket(
            freq = 4300.0 + rnd.nextUnit() * 900.0,
            pan = -0.8f + 1.6f * rnd.nextUnit(),
            amp = 0.03f + 0.05f * rnd.nextUnit(),
            periodSec = 0.55 + rnd.nextUnit() * 0.5,
            pulses = 3 + (rnd.nextUnit() * 2).toInt(),
        ).also { it.t = rnd.nextUnit() * it.periodSec }
    }
    private val bgL = PinkNoise(seed + 1)
    private val bgR = PinkNoise(seed + 2)
    private val lpL = Biquad().apply { setLowPass(fs, 1500.0, 0.7) }
    private val lpR = Biquad().apply { setLowPass(fs, 1500.0, 0.7) }
    private val dt = 1.0 / fs

    override fun render(left: FloatArray, right: FloatArray, frames: Int) {
        for (i in 0 until frames) {
            var l = lpL.process(bgL.next()) * 0.25f
            var r = lpR.process(bgR.next()) * 0.25f
            for (c in crickets) {
                c.t += dt
                if (c.t >= c.periodSec + c.jitter) {
                    c.t = 0.0
                    c.jitter = (rnd.nextUnit() - 0.5) * 0.15
                }
                // pulse train: 18 ms on, 12 ms off
                val k = (c.t / 0.030).toInt()
                if (k < c.pulses) {
                    val inPulse = (c.t - k * 0.030) / 0.018
                    if (inPulse < 1.0) {
                        val env = (0.5 - 0.5 * cos(2 * PI * inPulse)).toFloat()
                        c.phase += 2 * PI * c.freq * dt
                        if (c.phase > 2 * PI) c.phase -= 2 * PI
                        val s = sin(c.phase).toFloat() * env * c.amp
                        l += s * (1f - c.pan) * 0.5f
                        r += s * (1f + c.pan) * 0.5f
                    }
                }
            }
            left[i] = l * GAIN
            right[i] = r * GAIN
        }
    }

    private companion object {
        const val GAIN = 4.5f
    }
}

class FanSynth(seed: Long, private val fs: Double) : StereoSource {
    private val chans = Array(2) { Channel(seed + it * 31) }
    private var humPhase = 0.0

    private inner class Channel(seed: Long) {
        val brown = BrownNoise(seed, fs)
        val pink = PinkNoise(seed + 1)
        val lp = Biquad().apply { setLowPass(fs, 1100.0, 0.6) }
        fun next(): Float = brown.next() * 0.8f + lp.process(pink.next()) * 0.9f
    }

    override fun render(left: FloatArray, right: FloatArray, frames: Int) {
        val inc = 2 * PI * 118.0 / fs
        for (i in 0 until frames) {
            humPhase += inc
            if (humPhase > 2 * PI) humPhase -= 2 * PI
            val hum = sin(humPhase).toFloat() * 0.004f
            left[i] = (chans[0].next() + hum) * GAIN
            right[i] = (chans[1].next() + hum) * GAIN
        }
    }

    private companion object {
        const val GAIN = 0.96f
    }
}

object NatureSynthFactory {
    fun create(sound: NatureSound, seed: Long, fs: Double): StereoSource = when (sound) {
        NatureSound.RAIN -> RainSynth(seed, fs)
        NatureSound.WAVES -> WavesSynth(seed, fs)
        NatureSound.BROOK -> BrookSynth(seed, fs)
        NatureSound.WIND -> WindSynth(seed, fs)
        NatureSound.CRICKETS -> CricketsSynth(seed, fs)
        NatureSound.FAN -> FanSynth(seed, fs)
    }
}
