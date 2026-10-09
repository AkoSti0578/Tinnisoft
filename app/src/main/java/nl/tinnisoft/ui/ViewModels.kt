package nl.tinnisoft.ui

import android.app.Application
import android.content.Context
import android.os.PowerManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import nl.tinnisoft.R
import nl.tinnisoft.audio.engine.pure.ToneParams
import nl.tinnisoft.data.PresetRepository
import nl.tinnisoft.data.db.LogDao
import nl.tinnisoft.data.db.LogEntryEntity
import nl.tinnisoft.data.model.MixConfig
import nl.tinnisoft.data.model.SourceKind
import nl.tinnisoft.data.settings.FocusBehavior
import nl.tinnisoft.data.settings.Settings
import nl.tinnisoft.data.settings.SettingsRepository
import nl.tinnisoft.playback.PlaybackManager
import nl.tinnisoft.playback.PlaybackService
import nl.tinnisoft.schedule.ScheduleManager
import java.time.LocalDate
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.log2

/** Root: first-run flow, theme, default presets. `null` settings = still loading. */
@HiltViewModel
class AppViewModel @Inject constructor(
    app: Application,
    private val settings: SettingsRepository,
    presets: PresetRepository,
) : AndroidViewModel(app) {
    val state: StateFlow<Settings?> = settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        viewModelScope.launch {
            presets.seedDefaults(
                app.getString(R.string.preset_sleep),
                app.getString(R.string.preset_work),
                app.getString(R.string.preset_calm),
            )
        }
    }

    fun acceptDisclaimer() = viewModelScope.launch { settings.setDisclaimerAccepted() }
    fun batteryPromptDone() = viewModelScope.launch { settings.setBatteryPromptShown() }
}

@HiltViewModel
class PlayerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    val manager: PlaybackManager,
    settings: SettingsRepository,
) : ViewModel() {
    val state = manager.state
    val settings: StateFlow<Settings> = settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, Settings())

    /** The high-volume advice is shown once per app session. */
    private val _warningShown = MutableStateFlow(false)
    val warningShown = _warningShown.asStateFlow()
    fun markWarningShown() {
        _warningShown.value = true
    }

    fun togglePlay() {
        if (state.value.isActive) manager.pause() else PlaybackService.play(context)
    }

    fun stop() = manager.stopWithFade()
    fun setLevel(level: Float) = manager.setUserLevel(level)
    fun selectPreset(id: Long) = manager.selectPreset(id)
    fun setTimer(minutes: Int) = manager.setTimerMinutes(minutes)
    fun setTimerUntil(h: Int, m: Int) = manager.setTimerUntil(h, m)
    fun cancelTimer() = manager.cancelTimer()
    fun consumeNotice() = manager.consumeNotice()
}

@HiltViewModel
class MixerViewModel @Inject constructor(
    private val manager: PlaybackManager,
    private val presets: PresetRepository,
    settings: SettingsRepository,
) : ViewModel() {
    val state = manager.state
    val tinnitusHz: StateFlow<Float> = settings.settings.map { it.tinnitusHz }.stateIn(viewModelScope, SharingStarted.Eagerly, 0f)

    fun update(mix: MixConfig) = manager.updateMix(mix)

    fun saveAsNew(name: String) = viewModelScope.launch {
        val id = presets.create(name, state.value.mix)
        manager.markSavedAs(id)
    }

    fun overwriteActive() = viewModelScope.launch {
        val id = state.value.activePresetId
        if (id >= 0) {
            presets.overwrite(id, state.value.mix)
            manager.markSavedAs(id)
        }
    }

    /** EQ suggestion around the tinnitus pitch: lift the matching band a little for masking. */
    fun eqForTinnitus(hz: Float) {
        val bands = MixConfig.EQ_BANDS_HZ
        val nearest = bands.indices.minBy { abs(log2(bands[it] / hz)) }
        val gains = MutableList(bands.size) { 0f }
        gains[nearest] = 6f
        if (nearest > 0) gains[nearest - 1] = 2f
        if (nearest < bands.lastIndex) gains[nearest + 1] = 3f
        update(state.value.mix.copy(eqGainsDb = gains))
    }
}

@HiltViewModel
class PresetsViewModel @Inject constructor(
    private val manager: PlaybackManager,
    private val presets: PresetRepository,
) : ViewModel() {
    val state = manager.state
    fun select(id: Long) = manager.selectPreset(id)
    fun rename(id: Long, name: String) = viewModelScope.launch { presets.rename(id, name) }
    fun delete(id: Long) = viewModelScope.launch { presets.delete(id) }
    fun move(id: Long, delta: Int) = viewModelScope.launch { presets.move(id, delta) }
    fun saveCurrent(name: String) = viewModelScope.launch {
        val id = presets.create(name, state.value.mix)
        manager.markSavedAs(id)
    }
}

@HiltViewModel
class MatcherViewModel @Inject constructor(
    private val manager: PlaybackManager,
    private val settings: SettingsRepository,
) : ViewModel() {
    data class UiState(
        val step: Int = 0,
        val frequency: Float = 4000f,
        val narrowBand: Boolean = false,
        /** Starts soft on purpose. */
        val level: Float = 0.35f,
        val playing: Boolean = false,
        val savedHz: Float = 0f,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            val s = settings.current()
            if (s.tinnitusHz > 0) _ui.value = _ui.value.copy(frequency = s.tinnitusHz, savedHz = s.tinnitusHz)
        }
    }

    fun setStep(step: Int) {
        _ui.value = _ui.value.copy(step = step)
    }

    fun setFrequency(hz: Float) {
        _ui.value = _ui.value.copy(frequency = hz.coerceIn(nl.tinnisoft.data.model.FrequencyRange.MIN_HZ, nl.tinnisoft.data.model.FrequencyRange.MAX_HZ))
        push()
    }

    fun setNarrowBand(nb: Boolean) {
        _ui.value = _ui.value.copy(narrowBand = nb)
        push()
    }

    fun setLevel(level: Float) {
        _ui.value = _ui.value.copy(level = level.coerceIn(0f, 1f))
        push()
    }

    fun play() {
        _ui.value = _ui.value.copy(playing = true)
        push()
    }

    /** Play one specific frequency (octave check) without changing the chosen one. */
    fun preview(hz: Float) {
        _ui.value = _ui.value.copy(playing = true)
        manager.startTone(ToneParams(hz, _ui.value.narrowBand, _ui.value.level))
    }

    fun stop() {
        _ui.value = _ui.value.copy(playing = false)
        manager.stopTone()
    }

    private fun push() {
        val u = _ui.value
        if (u.playing) manager.startTone(ToneParams(u.frequency, u.narrowBand, u.level))
    }

    // NonCancellable: the screen closes right after saving, which clears this ViewModel.
    fun save(applyToNotch: Boolean, applyEq: Boolean) = viewModelScope.launch(NonCancellable) {
        val hz = _ui.value.frequency
        settings.setTinnitusHz(hz)
        _ui.value = _ui.value.copy(savedHz = hz)
        stop()
        val mix = manager.state.value.mix
        var layers = mix.layers.map {
            if (applyToNotch && (it.kind == SourceKind.NOTCHED || it.kind == SourceKind.NARROWBAND)) it.copy(centerHz = hz) else it
        }
        if (applyToNotch && layers.none { it.kind == SourceKind.NOTCHED } && layers.size < MixConfig.MAX_LAYERS) {
            layers = layers + nl.tinnisoft.data.model.LayerConfig(SourceKind.NOTCHED, nl.tinnisoft.data.model.NoiseColor.PINK, centerHz = hz)
        }
        var newMix = mix.copy(layers = layers)
        if (applyEq) {
            val bands = MixConfig.EQ_BANDS_HZ
            val nearest = bands.indices.minBy { abs(log2(bands[it] / hz)) }
            newMix = newMix.copy(eqGainsDb = List(bands.size) { i -> if (i == nearest) 6f else if (abs(i - nearest) == 1) 2f else 0f })
        }
        manager.updateMix(newMix)
    }

    override fun onCleared() {
        manager.stopTone()
    }
}

@HiltViewModel
class LogViewModel @Inject constructor(
    private val dao: LogDao,
    private val settings: SettingsRepository,
) : ViewModel() {
    val today: Long = LocalDate.now().toEpochDay()
    val entries: StateFlow<List<LogEntryEntity>> =
        dao.observeFrom(today - 7 * 8).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val enabled: StateFlow<Boolean> = settings.settings.map { it.logEnabled }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setEnabled(v: Boolean) = viewModelScope.launch { settings.setLogEnabled(v) }

    fun save(loudness: Int, relief: Int, note: String) = viewModelScope.launch {
        dao.upsert(LogEntryEntity(today, loudness.coerceIn(0, 10), relief.coerceIn(0, 10), note.trim()))
    }
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: SettingsRepository,
    private val schedule: ScheduleManager,
    manager: PlaybackManager,
    presets: PresetRepository,
) : ViewModel() {
    val settings: StateFlow<Settings> = repo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, Settings())
    val presetList = presets.presets.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val cpuLoad = manager.state.map { it.cpuLoad }.stateIn(viewModelScope, SharingStarted.Eagerly, 0f)

    /** Protected settings are unlocked for this session after entering the PIN. */
    private val _unlocked = MutableStateFlow(false)
    val unlocked = _unlocked.asStateFlow()

    fun isLocked(s: Settings) = s.pinSet && !_unlocked.value

    fun unlock(pin: String): Boolean {
        val ok = SettingsRepository.verifyPin(settings.value, pin)
        if (ok) _unlocked.value = true
        return ok
    }

    private fun guarded(block: suspend () -> Unit) {
        if (isLocked(settings.value)) return
        viewModelScope.launch { block() }
    }

    fun setMaxHeadphones(v: Float) = guarded { repo.setMaxLevelHeadphones(v) }
    fun setMaxSpeaker(v: Float) = guarded { repo.setMaxLevelSpeaker(v) }
    fun setMin(v: Float) = guarded { repo.setMinLevel(v) }
    fun setSafe(v: Float) = guarded { repo.setSafeLevel(v) }
    fun setWarn(v: Float) = guarded { repo.setWarnLevel(v) }
    fun setDailyLimit(min: Int) = guarded { repo.setDailyLimit(min) }
    fun setPin(pin: String?) = guarded {
        repo.setPin(pin)
        _unlocked.value = false
    }

    fun setFadeIn(v: Float) = viewModelScope.launch { repo.setFadeIn(v) }
    fun setFadeOut(v: Float) = viewModelScope.launch { repo.setFadeOut(v) }
    fun setAutoResume(v: Boolean) = viewModelScope.launch { repo.setAutoResume(v) }
    fun setFocus(v: FocusBehavior) = viewModelScope.launch { repo.setFocusBehavior(v) }
    fun setResumeFocus(v: Boolean) = viewModelScope.launch { repo.setResumeAfterFocusLoss(v) }
    fun setRelaxed(v: Boolean) = viewModelScope.launch { repo.setRelaxedLatency(v) }
    fun setResumeBoot(v: Boolean) = viewModelScope.launch { repo.setResumeAfterBoot(v) }
    fun setLightTheme(v: Boolean) = viewModelScope.launch { repo.setLightTheme(v) }

    fun setSchedule(enabled: Boolean, start: Int, stop: Int, presetId: Long) = viewModelScope.launch {
        repo.setSchedule(enabled, start, stop, presetId)
        schedule.reschedule()
    }

    fun canScheduleExact() = schedule.canScheduleExact()

    fun isIgnoringBatteryOptimizations(): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
}
