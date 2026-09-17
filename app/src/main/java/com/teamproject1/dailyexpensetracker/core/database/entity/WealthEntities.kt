package com.teamproject1.dailyexpensetracker.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class InterestType { SIMPLE, COMPOUND }
enum class CompoundingFrequency { MONTHLY, QUARTERLY, ANNUALLY, CUMULATIVE }
enum class WealthInstrumentKind { FD, NSC }
enum class AutoRenewMode { PRINCIPAL_ONLY, PRINCIPAL_PLUS_INTEREST, DISABLED }

/**
 * Fixed Deposits AND NSC (National Savings Certificates) — pure compound-
 * interest math, no internet dependency. NSC is mechanically identical to
 * FD (fixed rate, fixed tenure, compound interest), so per item 37 it
 * reuses this exact table and FdCalculator rather than a separate near-
 * duplicate entity/calculator — `kind` just tags which one it is for
 * display purposes on the Wealth Dashboard.
 */
@Entity(
    tableName = "fixed_deposits",
    foreignKeys = [ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("bookId")]
)
data class FixedDepositEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val name: String,               // e.g. "SBI FD 2026", bank/account reference
    val kind: WealthInstrumentKind = WealthInstrumentKind.FD,
    val bankName: String? = null,
    val accountOrCertificateNumber: String? = null,
    val principalAmount: Double,
    val startDate: Long,
    val tenureMonths: Int,           // total whole months (unchanged semantics — existing data stays valid)
    val tenureExtraDays: Int = 0,    // extra days beyond whole months, for precise Year+Month+Day tenure input
    val interestRate: Double,       // annual %, e.g. 7.0
    val interestType: InterestType = InterestType.COMPOUND,
    val compoundingFrequency: CompoundingFrequency = CompoundingFrequency.CUMULATIVE,
    val autoRenewMode: AutoRenewMode = AutoRenewMode.DISABLED,
    val isArchived: Boolean = false
)

/**
 * Liabilities — loans, credit cards tracked as debt (distinct from the
 * Expense-side Credit Card account type). Manual entry; outstanding
 * balance is tracked directly rather than derived, since EMI payment
 * history isn't itself modeled in this shell.
 */
@Entity(
    tableName = "liabilities",
    foreignKeys = [ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("bookId")]
)
data class LiabilityEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val name: String,                // e.g. "Home Loan - HDFC"
    val liabilityType: String,       // HOME_LOAN / CAR_LOAN / PERSONAL_LOAN / CREDIT_CARD / OTHER
    val principalAmount: Double,
    val interestRate: Double,
    val tenureMonths: Int,
    val emiAmount: Double,
    val startDate: Long,
    val outstandingBalance: Double,  // manually updated as EMIs are paid
    val isArchived: Boolean = false
)

/**
 * Cash in Hand — a single editable number per book, per the locked design
 * ("just a snapshot, no ledger, no history"). One row per book, upserted.
 */
@Entity(
    tableName = "cash_in_hand",
    foreignKeys = [ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("bookId", unique = true)]
)
data class CashInHandEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val amount: Double,
    val lastUpdatedAt: Long
)

enum class ExchangeCode { NSE, BSE }

/**
 * Demat/Stock holdings — the first Wealth category needing LIVE internet
 * data (unlike FD/Liabilities/Cash in Hand, which are pure math or manual).
 * Stock symbol + exchange is the precise identifier needed to fetch an
 * unambiguous live price, per the locked design ("plain names are too
 * ambiguous to fetch against reliably").
 *
 * lastFetchedPrice/lastFetchedAt back the "last updated" timestamp shown
 * when offline or before a successful fetch — current value is always
 * unitsHeld × lastFetchedPrice, never a separately stored "current value"
 * field, matching the derived-value principle used throughout this app.
 */
/**
 * A broker/platform account (e.g. "Zerodha", "Groww") — introduced per
 * explicit request, since a person can hold stocks across multiple
 * brokers with entirely separate holdings. Just a name; no broker-
 * specific details tracked. Net Worth still sums across every account
 * for this book — this only changes navigation (select an account, then
 * see its holdings) not the total.
 */
@Entity(
    tableName = "demat_accounts",
    foreignKeys = [ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("bookId")]
)
data class DematAccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val name: String,
    val isArchived: Boolean = false
)

@Entity(
    tableName = "demat_holdings",
    foreignKeys = [
        ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = DematAccountEntity::class, parentColumns = ["id"], childColumns = ["dematAccountId"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("bookId"), Index("dematAccountId")]
)
data class DematHoldingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val dematAccountId: Long,
    val stockSymbol: String,       // e.g. "RELIANCE", "TCS"
    val exchangeCode: ExchangeCode,
    val unitsHeld: Double,
    val avgBuyPrice: Double = 0.0, // optional, for gain/loss display
    val lastFetchedPrice: Double? = null,
    val lastFetchedAt: Long? = null,
    val isArchived: Boolean = false
)

/**
 * Recurring Deposit — new category, per the explicit "build RD similar to
 * FD" decision. Unlike FD's lump-sum principal, RD accumulates via
 * periodic installments, so it needs its own maturity formula (see
 * RdCalculator) even though it shares FD's bank-details/tenure-vs-end-date
 * pattern.
 */
@Entity(
    tableName = "recurring_deposits",
    foreignKeys = [ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("bookId")]
)
data class RecurringDepositEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val name: String,
    val bankName: String? = null,
    val accountOrCertificateNumber: String? = null,
    val monthlyInstallment: Double,
    val startDate: Long,
    val tenureMonths: Int,
    val interestRate: Double,
    val compoundingFrequency: CompoundingFrequency = CompoundingFrequency.QUARTERLY,
    val autoRenewMode: AutoRenewMode = AutoRenewMode.DISABLED,
    val isArchived: Boolean = false
)

/**
 * RD installment entries — real, individually editable per-month records,
 * per the confirmed redesign. Replaces the old "assume every elapsed
 * month was paid" calculation: current value now compounds each actual
 * recorded entry from its real date, not the theoretical schedule. Amount
 * defaults to the account's fixed monthlyInstallment but is editable
 * (missed month, late payment, extra payment).
 */
@Entity(
    tableName = "rd_installments",
    foreignKeys = [ForeignKey(entity = RecurringDepositEntity::class, parentColumns = ["id"], childColumns = ["rdAccountId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("rdAccountId")]
)
data class RdInstallmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val rdAccountId: Long,
    val amount: Double,
    val installmentDate: Long
)
