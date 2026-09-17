package com.teamproject1.dailyexpensetracker.core.wealth

/**
 * Kametti (chit fund) balance — deliberately NOT interest-bearing, per
 * explicit request: "balance will be calculated as deposit add and won
 * subtract". A chit fund pools money and pays it out to one member per
 * month rather than earning interest, so this is a plain running sum of
 * signed entries (deposits positive, the one-time Won payout negative),
 * not a compounding calculation like RD/PPF/EPF.
 */
object KamettiCalculator {
    fun currentBalance(entries: List<Pair<Long, Double>>): Double = entries.sumOf { it.second }
}
