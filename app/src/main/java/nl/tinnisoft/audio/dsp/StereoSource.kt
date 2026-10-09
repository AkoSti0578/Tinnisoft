package nl.tinnisoft.audio.dsp

/**
 * A stereo layer source. Writes (overwrites) [frames] samples into [left] and [right].
 * Called on the audio thread only.
 */
interface StereoSource {
    fun render(left: FloatArray, right: FloatArray, frames: Int)
}

/** Slowly varying random value in [0, 1], smoothed by two one-pole filters. */
class SmoothRandom(seed: Long, rateHz: Double, private val fs: Double) {
    private val rnd = FastRandom(seed)
    private var target = rnd.nextUnit()
    private var s1 = target
    private var s2 = target
    private val period = (fs / rateHz).toInt().coerceAtLeast(1)
    private var counter = period
    private val a = (1.0 - kotlin.math.exp(-2.0 * Math.PI * rateHz / fs)).toFloat()

    fun next(): Float {
        if (--counter <= 0) {
            counter = (period * (0.5f + rnd.nextUnit())).toInt().coerceAtLeast(1)
            target = rnd.nextUnit()
        }
        s1 += (target - s1) * a
        s2 += (s1 - s2) * a
        return s2
    }

    /** Advance by [n] samples and return the value (cheap, for block-rate control). */
    fun advance(n: Int): Float {
        var v = s2
        repeat(n) { v = next() }
        return v
    }
}

/** Cheap one-pole low-pass, coefficient can be changed every sample. */
class OnePole {
    private var y = 0f
    var coefficient = 1f

    fun setCutoff(fs: Double, hz: Double) {
        coefficient = (1.0 - kotlin.math.exp(-2.0 * Math.PI * hz / fs)).toFloat()
    }

    fun process(x: Float): Float {
        y += (x - y) * coefficient
        return y
    }
}
