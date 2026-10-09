package nl.tinnisoft.audio

import nl.tinnisoft.audio.dsp.SeamlessLoop
import nl.tinnisoft.audio.nature.NatureSynthFactory
import nl.tinnisoft.data.model.NatureSound
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

class NatureSoundTest {

    @Test
    fun natureSoundsAreNormalizedAndDoNotClip() {
        val failures = mutableListOf<String>()
        for (s in NatureSound.entries) {
            val src = NatureSynthFactory.create(s, 42L, 48000.0)
            val n = 48000 * 20
            val l = FloatArray(n)
            val r = FloatArray(n)
            var off = 0
            val bl = FloatArray(512)
            val br = FloatArray(512)
            while (off < n) {
                src.render(bl, br, 512)
                System.arraycopy(bl, 0, l, off, 512)
                System.arraycopy(br, 0, r, off, 512)
                off += 512
            }
            val rms = (SpectrumUtil.rms(l, 48000) + SpectrumUtil.rms(r, 48000)) / 2
            val peak = maxOf(l.maxOf { abs(it) }, r.maxOf { abs(it) })
            println("NATURE $s rms=$rms peak=$peak")
            if (abs(rms - 0.1) > 0.03 || peak >= 0.9f) failures += "$s rms=$rms peak=$peak"
        }
        assertTrue(failures.joinToString(), failures.isEmpty())
    }

    @Test
    fun seamlessLoopHasNoJumpAtWrap() {
        val n = 48000
        // a slowly varying signal with a big discontinuity between end and start
        val x = FloatArray(n) { (sin(it * 0.01) * 0.5 + it.toDouble() / n).toFloat() }
        val loop = SeamlessLoop.build(x, 4800)
        assertEquals(n - 4800, loop.size)
        val wrapJump = abs(loop[0] - loop[loop.size - 1])
        var maxStep = 0f
        for (i in 1 until loop.size) maxStep = maxOf(maxStep, abs(loop[i] - loop[i - 1]))
        assertTrue("wrap jump $wrapJump vs max step $maxStep", wrapJump <= maxStep * 1.5f)
    }
}
