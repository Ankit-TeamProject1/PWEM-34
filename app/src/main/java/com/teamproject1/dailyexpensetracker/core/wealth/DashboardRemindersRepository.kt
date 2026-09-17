package com.teamproject1.dailyexpensetracker.core.wealth

import javax.inject.Inject
import javax.inject.Singleton

/** One row for the Dashboard's embedded Pending/Upcoming reminders list —
 *  reuses RecurringTransactionItem's shape rather than inventing a
 *  parallel structure, just adding whether it's currently pending. */
data class DashboardReminderItem(val item: RecurringTransactionItem, val isPending: Boolean)

/**
 * Combines the five existing due-date repositories (APY, EPF, RD,
 * Kametti, Mutual Fund SIP) with the full Recurring Transactions list to
 * produce the Dashboard's "Pending Dues and Upcoming" section, per
 * explicit request. Deliberately reuses the existing due-checks rather
 * than building new next-due-date forecasting logic from scratch — an
 * item is "pending" if one of the five repositories already flags it as
 * due right now, and "upcoming" otherwise.
 */
@Singleton
class DashboardRemindersRepository @Inject constructor(
    private val recurringTransactionsRepository: RecurringTransactionsRepository,
    private val apyContributionRepository: ApyContributionRepository,
    private val epfContributionRepository: EpfContributionRepository,
    private val rdContributionRepository: RdContributionRepository,
    private val kamettiContributionRepository: KamettiContributionRepository,
    private val mutualFundSipRepository: MutualFundSipRepository
) {
    suspend fun getAll(): List<DashboardReminderItem> {
        val allItems = recurringTransactionsRepository.getAll()

        val pendingKeys = mutableSetOf<Pair<RecurringTransactionType, Long>>()
        apyContributionRepository.getDueContributions().forEach { pendingKeys.add(RecurringTransactionType.APY to it.account.id) }
        epfContributionRepository.getDueContributions().forEach { pendingKeys.add(it.tileType.let { t -> if (t == com.teamproject1.dailyexpensetracker.core.database.entity.EpfTileType.EMPLOYEE_EMPLOYER) RecurringTransactionType.EPF_EMPLOYEE_EMPLOYER else RecurringTransactionType.EPF_PENSION } to it.account.id) }
        rdContributionRepository.getDueInstallments().forEach { pendingKeys.add(RecurringTransactionType.RD to it.account.id) }
        kamettiContributionRepository.getDueInstallments().forEach { pendingKeys.add(RecurringTransactionType.KAMETTI to it.account.id) }
        mutualFundSipRepository.getDueSips().forEach { pendingKeys.add(RecurringTransactionType.MUTUAL_FUND_SIP to it.account.id) }

        return allItems
            .map { DashboardReminderItem(it, isPending = (it.type to it.accountId) in pendingKeys) }
            .sortedByDescending { it.isPending } // pending items first, then upcoming
    }
}
