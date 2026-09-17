package com.teamproject1.dailyexpensetracker.di

import android.content.Context
import androidx.room.Room
import com.teamproject1.dailyexpensetracker.core.database.AppDatabase
import com.teamproject1.dailyexpensetracker.core.database.dao.*
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.DATABASE_NAME)
            // No fallbackToDestructiveMigration — this is financial data.
            // Every future schema change adds an explicit Migration here.
            .build()

    @Provides fun provideBookDao(db: AppDatabase): BookDao = db.bookDao()
    @Provides fun provideAccountDao(db: AppDatabase): AccountDao = db.accountDao()
    @Provides fun provideCategoryDao(db: AppDatabase): CategoryDao = db.categoryDao()
    @Provides fun provideTransactionDao(db: AppDatabase): TransactionDao = db.transactionDao()
    @Provides fun provideBudgetDao(db: AppDatabase): BudgetDao = db.budgetDao()
    @Provides fun provideRecurringRuleDao(db: AppDatabase): RecurringRuleDao = db.recurringRuleDao()
    @Provides fun provideFixedDepositDao(db: AppDatabase): FixedDepositDao = db.fixedDepositDao()
    @Provides fun provideLiabilityDao(db: AppDatabase): LiabilityDao = db.liabilityDao()
    @Provides fun provideCashInHandDao(db: AppDatabase): CashInHandDao = db.cashInHandDao()
    @Provides fun provideDematHoldingDao(db: AppDatabase): DematHoldingDao = db.dematHoldingDao()
    @Provides fun provideWealthRateHistoryDao(db: AppDatabase): WealthRateHistoryDao = db.wealthRateHistoryDao()
    @Provides fun providePpfDao(db: AppDatabase): PpfDao = db.ppfDao()
    @Provides fun provideEpfDao(db: AppDatabase): EpfDao = db.epfDao()
    @Provides fun provideNpsDao(db: AppDatabase): NpsDao = db.npsDao()
    @Provides fun provideApyDao(db: AppDatabase): ApyDao = db.apyDao()
    @Provides fun provideInsuranceDao(db: AppDatabase): InsuranceDao = db.insuranceDao()
    @Provides fun provideManualAssetDao(db: AppDatabase): ManualAssetDao = db.manualAssetDao()
    @Provides fun provideRecurringDepositDao(db: AppDatabase): RecurringDepositDao = db.recurringDepositDao()
    @Provides fun provideNpsSchemeDao(db: AppDatabase): NpsSchemeDao = db.npsSchemeDao()
    @Provides fun provideBankBalanceDao(db: AppDatabase): BankBalanceDao = db.bankBalanceDao()
    @Provides fun provideWealthReceivableDao(db: AppDatabase): WealthReceivableDao = db.wealthReceivableDao()
    @Provides fun provideExpenseReceivableDao(db: AppDatabase): ExpenseReceivableDao = db.expenseReceivableDao()
    @Provides fun provideRdInstallmentDao(db: AppDatabase): RdInstallmentDao = db.rdInstallmentDao()
    @Provides fun provideMutualFundDao(db: AppDatabase): MutualFundDao = db.mutualFundDao()
    @Provides fun provideKamettiDao(db: AppDatabase): KamettiDao = db.kamettiDao()
    @Provides fun provideSnoozedReminderDao(db: AppDatabase): SnoozedReminderDao = db.snoozedReminderDao()
    @Provides fun provideMetalHoldingDao(db: AppDatabase): MetalHoldingDao = db.metalHoldingDao()
    @Provides fun provideDematAccountDao(db: AppDatabase): DematAccountDao = db.dematAccountDao()
    @Provides fun provideMutualFundPlatformDao(db: AppDatabase): MutualFundPlatformDao = db.mutualFundPlatformDao()
}
