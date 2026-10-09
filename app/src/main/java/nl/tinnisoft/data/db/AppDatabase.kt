package nl.tinnisoft.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import nl.tinnisoft.data.model.MixConfig

@Entity(tableName = "presets")
data class PresetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val sortOrder: Int,
    val mixJson: String,
)

fun PresetEntity.mix(): MixConfig = MixConfig.fromJson(mixJson) ?: MixConfig()

/** One entry per day. */
@Entity(tableName = "log_entries")
data class LogEntryEntity(
    @PrimaryKey val epochDay: Long,
    /** How loud was the tinnitus today, 0..10. */
    val loudness: Int,
    /** How well did masking help, 0..10. */
    val relief: Int,
    val note: String = "",
)

@Dao
abstract class PresetDao {
    @Query("SELECT * FROM presets ORDER BY sortOrder, id")
    abstract fun observeAll(): Flow<List<PresetEntity>>

    @Query("SELECT * FROM presets ORDER BY sortOrder, id")
    abstract suspend fun getAll(): List<PresetEntity>

    @Query("SELECT * FROM presets WHERE id = :id")
    abstract suspend fun get(id: Long): PresetEntity?

    @Insert
    abstract suspend fun insert(p: PresetEntity): Long

    @Update
    abstract suspend fun update(p: PresetEntity)

    @Query("DELETE FROM presets WHERE id = :id")
    abstract suspend fun delete(id: Long)

    @Query("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM presets")
    abstract suspend fun nextOrder(): Int

    @Query("UPDATE presets SET sortOrder = :order WHERE id = :id")
    abstract suspend fun setOrder(id: Long, order: Int)

    @Transaction
    open suspend fun reorder(idsInOrder: List<Long>) {
        idsInOrder.forEachIndexed { i, id -> setOrder(id, i) }
    }
}

@Dao
interface LogDao {
    @Query("SELECT * FROM log_entries WHERE epochDay >= :fromDay ORDER BY epochDay")
    fun observeFrom(fromDay: Long): Flow<List<LogEntryEntity>>

    @Query("SELECT * FROM log_entries WHERE epochDay = :day")
    suspend fun get(day: Long): LogEntryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(e: LogEntryEntity)
}

@Database(entities = [PresetEntity::class, LogEntryEntity::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun presets(): PresetDao
    abstract fun log(): LogDao
}
