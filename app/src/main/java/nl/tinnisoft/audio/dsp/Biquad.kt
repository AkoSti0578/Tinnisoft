package nl.tinnisoft.audio.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Second-order IIR section (RBJ Audio EQ Cookbook), transposed direct form II.
 * State is kept in double precision so low cut-offs (20 Hz at 48 kHz) stay stable.
 * Coefficients can be changed while running; state is preserved for click-free sweeps.
 */
class Biquad {
    private var b0 = 1.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0
    private var z1 = 0.0
    private var z2 = 0.0

    /** When false the section passes audio through unchanged. */
    var enabled = true

    fun process(x: Float): Float {
        if (!enabled) return x
        val xd = x.toDouble()
        val y = b0 * xd + z1
        z1 = b1 * xd - a1 * y + z2
        z2 = b2 * xd - a2 * y
        return y.toFloat()
    }

    fun reset() {
        z1 = 0.0
        z2 = 0.0
    }

    fun setPassThrough() = setNormalized(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)

    fun setLowPass(fs: Double, f: Double, q: Double) {
        val (cw, alpha) = prep(fs, f, q)
        setNormalized((1 - cw) / 2, 1 - cw, (1 - cw) / 2, 1 + alpha, -2 * cw, 1 - alpha)
    }

    fun setHighPass(fs: Double, f: Double, q: Double) {
        val (cw, alpha) = prep(fs, f, q)
        setNormalized((1 + cw) / 2, -(1 + cw), (1 + cw) / 2, 1 + alpha, -2 * cw, 1 - alpha)
    }

    /** Band-pass with 0 dB peak gain. */
    fun setBandPass(fs: Double, f: Double, q: Double) {
        val (cw, alpha) = prep(fs, f, q)
        setNormalized(alpha, 0.0, -alpha, 1 + alpha, -2 * cw, 1 - alpha)
    }

    fun setNotch(fs: Double, f: Double, q: Double) {
        val (cw, alpha) = prep(fs, f, q)
        setNormalized(1.0, -2 * cw, 1.0, 1 + alpha, -2 * cw, 1 - alpha)
    }

    fun setPeaking(fs: Double, f: Double, q: Double, gainDb: Double) {
        val a = 10.0.pow(gainDb / 40)
        val (cw, alpha) = prep(fs, f, q)
        setNormalized(1 + alpha * a, -2 * cw, 1 - alpha * a, 1 + alpha / a, -2 * cw, 1 - alpha / a)
    }

    fun setLowShelf(fs: Double, f: Double, gainDb: Double, slope: Double = 1.0) {
        val a = 10.0.pow(gainDb / 40)
        val w = 2 * PI * clampF(fs, f) / fs
        val cw = cos(w)
        val alpha = sin(w) / 2 * sqrt((a + 1 / a) * (1 / slope - 1) + 2)
        val sa = 2 * sqrt(a) * alpha
        setNormalized(
            a * ((a + 1) - (a - 1) * cw + sa),
            2 * a * ((a - 1) - (a + 1) * cw),
            a * ((a + 1) - (a - 1) * cw - sa),
            (a + 1) + (a - 1) * cw + sa,
            -2 * ((a - 1) + (a + 1) * cw),
            (a + 1) + (a - 1) * cw - sa,
        )
    }

    fun setHighShelf(fs: Double, f: Double, gainDb: Double, slope: Double = 1.0) {
        val a = 10.0.pow(gainDb / 40)
        val w = 2 * PI * clampF(fs, f) / fs
        val cw = cos(w)
        val alpha = sin(w) / 2 * sqrt((a + 1 / a) * (1 / slope - 1) + 2)
        val sa = 2 * sqrt(a) * alpha
        setNormalized(
            a * ((a + 1) + (a - 1) * cw + sa),
            -2 * a * ((a - 1) + (a + 1) * cw),
            a * ((a + 1) + (a - 1) * cw - sa),
            (a + 1) - (a - 1) * cw + sa,
            2 * ((a - 1) - (a + 1) * cw),
            (a + 1) - (a - 1) * cw - sa,
        )
    }

    private fun prep(fs: Double, f: Double, q: Double): Pair<Double, Double> {
        val w = 2 * PI * clampF(fs, f) / fs
        return cos(w) to sin(w) / (2 * q)
    }

    private fun clampF(fs: Double, f: Double) = f.coerceIn(1.0, fs * 0.499)

    private fun setNormalized(nb0: Double, nb1: Double, nb2: Double, a0: Double, na1: Double, na2: Double) {
        b0 = nb0 / a0
        b1 = nb1 / a0
        b2 = nb2 / a0
        a1 = na1 / a0
        a2 = na2 / a0
    }
}

/**
 * Butterworth low/high-pass made of cascaded biquads (order 2, 4, 6 or 8).
 */
class ButterworthFilter(private val order: Int, private val highPass: Boolean) {
    init {
        require(order in setOf(2, 4, 6, 8)) { "order must be 2, 4, 6 or 8" }
    }

    private val sections = Array(order / 2) { Biquad() }
    private val qs = DoubleArray(order / 2) { k ->
        1.0 / (2 * cos((2 * (k + 1) - 1) * PI / (2 * order)))
    }

    var enabled = true

    fun set(fs: Double, cutoff: Double) {
        for (i in sections.indices) {
            if (highPass) sections[i].setHighPass(fs, cutoff, qs[i]) else sections[i].setLowPass(fs, cutoff, qs[i])
        }
    }

    fun process(x: Float): Float {
        if (!enabled) return x
        var y = x
        for (s in sections) y = s.process(y)
        return y
    }

    fun reset() = sections.forEach { it.reset() }
}
