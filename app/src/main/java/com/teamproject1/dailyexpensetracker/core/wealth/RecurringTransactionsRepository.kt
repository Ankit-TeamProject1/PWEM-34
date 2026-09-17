package com.teamproject1.dailyexpensetracker.core.wealth

import com.teamproject1.dailyexpensetracker.core.database.dao.ApyDao
import com.teamproject1.dailyexpensetracker.core.database.dao.EpfDao
import com.teamproject1.dailyexpensetracker.core.database.dao.KamettiDao
import com.teamproject1.dailyexpensetracker.core.database.dao.MutualFundDao
import com.teamproject1.dailyexpensetracker.core.database.dao.RdInstallmentDao
import com.teamproject1.dailyexpensetracker.core.database.dao.RecurringDepositDao
import com.teamproject1.dailyexpensetracker.core.database.entity.EpfTileType
import com.teamproject1.dailyexpensetracker.core.session.SessionManager
import kotlinx.coroutines.flow.first
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

enum class RecurringTransactionType { RD, EPF_EMPLOYEE_EMPLOYER, EPF_PENSION, APY, NPS, MUTUAL_FUND_SIP, KAMETTI }

/** One row in the consolidated Recurring Transactions view. accountId is
 *  the underlying account's own ID, used to navigate to that item's real
 *  detail/edit screen — this aggregator deliberately doesn't duplicate
 *  edit/step-up/hold actions inline; tapping a row takes you to the
 *  screen that already correctly supports whatever's applicable for that
 *  type, per explicit request ("as per their ability"). scheduleAnchorDate
 *  is the actual NEXT scheduled/expected date, not the account's original
 *  start date — the earlier version showed the start date unconditionally,
 *  which never changed even after months of entries had been filled. */
data class RecurringTransactionItem(
    val type: RecurringTransactionType,
    val accountId: Long,
    val name: String,
    val amount: Double,
    val scheduleAnchorDate: Long?,
    val isHeld: Boolean = false
)

@Singleton
class RecurringTransactionsRepository @Inject constructor(
    private val recurringDepositDao: RecurringDepositDao,
    private val rdInstallmentDao: RdInstallmentDao,
    private val epfDao: EpfDao,
    private val apyDao: ApyDao,
    private val npsDao: com.teamproject1.dailyexpensetracker.core.database.dao.NpsDao,
    private val mutualFundDao: MutualFundDao,
    private val kamettiDao: KamettiDao,
    private val session: SessionManager
) {
    /** Walks a monthly schedule from anchorDate and returns the earliest
     *  month with no matching entry — whether that month has already
     *  passed (overdue) or hasn't arrived yet (upcoming). This is the
     *  actual "next" scheduled date, unlike the due-repositories' own
     *  versions of this walk, which stop at "already passed" only. */
    private fun nextScheduledDate(anchorDate: Long, filledYearMonths: Set<Int>, maxMonths: Int): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = anchorDate }
        var monthsChecked = 0
        while (monthsChecked < maxMonths) {
            val ym = cal.get(Calendar.YEAR) * 12 + cal.get(Calendar.MONTH)
            if (ym !in filledYearMonths) return cal.timeInMillis
            cal.add(Calendar.MONTH, 1)
            monthsChecked++
        }
        return cal.timeInMillis
    }

    /** EPF-specific version of the walk above — kept separate rather than
     *  modifying the generic helper (used by RD/APY/Kametti/MF SIP too),
     *  since EPF alone needs two extra nuances: the payroll lag (a
     *  month's contribution isn't posted until the FOLLOWING month) and
     *  the exact recurring day within that posting month. Mirrors
     *  EpfContributionRepository's own algorithm exactly, so this
     *  screen's "next due" date can never disagree with what the actual
     *  due-reminder popup shows. */
    private fun nextEpfScheduledDate(accountOpenDate: Long, recurringDepositDate: Long?, filledYearMonths: Set<Int>, maxMonths: Int): Long {
        val recurringDay = Calendar.getInstance().apply { timeInMillis = recurringDepositDate ?: accountOpenDate }.get(Calendar.DAY_OF_MONTH)
        val scheduleCal = Calendar.getInstance().apply { timeInMillis = accountOpenDate }
        var monthsChecked = 0
        while (monthsChecked < maxMonths) {
            val scheduledYearMonth = scheduleCal.get(Calendar.YEAR) * 12 + scheduleCal.get(Calendar.MONTH)
            if (scheduledYearMonth !in filledYearMonths) {
                // Found the earliest unfilled month — its actual due date
                // is the recurring day in the FOLLOWING month (the
                // posting month), not the scheduled month itself.
                return (scheduleCal.clone() as Calendar).apply {
                    add(Calendar.MONTH, 1)
                    set(Calendar.DAY_OF_MONTH, minOf(recurringDay, getActualMaximum(Calendar.DAY_OF_MONTH)))
                }.timeInMillis
            }
            scheduleCal.add(Calendar.MONTH, 1)
            monthsChecked++
        }
        return scheduleCal.timeInMillis
    }

    suspend fun getAll(): List<RecurringTransactionItem> {
        val bookId = session.activeBookId.first() ?: return emptyList()
        val items = mutableListOf<RecurringTransactionItem>()

        recurringDepositDao.getActive(bookId).first().forEach { rd ->
            val filled = rdInstallmentDao.getInstallmentsOnce(rd.id).map { entry ->
                val cal = Calendar.getInstance().apply { timeInMillis = entry.installmentDate }
                cal.get(Calendar.YEAR) * 12 + cal.get(Calendar.MONTH)
            }.toSet()
            val nextDate = nextScheduledDate(rd.startDate, filled, rd.tenureMonths)
            items.add(RecurringTransactionItem(RecurringTransactionType.RD, rd.id, rd.name, rd.monthlyInstallment, nextDate))
        }

        epfDao.getActiveAccounts(bookId).first().forEach { epf ->
            val allContributions = epfDao.getContributionsOnce(epf.id)
            // Same amount + isTransfer filtering as EpfContributionRepository's
            // due-check, per explicit request — a month only counts as
            // filled if it has a real, regular entry matching the fixed
            // amount. Kept consistent so this screen's "next due" date
            // never disagrees with what the actual due-reminder popup
            // would show.
            epf.monthlyEeContribution?.let { amount ->
                if (amount > 0) {
                    val filled = allContributions.filter { it.tileType == EpfTileType.EMPLOYEE_EMPLOYER && !it.isTransfer && Math.abs(it.amount - amount) < 0.01 }.map { entry ->
                        val cal = Calendar.getInstance().apply { timeInMillis = entry.contributionDate }
                        cal.get(Calendar.YEAR) * 12 + cal.get(Calendar.MONTH)
                    }.toSet()
                    val nextDate = nextEpfScheduledDate(epf.accountOpenDate, epf.recurringDepositDate, filled, 600)
                    items.add(RecurringTransactionItem(RecurringTransactionType.EPF_EMPLOYEE_EMPLOYER, epf.id, "${epf.name} — Employee+Employer", amount, nextDate))
                }
            }
            epf.monthlyPensionContribution?.let { amount ->
                if (amount > 0) {
                    val filled = allContributions.filter { it.tileType == EpfTileType.PENSION && !it.isTransfer && Math.abs(it.amount - amount) < 0.01 }.map { entry ->
                        val cal = Calendar.getInstance().apply { timeInMillis = entry.contributionDate }
                        cal.get(Calendar.YEAR) * 12 + cal.get(Calendar.MONTH)
                    }.toSet()
                    val nextDate = nextEpfScheduledDate(epf.accountOpenDate, epf.recurringDepositDate, filled, 600)
                    items.add(RecurringTransactionItem(RecurringTransactionType.EPF_PENSION, epf.id, "${epf.name} — Pension", amount, nextDate))
                }
            }
        }

        apyDao.getActive(bookId).first().forEach { apy ->
            // APY has no entry ledger — nextDueDate already IS "the next
            // scheduled date", same formula ApyContributionRepository uses.
            val baseDate = apy.lastContributionDate ?: apy.recurringDepositDate ?: apy.startDate
            val nextDate = Calendar.getInstance().apply { timeInMillis = baseDate; add(Calendar.MONTH, 1) }.timeInMillis
            items.add(RecurringTransactionItem(RecurringTransactionType.APY, apy.id, apy.name, apy.monthlyContribution, nextDate))
        }

        npsDao.getActive(bookId).first().forEach { nps ->
            // Same no-entry-ledger pattern as APY — nextDueDate already IS
            // "the next scheduled date", matching NpsContributionRepository.
            val monthlyContribution = nps.monthlyContribution
            if (monthlyContribution != null && monthlyContribution > 0) {
                val baseDate = nps.lastContributionDate ?: nps.recurringDepositDate ?: nps.lastUpdatedAt
                val nextDate = Calendar.getInstance().apply { timeInMillis = baseDate; add(Calendar.MONTH, 1) }.timeInMillis
                items.add(RecurringTransactionItem(RecurringTransactionType.NPS, nps.id, nps.name, monthlyContribution, nextDate))
            }
        }

        mutualFundDao.getActiveAccounts(bookId).first().forEach { fund ->
            val sip = mutualFundDao.getSipOnce(fund.id)
            if (sip != null) {
                val baseDate = sip.lastConfirmedDate ?: sip.startDate
                val nextDate = Calendar.getInstance().apply { timeInMillis = baseDate; add(Calendar.MONTH, 1) }.timeInMillis
                items.add(RecurringTransactionItem(RecurringTransactionType.MUTUAL_FUND_SIP, fund.id, fund.schemeName, sip.sipAmount, nextDate, isHeld = sip.isHeld))
            }
        }

        kamettiDao.getActiveAccounts(bookId).first().forEach { kametti ->
            val filled = kamettiDao.getEntriesOnce(kametti.id).filter { !it.isWon }.map { entry ->
                val cal = Calendar.getInstance().apply { timeInMillis = entry.entryDate }
                cal.get(Calendar.YEAR) * 12 + cal.get(Calendar.MONTH)
            }.toSet()
            val anchor = kametti.recurringDepositDate ?: kametti.startDate
            val nextDate = nextScheduledDate(anchor, filled, kametti.totalMonths)
            items.add(RecurringTransactionItem(RecurringTransactionType.KAMETTI, kametti.id, kametti.name, kametti.monthlyAmount, nextDate))
        }

        return items
    }
}
