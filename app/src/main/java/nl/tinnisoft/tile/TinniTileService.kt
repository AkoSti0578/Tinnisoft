package nl.tinnisoft.tile

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import nl.tinnisoft.MainActivity
import nl.tinnisoft.R
import nl.tinnisoft.playback.PlaybackService
import nl.tinnisoft.playback.PlaybackState
import nl.tinnisoft.schedule.appEntryPoint

/** Quick Settings tile: tap to start/pause the masking sound. */
class TinniTileService : TileService() {
    private val scope = MainScope()
    private var job: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        val manager = appEntryPoint().manager()
        job = scope.launch { manager.state.collect { render(it) } }
    }

    override fun onStopListening() {
        job?.cancel()
        super.onStopListening()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun render(state: PlaybackState) {
        val tile = qsTile ?: return
        tile.state = if (state.isActive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.app_name)
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = state.activePreset?.name ?: getString(R.string.custom_mix)
        }
        tile.updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        val manager = appEntryPoint().manager()
        if (manager.state.value.isActive) {
            manager.pause()
            return
        }
        if (PlaybackService.play(this)) return
        // Not allowed to start from here: open the app, which starts playback.
        val intent = Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_AUTOPLAY, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 61, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
