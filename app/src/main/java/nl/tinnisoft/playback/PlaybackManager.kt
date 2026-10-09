package nl.tinnisoft.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import nl.tinnisoft.audio.engine.AudioEngine
import nl.tinnisoft.audio.engine.OutputDevice
import nl.tinnisoft.audio.engine.OutputDeviceMonitor
import nl.tinnisoft.audio.engine.pure.ToneParams
import nl.tinnisoft.audio.engine.pure.VolumeLimiter
import nl.tinnisoft.data.PresetRepository
import nl.tinnisoft.data.db.PresetEntity
import nl.tinnisoft.data.db.mix
import nl.tinnisoft.data.model.MixConfig
import nl.tinnisoft.data.settings.FocusBehavior
import nl.tinnisoft.data.settings.Settings
import nl.tinnisoft.data.settings.SettingsRepository
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

enum class PlayStatus { STOPPED, PLAYING, FADING_OUT }

data class PlaybackState(
    val status: PlayStatus = PlayStatus.STOPPED,
    val mix: MixConfig = MixConfig(),
    val presets: List<PresetEntity> = emptyList(),
    val activePresetId: Long = -1,
    /** The active preset was changed in the mixer and not saved yet. */
    val modified: Boolean = false,
    val userLevel: Float = 0.35f,
    val effectiveLevel: Float = 0.35f,
    val maxLevel: Float = 1f,
    val minLevel: Float = 0f,
    val output: OutputDevice = OutputDevice(-1, OutputDevice.Kind.SPEAKER, null),
    /** Wall-clock time (ms) at which the sleep timer stops playback, or null. */
    val timerEndMillis: Long? = null,
    val toneActive: Boolean = false,
    val cpuLoad: Float = 0f,
    /** One-shot notices for the UI (volume reset, limit reached …). */
    val notice: Notice? = null,
) {
    val isActive: Boolean get() = status != PlayStatus.STOPPED
    val activePreset: PresetEntity? get() = presets.firstOrNull { it.id == activePresetId }
}

enum class Notice { VOLUME_RESET_DEVICE_CHANGE, DAILY_LIMIT_REACHED, FOCUS_DENIED }

/**
 * Central playback controller (main thread). Owns the audio engine and implements the
 * protection and device rules: volume caps per output, fades, audio focus, becoming-noisy,
 * auto-resume, sleep timer, daily listening limit and the matcher tone.
 */
@Singleton
class PlaybackManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: AudioEngine,
    private val monitor: OutputDeviceMonitor,
    private val settingsRepo: SettingsRepository,
    private val presetRepo: PresetRepository,
    private val notifier: Notifier,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val main = Handler(Looper.getMainLooper())
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val wakeLock = context.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Tinnisoft:playback")
        .apply { setReferenceCounted(false) }

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state

    private var settings = Settings()
    private val loaded = CompletableDeferred<Unit>()
    private var focusRequest: AudioFocusRequest? = null
    private var pausedByFocus = false
    private var pausedByNoisy = false
    private var noisyRegistered = false
    private var usageJob: Job? = null
    private var timerJob: Job? = null
    private var persistLevelJob: Job? = null
    private var persistMixJob: Job? = null
    private var limitNotifiedDay = -1L
    private var lastDevice: OutputDevice? = null
    private var toneWasPlaying = false

    init {
        scope.launch {
            settingsRepo.settings.collect { s ->
                val first = !loaded.isCompleted
                settings = s
                if (first) {
                    _state.update { it.copy(mix = s.currentMix, activePresetId = s.activePresetId, userLevel = s.userLevel) }
                    loaded.complete(Unit)
                }
                engine.setRelaxedLatency(s.relaxedLatency)
                applyGain()
            }
        }
        scope.launch {
            presetRepo.presets.collect { list ->
                _state.update { st ->
                    val stillThere = list.any { it.id == st.activePresetId }
                    st.copy(presets = list, activePresetId = if (stillThere) st.activePresetId else -1)
                }
            }
        }
        monitor.onHeadphonesConnected = { onHeadphonesConnected() }
        monitor.start()
        scope.launch { monitor.current.collect { onOutputChanged(it) } }
        scope.launch {
            engine.state.collect { es ->
                // The playback status is owned by this class; the engine only reports when
                // its thread has really ended (after a fade-out).
                if (es == AudioEngine.State.STOPPED && engine.state.value == AudioEngine.State.STOPPED) onEngineStopped()
            }
        }
        scope.launch { engine.cpuLoad.collect { c -> _state.update { it.copy(cpuLoad = c) } } }
    }

    // ------------------------------------------------------------------ playback

    /** Start playing (fade-in). Call through [PlaybackService.play] when the app may be in the background. */
    fun play() {
        scope.launch {
            loaded.await()
            if (_state.value.toneActive) stopTone()
            if (_state.value.status == PlayStatus.PLAYING) return@launch
            if (!requestFocus()) {
                _state.update { it.copy(notice = Notice.FOCUS_DENIED) }
                return@launch
            }
            pausedByFocus = false
            pausedByNoisy = false
            registerNoisy()
            wakeLock.acquire(12 * 60 * 60 * 1000L)
            engine.setMix(_state.value.mix)
            applyGain()
            _state.update { it.copy(status = PlayStatus.PLAYING) }
            engine.start(settings.fadeInSec)
            startUsageTicker()
            settingsRepo.setWasPlaying(true)
            checkDailyLimit()
        }
    }

    /** User pause: short fade so it feels immediate. */
    fun pause() = pauseInternal(PAUSE_FADE_SEC, abandonFocus = true)

    fun togglePlayPause() = if (_state.value.isActive) pause() else play()

    /** Stop with the configured (long) fade-out, used by the stop button, timer and schedule. */
    fun stopWithFade(fadeSec: Float = settings.fadeOutSec) {
        val st = _state.value
        when {
            st.status == PlayStatus.STOPPED -> return
            // second press while fading out: stop now
            st.status == PlayStatus.FADING_OUT -> pauseInternal(PAUSE_FADE_SEC, abandonFocus = true)
            else -> {
                _state.update { it.copy(status = PlayStatus.FADING_OUT) }
                engine.stop(fadeSec)
                cancelTimer()
            }
        }
    }

    private fun pauseInternal(fadeSec: Float, abandonFocus: Boolean) {
        _state.update { it.copy(status = PlayStatus.STOPPED) }
        engine.stop(fadeSec)
        if (abandonFocus) abandonFocus()
        unregisterNoisy()
        usageJob?.cancel()
        scope.launch { settingsRepo.setWasPlaying(false) }
    }

    private fun onEngineStopped() {
        if (_state.value.toneActive) _state.update { it.copy(toneActive = false) }
        if (_state.value.status != PlayStatus.STOPPED) {
            _state.update { it.copy(status = PlayStatus.STOPPED) }
            abandonFocus()
            unregisterNoisy()
            usageJob?.cancel()
            scope.launch { settingsRepo.setWasPlaying(false) }
        }
        if (wakeLock.isHeld) wakeLock.release()
    }

    // ------------------------------------------------------------------ volume

    fun setUserLevel(level: Float) {
        _state.update { it.copy(userLevel = level.coerceIn(0f, 1f)) }
        applyGain()
        persistLevelJob?.cancel()
        persistLevelJob = scope.launch {
            delay(400)
            settingsRepo.setUserLevel(_state.value.userLevel)
        }
    }

    private fun maxLevelFor(device: OutputDevice) =
        if (device.isHeadphones) settings.maxLevelHeadphones else settings.maxLevelSpeaker

    private fun applyGain() {
        val st = _state.value
        val max = maxLevelFor(st.output)
        val eff = VolumeLimiter.effectiveLevel(st.userLevel, settings.minLevel, max)
        engine.setMaxGain(VolumeLimiter.levelToGain(max))
        // In matcher mode the tone level is the volume (still under the cap).
        engine.setGain(VolumeLimiter.levelToGain(if (st.toneActive) max else eff))
        _state.update { it.copy(effectiveLevel = eff, maxLevel = max, minLevel = settings.minLevel) }
    }

    private fun onOutputChanged(device: OutputDevice) {
        val previous = lastDevice
        lastDevice = device
        _state.update { it.copy(output = device) }
        if (previous != null && previous.id != device.id) {
            // Different output: fall back to a safe level before anything is heard.
            val safe = VolumeLimiter.safeLevelAfterDeviceChange(_state.value.userLevel, settings.safeLevel, maxLevelFor(device))
            if (safe < _state.value.userLevel) {
                setUserLevel(safe)
                _state.update { it.copy(notice = Notice.VOLUME_RESET_DEVICE_CHANGE) }
            }
        }
        applyGain()
    }

    fun consumeNotice() = _state.update { it.copy(notice = null) }

    // ------------------------------------------------------------------ mix & presets

    fun updateMix(mix: MixConfig) {
        val st = _state.value
        val m = mix.normalized()
        _state.update { it.copy(mix = m, modified = st.activePresetId >= 0) }
        engine.setMix(m)
        persistMix()
    }

    fun selectPreset(id: Long) {
        val p = _state.value.presets.firstOrNull { it.id == id } ?: return
        _state.update { it.copy(mix = p.mix(), activePresetId = id, modified = false) }
        engine.setMix(p.mix())
        persistMix()
    }

    /** Select a preset (also before the preset list has loaded, e.g. from an alarm) and play. */
    fun playPreset(id: Long) {
        scope.launch {
            loaded.await()
            presetRepo.get(id)?.let { p ->
                _state.update { it.copy(mix = p.mix(), activePresetId = id, modified = false) }
                engine.setMix(p.mix())
                persistMix()
            }
            play()
        }
    }

    fun skipPreset(delta: Int) {
        scope.launch {
            loaded.await()
            val list = presetRepo.all()
            if (list.isEmpty()) return@launch
            val i = list.indexOfFirst { it.id == _state.value.activePresetId }
            val next = if (i < 0) (if (delta > 0) 0 else list.lastIndex) else (i + delta).mod(list.size)
            val p = list[next]
            _state.update { it.copy(mix = p.mix(), activePresetId = p.id, modified = false) }
            engine.setMix(p.mix())
            persistMix()
        }
    }

    fun markSavedAs(id: Long) {
        _state.update { it.copy(activePresetId = id, modified = false) }
        persistMix()
    }

    private fun persistMix() {
        persistMixJob?.cancel()
        persistMixJob = scope.launch {
            delay(300)
            settingsRepo.setCurrentMix(_state.value.mix, _state.value.activePresetId)
        }
    }

    // ------------------------------------------------------------------ sleep timer

    fun setTimerMinutes(minutes: Int) = setTimerEnd(System.currentTimeMillis() + minutes * 60_000L)

    /** "Until 07:00": next occurrence of that time. */
    fun setTimerUntil(hour: Int, minute: Int) {
        val now = LocalDateTime.now()
        var end = LocalDateTime.of(LocalDate.now(), LocalTime.of(hour, minute))
        if (!end.isAfter(now)) end = end.plusDays(1)
        setTimerEnd(end.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
    }

    private fun setTimerEnd(end: Long) {
        timerJob?.cancel()
        _state.update { it.copy(timerEndMillis = end) }
        timerJob = scope.launch {
            // Start the long fade so that it is silent at the end time.
            val fade = maxOf(settings.fadeOutSec, MIN_TIMER_FADE_SEC)
            val wait = end - System.currentTimeMillis() - (fade * 1000).toLong()
            if (wait > 0) delay(wait)
            _state.update { it.copy(timerEndMillis = null) }
            if (_state.value.status == PlayStatus.PLAYING) {
                _state.update { it.copy(status = PlayStatus.FADING_OUT) }
                engine.stop(((end - System.currentTimeMillis()) / 1000f).coerceAtLeast(2f))
            }
        }
    }

    fun cancelTimer() {
        timerJob?.cancel()
        timerJob = null
        _state.update { it.copy(timerEndMillis = null) }
    }

    // ------------------------------------------------------------------ matcher tone

    fun startTone(params: ToneParams) {
        val st = _state.value
        if (!st.toneActive) {
            toneWasPlaying = st.status == PlayStatus.PLAYING
            _state.update { it.copy(toneActive = true) }
            applyGain()
            if (!toneWasPlaying) {
                engine.setMix(st.mix)
                engine.start(0.05f)
            }
        }
        engine.setTone(params)
    }

    fun stopTone() {
        if (!_state.value.toneActive) return
        engine.setTone(null)
        _state.update { it.copy(toneActive = false) }
        applyGain()
        if (!toneWasPlaying) engine.stop(0.4f)
    }

    // ------------------------------------------------------------------ focus & noisy

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                pausedByFocus = false
                if (_state.value.isActive) pauseInternal(PAUSE_FADE_SEC, abandonFocus = true)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
            -> if (_state.value.status == PlayStatus.PLAYING) {
                // CAN_DUCK only arrives here in PAUSE mode; in DUCK mode the system ducks for us.
                pausedByFocus = settings.resumeAfterFocusLoss
                pauseInternal(0.3f, abandonFocus = false)
            }
            AudioManager.AUDIOFOCUS_GAIN -> if (pausedByFocus) {
                pausedByFocus = false
                play()
            }
        }
    }

    private fun requestFocus(): Boolean {
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setWillPauseWhenDucked(settings.focusBehavior == FocusBehavior.PAUSE)
            .setOnAudioFocusChangeListener(focusListener, main)
            .build()
        focusRequest = req
        return audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
        pausedByFocus = false
    }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY && _state.value.isActive) {
                // Earbuds disconnected: stop at once so nothing comes out of the speaker.
                pauseInternal(0.05f, abandonFocus = true)
                pausedByNoisy = true
            }
        }
    }

    private fun registerNoisy() {
        if (noisyRegistered) return
        ContextCompat.registerReceiver(
            context,
            noisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        noisyRegistered = true
    }

    private fun unregisterNoisy() {
        if (!noisyRegistered) return
        runCatching { context.unregisterReceiver(noisyReceiver) }
        noisyRegistered = false
    }

    private fun onHeadphonesConnected() {
        if (pausedByNoisy && settings.autoResumeOnReconnect && !_state.value.isActive) {
            pausedByNoisy = false
            // Give the route a moment to settle on the new device.
            main.postDelayed({ PlaybackService.play(context) }, 1200)
        }
    }

    // ------------------------------------------------------------------ daily limit

    private fun startUsageTicker() {
        usageJob?.cancel()
        usageJob = scope.launch {
            while (isActive) {
                delay(USAGE_TICK_MS)
                if (_state.value.status != PlayStatus.PLAYING) continue
                val today = LocalDate.now().toEpochDay()
                val s = settingsRepo.current()
                val secs = (if (s.usageDay == today) s.usageSeconds else 0L) + USAGE_TICK_MS / 1000
                settingsRepo.setUsage(today, secs)
                checkDailyLimit()
            }
        }
    }

    private suspend fun checkDailyLimit() {
        val s = settingsRepo.current()
        val today = LocalDate.now().toEpochDay()
        if (s.dailyLimitMinutes <= 0 || s.usageDay != today) return
        if (s.usageSeconds >= s.dailyLimitMinutes * 60L && limitNotifiedDay != today) {
            limitNotifiedDay = today
            notifier.dailyLimitReached(s.dailyLimitMinutes)
            _state.update { it.copy(notice = Notice.DAILY_LIMIT_REACHED) }
            stopWithFade()
        }
    }

    companion object {
        const val PAUSE_FADE_SEC = 1.5f
        const val MIN_TIMER_FADE_SEC = 60f
        private const val USAGE_TICK_MS = 15_000L
    }
}
