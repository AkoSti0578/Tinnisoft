package nl.tinnisoft.audio.dsp

/**
 * xorshift128+ pseudo-random generator. Period 2^128 - 1, so generated noise never
 * audibly repeats (a 48 kHz stream would need ~10^31 years to wrap).
 * Not thread-safe: every generator owns its own instance.
 */
class FastRandom(seed: Long) {
    private var s0: Long
    private var s1: Long

    init {
        s0 = splitMix(seed)
        s1 = splitMix(s0)
        if (s0 == 0L && s1 == 0L) s1 = 1L
    }

    /** Uniform value in [-1, 1). RMS = 1/sqrt(3). */
    fun nextBipolar(): Float {
        var x = s0
        val y = s1
        s0 = y
        x = x xor (x shl 23)
        s1 = x xor y xor (x ushr 17) xor (y ushr 26)
        val r = s1 + y
        return (r ushr 40).toInt() * INV_2_23 - 1f
    }

    /** Uniform value in [0, 1). */
    fun nextUnit(): Float = (nextBipolar() + 1f) * 0.5f

    private companion object {
        const val INV_2_23 = 1f / (1 shl 23)

        fun splitMix(v: Long): Long {
            var z = v + -0x61c8864680b583ebL
            z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
            z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
            return z xor (z ushr 31)
        }
    }
}
