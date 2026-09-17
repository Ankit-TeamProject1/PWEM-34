package com.teamproject1.dailyexpensetracker.core.wealth

import com.teamproject1.dailyexpensetracker.core.database.dao.FixedDepositDao
import com.teamproject1.dailyexpensetracker.core.database.dao.RecurringDepositDao
import com.teamproject1.dailyexpensetracker.core.database.entity.AutoRenewMode
import com.teamproject1.dailyexpensetracker.core.database.entity.FixedDepositEntity
import com.teamproject1.dailyexpensetracker.core.database.entity.RecurringDepositEntity
import com.teamproject1.dailyexpensetracker.core.session.SessionManager
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** A matured FD or RD awaiting renewal confirmation — union type so a
 *  single popup list can hold both kinds. */
sealed class MaturedDeposit {
    abstract val id: Long
    abstract val name: String
    abstract val maturityValue: Double
    abstract val principal: Double

    data class Fd(val entity: FixedDepositEntity, override val maturityValue: Double) : MaturedDeposit() {
        override val id get() = entity.id
        override val name get() = entity.name
        override val principal get() = entity.principalAmount
    }
    data class Rd(val entity: RecurringDepositEntity, override val maturityValue: Double) : MaturedDeposit() {
        override val id get() = entity.id
        override val name get() = entity.name
        override val principal get() = entity.monthlyInstallment * entity.tenureMonths
    }
}

/**
 * FD/RD auto-renewal — confirmation-based, per the locked design: nothing
 * renews silently. This scans for matured, auto-renew-enabled deposits;
 * the caller (a popup, same UX pattern as the Recurring due-reminder)
 * shows each one with editable proposed terms before confirming.
 *
 * Once a deposit is renewed (or the user declines), the OLD entry is
 * archived and its autoRenewMode set to DISABLED — this is what prevents
 * it from being picked up again on the next scan, rather than needing a
 * separate "already handled" flag.
 */
@Singleton
class FdRdAutoRenewalRepository @Inject constructor(
    private val fixedDepositDao: FixedDepositDao,
    private val recurringDepositDao: RecurringDepositDao,
    private val session: SessionManager
) {

    suspend fun getMaturedDeposits(): List<MaturedDeposit> {
        val bookId = session.activeBookId.first() ?: return emptyList()
        val now = System.currentTimeMillis()

        val maturedFds = fixedDepositDao.getActive(bookId).first()
            .filter { it.autoRenewMode != AutoRenewMode.DISABLED && FdCalculator.maturityDate(it) <= now }
            .map { MaturedDeposit.Fd(it, FdCalculator.maturityValue(it)) }

        val maturedRds = recurringDepositDao.getActive(bookId).first()
            .filter { it.autoRenewMode != AutoRenewMode.DISABLED && RdCalculator.maturityDate(it) <= now }
            .map { MaturedDeposit.Rd(it, RdCalculator.maturityValue(it)) }

        return maturedFds + maturedRds
    }

    /** Confirms renewal with possibly-edited terms, creating a new deposit
     *  and retiring the old one. */
    suspend fun confirmRenewal(
        matured: MaturedDeposit,
        newPrincipalOrInstallment: Double,
        newTenureMonths: Int,
        newInterestRate: Double,
        newStartDate: Long
    ) {
        val bookId = session.activeBookId.first() ?: return

        when (matured) {
            is MaturedDeposit.Fd -> {
                fixedDepositDao.insert(
                    matured.entity.copy(
                        id = 0, bookId = bookId, principalAmount = newPrincipalOrInstallment,
                        tenureMonths = newTenureMonths, interestRate = newInterestRate, startDate = newStartDate
                    )
                )
                fixedDepositDao.update(matured.entity.copy(autoRenewMode = AutoRenewMode.DISABLED, isArchived = true))
            }
            is MaturedDeposit.Rd -> {
                recurringDepositDao.insert(
                    matured.entity.copy(
                        id = 0, bookId = bookId, monthlyInstallment = newPrincipalOrInstallment,
                        tenureMonths = newTenureMonths, interestRate = newInterestRate, startDate = newStartDate
                    )
                )
                recurringDepositDao.update(matured.entity.copy(autoRenewMode = AutoRenewMode.DISABLED, isArchived = true))
            }
        }
    }

    /** User declined this round — turns off auto-renew so it stops being
     *  flagged, without silently creating anything. They can always
     *  re-enable it later by editing the (now-matured, still visible)
     *  original entry. */
    suspend fun declineRenewal(matured: MaturedDeposit) {
        when (matured) {
            is MaturedDeposit.Fd -> fixedDepositDao.update(matured.entity.copy(autoRenewMode = AutoRenewMode.DISABLED))
            is MaturedDeposit.Rd -> recurringDepositDao.update(matured.entity.copy(autoRenewMode = AutoRenewMode.DISABLED))
        }
    }

    /** Suggested starting values for the confirmation popup's editable
     *  fields — Principal Only uses just the original principal, Principal
     *  + Interest uses the full maturity value. Rate/tenure default to the
     *  same as before, editable by the user before confirming. */
    fun suggestedPrincipal(matured: MaturedDeposit): Double = when (matured) {
        is MaturedDeposit.Fd -> if (matured.entity.autoRenewMode == AutoRenewMode.PRINCIPAL_PLUS_INTEREST) matured.maturityValue else matured.entity.principalAmount
        is MaturedDeposit.Rd -> if (matured.entity.autoRenewMode == AutoRenewMode.PRINCIPAL_PLUS_INTEREST) matured.maturityValue else matured.entity.monthlyInstallment
    }
}
