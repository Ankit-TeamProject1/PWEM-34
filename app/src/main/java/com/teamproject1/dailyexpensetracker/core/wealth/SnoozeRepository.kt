package com.teamproject1.dailyexpensetracker.core.wealth

import com.teamproject1.dailyexpensetracker.core.database.dao.SnoozedReminderDao
import com.teamproject1.dailyexpensetracker.core.database.entity.SnoozedReminderEntity
import javax.inject.Inject
import javax.inject.Singleton

private const val SNOOZE_DURATION_MS = 5 * 60 * 60 * 1000L // 5 hours, per explicit request

/**
 * Shared "Skip for Now" suppression used by every due-reminder popup
 * (APY, EPF, RD, Kametti, Mutual Fund SIP) — per explicit request,
 * skipping a reminder should suppress that SPECIFIC item for about 5
 * hours, not just for the current in-memory session. Without this, the
 * same popup could reappear seconds later just by navigating away from
 * and back to the Dashboard, since the underlying data never actually
 * changed.
 */
@Singleton
class SnoozeRepository @Inject constructor(
    private val snoozedReminderDao: SnoozedReminderDao
) {
    suspend fun isSnoozed(reminderType: String, accountId: Long): Boolean {
        val snoozedUntil = snoozedReminderDao.getSnoozedUntil(reminderType, accountId) ?: return false
        return System.currentTimeMillis() < snoozedUntil
    }

    suspend fun snooze(reminderType: String, accountId: Long) {
        val now = System.currentTimeMillis()
        snoozedReminderDao.deleteExpired(now) // light housekeeping, not a correctness requirement
        snoozedReminderDao.insert(SnoozedReminderEntity(reminderType = reminderType, accountId = accountId, snoozedUntil = now + SNOOZE_DURATION_MS))
    }
}
