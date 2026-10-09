package nl.tinnisoft.audio.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.tanh

/**
 * Linear ramp to a target over a number of samples. Used for every gain change so
 * that starting, stopping, switching and volume changes never click.
 */
class Ramp(initial: Float = 0f) {
    var value = initial
        private set
    var target = initial
        private set
    private var step = 0f
    private var remaining = 0

    fun setTarget(t: Float, samples: Int) {
        target = t
        if (samples <= 0) {
            value = t
            remaining = 0
            step = 0f
        } else {
            remaining = samples
            step = (t - value) / samples
        }
    }

    fun jumpTo(v: Float) = setTarget(v, 0)

    val isRamping: Boolean get() = remaining > 0

    fun next(): Float {
        if (remaining > 0) {
            value += step
            if (--remaining == 0) value = target
        }
        return value
    }

    /** Advance [n] samples at once (used when a block needs only the end value). */
    fun skip(n: Int) {
        if (remaining <= 0) return
        if (n >= remaining) {
            value = target
            remaining = 0
        } else {
            value += step * n
            remaining -= n
        }
    }
}

/** Exponential glide for parameters such as frequencies (updated per block). */
class Glide(initial: Float, private val coefficient: Float = 0.15f) {
    var value = initial
        private set
    var target = initial

    fun jumpTo(v: Float) {
        value = v
        target = v
    }

    /** @return true when the value changed noticeably. */
    fun step(): Boolean {
        if (value == target) return false
        val d = target - value
        if (abs(d) < abs(target) * 1e-4f + 1e-6f) value = target else value += d * coefficient
        return true
    }
}

/**
 * Slow amplitude modulation ("waves") to reduce habituation. Raised cosine between
 * 1 - depth and 1, so it only ever lowers the level and never adds gain.
 */
class Modulator(private val fs: Double) {
    private var phase = 0.0
    private val depth = Ramp(0f)
    private var increment = 1.0 / (12.0 * fs)

    fun set(enabled: Boolean, depthValue: Float, periodSec: Float) {
        depth.setTarget(if (enabled) depthValue.coerceIn(0f, 1f) else 0f, (fs * 2).toInt())
        increment = 1.0 / (periodSec.coerceIn(2f, 60f) * fs)
    }

    fun next(): Float {
        val d = depth.next()
        if (d == 0f && !depth.isRamping) return 1f
        phase += increment
        if (phase >= 1.0) phase -= 1.0
        val shape = 0.5 - 0.5 * cos(2 * PI * phase)
        return (1.0 - d * shape).toFloat()
    }
}

/**
 * Transparent below the knee, smooth saturation above it; the output magnitude never
 * exceeds [ceiling]. Last safety stage, so the app can never clip.
 */
object SoftLimiter {
    private const val KNEE = 0.8f

    fun process(x: Float, ceiling: Float = 1f): Float {
        val ax = abs(x)
        if (ax <= KNEE * ceiling) return x
        val k = KNEE * ceiling
        val range = ceiling - k
        val y = k + range * tanh((ax - k) / range)
        return if (x < 0) -y else y
    }
}

/**
 * Multi-band EQ: low shelf, peaking bands and a high shelf at the given centers.
 * Skipped entirely when all gains are 0 dB to save battery.
 */
class Equalizer(private val fs: Double, private val bands: FloatArray) {
    private val filters = Array(bands.size) { Biquad() }
    private val current = FloatArray(bands.size)
    private var active = false

    fun setGains(gainsDb: List<Float>) {
        var any = false
        for (i in bands.indices) {
            val g = gainsDb.getOrElse(i) { 0f }
            if (g != 0f) any = true
            if (g == current[i]) continue
            current[i] = g
            val f = bands[i].toDouble()
            when (i) {
                0 -> filters[i].setLowShelf(fs, f * 1.4, g.toDouble())
                bands.lastIndex -> filters[i].setHighShelf(fs, f / 1.4, g.toDouble())
                else -> filters[i].setPeaking(fs, f, 1.41, g.toDouble())
            }
        }
        if (!active && any) filters.forEach { it.reset() }
        active = any
    }

    fun process(x: Float): Float {
        if (!active) return x
        var y = x
        for (f in filters) y = f.process(y)
        return y
    }
}
