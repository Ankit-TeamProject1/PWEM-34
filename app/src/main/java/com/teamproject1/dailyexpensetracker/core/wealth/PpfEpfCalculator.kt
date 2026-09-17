package com.teamproject1.dailyexpensetracker.core.wealth

import com.teamproject1.dailyexpensetracker.core.database.entity.WealthRateHistoryEntity
import java.util.Calendar

/**
 * Computes current value for PPF/EPF given a deposit history and a rate
 * history — applies the correct historical rate for each period rather
 * than one flat rate, per the locked design (these rates genuinely change
 * over time, unlike a fixed-tenure FD where the rate is locked at
 * inception).
 *
 * Implements the REAL PPF/EPF interest rule, per explicit request (this
 * replaces an earlier, simpler annual-compounding approximation):
 * - Interest is calculated MONTHLY, based on the balance as of the 5th of
 *   that month. A deposit made ON OR BEFORE the 5th counts toward that
 *   month's interest; a deposit made AFTER the 5th only starts earning
 *   interest from the FOLLOWING month.
 * - Interest is only CREDITED once a year, at financial-year end (March
 *   31) — the sum of the 12 months' individually-calculated interest —
 *   at which point it becomes principal for the next financial year.
 * - A financial year's carried-in opening balance (from the previous
 *   year's credited interest) is available from April 1 itself — it is
 *   NOT subject to the 5th-of-month cutoff, since it isn't a new deposit.
 */
object PpfEpfCalculator {

    fun currentValue(
        deposits: List<Pair<Long, Double>>, // (depositDate, amount) — positive for deposits, negative for deductions; INTEREST-type entries must already be excluded by the caller
        rateHistory: List<WealthRateHistoryEntity>,
        asOf: Long = System.currentTimeMillis()
    ): Double {
        if (deposits.isEmpty()) return 0.0
        val sortedRates = rateHistory.sortedBy { it.effectiveFrom }
        if (sortedRates.isEmpty()) {
            // No rate history configured yet — return principal only rather
            // than guessing a rate, so the number shown is never silently wrong.
            return deposits.sumOf { it.second }
        }

        val sortedDeposits = deposits.sortedBy { it.first }
        val earliestDeposit = sortedDeposits.first().first

        // Find the financial year (April 1 - March 31) containing the
        // earliest deposit, as the starting point.
        var fyStartCal = Calendar.getInstance().apply {
            timeInMillis = earliestDeposit
            val month = get(Calendar.MONTH)
            val year = get(Calendar.YEAR)
            val fyStartYear = if (month >= Calendar.APRIL) year else year - 1
            set(fyStartYear, Calendar.APRIL, 1, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }

        var openingBalance = 0.0

        while (fyStartCal.timeInMillis <= asOf) {
            val fyStart = fyStartCal.timeInMillis
            val fyEndCal = (fyStartCal.clone() as Calendar).apply { add(Calendar.YEAR, 1) }
            val fyEnd = fyEndCal.timeInMillis // exclusive — April 1 of the following year

            val rateForYear = sortedRates.lastOrNull { it.effectiveFrom <= fyStart }?.ratePercent
                ?: sortedRates.first().ratePercent
            val monthlyRate = rateForYear / 100.0 / 12.0

            var yearInterest = 0.0
            val monthCal = fyStartCal.clone() as Calendar
            var monthsProcessed = 0
            while (monthsProcessed < 12) {
                val fifthOfMonth = (monthCal.clone() as Calendar).apply {
                    set(Calendar.DAY_OF_MONTH, 5); set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59)
                }.timeInMillis
                // This month's 5th hasn't happened yet as of the check
                // date — this month's interest isn't determined yet, and
                // neither are any later months in this FY (chronological).
                if (fifthOfMonth > asOf) break

                // Balance for THIS month's interest = carried-in opening
                // balance (available from day 1 of the FY) + all deposits
                // made from FY-start up to and including the 5th of this
                // specific month.
                val cumulativeDeposits = sortedDeposits.filter { it.first in fyStart..fifthOfMonth }.sumOf { it.second }
                val monthBalance = openingBalance + cumulativeDeposits
                yearInterest += monthBalance * monthlyRate

                monthCal.add(Calendar.MONTH, 1)
                monthsProcessed++
            }

            val periodEnd = minOf(fyEnd, asOf + 1) // +1 so a deposit dated exactly on asOf is included
            val depositsThisYear = sortedDeposits.filter { it.first in fyStart until periodEnd }.sumOf { it.second }

            openingBalance += depositsThisYear + yearInterest
            fyStartCal = fyEndCal
        }

        return openingBalance
    }

    fun totalDeposited(deposits: List<Pair<Long, Double>>): Double = deposits.sumOf { it.second }
}
