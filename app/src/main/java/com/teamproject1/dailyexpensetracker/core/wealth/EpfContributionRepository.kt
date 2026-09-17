package com.teamproject1.dailyexpensetracker.core.wealth

import com.teamproject1.dailyexpensetracker.core.database.dao.EpfDao
import com.teamproject1.dailyexpensetracker.core.database.entity.EpfAccountEntity
import com.teamproject1.dailyexpensetracker.core.database.entity.EpfContributionEntity
import com.teamproject1.dailyexpensetracker.core.database.entity.EpfTileType
import com.teamproject1.dailyexpensetracker.core.session.SessionManager
import kotlinx.coroutines.flow.first
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

/** An EPF account with a specific missing contribution in ONE tile —
 *  dueDate is the EXACT scheduled month found missing, not an estimate.
 *  Both Employee+Employer and Pension are checked separately, per
 *  explicit request, since each can have its own fixed monthly amount
 *  and its own gaps. */
data class DueEpfContribution(val account: EpfAccountEntity, val tileType: EpfTileType, val suggestedAmount: Double, val dueDate: Long)

@Singleton
class EpfContributionRepository @Inject constructor(
    private val epfDao: EpfDao,
    private val snoozeRepository: SnoozeRepository,
    private val session: SessionManager
) {

    /**
     * Walks each account's real monthly schedule from its open date, one
     * calendar month at a time, for BOTH tiles separately, and returns
     * the earliest scheduled month in each tile that has already passed
     * and has no recorded entry.
     *
     * Accounts for the real EPF payroll lag, per explicit clarification:
     * a given month's contribution is deposited with the FOLLOWING
     * month's salary cycle — August's contribution is processed in
     * September, September's in October, and so on. So if it's currently
     * September, the check looks for August's contribution, not
     * September's — September's isn't expected to exist yet at all,
     * since it won't be processed until October. Without this, the
     * reminder would flag the current month as overdue before it could
     * possibly have been paid.
     */
    suspend fun getDueContributions(): List<DueEpfContribution> {
        val bookId = session.activeBookId.first() ?: return emptyList()
        val nowCal = Calendar.getInstance()
        val nowYearMonth = nowCal.get(Calendar.YEAR) * 12 + nowCal.get(Calendar.MONTH)

        val result = mutableListOf<DueEpfContribution>()
        for (account in epfDao.getActiveAccounts(bookId).first()) {
            val allEntries = epfDao.getContributionsOnce(account.id)

            val tilesToCheck = listOfNotNull(
                account.monthlyEeContribution?.let { EpfTileType.EMPLOYEE_EMPLOYER to it },
                account.monthlyPensionContribution?.let { EpfTileType.PENSION to it }
            )

            for ((tileType, fixedAmount) in tilesToCheck) {
                if (fixedAmount <= 0) continue
                val reminderType = if (tileType == EpfTileType.EMPLOYEE_EMPLOYER) RecurringTransactionType.EPF_EMPLOYEE_EMPLOYER.name else RecurringTransactionType.EPF_PENSION.name
                if (snoozeRepository.isSnoozed(reminderType, account.id)) continue

                // A month only counts as "filled" if there's a real,
                // regular contribution entry matching the fixed amount —
                // per explicit request. Previously this only checked
                // whether ANY entry existed for that month, so a
                // mistyped or partial amount would incorrectly satisfy
                // the check and the popup would move on to the next
                // month instead of flagging the real gap. isTransfer
                // entries (a one-time balance transfer from a closed
                // account) are also excluded, since they aren't a
                // regular monthly contribution — same principle as
                // Kametti excluding its "Won" payout from filled months.
                // A small tolerance avoids floating-point precision
                // issues on the amount comparison.
                val filledYearMonths = allEntries.filter { it.tileType == tileType && !it.isTransfer && Math.abs(it.amount - fixedAmount) < 0.01 }.map { entry ->
                    val cal = Calendar.getInstance().apply { timeInMillis = entry.contributionDate }
                    cal.get(Calendar.YEAR) * 12 + cal.get(Calendar.MONTH)
                }.toSet()

                // The day-of-month payroll actually posts on — per
                // explicit request, the popup waits for this exact day
                // the following month, not just "a new month has begun".
                // Falls back to accountOpenDate's own day for accounts
                // that predate this field.
                val recurringDay = Calendar.getInstance().apply { timeInMillis = account.recurringDepositDate ?: account.accountOpenDate }.get(Calendar.DAY_OF_MONTH)

                val scheduleCal = Calendar.getInstance().apply { timeInMillis = account.accountOpenDate }
                var monthsChecked = 0
                while (monthsChecked < 600) {
                    val scheduledYearMonth = scheduleCal.get(Calendar.YEAR) * 12 + scheduleCal.get(Calendar.MONTH)
                    // The payroll lag: a month's contribution isn't
                    // expected to exist until we're in the FOLLOWING
                    // month or later — so the current month itself (and
                    // anything later) is never flagged, only strictly
                    // past months.
                    if (scheduledYearMonth >= nowYearMonth) break
                    // On top of the month-level lag, wait for the exact
                    // recurring day within the posting month — flagging
                    // a gap the instant a new month begins would nag
                    // before payroll could plausibly have run yet.
                    val postingCal = (scheduleCal.clone() as Calendar).apply {
                        add(Calendar.MONTH, 1)
                        set(Calendar.DAY_OF_MONTH, minOf(recurringDay, getActualMaximum(Calendar.DAY_OF_MONTH)))
                    }
                    if (nowCal.timeInMillis < postingCal.timeInMillis) break
                    if (scheduledYearMonth !in filledYearMonths) {
                        result.add(DueEpfContribution(account, tileType, fixedAmount, scheduleCal.timeInMillis))
                        break
                    }
                    scheduleCal.add(Calendar.MONTH, 1)
                    monthsChecked++
                }
            }
        }
        return result
    }

    /** Confirms the EXACT missing month found above, not an estimated
     *  "last month from today". */
    suspend fun confirmContribution(due: DueEpfContribution, amount: Double) {
        epfDao.insertContribution(
            EpfContributionEntity(epfAccountId = due.account.id, amount = amount, contributionDate = due.dueDate, tileType = due.tileType)
        )
    }
}
