package com.bikeguard.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/** A location waiting to be uploaded. This table is the offline queue. */
@Entity(tableName = "pending_locations", indices = [Index(value = ["recordedAt"], unique = true)])
data class PendingLocation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val lat: Double,
    val lng: Double,
    val speed: Float?,
    val bearing: Float?,
    val accuracy: Float?,
    val battery: Int?,
    val charging: Boolean?,
    val recordedAt: Long,
) {
    fun toDto(deviceId: String) = LocationDto(
        deviceId = deviceId, lat = lat, lng = lng, speed = speed, bearing = bearing,
        accuracy = accuracy, battery = battery, isCharging = charging,
        recordedAt = Instant.ofEpochMilli(recordedAt).toString()
    )
}

@Dao
interface PendingDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(p: PendingLocation): Long

    @Query("SELECT * FROM pending_locations ORDER BY recordedAt ASC LIMIT :n")
    suspend fun next(n: Int): List<PendingLocation>

    @Query("DELETE FROM pending_locations WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>)

    @Query("SELECT COUNT(*) FROM pending_locations")
    fun count(): Flow<Int>

    /** Safety cap so a phone that stays offline for days cannot fill the storage. Keeps the newest rows. */
    @Query("DELETE FROM pending_locations WHERE id NOT IN (SELECT id FROM pending_locations ORDER BY recordedAt DESC LIMIT :keep)")
    suspend fun trim(keep: Int)

    @Query("DELETE FROM pending_locations")
    suspend fun clear()
}

/** An alert waiting to be uploaded (airplane mode, GPS off, theft motion, crash ...). Survives being offline. */
@Entity(tableName = "pending_events", indices = [Index(value = ["type", "createdAt"], unique = true)])
data class PendingEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    val severity: String,
    val lat: Double?,
    val lng: Double?,
    val data: String?,
    val createdAt: Long,
) {
    fun toDto(deviceId: String) = EventDto(
        deviceId = deviceId, type = type, severity = severity, lat = lat, lng = lng,
        data = data?.let { runCatching { kotlinx.serialization.json.Json.parseToJsonElement(it) }.getOrNull() },
        createdAt = Instant.ofEpochMilli(createdAt).toString()
    )
}

@Dao
interface EventDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(e: PendingEvent): Long

    @Query("SELECT * FROM pending_events ORDER BY createdAt ASC LIMIT :n")
    suspend fun next(n: Int): List<PendingEvent>

    @Query("DELETE FROM pending_events WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>)

    @Query("DELETE FROM pending_events")
    suspend fun clear()
}

@Database(entities = [PendingLocation::class, PendingEvent::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun pending(): PendingDao
    abstract fun events(): EventDao

    companion object {
        @Volatile private var instance: AppDatabase? = null
        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "bikeguard.db")
                .fallbackToDestructiveMigration() // v1 -> v2: only an unsent queue is lost
                .build().also { instance = it }
        }
    }
}
