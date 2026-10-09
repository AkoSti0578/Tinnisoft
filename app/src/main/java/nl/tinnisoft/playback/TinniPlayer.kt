package nl.tinnisoft.playback

import android.content.Context
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nl.tinnisoft.R

/**
 * Media3 [Player] facade over [PlaybackManager]. It exposes the presets as a playlist so
 * next/previous (notification, lock screen, earbuds) switch presets, and puts the sleep
 * timer's remaining time in the subtitle.
 */
@OptIn(UnstableApi::class)
class TinniPlayer(
    private val context: Context,
    private val manager: PlaybackManager,
    scope: CoroutineScope,
) : SimpleBasePlayer(Looper.getMainLooper()) {

    private var timerTicker: Job? = null

    init {
        scope.launch {
            manager.state.collect { st ->
                invalidateState()
                if (st.timerEndMillis != null && timerTicker?.isActive != true) {
                    timerTicker = scope.launch {
                        while (manager.state.value.timerEndMillis != null) {
                            delay(30_000)
                            invalidateState()
                        }
                    }
                }
            }
        }
    }

    private val commands = Player.Commands.Builder()
        .addAll(
            Player.COMMAND_PLAY_PAUSE,
            Player.COMMAND_STOP,
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_MEDIA_ITEM,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
            Player.COMMAND_GET_TIMELINE,
            Player.COMMAND_GET_METADATA,
        )
        .build()

    override fun getState(): State {
        val st = manager.state.value
        val subtitle = st.timerEndMillis?.let { end ->
            val min = ((end - System.currentTimeMillis()) / 60_000L + 1).coerceAtLeast(0)
            context.getString(R.string.timer_remaining, min.toInt())
        } ?: context.getString(R.string.app_name)

        val items = ArrayList<MediaItemData>()
        val activeInList = st.presets.any { it.id == st.activePresetId }
        if (!activeInList) items += item(UID_CUSTOM, context.getString(R.string.custom_mix), subtitle)
        for (p in st.presets) {
            val title = if (p.id == st.activePresetId && st.modified) context.getString(R.string.preset_modified, p.name) else p.name
            items += item(p.id, title, subtitle)
        }
        val index = if (activeInList) st.presets.indexOfFirst { it.id == st.activePresetId } else 0

        return State.Builder()
            .setAvailableCommands(commands)
            .setPlayWhenReady(st.isActive, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(Player.STATE_READY)
            .setRepeatMode(Player.REPEAT_MODE_ALL)
            .setPlaylist(items)
            .setCurrentMediaItemIndex(index)
            .build()
    }

    private fun item(uid: Any, title: String, subtitle: String): MediaItemData {
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setDisplayTitle(title)
            .setArtist(subtitle)
            .setIsPlayable(true)
            .build()
        return MediaItemData.Builder(uid)
            .setMediaItem(MediaItem.Builder().setMediaId(uid.toString()).setMediaMetadata(metadata).build())
            .setMediaMetadata(metadata)
            .setIsDynamic(true)
            .setIsSeekable(false)
            .setDurationUs(C.TIME_UNSET)
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) manager.play() else manager.pause()
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        manager.stopWithFade()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val st = manager.state.value
        val activeInList = st.presets.any { it.id == st.activePresetId }
        val presetIndex = if (activeInList) mediaItemIndex else mediaItemIndex - 1
        st.presets.getOrNull(presetIndex)?.let { manager.selectPreset(it.id) }
        return Futures.immediateVoidFuture()
    }

    private companion object {
        const val UID_CUSTOM = "custom"
    }
}
