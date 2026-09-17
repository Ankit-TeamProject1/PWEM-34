package com.teamproject1.dailyexpensetracker.core.wealth

import com.teamproject1.dailyexpensetracker.core.database.dao.KamettiDao
import com.teamproject1.dailyexpensetracker.core.database.entity.KamettiAccountEntity
import com.teamproject1.dailyexpensetracker.core.database.entity.KamettiEntryEntity
import com.teamproject1.dailyexpensetracker.core.session.SessionManager
import kotlinx.coroutines.flow.first
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

/** A Kametti account with a specific missing monthly deposit — dueDate is
 *  the EXACT scheduled month found missing, matching RD's fixed
 *  schedule-walking approach rather than an estimated day-count. */
data class DueKamettiInstallment(val account: KamettiAccountEntity, val suggestedAmount: Double, val dueDate: Long)

@Singleton
class KamettiContributionRepository @Inject constructor(
    private val kamettiDao: KamettiDao,
    private val snoozeRepository: SnoozeRepository,
    private val session: SessionManager
) {

    /** Walks the account's real monthly schedule from its recurring
     *  deposit date (falling back to start date), one calendar month at a
     *  time, and returns the earliest scheduled month that has already
     *  passed and has no recorded entry. Same approach as RD's fix —
     *  never estimates elapsed time from a day-count threshold. Stops
     *  once totalMonths worth of entries exist, since a chit fund ends
     *  once every member has had their turn. */
    suspend fun getDueInstallments(): List<DueKamettiInstallment> {
        val bookId = session.activeBookId.first() ?: return emptyList()
        val now = System.currentTimeMillis()

        val result = mutableListOf<DueKamettiInstallment>()
        for (account in kamettiDao.getActiveAccounts(bookId).first()) {
            if (snoozeRepository.isSnoozed(RecurringTransactionType.KAMETTI.name, account.id)) continue

            val entries = kamettiDao.getEntriesOnce(account.id)
            // The Won payout isn't a monthly deposit slot — only real
            // deposit entries count toward "is this month filled".
            val filledYearMonths = entries.filter { !it.isWon }.map { entry ->
                val cal = Calendar.getInstance().apply { timeInMillis = entry.entryDate }
                cal.get(Calendar.YEAR) * 12 + cal.get(Calendar.MONTH)
            }.toSet()

            val anchorDate = account.recurringDepositDate ?: account.startDate
            val scheduleCal = Calendar.getInstance().apply { timeInMillis = anchorDate }
            var monthsChecked = 0
            var found: Long? = null
            while (scheduleCal.timeInMillis <= now && monthsChecked < account.totalMonths) {
                val scheduledYearMonth = scheduleCal.get(Calendar.YEAR) * 12 + scheduleCal.get(Calendar.MONTH)
                if (scheduledYearMonth !in filledYearMonths) {
                    found = scheduleCal.timeInMillis
                    break
                }
                scheduleCal.add(Calendar.MONTH, 1)
                monthsChecked++
            }
            found?.let { result.add(DueKamettiInstallment(account, account.monthlyAmount, it)) }
        }
        return result
    }

    suspend fun confirmInstallment(due: DueKamettiInstallment, amount: Double) {
        kamettiDao.insertEntry(KamettiEntryEntity(kamettiAccountId = due.account.id, amount = amount, entryDate = due.dueDate))
    }
}
