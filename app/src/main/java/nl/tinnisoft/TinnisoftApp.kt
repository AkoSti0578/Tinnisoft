package nl.tinnisoft

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch
import nl.tinnisoft.playback.Notifier
import nl.tinnisoft.playback.PlaybackManager
import nl.tinnisoft.widget.QuickStartWidget
import javax.inject.Inject

@HiltAndroidApp
class TinnisoftApp : Application() {
    @Inject lateinit var manager: PlaybackManager

    @Inject lateinit var notifier: Notifier

    private val scope = MainScope()

    override fun onCreate() {
        super.onCreate()
        notifier.ensureChannels()
        scope.launch {
            manager.state
                .distinctUntilChangedBy { Triple(it.isActive, it.activePresetId, it.presets.map { p -> p.name }) }
                .collect { QuickStartWidget.update(this@TinnisoftApp, it) }
        }
    }
}
