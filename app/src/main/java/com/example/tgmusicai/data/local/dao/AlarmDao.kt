package com.example.tgmusicai.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.tgmusicai.data.local.entity.Alarm
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object (DAO) for managing [Alarm] database operations in Room.
 */
@Dao
interface AlarmDao {
    /** Live list of every alarm, in insertion order; used by the Alarms screen so the UI updates automatically when alarms change. */
    @Query("SELECT * FROM alarms ORDER BY id ASC")
    fun getAllAlarms(): Flow<List<Alarm>>

    /** One-shot (non-Flow) read of every alarm, in insertion order; used where a suspend snapshot is needed instead of an observed stream. */
    @Query("SELECT * FROM alarms ORDER BY id ASC")
    suspend fun getAllAlarmsList(): List<Alarm>

    // Read on boot / app start to re-register system alarms with AlarmManager -- disabled alarms
    // are skipped since they shouldn't have a pending system alarm at all.
    @Query("SELECT * FROM alarms WHERE isEnabled = 1")
    suspend fun getEnabledAlarms(): List<Alarm>

    /** Looks up a single alarm by its row id, or null if it no longer exists (e.g. already deleted). */
    @Query("SELECT * FROM alarms WHERE id = :alarmId")
    suspend fun getAlarmById(alarmId: Long): Alarm?

    /** Inserts a new alarm, or overwrites one with the same id if it already exists. Returns the row id (new or existing). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAlarm(alarm: Alarm): Long

    /** Overwrites an existing alarm's fields (matched by primary key) after the user edits it. */
    @Update
    suspend fun updateAlarm(alarm: Alarm)

    /** Removes an alarm using the full entity (Room matches it by primary key). */
    @Delete
    suspend fun deleteAlarm(alarm: Alarm)

    /** Removes an alarm by id, for callers that only have the id on hand rather than the full [Alarm] object. */
    @Query("DELETE FROM alarms WHERE id = :alarmId")
    suspend fun deleteAlarmById(alarmId: Long)
}
