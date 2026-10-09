package nl.tinnisoft.audio.dsp

import nl.tinnisoft.data.model.NoiseColor

/** A mono sample source, called once per sample on the audio thread. */
fun interface MonoSource {
    fun next(): Float
}

/**
 * All colored noise generators are normalized to roughly [TARGET_RMS] so switching
 * colors does not cause a jump in loudness and summing three layers keeps headroom.
 * The constants were measured over 10 s of output (see NoiseGeneratorTest).
 */
const val TARGET_RMS = 0.1f

class WhiteNoise(seed: Long) : MonoSource {
    private val rnd = FastRandom(seed)
    override fun next(): Float = rnd.nextBipolar() * GAIN

    private companion object {
        // uniform [-1,1) has RMS 1/sqrt(3)
        const val GAIN = TARGET_RMS * 1.7320508f
    }
}

/** -3 dB/octave. Paul Kellet's refined filter (accurate to ±0.05 dB above 9.2 Hz). */
class PinkNoise(seed: Long) : MonoSource {
    private val rnd = FastRandom(seed)
    private var b0 = 0f
    private var b1 = 0f
    private var b2 = 0f
    private var b3 = 0f
    private var b4 = 0f
    private var b5 = 0f
    private var b6 = 0f

    fun nextRaw(): Float {
        val w = rnd.nextBipolar()
        b0 = 0.99886f * b0 + w * 0.0555179f
        b1 = 0.99332f * b1 + w * 0.0750759f
        b2 = 0.96900f * b2 + w * 0.1538520f
        b3 = 0.86650f * b3 + w * 0.3104856f
        b4 = 0.55000f * b4 + w * 0.5329522f
        b5 = -0.7616f * b5 - w * 0.0168980f
        val out = b0 + b1 + b2 + b3 + b4 + b5 + b6 + w * 0.5362f
        b6 = w * 0.115926f
        return out
    }

    override fun next(): Float = nextRaw() * GAIN

    companion object {
        const val GAIN = TARGET_RMS / 1.7882f
    }
}

/**
 * -6 dB/octave (Brownian / red). Leaky integrator; the leak (pole at 0.998, ≈15 Hz)
 * prevents DC drift, and a 20 Hz high-pass keeps sub-sonic rumble out of small drivers.
 */
class BrownNoise(seed: Long, fs: Double) : MonoSource {
    private val rnd = FastRandom(seed)
    private var y = 0f
    private val dcBlock = Biquad().apply { setHighPass(fs, 20.0, 0.707) }

    override fun next(): Float {
        y = 0.998f * y + rnd.nextBipolar() * 0.0632f
        return dcBlock.process(y) * GAIN
    }

    private companion object {
        const val GAIN = TARGET_RMS / 0.3840f
    }
}

/** +3 dB/octave: first difference of pink noise. */
class BlueNoise(seed: Long) : MonoSource {
    private val pink = PinkNoise(seed)
    private var last = 0f

    override fun next(): Float {
        val p = pink.nextRaw()
        val out = p - last
        last = p
        return out * GAIN
    }

    private companion object {
        const val GAIN = TARGET_RMS / 1.0475f
    }
}

/** +6 dB/octave: first difference of white noise. */
class VioletNoise(seed: Long) : MonoSource {
    private val rnd = FastRandom(seed)
    private var last = 0f

    override fun next(): Float {
        val w = rnd.nextBipolar()
        val out = w - last
        last = w
        // difference of two independent uniforms: RMS sqrt(2/3)
        return out * GAIN
    }

    private companion object {
        const val GAIN = TARGET_RMS * 1.2247449f
    }
}

/**
 * Grey noise: white noise shaped by an approximation of the inverse ISO 226
 * equal-loudness contour (~40 phon), so all frequencies are perceived equally loud.
 * Boost below ~300 Hz, a dip around the ear's most sensitive region (3–4 kHz)
 * and a lift above 9 kHz.
 */
class GreyNoise(seed: Long, fs: Double) : MonoSource {
    private val rnd = FastRandom(seed)
    private val low = Biquad().apply { setLowShelf(fs, 250.0, 14.0, 0.8) }
    private val dip = Biquad().apply { setPeaking(fs, 3500.0, 1.1, -7.0) }
    private val high = Biquad().apply { setHighShelf(fs, 9000.0, 7.0, 0.9) }
    private val sub = Biquad().apply { setHighPass(fs, 30.0, 0.707) }

    override fun next(): Float {
        val w = rnd.nextBipolar()
        return sub.process(high.process(dip.process(low.process(w)))) * GAIN
    }

    private companion object {
        const val GAIN = TARGET_RMS / 1.0098f
    }
}

object NoiseFactory {
    fun create(color: NoiseColor, seed: Long, fs: Double): MonoSource = when (color) {
        NoiseColor.WHITE -> WhiteNoise(seed)
        NoiseColor.PINK -> PinkNoise(seed)
        NoiseColor.BROWN -> BrownNoise(seed, fs)
        NoiseColor.BLUE -> BlueNoise(seed)
        NoiseColor.VIOLET -> VioletNoise(seed)
        NoiseColor.GREY -> GreyNoise(seed, fs)
    }
}
