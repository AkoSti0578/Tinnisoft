package nl.tinnisoft.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nl.tinnisoft.MainActivity
import nl.tinnisoft.R
import javax.inject.Inject

/**
 * Foreground media service (type mediaPlayback). Hosts the Media3 [MediaSession], which
 * provides the persistent notification, lock-screen controls and earbud media buttons.
 */
@OptIn(UnstableApi::class)
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    @Inject lateinit var manager: PlaybackManager

    @Inject lateinit var notifier: Notifier

    private val scope = MainScope()
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = TinniPlayer(this, manager, scope)
        val stop = CommandButton.Builder()
            .setDisplayName(getString(R.string.stop))
            .setIconResId(R.drawable.ic_stop)
            .setSessionCommand(SessionCommand(CMD_STOP, Bundle.EMPTY))
            .build()
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val s = MediaSession.Builder(this, player)
            .setSessionActivity(openApp)
            .setCallback(Callback())
            .build()
        s.setCustomLayout(listOf(stop))
        session = s
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelName(R.string.channel_playback)
                .build()
                .apply { setSmallIcon(R.drawable.ic_notification) },
        )
        addSession(s)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val result = super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_PLAY, ACTION_PLAY_PRESET -> {
                // We were started with startForegroundService(): be in the foreground right
                // away. Media3 replaces this placeholder with the media notification.
                if (!manager.state.value.isActive) enterForegroundPlaceholder()
                notifier.cancelResumeOffer()
                val presetId = intent.getLongExtra(EXTRA_PRESET_ID, -1)
                if (presetId >= 0) manager.playPreset(presetId) else manager.play()
                scope.launch {
                    delay(3000)
                    // Playback could not start (e.g. during a phone call): drop the placeholder.
                    if (!manager.state.value.isActive) {
                        ServiceCompat.stopForeground(this@PlaybackService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    }
                }
            }
        }
        return result
    }

    private fun enterForegroundPlaceholder() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PLACEHOLDER, getString(R.string.channel_playback), NotificationManager.IMPORTANCE_LOW),
        )
        val n: Notification = NotificationCompat.Builder(this, CHANNEL_PLACEHOLDER)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.starting))
            .setSilent(true)
            .build()
        try {
            ServiceCompat.startForeground(
                this,
                DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID,
                n,
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0,
            )
        } catch (e: Exception) {
            Log.w(TAG, "could not enter foreground", e)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!manager.state.value.isActive) stopSelf()
    }

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        scope.cancel()
        super.onDestroy()
    }

    private inner class Callback : MediaSession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(SessionCommand(CMD_STOP, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == CMD_STOP) manager.stopWithFade()
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    companion object {
        const val ACTION_PLAY = "nl.tinnisoft.action.PLAY"
        const val ACTION_PLAY_PRESET = "nl.tinnisoft.action.PLAY_PRESET"
        const val EXTRA_PRESET_ID = "preset_id"
        private const val CMD_STOP = "nl.tinnisoft.STOP"
        private const val CHANNEL_PLACEHOLDER = "playback_start"
        private const val TAG = "PlaybackService"

        /**
         * Start playback through the foreground service. Returns false when Android does
         * not allow it right now (app in the background without an exemption).
         */
        fun play(context: Context, presetId: Long = -1): Boolean = try {
            val i = Intent(context, PlaybackService::class.java)
                .setAction(if (presetId >= 0) ACTION_PLAY_PRESET else ACTION_PLAY)
                .putExtra(EXTRA_PRESET_ID, presetId)
            ContextCompat.startForegroundService(context, i)
            true
        } catch (e: Exception) {
            Log.w(TAG, "start not allowed", e)
            false
        }
    }
}
