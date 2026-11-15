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
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

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
    fun toDto(deviceId: String, ownerId: String? = null) = LocationDto(
        deviceId = deviceId, ownerId = ownerId, lat = lat, lng = lng, speed = speed, bearing = bearing,
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
    fun toDto(deviceId: String, ownerId: String? = null) = EventDto(
        deviceId = deviceId, ownerId = ownerId, type = type, severity = severity, lat = lat, lng = lng,
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

    /** Safety cap, mirroring the locations queue: a flapping guard must not grow
     *  the alerts queue without bound while offline. Keeps the newest rows. */
    @Query("DELETE FROM pending_events WHERE id NOT IN (SELECT id FROM pending_events ORDER BY createdAt DESC LIMIT :keep)")
    suspend fun trim(keep: Int)

    @Query("DELETE FROM pending_events")
    suspend fun clear()
}

/** One expense line (fuel, service, repair, other). Local copy; the server has the same table. */
@Entity(tableName = "expenses")
data class ExpenseRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val category: String,
    val amount: Double,
    val note: String,
    val spentOn: Long,
    val createdAt: Long = System.currentTimeMillis(),
    /** Client UUID used as the server row's id, so uploads are idempotent
     *  (on_conflict=id, ignore-duplicates). Blank on rows written before
     *  v1.2.15; the first sync assigns one before uploading. */
    val serverId: String = "",
    /** True once this row is on the server. Rows added offline stay false
     *  until a sync succeeds. */
    val synced: Boolean = false,
) {
    /** Server payload: owner_id is left out so the server default (auth.uid())
     *  fills it, and device_id is left out (nullable; the Remote has no
     *  registered device row of its own). */
    fun toDto() = ExpenseDto(
        id = serverId, category = category, amount = amount,
        note = note.ifBlank { null },
        spentOn = Instant.ofEpochMilli(spentOn).atZone(ZoneId.systemDefault()).toLocalDate().toString(),
    )
}

/** A server `spent_on` date (yyyy-MM-dd) as epoch ms at the start of that day. */
fun expenseDateToMs(isoDate: String): Long = runCatching {
    LocalDate.parse(isoDate).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
}.getOrDefault(System.currentTimeMillis())

@Dao
interface ExpenseDao {
    @Insert
    suspend fun insert(e: ExpenseRecord): Long

    @Query("DELETE FROM expenses WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM expenses ORDER BY spentOn DESC, id DESC")
    fun all(): Flow<List<ExpenseRecord>>

    @Query("SELECT COALESCE(SUM(amount), 0) FROM expenses WHERE spentOn >= :fromMs")
    fun totalSince(fromMs: Long): Flow<Double>

    @Query("SELECT * FROM expenses WHERE synced = 0")
    suspend fun unsynced(): List<ExpenseRecord>

    @Query("SELECT * FROM expenses WHERE id = :id")
    suspend fun get(id: Long): ExpenseRecord?

    @Query("SELECT * FROM expenses WHERE serverId = :serverId LIMIT 1")
    suspend fun byServerId(serverId: String): ExpenseRecord?

    @Query("UPDATE expenses SET serverId = :serverId WHERE id = :id")
    suspend fun setServerId(id: Long, serverId: String)

    @Query("UPDATE expenses SET synced = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<Long>)
}

@Database(entities = [PendingLocation::class, PendingEvent::class, ExpenseRecord::class], version = 4, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun pending(): PendingDao
    abstract fun events(): EventDao
    abstract fun expenses(): ExpenseDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        /** v3 -> v4: the expense log gains its server id + synced flag. A real
         *  migration, so this upgrade does NOT wipe anything: the offline queue,
         *  the pending alerts and the expense log all survive. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE expenses ADD COLUMN serverId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE expenses ADD COLUMN synced INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "bikeguard.db")
                .addMigrations(MIGRATION_3_4)
                // Last resort only: a future schema bump without a migration must
                // never crash the tracker on launch. If it ever fires, the queue
                // and pending alerts from that window are lost, but the expense
                // log restores from the server on the next sync (v1.2.15).
                .fallbackToDestructiveMigration()
                .build().also { instance = it }
        }
    }
}
