package com.teamproject1.dailyexpensetracker.core.wealth

import com.teamproject1.dailyexpensetracker.core.database.entity.CompoundingFrequency
import com.teamproject1.dailyexpensetracker.core.database.entity.RecurringDepositEntity
import kotlin.math.pow

/**
 * RD maturity value — unlike FD's single lump-sum principal, an RD
 * accumulates via monthly installments, so each installment earns
 * compound interest for a different length of time (the first
 * installment earns interest for the full tenure, the last earns almost
 * none). This sums each installment's own compounded value rather than
 * using a single flat compounding pass, which is the standard approach
 * banks use for RD maturity calculations.
 *
 * Like the PPF/EPF calculator, this is a reasonable approximation, not a
 * bank-precise-to-the-rupee calculation — flagged here rather than
 * presented as exact.
 */
object RdCalculator {

    fun maturityValue(rd: RecurringDepositEntity): Double {
        val n = rd.compoundingsPerYear()
        val rate = rd.interestRate / 100.0
        var total = 0.0

        for (installmentIndex in 1..rd.tenureMonths) {
            val monthsRemaining = rd.tenureMonths - installmentIndex + 1
            val yearsRemaining = monthsRemaining / 12.0
            total += rd.monthlyInstallment * (1 + rate / n).pow(n * yearsRemaining)
        }
        return total
    }

    fun totalDeposited(rd: RecurringDepositEntity): Double = rd.monthlyInstallment * rd.tenureMonths

    fun maturityDate(rd: RecurringDepositEntity): Long {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = rd.startDate }
        cal.add(java.util.Calendar.MONTH, rd.tenureMonths)
        return cal.timeInMillis
    }

    /** Current accrued value as of now, for showing progress before maturity.
     *  DEPRECATED for accounts with real installment entries — kept only
     *  as a fallback for an account that has zero entries yet (a brand
     *  new RD created today, before its first logged installment). Prefer
     *  currentValueFromEntries() wherever installment history exists. */
    fun currentValue(rd: RecurringDepositEntity): Double {
        val now = System.currentTimeMillis()
        val elapsedMonths = ((now - rd.startDate) / (30.44 * 24 * 60 * 60 * 1000)).toInt().coerceIn(0, rd.tenureMonths)
        if (elapsedMonths <= 0) return 0.0
        if (elapsedMonths >= rd.tenureMonths) return maturityValue(rd)

        val n = rd.compoundingsPerYear()
        val rate = rd.interestRate / 100.0
        var total = 0.0
        for (installmentIndex in 1..elapsedMonths) {
            val monthsHeld = elapsedMonths - installmentIndex + 1
            val yearsHeld = monthsHeld / 12.0
            total += rd.monthlyInstallment * (1 + rate / n).pow(n * yearsHeld)
        }
        return total
    }

    /** Current value computed from REAL recorded installment entries, per
     *  the confirmed redesign — each entry compounds from its own actual
     *  date to now, rather than assuming every elapsed month was paid on
     *  schedule. A late payment correctly earns interest for a shorter
     *  real period; a missed month simply isn't included at all. */
    fun currentValueFromEntries(rd: RecurringDepositEntity, installments: List<com.teamproject1.dailyexpensetracker.core.database.entity.RdInstallmentEntity>): Double {
        if (installments.isEmpty()) return 0.0
        val now = System.currentTimeMillis()
        val n = rd.compoundingsPerYear()
        val rate = rd.interestRate / 100.0
        return installments.sumOf { installment ->
            val yearsHeld = ((now - installment.installmentDate) / (365.25 * 24 * 60 * 60 * 1000)).coerceAtLeast(0.0)
            installment.amount * (1 + rate / n).pow(n * yearsHeld)
        }
    }

    private fun RecurringDepositEntity.compoundingsPerYear(): Int = when (compoundingFrequency) {
        CompoundingFrequency.MONTHLY -> 12
        CompoundingFrequency.QUARTERLY -> 4
        CompoundingFrequency.ANNUALLY -> 1
        CompoundingFrequency.CUMULATIVE -> 4
    }
}
