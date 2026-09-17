package com.teamproject1.dailyexpensetracker.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Govt-declared interest rate history for PPF and EPF — these change
 * periodically (PPF quarterly, EPF annually) via official notification,
 * not a live-fetchable market rate. This table is maintained by the
 * developer (updated when rates actually change), not fetched — the
 * locked design for these two categories. `instrument` distinguishes
 * PPF vs EPF since their rates differ and change on different schedules.
 * `effectiveFrom` lets deposit-by-deposit compound growth use the correct
 * historical rate for each period rather than today's rate applied
 * retroactively to old deposits.
 */
enum class RateInstrument { PPF, EPF }

@Entity(tableName = "wealth_rate_history")
data class WealthRateHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val instrument: RateInstrument,
    val ratePercent: Double,
    val effectiveFrom: Long
)

/**
 * PPF (Public Provident Fund) — deposits tracked individually so compound
 * growth can apply the correct historical rate (via WealthRateHistoryEntity)
 * to each deposit based on when it was made, rather than one flat rate.
 */
@Entity(
    tableName = "ppf_accounts",
    foreignKeys = [ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("bookId")]
)
data class PpfAccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val name: String,               // e.g. "PPF - SBI Pitampura"
    val accountNumber: String? = null,
    val bankOrPostOfficeName: String? = null,
    val accountOpenDate: Long,
    val isArchived: Boolean = false
)

enum class PpfEntryType { DEPOSIT, DEDUCTION, INTEREST }

@Entity(
    tableName = "ppf_deposits",
    foreignKeys = [ForeignKey(entity = PpfAccountEntity::class, parentColumns = ["id"], childColumns = ["ppfAccountId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("ppfAccountId")]
)
data class PpfDepositEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ppfAccountId: Long,
    val amount: Double,          // signed — positive for DEPOSIT/INTEREST, negative for DEDUCTION
    val depositDate: Long,
    val type: PpfEntryType = PpfEntryType.DEPOSIT,
    val note: String? = null
)

/**
 * EPF — per item 31, supports MULTIPLE accounts per book (a user can have
 * several EPF/UAN accounts from different employers), unlike the single-
 * row-per-book pattern used for Cash in Hand. Same deposit-history +
 * rate-history approach as PPF.
 */
@Entity(
    tableName = "epf_accounts",
    foreignKeys = [ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("bookId")]
)
data class EpfAccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val name: String,               // e.g. "EPF - Infosys (2019-2022)"
    val companyName: String? = null,
    val pfAccountNumber: String? = null,
    val uan: String? = null,        // Universal Account Number, optional reference
    val accountOpenDate: Long,
    val monthlyEeContribution: Double? = null,      // fixed Employee+Employer monthly amount — step-up/down via direct edit, per explicit request
    val monthlyPensionContribution: Double? = null, // fixed Pension monthly amount — same step-up approach
    val recurringDepositDate: Long? = null, // day-of-month payroll actually posts the contribution — the due-reminder waits for this exact day the following month, not just "a new month has begun"
    val isArchived: Boolean = false
)

enum class EpfTileType { EMPLOYEE_EMPLOYER, PENSION }

@Entity(
    tableName = "epf_contributions",
    foreignKeys = [ForeignKey(entity = EpfAccountEntity::class, parentColumns = ["id"], childColumns = ["epfAccountId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("epfAccountId")]
)
data class EpfContributionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val epfAccountId: Long,
    val amount: Double,
    val contributionDate: Long,      // represents the CONTRIBUTION MONTH, not self/employer type
    val tileType: EpfTileType = EpfTileType.EMPLOYEE_EMPLOYER,
    val isTransfer: Boolean = false  // true for a balance-transfer-in entry from a closed account
)

/**
 * NPS — no public API exists for scheme NAV, per the locked design; value
 * is entered manually and updated whenever the user checks their NPS
 * statement, rather than auto-fetched like Demat.
 */
@Entity(
    tableName = "nps_accounts",
    foreignKeys = [ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("bookId")]
)
data class NpsAccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val name: String,               // e.g. "NPS Tier I - HDFC Pension"
    val pran: String? = null,
    val pfm: String,                // Pension Fund Manager
    val tier: String = "TIER_I",    // TIER_I or TIER_II
    val currentValue: Double,       // manually entered, updated occasionally
    val lastUpdatedAt: Long,
    // Optional recurring contribution tracking, per explicit request —
    // mirrors APY's exact design, since NPS is structurally identical: a
    // single running total with no entry ledger, rather than a schedule
    // derived from real posted entries.
    val monthlyContribution: Double? = null,
    val recurringDepositDate: Long? = null,
    val lastContributionDate: Long? = null,
    val isArchived: Boolean = false
)

/**
 * Reference-only scheme allocation per NPS account (e.g. Equity 60%, Corp
 * Bond 30%, Govt Securities 10%) — per the "simpler middle ground" scope
 * decision: tracked for reference, no auto-unit-purchase or rebalance math.
 */
@Entity(
    tableName = "nps_schemes",
    foreignKeys = [ForeignKey(entity = NpsAccountEntity::class, parentColumns = ["id"], childColumns = ["npsAccountId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("npsAccountId")]
)
data class NpsSchemeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val npsAccountId: Long,
    val schemeName: String,         // e.g. "Equity (E)", "Corporate Bond (C)", "Govt Securities (G)"
    val allocationPercent: Double
)

/**
 * APY (Atal Pension Yojana) — slab-based, not market-linked. Tracks the
 * contribution amount and the pension slab opted for reference.
 */
@Entity(
    tableName = "apy_accounts",
    foreignKeys = [ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("bookId")]
)
data class ApyAccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val name: String,
    val pran: String? = null,
    val bankName: String? = null,
    val accountNumber: String? = null,
    val ifsc: String? = null,
    val monthlyContribution: Double,
    val pensionSlab: Int,           // e.g. 1000, 2000, 3000, 4000, 5000 (monthly pension at 60)
    val startDate: Long,
    val recurringDepositDate: Long? = null, // the actual day-of-month contributions happen on — may differ from startDate; null falls back to startDate
    val totalContributedSoFar: Double = 0.0, // manually tracked running total
    val lastContributionDate: Long? = null,  // drives the due-date popup — null means "never confirmed yet, due one month after startDate"
    val isArchived: Boolean = false
)

/**
 * Insurance — covers both cash-value policies (ULIP, endowment/money-back
 * LIC) and pure protection policies (term, most health), per the locked
 * design. hasCashValue controls whether currentSurrenderValue counts
 * toward Net Worth — a term plan's sum assured is NOT an asset, since that
 * money only exists if the policyholder dies, which would misrepresent
 * net worth if included.
 */
@Entity(
    tableName = "insurance_policies",
    foreignKeys = [ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("bookId")]
)
data class InsurancePolicyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val policyName: String,
    val policyNumber: String? = null,
    val insurerName: String,
    val policyType: String,         // TERM / HEALTH / ENDOWMENT / MONEY_BACK / ULIP
    val startDate: Long? = null,
    val maturityDate: Long? = null,
    val sumAssured: Double,
    val premiumAmount: Double,
    val premiumFrequency: String,   // MONTHLY / QUARTERLY / ANNUALLY
    val premiumPaymentTermYears: Int? = null,
    val nextDueDate: Long? = null,
    val hasCashValue: Boolean = false,
    val currentSurrenderValue: Double = 0.0, // only meaningful if hasCashValue = true
    val isArchived: Boolean = false
)

/**
 * Manual Assets — property, vehicles, or anything else with no live price
 * feed anywhere. Purely manual, occasional updates.
 */
@Entity(
    tableName = "manual_assets",
    foreignKeys = [ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("bookId")]
)
data class ManualAssetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val name: String,
    val assetType: String,          // REAL_ESTATE / VEHICLE / OTHER
    val currentValue: Double,
    val lastUpdatedByUserAt: Long,
    val isArchived: Boolean = false
)

/**
 * Bank — a new savings-style asset entry per explicit request: current
 * balance shown, add/subtract calculator to adjust it, then Save persists
 * the new balance. No ledger/transaction history — same "entry style, not
 * a mini-ledger" design as Cash in Hand, just with bank identifying
 * details attached and multiple entries supported (one per bank account).
 */
@Entity(
    tableName = "bank_balances",
    foreignKeys = [ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("bookId")]
)
data class BankBalanceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val bankName: String,
    val accountDetails: String? = null, // e.g. account number, branch — free text, optional
    val currentBalance: Double,
    val lastUpdatedAt: Long,
    val isArchived: Boolean = false
)

/**
 * Wealth Account Receivables — money owed TO you by others, the mirror of
 * Liabilities. Same entry-style, no-ledger pattern as Bank per explicit
 * request: name of who owes you, current amount outstanding, adjustable
 * via the same add/subtract calculator when partially repaid.
 */
@Entity(
    tableName = "wealth_receivables",
    foreignKeys = [ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("bookId")]
)
data class WealthReceivableEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val personName: String,
    val currentAmount: Double,
    val note: String? = null,
    val lastUpdatedAt: Long,
    val isArchived: Boolean = false
)

/**
 * Mutual Funds — Account + Entries pattern like PPF/RD, per explicit
 * request. Identified by AMFI Scheme Code (found via the fund-name search
 * feature, not manually typed), since that's what's needed to look up the
 * NAV. Total units and invested amount are derived from entries, not
 * stored directly — same principle as PPF/EPF/RD, avoiding a
 * stored-total that could drift out of sync with its real entries.
 */
/**
 * A broker/platform account (e.g. "Zerodha Coin", "Groww", "Kuvera",
 * direct with the AMC) — same concept and same reasoning as
 * DematAccountEntity, introduced per explicit request since a person can
 * hold funds across multiple platforms with entirely separate holdings.
 * Just a name; Net Worth still sums across every platform for this book.
 */
@Entity(
    tableName = "mutual_fund_platforms",
    foreignKeys = [ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("bookId")]
)
data class MutualFundPlatformEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val name: String,
    val isArchived: Boolean = false
)

@Entity(
    tableName = "mutual_fund_accounts",
    foreignKeys = [
        ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = MutualFundPlatformEntity::class, parentColumns = ["id"], childColumns = ["platformId"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("bookId"), Index("platformId")]
)
data class MutualFundAccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val platformId: Long,
    val schemeCode: String,
    val schemeName: String,
    val folioNumber: String? = null,
    val fundHouse: String? = null,
    val lastFetchedNav: Double? = null,
    val lastFetchedAt: Long? = null,
    val isArchived: Boolean = false
)

/** A single purchase — either a manual lump-sum entry or a SIP
 *  confirmation. unitsAllotted is computed and stored at entry time
 *  (investedAmount / navAtPurchase), not recalculated later — the NAV on
 *  the actual purchase date is what determines units received, matching
 *  how a real mutual fund purchase works. */
@Entity(
    tableName = "mutual_fund_entries",
    foreignKeys = [ForeignKey(entity = MutualFundAccountEntity::class, parentColumns = ["id"], childColumns = ["mutualFundAccountId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("mutualFundAccountId")]
)
data class MutualFundEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mutualFundAccountId: Long,
    val investedAmount: Double,
    val navAtPurchase: Double,
    val unitsAllotted: Double,
    val purchaseDate: Long,
    val isSip: Boolean = false
)

/** SIP schedule for a fund — separate from entries, since this
 *  represents an ongoing PLAN (amount, cadence, held/active), not a
 *  transaction. Step-up/down is just editing sipAmount directly, per
 *  explicit request — no separate scheduled-increase mechanism. */
@Entity(
    tableName = "mutual_fund_sips",
    foreignKeys = [ForeignKey(entity = MutualFundAccountEntity::class, parentColumns = ["id"], childColumns = ["mutualFundAccountId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("mutualFundAccountId")]
)
data class MutualFundSipEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mutualFundAccountId: Long,
    val sipAmount: Double,
    val startDate: Long,
    val isHeld: Boolean = false,
    val lastConfirmedDate: Long? = null
)

/**
 * Kametti (chit fund / committee) — a pooled monthly savings scheme, per
 * explicit request. Unlike RD, there is NO interest or compounding: the
 * balance is simply a running ledger of deposits minus the one-time
 * "Won" withdrawal, since a chit fund pays out the pooled amount to one
 * member per month rather than paying interest. Same Account + Entries
 * pattern as RD otherwise — monthly amount, tenure, recurring date, real
 * editable entries, back-dated auto-generation, and a due-date reminder.
 */
@Entity(
    tableName = "kametti_accounts",
    foreignKeys = [ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("bookId")]
)
data class KamettiAccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val name: String,
    val monthlyAmount: Double,
    val startDate: Long,
    val totalMonths: Int,
    val recurringDepositDate: Long? = null, // may differ from startDate; null falls back to startDate
    val isArchived: Boolean = false
)

/** A single entry — a monthly deposit (positive) or the one-time "Won"
 *  payout (negative, per explicit request: "balance will be calculated
 *  as deposit add and won subtract"). isWon distinguishes the payout
 *  entry for display, separate from the sign of amount itself. */
@Entity(
    tableName = "kametti_entries",
    foreignKeys = [ForeignKey(entity = KamettiAccountEntity::class, parentColumns = ["id"], childColumns = ["kamettiAccountId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("kamettiAccountId")]
)
data class KamettiEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kamettiAccountId: Long,
    val amount: Double, // positive for a deposit, negative for the Won payout
    val entryDate: Long,
    val isWon: Boolean = false
)

/**
 * A temporary snooze on a specific reminder — per explicit request,
 * tapping "Skip for Now" on any due-date popup (APY, EPF, RD, Kametti,
 * Mutual Fund SIP) should suppress that SPECIFIC reminder for a few
 * hours, not just for the current in-memory session. Without this, the
 * exact same popup could reappear seconds later just by navigating away
 * from and back to the Dashboard, since nothing about the underlying
 * data actually changed. Keyed by (type, accountId, tileType) rather
 * than one snooze per account, since an account like EPF can have two
 * independent reminders (Employee+Employer and Pension) that should
 * snooze separately.
 */
@Entity(tableName = "snoozed_reminders")
data class SnoozedReminderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val reminderType: String, // matches RecurringTransactionType's name, or a tile-qualified variant for EPF
    val accountId: Long,
    val snoozedUntil: Long
)

enum class MetalType { GOLD, SILVER }
enum class MetalForm { PHYSICAL, DIGITAL }

/**
 * One tile per (metal, carat/purity, form) combination — simplified per
 * explicit request: no separate Name, no shared rate table, no
 * profit/loss tracking. Quantity and the current price are both entered
 * directly on the tile itself, so "Gold 24K Physical" and "Gold 24K
 * Digital" are independent tiles with their own quantity and price, even
 * though they're the same metal and carat. lastUpdatedAt tracks when the
 * price was last set, shown on the tile.
 */
@Entity(
    tableName = "metal_holdings",
    foreignKeys = [ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("bookId"), Index(value = ["bookId", "metalType", "caratType", "form"], unique = true)]
)
data class MetalHoldingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val metalType: MetalType,
    val caratType: String, // "24K"/"22K"/"18K"/"14K" for gold, "999"/"925" for silver — fixed list, enforced in the UI
    val form: MetalForm,
    val quantityGrams: Double = 0.0,
    val currentPricePerGram: Double? = null,
    val lastUpdatedAt: Long? = null,
    val isArchived: Boolean = false
)
