package com.honor.systemcontroller.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/**
 * The ORIGINAL state of a package, captured the first time this app changes it.
 * Restore always returns a package to this state. It is deleted only after a successful restore.
 */
@Entity(tableName = "package_snapshots")
data class PackageSnapshot(
    @PrimaryKey val packageName: String,
    val label: String,
    val isSystem: Boolean,
    /** PackageManager.COMPONENT_ENABLED_STATE_* value at capture time. */
    val enabledSetting: Int,
    val wasSuspended: Boolean,
    val wasStopped: Boolean,
    /** Raw appops mode of RUN_ANY_IN_BACKGROUND ("allow", "ignore", "default"...) or null if unreadable. */
    val runAnyInBackgroundMode: String?,
    /** Raw appops mode of RUN_IN_BACKGROUND or null. */
    val runInBackgroundMode: String?,
    /** App standby bucket name (active, working_set, frequent, rare, restricted) or null. */
    val standbyBucket: String?,
    /** Components disabled by this app, '|' separated. */
    val disabledComponents: String = "",
    /** Original APK directory hidden by the root debloat module, e.g. /product/app/HnBrowser. */
    val rootRemovedDir: String? = null,
    val capturedAt: Long = System.currentTimeMillis()
)

/** Complete audit log – every attempted operation, successful or not. */
@Entity(tableName = "operation_log")
data class OperationLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val packageName: String,
    val action: String,
    val mode: String,
    val command: String,
    val success: Boolean,
    val verified: Boolean,
    val message: String,
    val beforeState: String,
    val afterState: String
)

@Entity(tableName = "blocklist")
data class BlockedApp(
    @PrimaryKey val packageName: String,
    val label: String,
    val disablePackage: Boolean,
    val suspendLaunch: Boolean,
    val restrictBackground: Boolean,
    val forceStop: Boolean,
    val addedAt: Long = System.currentTimeMillis(),
    val lastCheckedAt: Long = 0,
    /** Human readable result of the last verification. */
    val lastStatus: String = "Not verified yet",
    val violationCount: Int = 0,
    val lastSeenActiveAt: Long = 0
)

@Dao
interface SnapshotDao {
    @Query("SELECT * FROM package_snapshots ORDER BY capturedAt DESC")
    fun observeAll(): Flow<List<PackageSnapshot>>

    @Query("SELECT * FROM package_snapshots")
    suspend fun all(): List<PackageSnapshot>

    @Query("SELECT * FROM package_snapshots WHERE packageName = :pkg")
    suspend fun get(pkg: String): PackageSnapshot?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(s: PackageSnapshot)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(s: PackageSnapshot)

    @Query("DELETE FROM package_snapshots WHERE packageName = :pkg")
    suspend fun delete(pkg: String)
}

@Dao
interface LogDao {
    @Query("SELECT * FROM operation_log ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(limit: Int = 300): Flow<List<OperationLog>>

    @Query("SELECT * FROM operation_log ORDER BY timestamp ASC")
    suspend fun all(): List<OperationLog>

    @Insert
    suspend fun insert(l: OperationLog): Long

    @Query("DELETE FROM operation_log")
    suspend fun clear()
}

@Dao
interface BlocklistDao {
    @Query("SELECT * FROM blocklist ORDER BY label COLLATE NOCASE")
    fun observeAll(): Flow<List<BlockedApp>>

    @Query("SELECT * FROM blocklist")
    suspend fun all(): List<BlockedApp>

    @Query("SELECT * FROM blocklist WHERE packageName = :pkg")
    suspend fun get(pkg: String): BlockedApp?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(b: BlockedApp)

    @Query("DELETE FROM blocklist WHERE packageName = :pkg")
    suspend fun delete(pkg: String)
}

@Database(
    entities = [PackageSnapshot::class, OperationLog::class, BlockedApp::class],
    version = 1,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun snapshots(): SnapshotDao
    abstract fun logs(): LogDao
    abstract fun blocklist(): BlocklistDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "honor_controller.db").build()
    }
}
