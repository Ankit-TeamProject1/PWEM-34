package com.teamproject1.dailyexpensetracker.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.teamproject1.dailyexpensetracker.core.database.dao.*
import com.teamproject1.dailyexpensetracker.core.database.entity.*

@Database(
    entities = [
        BookEntity::class,
        AccountEntity::class,
        CategoryEntity::class,
        TransactionEntity::class,
        BudgetEntity::class,
        RecurringRuleEntity::class,
        FixedDepositEntity::class,
        LiabilityEntity::class,
        CashInHandEntity::class,
        DematHoldingEntity::class,
        WealthRateHistoryEntity::class,
        PpfAccountEntity::class,
        PpfDepositEntity::class,
        EpfAccountEntity::class,
        EpfContributionEntity::class,
        NpsAccountEntity::class,
        ApyAccountEntity::class,
        InsurancePolicyEntity::class,
        ManualAssetEntity::class,
        RecurringDepositEntity::class,
        NpsSchemeEntity::class,
        BankBalanceEntity::class,
        WealthReceivableEntity::class,
        ExpenseReceivableEntity::class,
        RdInstallmentEntity::class,
        MutualFundAccountEntity::class,
        MutualFundEntryEntity::class,
        MutualFundSipEntity::class,
        KamettiAccountEntity::class,
        KamettiEntryEntity::class,
        SnoozedReminderEntity::class,
        MetalHoldingEntity::class,
        DematAccountEntity::class,
        MutualFundPlatformEntity::class
    ],
    version = 1,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun accountDao(): AccountDao
    abstract fun categoryDao(): CategoryDao
    abstract fun transactionDao(): TransactionDao
    abstract fun budgetDao(): BudgetDao
    abstract fun recurringRuleDao(): RecurringRuleDao
    abstract fun fixedDepositDao(): FixedDepositDao
    abstract fun liabilityDao(): LiabilityDao
    abstract fun cashInHandDao(): CashInHandDao
    abstract fun dematHoldingDao(): DematHoldingDao
    abstract fun wealthRateHistoryDao(): WealthRateHistoryDao
    abstract fun ppfDao(): PpfDao
    abstract fun epfDao(): EpfDao
    abstract fun npsDao(): NpsDao
    abstract fun apyDao(): ApyDao
    abstract fun insuranceDao(): InsuranceDao
    abstract fun manualAssetDao(): ManualAssetDao
    abstract fun recurringDepositDao(): RecurringDepositDao
    abstract fun npsSchemeDao(): NpsSchemeDao
    abstract fun bankBalanceDao(): BankBalanceDao
    abstract fun wealthReceivableDao(): WealthReceivableDao
    abstract fun expenseReceivableDao(): ExpenseReceivableDao
    abstract fun rdInstallmentDao(): RdInstallmentDao
    abstract fun mutualFundDao(): MutualFundDao
    abstract fun kamettiDao(): KamettiDao
    abstract fun snoozedReminderDao(): SnoozedReminderDao
    abstract fun metalHoldingDao(): MetalHoldingDao
    abstract fun dematAccountDao(): DematAccountDao
    abstract fun mutualFundPlatformDao(): MutualFundPlatformDao

    // NOTE: This is financial data. Every future schema change MUST go through
    // an explicit Migration object added here. Never use fallbackToDestructiveMigration()
    // in production — it would silently wipe a user's transaction history.
    // The Wealth tables added here are new in this shell; since the app
    // hasn't shipped to real end users yet (still in active dev/testing),
    // schema version stays at 1 rather than adding a migration for a
    // pre-release change — testers reinstalling is an acceptable cost at
    // this stage, but this exception should NOT continue once real user
    // data exists.
    companion object {
        const val DATABASE_NAME = "daily_expense_tracker.db"
    }
}
