package nl.tinnisoft.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import nl.tinnisoft.audio.engine.AudioEngine
import nl.tinnisoft.audio.engine.NatureLoopCache
import nl.tinnisoft.audio.engine.OutputDeviceMonitor
import nl.tinnisoft.data.db.AppDatabase
import nl.tinnisoft.data.db.LogDao
import nl.tinnisoft.data.db.PresetDao
import nl.tinnisoft.data.settings.dataStore
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun dataStore(@ApplicationContext ctx: Context): DataStore<Preferences> = ctx.dataStore

    @Provides
    @Singleton
    fun database(@ApplicationContext ctx: Context): AppDatabase =
        Room.databaseBuilder(ctx, AppDatabase::class.java, "tinnisoft.db").build()

    @Provides
    fun presetDao(db: AppDatabase): PresetDao = db.presets()

    @Provides
    fun logDao(db: AppDatabase): LogDao = db.log()

    @Provides
    @Singleton
    fun audioEngine(@ApplicationContext ctx: Context): AudioEngine = AudioEngine(NatureLoopCache(ctx))

    @Provides
    @Singleton
    fun outputMonitor(@ApplicationContext ctx: Context): OutputDeviceMonitor = OutputDeviceMonitor(ctx)
}
