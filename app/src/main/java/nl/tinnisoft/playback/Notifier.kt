package nl.tinnisoft.playback

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import nl.tinnisoft.MainActivity
import nl.tinnisoft.R
import javax.inject.Inject
import javax.inject.Singleton

/** Non-playback notifications (daily limit, resume after reboot). */
@Singleton
class Notifier @Inject constructor(@ApplicationContext private val context: Context) {

    fun ensureChannels() {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, context.getString(R.string.channel_alerts), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    private fun canPost() = android.os.Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    @SuppressLint("MissingPermission") // checked in canPost()
    fun dailyLimitReached(minutes: Int) {
        if (!canPost()) return
        ensureChannels()
        val n = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.limit_reached_title))
            .setContentText(context.getString(R.string.limit_reached_text, minutes))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.limit_reached_text, minutes)))
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(ID_LIMIT, n)
    }

    /** Android 15+ does not allow starting media playback directly after boot: ask with one tap. */
    @SuppressLint("MissingPermission") // checked in canPost()
    fun offerResumeAfterBoot() {
        if (!canPost()) return
        ensureChannels()
        val play = PendingIntent.getForegroundService(
            context,
            REQ_RESUME,
            Intent(context, PlaybackService::class.java).setAction(PlaybackService.ACTION_PLAY),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.resume_boot_title))
            .setContentText(context.getString(R.string.resume_boot_text))
            .setContentIntent(play)
            .addAction(R.drawable.ic_play, context.getString(R.string.play), play)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(ID_RESUME, n)
    }

    fun cancelResumeOffer() = NotificationManagerCompat.from(context).cancel(ID_RESUME)

    companion object {
        const val CHANNEL_ALERTS = "alerts"
        private const val ID_LIMIT = 2001
        private const val ID_RESUME = 2002
        private const val REQ_RESUME = 31
    }
}
