package nl.tinnisoft.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import nl.tinnisoft.data.model.MixConfig
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

enum class FocusBehavior { DUCK, PAUSE }

data class Settings(
    val disclaimerAccepted: Boolean = false,
    val batteryPromptShown: Boolean = false,
    val defaultsSeeded: Boolean = false,
    // volume & protection
    val userLevel: Float = 0.35f,
    val maxLevelHeadphones: Float = 0.6f,
    val maxLevelSpeaker: Float = 0.8f,
    val minLevel: Float = 0f,
    val safeLevel: Float = 0.3f,
    val warnLevel: Float = 0.7f,
    val fadeInSec: Float = 5f,
    val fadeOutSec: Float = 30f,
    val dailyLimitMinutes: Int = 0,
    val pinHash: String = "",
    val pinSalt: String = "",
    // bluetooth & focus
    val autoResumeOnReconnect: Boolean = false,
    val focusBehavior: FocusBehavior = FocusBehavior.DUCK,
    val resumeAfterFocusLoss: Boolean = true,
    val relaxedLatency: Boolean = false,
    // background
    val resumeAfterBoot: Boolean = false,
    val wasPlaying: Boolean = false,
    val scheduleEnabled: Boolean = false,
    val scheduleStartMinute: Int = 22 * 60 + 30,
    val scheduleStopMinute: Int = 7 * 60,
    val schedulePresetId: Long = -1,
    // tinnitus profile & mix
    val tinnitusHz: Float = 0f,
    val currentMixJson: String = "",
    val activePresetId: Long = -1,
    val logEnabled: Boolean = false,
    val lightTheme: Boolean = false,
    // usage
    val usageDay: Long = 0,
    val usageSeconds: Long = 0,
) {
    val pinSet: Boolean get() = pinHash.isNotEmpty()
    val currentMix: MixConfig get() = MixConfig.fromJson(currentMixJson) ?: MixConfig()
}

@Singleton
class SettingsRepository @Inject constructor(private val store: DataStore<Preferences>) {

    private object K {
        val disclaimer = booleanPreferencesKey("disclaimer")
        val battery = booleanPreferencesKey("battery_prompt")
        val seeded = booleanPreferencesKey("seeded")
        val userLevel = floatPreferencesKey("user_level")
        val maxHp = floatPreferencesKey("max_hp")
        val maxSp = floatPreferencesKey("max_sp")
        val minLevel = floatPreferencesKey("min_level")
        val safeLevel = floatPreferencesKey("safe_level")
        val warnLevel = floatPreferencesKey("warn_level")
        val fadeIn = floatPreferencesKey("fade_in")
        val fadeOut = floatPreferencesKey("fade_out")
        val dailyLimit = intPreferencesKey("daily_limit")
        val pinHash = stringPreferencesKey("pin_hash")
        val pinSalt = stringPreferencesKey("pin_salt")
        val autoResume = booleanPreferencesKey("auto_resume")
        val focus = stringPreferencesKey("focus")
        val resumeFocus = booleanPreferencesKey("resume_focus")
        val relaxed = booleanPreferencesKey("relaxed_latency")
        val resumeBoot = booleanPreferencesKey("resume_boot")
        val wasPlaying = booleanPreferencesKey("was_playing")
        val schedEnabled = booleanPreferencesKey("sched_enabled")
        val schedStart = intPreferencesKey("sched_start")
        val schedStop = intPreferencesKey("sched_stop")
        val schedPreset = longPreferencesKey("sched_preset")
        val tinnitusHz = floatPreferencesKey("tinnitus_hz")
        val mix = stringPreferencesKey("mix")
        val activePreset = longPreferencesKey("active_preset")
        val logEnabled = booleanPreferencesKey("log_enabled")
        val lightTheme = booleanPreferencesKey("light_theme")
        val usageDay = longPreferencesKey("usage_day")
        val usageSeconds = longPreferencesKey("usage_seconds")
    }

    val settings: Flow<Settings> = store.data.map { p ->
        val d = Settings()
        Settings(
            disclaimerAccepted = p[K.disclaimer] ?: d.disclaimerAccepted,
            batteryPromptShown = p[K.battery] ?: d.batteryPromptShown,
            defaultsSeeded = p[K.seeded] ?: d.defaultsSeeded,
            userLevel = p[K.userLevel] ?: d.userLevel,
            maxLevelHeadphones = p[K.maxHp] ?: d.maxLevelHeadphones,
            maxLevelSpeaker = p[K.maxSp] ?: d.maxLevelSpeaker,
            minLevel = p[K.minLevel] ?: d.minLevel,
            safeLevel = p[K.safeLevel] ?: d.safeLevel,
            warnLevel = p[K.warnLevel] ?: d.warnLevel,
            fadeInSec = p[K.fadeIn] ?: d.fadeInSec,
            fadeOutSec = p[K.fadeOut] ?: d.fadeOutSec,
            dailyLimitMinutes = p[K.dailyLimit] ?: d.dailyLimitMinutes,
            pinHash = p[K.pinHash] ?: d.pinHash,
            pinSalt = p[K.pinSalt] ?: d.pinSalt,
            autoResumeOnReconnect = p[K.autoResume] ?: d.autoResumeOnReconnect,
            focusBehavior = p[K.focus]?.let { runCatching { FocusBehavior.valueOf(it) }.getOrNull() } ?: d.focusBehavior,
            resumeAfterFocusLoss = p[K.resumeFocus] ?: d.resumeAfterFocusLoss,
            relaxedLatency = p[K.relaxed] ?: d.relaxedLatency,
            resumeAfterBoot = p[K.resumeBoot] ?: d.resumeAfterBoot,
            wasPlaying = p[K.wasPlaying] ?: d.wasPlaying,
            scheduleEnabled = p[K.schedEnabled] ?: d.scheduleEnabled,
            scheduleStartMinute = p[K.schedStart] ?: d.scheduleStartMinute,
            scheduleStopMinute = p[K.schedStop] ?: d.scheduleStopMinute,
            schedulePresetId = p[K.schedPreset] ?: d.schedulePresetId,
            tinnitusHz = p[K.tinnitusHz] ?: d.tinnitusHz,
            currentMixJson = p[K.mix] ?: d.currentMixJson,
            activePresetId = p[K.activePreset] ?: d.activePresetId,
            logEnabled = p[K.logEnabled] ?: d.logEnabled,
            lightTheme = p[K.lightTheme] ?: d.lightTheme,
            usageDay = p[K.usageDay] ?: d.usageDay,
            usageSeconds = p[K.usageSeconds] ?: d.usageSeconds,
        )
    }

    suspend fun current(): Settings = settings.first()

    private suspend fun set(block: (MutablePreferences) -> Unit) {
        store.edit { block(it) }
    }

    suspend fun setDisclaimerAccepted() = set { it[K.disclaimer] = true }
    suspend fun setBatteryPromptShown() = set { it[K.battery] = true }
    suspend fun setDefaultsSeeded() = set { it[K.seeded] = true }
    suspend fun setUserLevel(v: Float) = set { it[K.userLevel] = v.coerceIn(0f, 1f) }
    suspend fun setMaxLevelHeadphones(v: Float) = set { it[K.maxHp] = v.coerceIn(0f, 1f) }
    suspend fun setMaxLevelSpeaker(v: Float) = set { it[K.maxSp] = v.coerceIn(0f, 1f) }
    suspend fun setMinLevel(v: Float) = set { it[K.minLevel] = v.coerceIn(0f, 1f) }
    suspend fun setSafeLevel(v: Float) = set { it[K.safeLevel] = v.coerceIn(0f, 1f) }
    suspend fun setWarnLevel(v: Float) = set { it[K.warnLevel] = v.coerceIn(0f, 1f) }
    suspend fun setFadeIn(sec: Float) = set { it[K.fadeIn] = sec.coerceIn(0f, 60f) }
    suspend fun setFadeOut(sec: Float) = set { it[K.fadeOut] = sec.coerceIn(0f, 300f) }
    suspend fun setDailyLimit(min: Int) = set { it[K.dailyLimit] = min.coerceIn(0, 24 * 60) }
    suspend fun setAutoResume(v: Boolean) = set { it[K.autoResume] = v }
    suspend fun setFocusBehavior(v: FocusBehavior) = set { it[K.focus] = v.name }
    suspend fun setResumeAfterFocusLoss(v: Boolean) = set { it[K.resumeFocus] = v }
    suspend fun setRelaxedLatency(v: Boolean) = set { it[K.relaxed] = v }
    suspend fun setResumeAfterBoot(v: Boolean) = set { it[K.resumeBoot] = v }
    suspend fun setWasPlaying(v: Boolean) = set { it[K.wasPlaying] = v }
    suspend fun setSchedule(enabled: Boolean, startMinute: Int, stopMinute: Int, presetId: Long) = set {
        it[K.schedEnabled] = enabled
        it[K.schedStart] = startMinute
        it[K.schedStop] = stopMinute
        it[K.schedPreset] = presetId
    }
    suspend fun setTinnitusHz(v: Float) = set { it[K.tinnitusHz] = v }
    suspend fun setCurrentMix(mix: MixConfig, presetId: Long) = set {
        it[K.mix] = mix.toJson()
        it[K.activePreset] = presetId
    }
    suspend fun setLogEnabled(v: Boolean) = set { it[K.logEnabled] = v }
    suspend fun setLightTheme(v: Boolean) = set { it[K.lightTheme] = v }
    suspend fun setUsage(day: Long, seconds: Long) = set {
        it[K.usageDay] = day
        it[K.usageSeconds] = seconds
    }

    suspend fun setPin(pin: String?) = set {
        if (pin.isNullOrEmpty()) {
            it.remove(K.pinHash)
            it.remove(K.pinSalt)
        } else {
            val salt = ByteArray(16).also { b -> SecureRandom().nextBytes(b) }.toHex()
            it[K.pinSalt] = salt
            it[K.pinHash] = hash(pin, salt)
        }
    }

    companion object {
        fun hash(pin: String, salt: String): String =
            MessageDigest.getInstance("SHA-256").digest((salt + pin).toByteArray()).toHex()

        fun verifyPin(s: Settings, pin: String): Boolean = s.pinSet && hash(pin, s.pinSalt) == s.pinHash

        private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
    }
}
