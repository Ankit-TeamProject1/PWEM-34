package com.teamproject1.dailyexpensetracker.core.wealth

import com.teamproject1.dailyexpensetracker.core.database.dao.RdInstallmentDao
import com.teamproject1.dailyexpensetracker.core.database.dao.RecurringDepositDao
import com.teamproject1.dailyexpensetracker.core.database.entity.RdInstallmentEntity
import com.teamproject1.dailyexpensetracker.core.database.entity.RecurringDepositEntity
import com.teamproject1.dailyexpensetracker.core.session.SessionManager
import kotlinx.coroutines.flow.first
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

/** An RD account with a specific missing installment — dueDate is the
 *  EXACT scheduled month found missing, not an estimate, so confirming
 *  records the entry against the correct month rather than "last month
 *  relative to today". */
data class DueRdInstallment(val account: RecurringDepositEntity, val suggestedAmount: Double, val dueDate: Long)

@Singleton
class RdContributionRepository @Inject constructor(
    private val recurringDepositDao: RecurringDepositDao,
    private val rdInstallmentDao: RdInstallmentDao,
    private val snoozeRepository: SnoozeRepository,
    private val session: SessionManager
) {

    /**
     * Walks the account's real monthly schedule starting from its start
     * date, one calendar month at a time, and returns the EARLIEST
     * scheduled month that has both already passed and has no recorded
     * entry. This replaces an earlier version that estimated "months
     * elapsed" from a day-count threshold — that approach was the actual
     * bug behind reports of this reminder either not appearing when it
     * should, or requiring an unreasonable gap before re-appearing after
     * confirming. Walking the calendar directly removes the estimation
     * entirely: every scheduled month is checked individually against
     * what's actually been recorded, so confirming one due month makes
     * the very next visit correctly check for the NEXT one, continuing
     * until every elapsed month has a real entry.
     */
    suspend fun getDueInstallments(): List<DueRdInstallment> {
        val bookId = session.activeBookId.first() ?: return emptyList()
        val now = System.currentTimeMillis()

        val result = mutableListOf<DueRdInstallment>()
        for (account in recurringDepositDao.getActive(bookId).first()) {
            if (snoozeRepository.isSnoozed(RecurringTransactionType.RD.name, account.id)) continue

            val entries = rdInstallmentDao.getInstallmentsOnce(account.id)
            val filledYearMonths = entries.map { entry ->
                val cal = Calendar.getInstance().apply { timeInMillis = entry.installmentDate }
                cal.get(Calendar.YEAR) * 12 + cal.get(Calendar.MONTH)
            }.toSet()

            val scheduleCal = Calendar.getInstance().apply { timeInMillis = account.startDate }
            var monthsChecked = 0
            var found: Long? = null
            while (scheduleCal.timeInMillis <= now && monthsChecked < account.tenureMonths) {
                val scheduledYearMonth = scheduleCal.get(Calendar.YEAR) * 12 + scheduleCal.get(Calendar.MONTH)
                if (scheduledYearMonth !in filledYearMonths) {
                    found = scheduleCal.timeInMillis
                    break
                }
                scheduleCal.add(Calendar.MONTH, 1)
                monthsChecked++
            }
            found?.let { result.add(DueRdInstallment(account, account.monthlyInstallment, it)) }
        }
        return result
    }

    /** Confirms the EXACT missing month found above, not an estimated
     *  "last month from today" — this was the second half of the bug,
     *  since a mis-dated entry would itself throw off the next check. */
    suspend fun confirmInstallment(due: DueRdInstallment, amount: Double) {
        rdInstallmentDao.insert(RdInstallmentEntity(rdAccountId = due.account.id, amount = amount, installmentDate = due.dueDate))
    }
}
