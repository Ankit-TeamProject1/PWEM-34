package com.teamproject1.dailyexpensetracker.core.wealth

import com.teamproject1.dailyexpensetracker.core.database.dao.ApyDao
import com.teamproject1.dailyexpensetracker.core.database.entity.ApyAccountEntity
import com.teamproject1.dailyexpensetracker.core.session.SessionManager
import kotlinx.coroutines.flow.first
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

/** An APY account whose next contribution is due — checked at Dashboard
 *  level, same pattern as FD/RD Auto-Renewal. */
data class DueApyContribution(val account: ApyAccountEntity, val dueDate: Long)

/**
 * APY contribution due-date reminder — confirmation-based, per the locked
 * design: nothing posts silently. Since APY deliberately has no entry
 * ledger (just a single running total, per explicit request), the due
 * date is tracked via lastContributionDate on the account itself rather
 * than derived from entry history.
 */
@Singleton
class ApyContributionRepository @Inject constructor(
    private val apyDao: ApyDao,
    private val snoozeRepository: SnoozeRepository,
    private val session: SessionManager
) {

    suspend fun getDueContributions(): List<DueApyContribution> {
        val bookId = session.activeBookId.first() ?: return emptyList()
        val now = System.currentTimeMillis()
        val result = mutableListOf<DueApyContribution>()
        for (account in apyDao.getActive(bookId).first()) {
            if (snoozeRepository.isSnoozed(RecurringTransactionType.APY.name, account.id)) continue
            val dueDate = nextDueDate(account)
            if (now >= dueDate) result.add(DueApyContribution(account, dueDate))
        }
        return result
    }

    private fun nextDueDate(account: ApyAccountEntity): Long {
        // Anchor priority: last confirm (most recent activity) > the
        // recurring deposit date the user actually confirmed (may differ
        // from startDate) > startDate as a final fallback for older
        // accounts that predate this field.
        val baseDate = account.lastContributionDate ?: account.recurringDepositDate ?: account.startDate
        return Calendar.getInstance().apply {
            timeInMillis = baseDate
            add(Calendar.MONTH, 1)
        }.timeInMillis
    }

    /** Confirms this month's contribution — adds to the running total and
     *  advances lastContributionDate to NOW, not the theoretical due date.
     *  This was a real bug: anchoring to the due date only advances the
     *  schedule by one month at a time, so an account that's several
     *  months behind would re-trigger the popup on every single Dashboard
     *  visit until you'd confirmed once per overdue month. Anchoring to
     *  "now" means confirming once genuinely catches you up. */
    suspend fun confirmContribution(due: DueApyContribution, amount: Double) {
        apyDao.update(
            due.account.copy(
                totalContributedSoFar = due.account.totalContributedSoFar + amount,
                lastContributionDate = System.currentTimeMillis()
            )
        )
    }
}
