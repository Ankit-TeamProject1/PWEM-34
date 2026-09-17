package com.teamproject1.dailyexpensetracker.core.wealth

import com.teamproject1.dailyexpensetracker.core.database.dao.MutualFundDao
import com.teamproject1.dailyexpensetracker.core.database.entity.MutualFundAccountEntity
import com.teamproject1.dailyexpensetracker.core.database.entity.MutualFundEntryEntity
import com.teamproject1.dailyexpensetracker.core.database.entity.MutualFundSipEntity
import com.teamproject1.dailyexpensetracker.core.session.SessionManager
import kotlinx.coroutines.flow.first
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

data class DueMutualFundSip(val account: MutualFundAccountEntity, val sip: MutualFundSipEntity)

/**
 * Mutual Fund SIP due-date reminder — same Dashboard-level pattern as
 * APY/EPF/RD, per explicit request. Respects the SIP's hold state — a
 * held SIP is never flagged, matching the explicit "option to... hold
 * sip" request.
 */
@Singleton
class MutualFundSipRepository @Inject constructor(
    private val mutualFundDao: MutualFundDao,
    private val navFetcher: AmfiNavFetcher,
    private val snoozeRepository: SnoozeRepository,
    private val session: SessionManager
) {

    suspend fun getDueSips(): List<DueMutualFundSip> {
        val bookId = session.activeBookId.first() ?: return emptyList()
        val nowCal = Calendar.getInstance()
        val nowYearMonth = nowCal.get(Calendar.YEAR) * 12 + nowCal.get(Calendar.MONTH)

        val result = mutableListOf<DueMutualFundSip>()
        for (account in mutualFundDao.getActiveAccounts(bookId).first()) {
            val sip = mutualFundDao.getSipOnce(account.id) ?: continue
            if (sip.isHeld) continue
            if (snoozeRepository.isSnoozed(RecurringTransactionType.MUTUAL_FUND_SIP.name, account.id)) continue

            val baseDate = sip.lastConfirmedDate ?: sip.startDate
            val baseCal = Calendar.getInstance().apply { timeInMillis = baseDate }
            val baseYearMonth = baseCal.get(Calendar.YEAR) * 12 + baseCal.get(Calendar.MONTH)

            // Same calendar year-month comparison already fixed for
            // EPF/RD — avoids the exact-day-arithmetic bug that let a
            // reminder re-trigger within moments of confirming.
            if (nowYearMonth - baseYearMonth >= 1) result.add(DueMutualFundSip(account, sip))
        }
        return result
    }

    /** Confirms this month's SIP installment — fetches the fund's current
     *  NAV fresh (not the last cached value, since a SIP is invested at
     *  today's NAV) and creates a real entry from it. */
    suspend fun confirmSip(due: DueMutualFundSip): Boolean {
        val nav = navFetcher.getNav(due.account.schemeCode) ?: due.account.lastFetchedNav ?: return false
        val units = if (nav > 0) due.sip.sipAmount / nav else 0.0
        val now = System.currentTimeMillis()
        mutualFundDao.insertEntry(
            MutualFundEntryEntity(
                mutualFundAccountId = due.account.id, investedAmount = due.sip.sipAmount, navAtPurchase = nav,
                unitsAllotted = units, purchaseDate = now, isSip = true
            )
        )
        mutualFundDao.updateSip(due.sip.copy(lastConfirmedDate = now))
        mutualFundDao.updateFetchedNav(due.account.id, nav, now)
        return true
    }

    suspend fun holdSip(due: DueMutualFundSip) {
        mutualFundDao.updateSip(due.sip.copy(isHeld = true))
    }
}
