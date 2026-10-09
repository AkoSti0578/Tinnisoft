package nl.tinnisoft.audio

import nl.tinnisoft.audio.dsp.Ramp
import nl.tinnisoft.audio.dsp.SoftLimiter
import nl.tinnisoft.audio.engine.pure.VolumeLimiter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class VolumeLimiterTest {

    @Test
    fun levelIsClampedToMaximum() {
        assertEquals(0.6f, VolumeLimiter.effectiveLevel(1f, 0f, 0.6f), 0f)
        assertTrue(VolumeLimiter.appGain(1f, 0f, 0.6f) <= VolumeLimiter.levelToGain(0.6f))
    }

    @Test
    fun levelIsRaisedToMinimum() {
        assertEquals(0.2f, VolumeLimiter.effectiveLevel(0.05f, 0.2f, 0.8f), 0f)
    }

    @Test
    fun maximumWinsOverMisconfiguredMinimum() {
        assertEquals(0.4f, VolumeLimiter.effectiveLevel(0.9f, 0.7f, 0.4f), 0f)
        assertEquals(0.4f, VolumeLimiter.effectiveLevel(0.1f, 0.7f, 0.4f), 0f)
    }

    @Test
    fun gainNeverExceedsCapForAnyInput() {
        val caps = listOf(0f, 0.1f, 0.5f, 0.8f, 1f)
        for (cap in caps) for (u in -10..20) for (m in 0..10) {
            val g = VolumeLimiter.appGain(u / 10f, m / 10f, cap)
            assertTrue(g <= VolumeLimiter.levelToGain(cap) + 1e-7f)
            assertTrue(g in 0f..1f)
        }
    }

    @Test
    fun mappingIsMonotonicAndSpans50Db() {
        assertEquals(0f, VolumeLimiter.levelToGain(0f), 0f)
        assertEquals(1f, VolumeLimiter.levelToGain(1f), 1e-6f)
        assertEquals(-25f, VolumeLimiter.levelToDb(0.5f), 1e-4f)
        var last = -1f
        for (i in 0..100) {
            val g = VolumeLimiter.levelToGain(i / 100f)
            assertTrue(g > last || (i == 0 && g == 0f))
            last = g
        }
    }

    @Test
    fun deviceChangeFallsBackToSafeLevel() {
        assertEquals(0.3f, VolumeLimiter.safeLevelAfterDeviceChange(0.7f, 0.3f, 0.8f), 0f)
        assertEquals(0.2f, VolumeLimiter.safeLevelAfterDeviceChange(0.2f, 0.3f, 0.8f), 0f)
        assertEquals(0.25f, VolumeLimiter.safeLevelAfterDeviceChange(0.7f, 0.3f, 0.25f), 0f)
    }

    @Test
    fun softLimiterNeverExceedsCeilingAndIsTransparentBelowKnee() {
        for (i in -400..400) {
            val x = i / 100f
            val y = SoftLimiter.process(x)
            assertTrue(abs(y) <= 1f)
            if (abs(x) <= 0.8f) assertEquals(x, y, 0f)
        }
        // monotonic
        var prev = -2f
        for (i in -400..400) {
            val y = SoftLimiter.process(i / 100f)
            assertTrue(y >= prev)
            prev = y
        }
    }

    @Test
    fun rampHasNoJumps() {
        val r = Ramp(0f)
        r.setTarget(1f, 480)
        var prev = 0f
        repeat(600) {
            val v = r.next()
            assertTrue(abs(v - prev) <= 1f / 480 + 1e-6f)
            prev = v
        }
        assertEquals(1f, prev, 0f)
    }
}
