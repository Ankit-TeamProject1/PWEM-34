package com.teamproject1.dailyexpensetracker.core.wealth

import com.teamproject1.dailyexpensetracker.core.database.entity.CompoundingFrequency
import com.teamproject1.dailyexpensetracker.core.database.entity.FixedDepositEntity
import com.teamproject1.dailyexpensetracker.core.database.entity.InterestType
import java.util.Calendar
import kotlin.math.pow

/**
 * Pure math, no internet dependency — the reason FD was picked as the
 * first Wealth category to build. Given principal, rate, tenure, and
 * compounding frequency, computes maturity date and value.
 */
object FdCalculator {

    fun maturityDate(fd: FixedDepositEntity): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = fd.startDate }
        cal.add(Calendar.MONTH, fd.tenureMonths)
        cal.add(Calendar.DAY_OF_MONTH, fd.tenureExtraDays)
        return cal.timeInMillis
    }

    fun maturityValue(fd: FixedDepositEntity): Double {
        // Extra days folded into the year fraction (not just the maturity
        // date) so the interest calculation itself reflects a Year+Month+Day
        // tenure precisely, not just where the maturity date lands.
        val years = (fd.tenureMonths + fd.tenureExtraDays / 30.44) / 12.0
        val rate = fd.interestRate / 100.0

        return if (fd.interestType == InterestType.SIMPLE) {
            fd.principalAmount * (1 + rate * years)
        } else {
            val n = when (fd.compoundingFrequency) {
                CompoundingFrequency.MONTHLY -> 12
                CompoundingFrequency.QUARTERLY -> 4
                CompoundingFrequency.ANNUALLY -> 1
                CompoundingFrequency.CUMULATIVE -> 4 // banks commonly compound cumulative FDs quarterly
            }
            fd.principalAmount * (1 + rate / n).pow(n * years)
        }
    }

    /** Current accrued value as of now (not full maturity) — useful for a
     *  "current value" display on the Wealth Dashboard before maturity. */
    fun currentValue(fd: FixedDepositEntity): Double {
        val now = System.currentTimeMillis()
        val maturity = maturityDate(fd)
        if (now >= maturity) return maturityValue(fd)
        if (now <= fd.startDate) return fd.principalAmount

        val elapsedYears = (now - fd.startDate).toDouble() / (365.25 * 24 * 60 * 60 * 1000)
        val rate = fd.interestRate / 100.0

        return if (fd.interestType == InterestType.SIMPLE) {
            fd.principalAmount * (1 + rate * elapsedYears)
        } else {
            val n = when (fd.compoundingFrequency) {
                CompoundingFrequency.MONTHLY -> 12
                CompoundingFrequency.QUARTERLY -> 4
                CompoundingFrequency.ANNUALLY -> 1
                CompoundingFrequency.CUMULATIVE -> 4
            }
            fd.principalAmount * (1 + rate / n).pow(n * elapsedYears)
        }
    }
}
