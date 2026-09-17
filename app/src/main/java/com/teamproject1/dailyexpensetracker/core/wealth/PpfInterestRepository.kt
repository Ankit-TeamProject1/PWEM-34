package com.teamproject1.dailyexpensetracker.core.wealth

import com.teamproject1.dailyexpensetracker.core.database.dao.PpfDao
import com.teamproject1.dailyexpensetracker.core.database.dao.WealthRateHistoryDao
import com.teamproject1.dailyexpensetracker.core.database.entity.PpfAccountEntity
import com.teamproject1.dailyexpensetracker.core.database.entity.PpfDepositEntity
import com.teamproject1.dailyexpensetracker.core.database.entity.PpfEntryType
import com.teamproject1.dailyexpensetracker.core.database.entity.RateInstrument
import com.teamproject1.dailyexpensetracker.core.session.SessionManager
import kotlinx.coroutines.flow.first
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

/** A PPF account with unrecorded interest for its most recently completed
 *  financial year. entryDate is always April 1 of the CURRENT financial
 *  year (the one right after the year being confirmed) — a fixed,
 *  predictable convention per explicit request, rather than "whenever
 *  the user happened to click confirm". */
data class DuePpfInterest(val account: PpfAccountEntity, val suggestedAmount: Double, val fyLabel: String, val entryDate: Long)

@Singleton
class PpfInterestRepository @Inject constructor(
    private val ppfDao: PpfDao,
    private val rateHistoryDao: WealthRateHistoryDao,
    private val snoozeRepository: SnoozeRepository,
    private val session: SessionManager
) {
    suspend fun getDueInterestConfirmations(): List<DuePpfInterest> {
        val bookId = session.activeBookId.first() ?: return emptyList()
        val rates = rateHistoryDao.getHistoryOnce(RateInstrument.PPF)

        val result = mutableListOf<DuePpfInterest>()
        for (account in ppfDao.getActiveAccounts(bookId).first()) {
            if (snoozeRepository.isSnoozed("PPF_INTEREST", account.id)) continue

            val allEntries = ppfDao.getDepositsOnce(account.id)
            val deposits = allEntries.filter { it.type != PpfEntryType.INTEREST }.map { it.depositDate to it.amount }
            if (deposits.isEmpty()) continue

            // Indian FY runs April 1 - March 31. fyStart is the CURRENT
            // financial year's start — this is also the FIXED date used
            // for the confirmed entry, per explicit request ("interest
            // date will always be 1 April of next FY"). The bug this
            // replaces: dating the entry to "today" (System.
            // currentTimeMillis()) meant the date was always on or after
            // fyStart, which is always outside the completed-FY window
            // being checked — so the popup could never recognize its own
            // confirmation and kept reappearing every time, regardless of
            // when it was confirmed.
            val cal = Calendar.getInstance()
            val currentMonth = cal.get(Calendar.MONTH)
            val fyStartYear = if (currentMonth >= Calendar.APRIL) cal.get(Calendar.YEAR) else cal.get(Calendar.YEAR) - 1
            val fyStart = Calendar.getInstance().apply {
                set(fyStartYear, Calendar.APRIL, 1, 0, 0, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis

            val lastCompletedFyStart = Calendar.getInstance().apply {
                set(fyStartYear - 1, Calendar.APRIL, 1, 0, 0, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            val lastCompletedFyEnd = fyStart - 1

            // Classifies each interest entry by which ONGOING FY period
            // its date falls into — the same month>=April rule used for
            // fyStartYear itself — and checks whether that matches this
            // account's current target year. This is simpler and more
            // robust than a raw millisecond-range comparison: it
            // correctly recognizes an entry dated exactly to fyStart
            // (the current convention), an entry dated any number of
            // days or months later within that same ongoing FY (how the
            // very first, pre-fix version of this popup dated entries —
            // to "today", whenever the user happened to confirm), and a
            // manually-added entry dated to the real PPF credit date of
            // March 31 (which correctly buckets into the PRIOR ongoing
            // FY, not this one) — without ever double-counting the same
            // entry across two adjacent years' checks, since every date
            // belongs to exactly one such bucket by construction.
            val alreadyRecorded = allEntries.any { entry ->
                if (entry.type != PpfEntryType.INTEREST) return@any false
                val entryCal = Calendar.getInstance().apply { timeInMillis = entry.depositDate }
                val entryMonth = entryCal.get(Calendar.MONTH)
                val entryFyStartYear = if (entryMonth >= Calendar.APRIL) entryCal.get(Calendar.YEAR) else entryCal.get(Calendar.YEAR) - 1
                entryFyStartYear == fyStartYear
            }
            if (alreadyRecorded) continue

            val valueAtStart = PpfEpfCalculator.currentValue(deposits, rates, asOf = lastCompletedFyStart)
            val valueAtEnd = PpfEpfCalculator.currentValue(deposits, rates, asOf = lastCompletedFyEnd)
            val depositsDuringYear = deposits.filter { it.first in lastCompletedFyStart..lastCompletedFyEnd }.sumOf { it.second }
            val interestForYear = (valueAtEnd - valueAtStart - depositsDuringYear).coerceAtLeast(0.0)

            if (interestForYear > 0) {
                val fyLabel = "FY %d-%02d".format(fyStartYear - 1, (fyStartYear) % 100)
                result.add(DuePpfInterest(account, interestForYear, fyLabel, fyStart))
            }
        }
        return result
    }

    suspend fun confirmInterest(due: DuePpfInterest, amount: Double) {
        ppfDao.insertDeposit(
            PpfDepositEntity(ppfAccountId = due.account.id, amount = amount, depositDate = due.entryDate, type = PpfEntryType.INTEREST, note = "Interest credited")
        )
    }
}
