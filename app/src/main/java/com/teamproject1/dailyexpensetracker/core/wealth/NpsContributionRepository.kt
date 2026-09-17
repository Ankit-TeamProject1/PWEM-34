package com.teamproject1.dailyexpensetracker.core.wealth

import com.teamproject1.dailyexpensetracker.core.database.dao.NpsDao
import com.teamproject1.dailyexpensetracker.core.database.entity.NpsAccountEntity
import com.teamproject1.dailyexpensetracker.core.session.SessionManager
import kotlinx.coroutines.flow.first
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

/** An NPS account whose next contribution is due — checked at Dashboard
 *  level, same pattern as APY. */
data class DueNpsContribution(val account: NpsAccountEntity, val dueDate: Long)

/**
 * NPS contribution due-date reminder — confirmation-based, per the locked
 * design: nothing posts silently. Mirrors ApyContributionRepository's
 * exact design, since NPS is structurally identical: a single running
 * total (currentValue) with no entry ledger, so the due date is tracked
 * via lastContributionDate on the account itself rather than derived
 * from entry history.
 */
@Singleton
class NpsContributionRepository @Inject constructor(
    private val npsDao: NpsDao,
    private val snoozeRepository: SnoozeRepository,
    private val session: SessionManager
) {

    suspend fun getDueContributions(): List<DueNpsContribution> {
        val bookId = session.activeBookId.first() ?: return emptyList()
        val now = System.currentTimeMillis()
        val result = mutableListOf<DueNpsContribution>()
        for (account in npsDao.getActive(bookId).first()) {
            if (account.monthlyContribution == null || account.monthlyContribution <= 0) continue
            if (snoozeRepository.isSnoozed(RecurringTransactionType.NPS.name, account.id)) continue
            val dueDate = nextDueDate(account)
            if (now >= dueDate) result.add(DueNpsContribution(account, dueDate))
        }
        return result
    }

    private fun nextDueDate(account: NpsAccountEntity): Long {
        // Anchor priority: last confirm (most recent activity) > the
        // recurring deposit date the user actually confirmed > lastUpdatedAt
        // as a final fallback for accounts predating this field.
        val baseDate = account.lastContributionDate ?: account.recurringDepositDate ?: account.lastUpdatedAt
        return Calendar.getInstance().apply {
            timeInMillis = baseDate
            add(Calendar.MONTH, 1)
        }.timeInMillis
    }

    /** Confirms this month's contribution — adds to currentValue and
     *  advances lastContributionDate to NOW, not the theoretical due
     *  date, matching APY's exact anchor-to-now approach so an account
     *  several months behind gets fully caught up by a single
     *  confirmation rather than re-triggering once per overdue month. */
    suspend fun confirmContribution(due: DueNpsContribution, amount: Double) {
        npsDao.update(
            due.account.copy(
                currentValue = due.account.currentValue + amount,
                lastContributionDate = System.currentTimeMillis(),
                lastUpdatedAt = System.currentTimeMillis()
            )
        )
    }
}
