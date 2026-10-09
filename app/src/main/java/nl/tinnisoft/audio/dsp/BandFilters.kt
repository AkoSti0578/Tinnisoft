package nl.tinnisoft.audio.dsp

import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Wide band-stop ("notched noise") filter.
 *
 * The stop band spans [center / 2^(w/2), center * 2^(w/2)]. It is built as
 * 8th-order Butterworth low-pass (below the band) + 8th-order high-pass (above the band),
 * summed, followed by three notches spread over the band so the whole band is deeply removed.
 * Gives > 25 dB attenuation across most of the band with a flat pass band.
 */
class NotchFilter(private val fs: Double) {
    private val lp = ButterworthFilter(8, highPass = false)
    private val hp = ButterworthFilter(8, highPass = true)
    private val notches = Array(3) { Biquad() }
    private var useLow = true
    private var useHigh = true

    fun set(centerHz: Double, widthOct: Double) {
        val w = widthOct.coerceIn(0.1, 3.0)
        val low = centerHz * 2.0.pow(-w / 2)
        val high = centerHz * 2.0.pow(w / 2)
        useLow = low >= 20.0
        useHigh = high <= fs * 0.45
        if (useLow) lp.set(fs, low)
        if (useHigh) hp.set(fs, high)
        // Three notches inside the band (at -w/4, 0, +w/4 octave) flatten the stop band floor.
        val bw = w / 4
        val q = sqrt(2.0.pow(bw)) / (2.0.pow(bw) - 1)
        for (i in notches.indices) {
            val f = centerHz * 2.0.pow((i - 1) * w / 4)
            if (f < fs * 0.47) notches[i].setNotch(fs, f, q) else notches[i].setPassThrough()
        }
    }

    fun process(x: Float): Float {
        var y = 0f
        if (useLow) y += lp.process(x)
        if (useHigh) y += hp.process(x)
        if (!useLow && !useHigh) y = x
        for (n in notches) y = n.process(y)
        return y
    }
}

/**
 * Narrow band-pass: 4th-order high-pass followed by 4th-order low-pass.
 * [gainCompensation] scales white input so the band keeps roughly the input RMS.
 */
class BandPassFilter(private val fs: Double) {
    private val hp = ButterworthFilter(4, highPass = true)
    private val lp = ButterworthFilter(4, highPass = false)

    var gainCompensation = 1f
        private set

    fun set(centerHz: Double, widthOct: Double) {
        val w = widthOct.coerceIn(0.05, 3.0)
        val low = (centerHz * 2.0.pow(-w / 2)).coerceAtLeast(20.0)
        val high = (centerHz * 2.0.pow(w / 2)).coerceAtMost(fs * 0.47)
        hp.set(fs, low)
        lp.set(fs, high)
        val fraction = ((high - low) / (fs / 2)).coerceIn(1e-4, 1.0)
        gainCompensation = (1.0 / sqrt(fraction)).toFloat().coerceAtMost(40f)
    }

    fun process(x: Float): Float = lp.process(hp.process(x))
}
