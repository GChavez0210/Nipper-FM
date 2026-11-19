package app.openradio.data.local

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import app.openradio.domain.OnAirEntry
import app.openradio.domain.SavedStation
import app.openradio.domain.Station
import app.openradio.domain.UrlHealth
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
    val latitude: Double? = null,
    val longitude: Double? = null,
    val isCustom: Boolean = false,
    val groupName: String? = null,
    val failedLastAttempt: Boolean = false,
    /** Per-station loudness offset in whole dB (see VolumeTrim). */
    val volumeTrimDb: Int = 0,
)

/** Per-station, per-URL outcome history used to avoid URLs that just failed. */
@Entity(tableName = "stream_health", primaryKeys = ["stationId", "url"])
data class StreamHealthEntity(
    val stationId: String,
    val url: String,
    val lastSuccessAt: Long? = null,
    val lastFailureAt: Long? = null,
    val failureCount: Int = 0,
)

fun StreamHealthEntity.toDomain() = UrlHealth(url, lastSuccessAt, lastFailureAt, failureCount)

@Entity(tableName = "favorite_groups")
data class FavoriteGroupEntity(
    @PrimaryKey val name: String,
    val createdAt: Long,
)

@Entity(tableName = "on_air_history")
data class OnAirEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val stationId: String,
    val stationName: String,
    val trackTitle: String,
    val timestampMs: Long,
    val isBookmarked: Boolean = false,
)

fun OnAirEntity.toDomain() = OnAirEntry(
    id = id,
    stationId = stationId,
    stationName = stationName,
    trackTitle = trackTitle,
    timestampMs = timestampMs,
    isBookmarked = isBookmarked,
)

fun StationEntity.toStation() = Station(
    id = id, name = name, streamUrl = streamUrl, faviconUrl = faviconUrl, country = country,
    countryCode = countryCode, state = state, tags = tags.split(',').filter { it.isNotBlank() },
    codec = codec, bitrate = bitrate, isHls = isHls,
    latitude = latitude, longitude = longitude, isCustom = isCustom,
)

fun StationEntity.toSaved() = SavedStation(toStation(), isFavorite, pinnedOrder, lastPlayedAt, groupName, failedLastAttempt)

fun Station.toEntity(existing: StationEntity? = null) = StationEntity(
    id = id, name = name, streamUrl = streamUrl, faviconUrl = faviconUrl, country = country,
    countryCode = countryCode, state = state, tags = tags.joinToString(","), codec = codec,
    bitrate = bitrate, isHls = isHls,
    isFavorite = existing?.isFavorite ?: false,
    favoritedAt = existing?.favoritedAt,
    pinnedOrder = existing?.pinnedOrder,
    lastPlayedAt = existing?.lastPlayedAt,
    latitude = latitude ?: existing?.latitude,
    longitude = longitude ?: existing?.longitude,
    isCustom = isCustom || (existing?.isCustom == true),
    groupName = existing?.groupName,
    failedLastAttempt = existing?.failedLastAttempt ?: false,
    volumeTrimDb = existing?.volumeTrimDb ?: 0,
)

@Dao
interface StationDao {
    @Query("SELECT * FROM stations WHERE id = :id")
    suspend fun get(id: String): StationEntity?

    @Query("SELECT * FROM stations")
    suspend fun allStations(): List<StationEntity>

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

    @Query("UPDATE stations SET isFavorite = 0, favoritedAt = NULL, pinnedOrder = NULL, groupName = NULL WHERE id = :id")
    suspend fun clearFavorite(id: String)

    @Query("UPDATE stations SET lastPlayedAt = :now WHERE id = :id")
    suspend fun markPlayed(id: String, now: Long)

    @Query("UPDATE stations SET pinnedOrder = NULL")
    suspend fun clearPins()

    @Query("UPDATE stations SET pinnedOrder = :order WHERE id = :id AND isFavorite = 1")
    suspend fun setPin(id: String, order: Int)

    @Query("UPDATE stations SET streamUrl = :url WHERE id = :id")
    suspend fun updateStreamUrl(id: String, url: String)

    @Query("UPDATE stations SET groupName = :groupName WHERE id = :id")
    suspend fun setGroup(id: String, groupName: String?)

    @Query("UPDATE stations SET groupName = NULL WHERE groupName = :groupName")
    suspend fun clearGroupFromStations(groupName: String)

    @Query("SELECT name FROM favorite_groups ORDER BY createdAt ASC")
    fun observeGroupNames(): Flow<List<String>>

    @Query("SELECT name FROM favorite_groups ORDER BY createdAt ASC")
    suspend fun groupNames(): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertGroup(group: FavoriteGroupEntity)

    @Query("DELETE FROM favorite_groups WHERE name = :name")
    suspend fun deleteGroup(name: String)

    @Query("DELETE FROM favorite_groups")
    suspend fun deleteAllGroups()

    @Query("DELETE FROM stations")
    suspend fun deleteAllStations()

    @Query(
        "UPDATE stations SET lastPlayedAt = NULL WHERE lastPlayedAt IS NOT NULL AND id NOT IN " +
            "(SELECT id FROM stations WHERE lastPlayedAt IS NOT NULL ORDER BY lastPlayedAt DESC LIMIT :keep)",
    )
    suspend fun trimRecents(keep: Int)

    @Query("UPDATE stations SET volumeTrimDb = :db WHERE id = :id")
    suspend fun setVolumeTrim(id: String, db: Int)

    @Query("SELECT volumeTrimDb FROM stations WHERE id = :id")
    suspend fun volumeTrim(id: String): Int?

    @Query(
        "DELETE FROM stations WHERE isFavorite = 0 AND lastPlayedAt IS NULL AND failedLastAttempt = 0 " +
            "AND volumeTrimDb = 0",
    )
    suspend fun deleteOrphans()

    @Query("SELECT id FROM stations WHERE failedLastAttempt = 1")
    fun observeFailedIds(): Flow<List<String>>

    @Query("UPDATE stations SET failedLastAttempt = :failed WHERE id = :id")
    suspend fun setFailedLastAttempt(id: String, failed: Boolean)

    @Query(
        "SELECT * FROM stations WHERE isFavorite = 1 AND name LIKE '%' || :q || '%' COLLATE NOCASE " +
            "ORDER BY favoritedAt DESC",
    )
    suspend fun searchFavorites(q: String): List<StationEntity>

    // ---- Stream URL health ------------------------------------------------------------------

    @Query("SELECT * FROM stream_health WHERE stationId = :stationId")
    suspend fun streamHealth(stationId: String): List<StreamHealthEntity>

    @Query("SELECT * FROM stream_health WHERE stationId = :stationId AND url = :url")
    suspend fun streamHealth(stationId: String, url: String): StreamHealthEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertStreamHealth(entity: StreamHealthEntity)

    @Query("DELETE FROM stream_health WHERE COALESCE(lastSuccessAt, 0) < :before AND COALESCE(lastFailureAt, 0) < :before")
    suspend fun pruneStreamHealth(before: Long)

    // ---- Recently On Air --------------------------------------------------------------------

    @Query("SELECT * FROM on_air_history ORDER BY timestampMs DESC LIMIT :limit")
    fun observeOnAir(limit: Int = 60): Flow<List<OnAirEntity>>

    @Query("SELECT * FROM on_air_history ORDER BY timestampMs DESC LIMIT 1")
    suspend fun latestOnAir(): OnAirEntity?

    @Query("SELECT * FROM on_air_history WHERE id = :id")
    suspend fun getOnAir(id: Long): OnAirEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOnAir(entry: OnAirEntity)

    @Query("UPDATE on_air_history SET isBookmarked = :bookmarked WHERE id = :id")
    suspend fun setOnAirBookmark(id: Long, bookmarked: Boolean)

    @Query(
        "DELETE FROM on_air_history WHERE isBookmarked = 0 AND id NOT IN " +
            "(SELECT id FROM on_air_history ORDER BY timestampMs DESC LIMIT :keep)",
    )
    suspend fun trimOnAir(keep: Int = 80)
}

@Database(
    entities = [StationEntity::class, FavoriteGroupEntity::class, OnAirEntity::class, StreamHealthEntity::class],
    version = 5,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun stations(): StationDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE stations ADD COLUMN latitude REAL")
                db.execSQL("ALTER TABLE stations ADD COLUMN longitude REAL")
                db.execSQL("ALTER TABLE stations ADD COLUMN isCustom INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE stations ADD COLUMN groupName TEXT")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS favorite_groups (" +
                        "name TEXT NOT NULL PRIMARY KEY, " +
                        "createdAt INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS on_air_history (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "stationId TEXT NOT NULL, " +
                        "stationName TEXT NOT NULL, " +
                        "trackTitle TEXT NOT NULL, " +
                        "timestampMs INTEGER NOT NULL, " +
                        "isBookmarked INTEGER NOT NULL DEFAULT 0)",
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE stations ADD COLUMN failedLastAttempt INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE stations ADD COLUMN volumeTrimDb INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS stream_health (" +
                        "stationId TEXT NOT NULL, " +
                        "url TEXT NOT NULL, " +
                        "lastSuccessAt INTEGER, " +
                        "lastFailureAt INTEGER, " +
                        "failureCount INTEGER NOT NULL, " +
                        "PRIMARY KEY(stationId, url))",
                )
            }
        }
    }
}
