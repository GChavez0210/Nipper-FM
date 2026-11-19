package app.openradio.data.local

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import app.openradio.domain.SavedStation
import app.openradio.domain.Station
import kotlinx.coroutines.flow.Flow

/**
 * One row per station the user has saved or played. Directory fields are a cache so favorites,
 * presets and recents stay browseable (and playable via [streamUrl]) when the directory is down.
 */
@Entity(tableName = "stations")
data class StationEntity(
    @PrimaryKey val id: String,
    val name: String,
    val streamUrl: String,
    val faviconUrl: String?,
    val country: String?,
    val countryCode: String?,
    val state: String?,
    val tags: String,
    val codec: String?,
    val bitrate: Int,
    val isHls: Boolean,
    val isFavorite: Boolean = false,
    val favoritedAt: Long? = null,
    val pinnedOrder: Int? = null,
    val lastPlayedAt: Long? = null,
)

fun StationEntity.toStation() = Station(
    id = id, name = name, streamUrl = streamUrl, faviconUrl = faviconUrl, country = country,
    countryCode = countryCode, state = state, tags = tags.split(',').filter { it.isNotBlank() },
    codec = codec, bitrate = bitrate, isHls = isHls,
)

fun StationEntity.toSaved() = SavedStation(toStation(), isFavorite, pinnedOrder, lastPlayedAt)

fun Station.toEntity(existing: StationEntity? = null) = StationEntity(
    id = id, name = name, streamUrl = streamUrl, faviconUrl = faviconUrl, country = country,
    countryCode = countryCode, state = state, tags = tags.joinToString(","), codec = codec,
    bitrate = bitrate, isHls = isHls,
    isFavorite = existing?.isFavorite ?: false,
    favoritedAt = existing?.favoritedAt,
    pinnedOrder = existing?.pinnedOrder,
    lastPlayedAt = existing?.lastPlayedAt,
)

@Dao
interface StationDao {
    @Query("SELECT * FROM stations WHERE id = :id")
    suspend fun get(id: String): StationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: StationEntity)

    @Query("SELECT * FROM stations WHERE isFavorite = 1 ORDER BY favoritedAt DESC")
    fun observeFavorites(): Flow<List<StationEntity>>

    @Query("SELECT * FROM stations WHERE isFavorite = 1 ORDER BY favoritedAt DESC")
    suspend fun favorites(): List<StationEntity>

    @Query("SELECT * FROM stations WHERE lastPlayedAt IS NOT NULL ORDER BY lastPlayedAt DESC LIMIT :limit")
    fun observeRecents(limit: Int): Flow<List<StationEntity>>

    @Query("SELECT * FROM stations WHERE lastPlayedAt IS NOT NULL ORDER BY lastPlayedAt DESC LIMIT :limit")
    suspend fun recents(limit: Int): List<StationEntity>

    @Query("SELECT id FROM stations WHERE isFavorite = 1")
    fun observeFavoriteIds(): Flow<List<String>>

    @Query("UPDATE stations SET isFavorite = 1, favoritedAt = :now WHERE id = :id")
    suspend fun markFavorite(id: String, now: Long)

    @Query("UPDATE stations SET isFavorite = 0, favoritedAt = NULL, pinnedOrder = NULL WHERE id = :id")
    suspend fun clearFavorite(id: String)

    @Query("UPDATE stations SET lastPlayedAt = :now WHERE id = :id")
    suspend fun markPlayed(id: String, now: Long)

    @Query("UPDATE stations SET pinnedOrder = NULL")
    suspend fun clearPins()

    @Query("UPDATE stations SET pinnedOrder = :order WHERE id = :id AND isFavorite = 1")
    suspend fun setPin(id: String, order: Int)

    @Query("UPDATE stations SET streamUrl = :url WHERE id = :id")
    suspend fun updateStreamUrl(id: String, url: String)

    @Query(
        "UPDATE stations SET lastPlayedAt = NULL WHERE lastPlayedAt IS NOT NULL AND id NOT IN " +
            "(SELECT id FROM stations WHERE lastPlayedAt IS NOT NULL ORDER BY lastPlayedAt DESC LIMIT :keep)",
    )
    suspend fun trimRecents(keep: Int)

    @Query("DELETE FROM stations WHERE isFavorite = 0 AND lastPlayedAt IS NULL")
    suspend fun deleteOrphans()

    @Query(
        "SELECT * FROM stations WHERE isFavorite = 1 AND name LIKE '%' || :q || '%' COLLATE NOCASE " +
            "ORDER BY favoritedAt DESC",
    )
    suspend fun searchFavorites(q: String): List<StationEntity>
}

@Database(entities = [StationEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun stations(): StationDao
}
