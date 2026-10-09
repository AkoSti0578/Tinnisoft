package nl.tinnisoft.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import nl.tinnisoft.data.settings.SettingsRepository
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** Alarm-clock-like schedule: start and stop playback at fixed times every day. */
@Singleton
class ScheduleManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
) {
    private val alarms = context.getSystemService(AlarmManager::class.java)

    /** Exact alarms need the user's permission on Android 12+ (Settings › Alarms & reminders). */
    fun canScheduleExact(): Boolean = Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()

    suspend fun reschedule() {
        val s = settings.current()
        cancel(ACTION_START)
        cancel(ACTION_STOP)
        if (!s.scheduleEnabled) return
        set(ACTION_START, nextOccurrence(s.scheduleStartMinute), s.schedulePresetId)
        set(ACTION_STOP, nextOccurrence(s.scheduleStopMinute), -1)
    }

    private fun pending(action: String, presetId: Long): PendingIntent = PendingIntent.getBroadcast(
        context,
        if (action == ACTION_START) 41 else 42,
        Intent(context, ScheduleReceiver::class.java).setAction(action).putExtra(EXTRA_PRESET, presetId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun set(action: String, at: Long, presetId: Long) {
        val pi = pending(action, presetId)
        if (canScheduleExact()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            // Without the exact-alarm permission Android may deliver up to ~10 min late.
            alarms.setWindow(AlarmManager.RTC_WAKEUP, at, 10 * 60_000L, pi)
        }
    }

    private fun cancel(action: String) = alarms.cancel(pending(action, -1))

    companion object {
        const val ACTION_START = "nl.tinnisoft.schedule.START"
        const val ACTION_STOP = "nl.tinnisoft.schedule.STOP"
        const val EXTRA_PRESET = "preset"

        fun nextOccurrence(minuteOfDay: Int, now: LocalDateTime = LocalDateTime.now()): Long {
            var t = LocalDateTime.of(LocalDate.from(now), LocalTime.of(minuteOfDay / 60, minuteOfDay % 60))
            if (!t.isAfter(now)) t = t.plusDays(1)
            return t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }
    }
}
