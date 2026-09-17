package com.teamproject1.dailyexpensetracker.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.teamproject1.dailyexpensetracker.core.database.entity.SnoozedReminderEntity

@Dao
interface SnoozedReminderDao {
    @Insert
    suspend fun insert(snooze: SnoozedReminderEntity): Long

    @Query("SELECT snoozedUntil FROM snoozed_reminders WHERE reminderType = :reminderType AND accountId = :accountId ORDER BY snoozedUntil DESC LIMIT 1")
    suspend fun getSnoozedUntil(reminderType: String, accountId: Long): Long?

    @Query("DELETE FROM snoozed_reminders WHERE snoozedUntil < :now")
    suspend fun deleteExpired(now: Long)
}
