package nl.tinnisoft.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import nl.tinnisoft.data.settings.SettingsRepository
import nl.tinnisoft.playback.Notifier
import nl.tinnisoft.playback.PlaybackManager
import nl.tinnisoft.playback.PlaybackService

private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

/** Access to singletons from components Hilt cannot inject (receivers, widgets, tiles). */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface AppEntryPoint {
    fun manager(): PlaybackManager
    fun schedule(): ScheduleManager
    fun notifier(): Notifier
    fun settings(): SettingsRepository
}

fun Context.appEntryPoint(): AppEntryPoint = EntryPointAccessors.fromApplication(applicationContext, AppEntryPoint::class.java)

/** Fires the scheduled start/stop. */
class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ep = context.appEntryPoint()
        val manager = ep.manager()
        val schedule = ep.schedule()
        val notifier = ep.notifier()
        when (intent.action) {
            ScheduleManager.ACTION_START -> {
                // Exact alarms are allowed to start a foreground service from the background.
                val ok = PlaybackService.play(context, intent.getLongExtra(ScheduleManager.EXTRA_PRESET, -1))
                if (!ok) notifier.offerResumeAfterBoot()
            }
            ScheduleManager.ACTION_STOP -> manager.stopWithFade()
        }
        val pending = goAsync()
        receiverScope.launch {
            try {
                schedule.reschedule()
            } finally {
                pending.finish()
            }
        }
    }
}

/** After a reboot: restore the schedule and optionally resume playback. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ep = context.appEntryPoint()
        val settings = ep.settings()
        val schedule = ep.schedule()
        val notifier = ep.notifier()
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        receiverScope.launch {
            try {
                schedule.reschedule()
                val s = settings.current()
                if (intent.action == Intent.ACTION_BOOT_COMPLETED && s.resumeAfterBoot && s.wasPlaying) {
                    // Android 15 forbids starting media playback from BOOT_COMPLETED,
                    // so there we post a one-tap notification instead.
                    val started = Build.VERSION.SDK_INT < 35 && PlaybackService.play(context)
                    if (!started) notifier.offerResumeAfterBoot()
                }
            } finally {
                pending.finish()
            }
        }
    }
}

/** Pause / next / stop from the widget (no foreground-service start needed). */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val manager = context.appEntryPoint().manager()
        when (intent.action) {
            ACTION_PAUSE -> manager.pause()
            ACTION_NEXT -> manager.skipPreset(1)
            ACTION_STOP -> manager.stopWithFade()
        }
    }

    companion object {
        const val ACTION_PAUSE = "nl.tinnisoft.action.PAUSE"
        const val ACTION_NEXT = "nl.tinnisoft.action.NEXT"
        const val ACTION_STOP = "nl.tinnisoft.action.STOP"
    }
}
