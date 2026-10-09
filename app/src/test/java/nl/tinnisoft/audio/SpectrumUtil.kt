package nl.tinnisoft.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/** Small Welch power-spectrum estimator for tests. */
object SpectrumUtil {
    const val FS = 48000.0
    private const val N = 4096

    fun rms(x: FloatArray, skip: Int = 0): Double {
        var s = 0.0
        for (i in skip until x.size) s += x[i].toDouble() * x[i]
        return sqrt(s / (x.size - skip))
    }

    /** Averaged power spectrum (Hann window), length N/2. */
    fun psd(x: FloatArray, skip: Int = 0): DoubleArray {
        val out = DoubleArray(N / 2)
        val win = DoubleArray(N) { 0.5 - 0.5 * cos(2 * PI * it / (N - 1)) }
        var frames = 0
        var start = skip
        val re = DoubleArray(N)
        val im = DoubleArray(N)
        while (start + N <= x.size) {
            for (i in 0 until N) {
                re[i] = x[start + i] * win[i]
                im[i] = 0.0
            }
            fft(re, im)
            for (k in 0 until N / 2) out[k] += re[k] * re[k] + im[k] * im[k]
            frames++
            start += N / 2
        }
        for (k in out.indices) out[k] /= frames.toDouble()
        return out
    }

    /** Mean power density (dB) in a band. */
    fun bandDb(psd: DoubleArray, lo: Double, hi: Double): Double {
        val df = FS / N
        val k0 = (lo / df).toInt().coerceAtLeast(1)
        val k1 = (hi / df).toInt().coerceAtMost(psd.size - 1)
        var s = 0.0
        for (k in k0..k1) s += psd[k]
        return 10 * log10(s / (k1 - k0 + 1))
    }

    /** Density (dB) in a 1/3-octave band around f. */
    fun at(psd: DoubleArray, f: Double): Double = bandDb(psd, f / 1.122, f * 1.122)

    /** Least-squares slope in dB/octave of the density over [lo, hi]. */
    fun slopePerOctave(psd: DoubleArray, lo: Double, hi: Double): Double {
        val xs = ArrayList<Double>()
        val ys = ArrayList<Double>()
        var f = lo
        while (f <= hi) {
            xs += kotlin.math.ln(f) / kotlin.math.ln(2.0)
            ys += at(psd, f)
            f *= 1.26
        }
        val mx = xs.average()
        val my = ys.average()
        var num = 0.0
        var den = 0.0
        for (i in xs.indices) {
            num += (xs[i] - mx) * (ys[i] - my)
            den += (xs[i] - mx) * (xs[i] - mx)
        }
        return num / den
    }

    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang)
            val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0
                var ci = 0.0
                for (k in 0 until len / 2) {
                    val ur = re[i + k]
                    val ui = im[i + k]
                    val vr = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val vi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k] = ur + vr
                    im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr
                    im[i + k + len / 2] = ui - vi
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }
}
