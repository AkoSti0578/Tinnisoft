package nl.tinnisoft.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import nl.tinnisoft.MainActivity
import nl.tinnisoft.R
import nl.tinnisoft.playback.PlaybackService
import nl.tinnisoft.playback.PlaybackState
import nl.tinnisoft.schedule.ActionReceiver
import nl.tinnisoft.schedule.appEntryPoint

/** Home-screen quick-start widget: play/pause, current preset and next preset. */
class QuickStartWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        update(context, context.appEntryPoint().manager().state.value)
    }

    companion object {
        fun update(context: Context, state: PlaybackState) {
            val awm = AppWidgetManager.getInstance(context)
            val ids = awm.getAppWidgetIds(ComponentName(context, QuickStartWidget::class.java))
            if (ids.isEmpty()) return
            val views = RemoteViews(context.packageName, R.layout.widget_quick_start)
            val title = state.activePreset?.name ?: context.getString(R.string.custom_mix)
            views.setTextViewText(R.id.widget_title, title)
            views.setTextViewText(
                R.id.widget_status,
                context.getString(if (state.isActive) R.string.status_playing else R.string.status_paused),
            )
            views.setImageViewResource(R.id.widget_play, if (state.isActive) R.drawable.ic_pause else R.drawable.ic_play)
            views.setContentDescription(
                R.id.widget_play,
                context.getString(if (state.isActive) R.string.pause else R.string.play),
            )
            val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            val playPause = if (state.isActive) {
                PendingIntent.getBroadcast(
                    context, 51,
                    Intent(context, ActionReceiver::class.java).setAction(ActionReceiver.ACTION_PAUSE), flags,
                )
            } else {
                // Widget taps may start a foreground service from the background.
                PendingIntent.getForegroundService(
                    context, 52,
                    Intent(context, PlaybackService::class.java).setAction(PlaybackService.ACTION_PLAY), flags,
                )
            }
            views.setOnClickPendingIntent(R.id.widget_play, playPause)
            views.setOnClickPendingIntent(
                R.id.widget_next,
                PendingIntent.getBroadcast(
                    context, 53,
                    Intent(context, ActionReceiver::class.java).setAction(ActionReceiver.ACTION_NEXT), flags,
                ),
            )
            views.setOnClickPendingIntent(
                R.id.widget_root,
                PendingIntent.getActivity(context, 54, Intent(context, MainActivity::class.java), flags),
            )
            awm.updateAppWidget(ids, views)
        }
    }
}
