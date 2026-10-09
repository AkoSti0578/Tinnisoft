package nl.tinnisoft.data

import kotlinx.coroutines.flow.Flow
import nl.tinnisoft.data.db.PresetDao
import nl.tinnisoft.data.db.PresetEntity
import nl.tinnisoft.data.model.LayerConfig
import nl.tinnisoft.data.model.MixConfig
import nl.tinnisoft.data.model.NatureSound
import nl.tinnisoft.data.model.NoiseColor
import nl.tinnisoft.data.model.SourceKind
import nl.tinnisoft.data.settings.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PresetRepository @Inject constructor(
    private val dao: PresetDao,
    private val settings: SettingsRepository,
) {
    val presets: Flow<List<PresetEntity>> = dao.observeAll()

    suspend fun all(): List<PresetEntity> = dao.getAll()

    suspend fun get(id: Long): PresetEntity? = dao.get(id)

    suspend fun create(name: String, mix: MixConfig): Long =
        dao.insert(PresetEntity(name = name.trim(), sortOrder = dao.nextOrder(), mixJson = mix.toJson()))

    suspend fun rename(id: Long, name: String) {
        dao.get(id)?.let { dao.update(it.copy(name = name.trim())) }
    }

    suspend fun overwrite(id: Long, mix: MixConfig) {
        dao.get(id)?.let { dao.update(it.copy(mixJson = mix.toJson())) }
    }

    suspend fun delete(id: Long) = dao.delete(id)

    suspend fun move(id: Long, delta: Int) {
        val list = dao.getAll().map { it.id }.toMutableList()
        val i = list.indexOf(id)
        val j = i + delta
        if (i < 0 || j !in list.indices) return
        list.removeAt(i)
        list.add(j, id)
        dao.reorder(list)
    }

    /** First start: create "Slapen", "Werk" and "Rustig". Names come from resources. */
    suspend fun seedDefaults(sleep: String, work: String, calm: String) {
        if (settings.current().defaultsSeeded) return
        if (dao.getAll().isEmpty()) {
            create(
                sleep,
                MixConfig(
                    layers = listOf(
                        LayerConfig(SourceKind.NOISE, NoiseColor.BROWN, volume = 0.8f),
                        LayerConfig(SourceKind.NATURE, nature = NatureSound.RAIN, volume = 0.6f),
                    ),
                    modulationEnabled = true,
                    modulationDepth = 0.2f,
                    modulationPeriodSec = 14f,
                ),
            )
            create(work, MixConfig(layers = listOf(LayerConfig(SourceKind.NOISE, NoiseColor.PINK, volume = 0.8f))))
            create(
                calm,
                MixConfig(
                    layers = listOf(
                        LayerConfig(SourceKind.NOTCHED, NoiseColor.PINK, volume = 0.8f),
                        LayerConfig(SourceKind.NATURE, nature = NatureSound.WAVES, volume = 0.6f),
                    ),
                ),
            )
        }
        settings.setDefaultsSeeded()
    }
}
