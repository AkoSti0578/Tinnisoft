package nl.tinnisoft.audio.engine.pure

import kotlin.math.pow

/**
 * App-level volume policy. Pure logic so it can be unit tested.
 *
 * Levels are 0..1 and map to a 50 dB range ([levelToDb]). The cap is applied to the
 * digital gain of the app's own output, which is the last stage before the system
 * mixer. Hardware volume keys only change the system stream volume, which can attenuate
 * but never amplify (0 dB is its maximum). The total level can therefore never exceed
 * the cap, whatever the phone or earbud volume buttons do.
 */
object VolumeLimiter {
    const val RANGE_DB = 50f

    fun levelToDb(level: Float): Float =
        if (level <= 0f) Float.NEGATIVE_INFINITY else RANGE_DB * (level.coerceAtMost(1f) - 1f)

    fun levelToGain(level: Float): Float =
        if (level <= 0f) 0f else 10f.pow(levelToDb(level) / 20f)

    /**
     * Clamp the user's chosen level to [minLevel, maxLevel]. If the configured minimum is
     * above the maximum, the maximum wins (protection first).
     */
    fun effectiveLevel(userLevel: Float, minLevel: Float, maxLevel: Float): Float {
        val hi = maxLevel.coerceIn(0f, 1f)
        val lo = minLevel.coerceIn(0f, hi)
        return userLevel.coerceIn(lo, hi)
    }

    /** Linear gain for the audio engine. Never above [levelToGain] of [maxLevel]. */
    fun appGain(userLevel: Float, minLevel: Float, maxLevel: Float): Float =
        levelToGain(effectiveLevel(userLevel, minLevel, maxLevel))

    /** Level to fall back to after the output device changes. */
    fun safeLevelAfterDeviceChange(current: Float, safeLevel: Float, newMax: Float): Float =
        minOf(current, safeLevel, newMax).coerceAtLeast(0f)

    fun isHigh(level: Float, warnLevel: Float): Boolean = level > warnLevel
}
