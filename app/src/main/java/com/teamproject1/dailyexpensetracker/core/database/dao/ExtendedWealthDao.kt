package com.teamproject1.dailyexpensetracker.core.database.dao

import androidx.room.*
import com.teamproject1.dailyexpensetracker.core.database.entity.*
import kotlinx.coroutines.flow.Flow

@Dao
interface WealthRateHistoryDao {
    @Insert
    suspend fun insert(rate: WealthRateHistoryEntity): Long

    @Update
    suspend fun update(rate: WealthRateHistoryEntity)

    @Query("DELETE FROM wealth_rate_history WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM wealth_rate_history WHERE instrument = :instrument ORDER BY effectiveFrom ASC")
    fun getHistory(instrument: RateInstrument): Flow<List<WealthRateHistoryEntity>>

    @Query("SELECT * FROM wealth_rate_history WHERE instrument = :instrument ORDER BY effectiveFrom ASC")
    suspend fun getHistoryOnce(instrument: RateInstrument): List<WealthRateHistoryEntity>
}

@Dao
interface PpfDao {
    @Insert
    suspend fun insertAccount(account: PpfAccountEntity): Long
    @Update
    suspend fun updateAccount(account: PpfAccountEntity)
    @Query("SELECT * FROM ppf_accounts WHERE bookId = :bookId AND isArchived = 0 ORDER BY accountOpenDate DESC")
    fun getActiveAccounts(bookId: Long): Flow<List<PpfAccountEntity>>
    @Query("SELECT * FROM ppf_accounts WHERE bookId = :bookId AND isArchived = 1 ORDER BY accountOpenDate DESC")
    fun getArchivedAccounts(bookId: Long): Flow<List<PpfAccountEntity>>
    @Query("UPDATE ppf_accounts SET isArchived = 1 WHERE id = :id")
    suspend fun archiveAccount(id: Long)
    @Query("UPDATE ppf_accounts SET isArchived = 0 WHERE id = :id")
    suspend fun unarchiveAccount(id: Long)

    @Insert
    suspend fun insertDeposit(deposit: PpfDepositEntity): Long
    @Update
    suspend fun updateDeposit(deposit: PpfDepositEntity)
    @Query("DELETE FROM ppf_deposits WHERE id = :depositId")
    suspend fun deleteDeposit(depositId: Long)
    @Query("SELECT * FROM ppf_deposits WHERE ppfAccountId = :accountId ORDER BY depositDate ASC")
    fun getDeposits(accountId: Long): Flow<List<PpfDepositEntity>>
    @Query("SELECT * FROM ppf_deposits WHERE ppfAccountId = :accountId ORDER BY depositDate ASC")
    suspend fun getDepositsOnce(accountId: Long): List<PpfDepositEntity>
}

@Dao
interface EpfDao {
    @Insert
    suspend fun insertAccount(account: EpfAccountEntity): Long
    @Update
    suspend fun updateAccount(account: EpfAccountEntity)
    @Query("SELECT * FROM epf_accounts WHERE bookId = :bookId AND isArchived = 0 ORDER BY accountOpenDate DESC")
    fun getActiveAccounts(bookId: Long): Flow<List<EpfAccountEntity>>
    @Query("SELECT * FROM epf_accounts WHERE bookId = :bookId AND isArchived = 1 ORDER BY accountOpenDate DESC")
    fun getArchivedAccounts(bookId: Long): Flow<List<EpfAccountEntity>>
    @Query("UPDATE epf_accounts SET isArchived = 1 WHERE id = :id")
    suspend fun archiveAccount(id: Long)
    @Query("UPDATE epf_accounts SET isArchived = 0 WHERE id = :id")
    suspend fun unarchiveAccount(id: Long)

    @Insert
    suspend fun insertContribution(contribution: EpfContributionEntity): Long
    @Update
    suspend fun updateContribution(contribution: EpfContributionEntity)
    @Query("DELETE FROM epf_contributions WHERE id = :id")
    suspend fun deleteContribution(id: Long)
    @Query("SELECT * FROM epf_contributions WHERE epfAccountId = :accountId ORDER BY contributionDate ASC")
    fun getContributions(accountId: Long): Flow<List<EpfContributionEntity>>
    @Query("SELECT * FROM epf_contributions WHERE epfAccountId = :accountId ORDER BY contributionDate ASC")
    suspend fun getContributionsOnce(accountId: Long): List<EpfContributionEntity>
}

@Dao
interface NpsDao {
    @Insert
    suspend fun insert(account: NpsAccountEntity): Long
    @Update
    suspend fun update(account: NpsAccountEntity)
    @Query("SELECT * FROM nps_accounts WHERE bookId = :bookId AND isArchived = 0 ORDER BY name ASC")
    fun getActive(bookId: Long): Flow<List<NpsAccountEntity>>
    @Query("SELECT * FROM nps_accounts WHERE bookId = :bookId AND isArchived = 1 ORDER BY name ASC")
    fun getArchived(bookId: Long): Flow<List<NpsAccountEntity>>
    @Query("UPDATE nps_accounts SET isArchived = 1 WHERE id = :id")
    suspend fun archive(id: Long)
    @Query("UPDATE nps_accounts SET isArchived = 0 WHERE id = :id")
    suspend fun unarchive(id: Long)
}

@Dao
interface ApyDao {
    @Insert
    suspend fun insert(account: ApyAccountEntity): Long
    @Update
    suspend fun update(account: ApyAccountEntity)
    @Query("SELECT * FROM apy_accounts WHERE bookId = :bookId AND isArchived = 0 ORDER BY startDate DESC")
    fun getActive(bookId: Long): Flow<List<ApyAccountEntity>>
    @Query("SELECT * FROM apy_accounts WHERE bookId = :bookId AND isArchived = 1 ORDER BY startDate DESC")
    fun getArchived(bookId: Long): Flow<List<ApyAccountEntity>>
    @Query("UPDATE apy_accounts SET isArchived = 1 WHERE id = :id")
    suspend fun archive(id: Long)
    @Query("UPDATE apy_accounts SET isArchived = 0 WHERE id = :id")
    suspend fun unarchive(id: Long)
}

@Dao
interface InsuranceDao {
    @Insert
    suspend fun insert(policy: InsurancePolicyEntity): Long
    @Update
    suspend fun update(policy: InsurancePolicyEntity)
    @Query("SELECT * FROM insurance_policies WHERE bookId = :bookId AND isArchived = 0 ORDER BY policyName ASC")
    fun getActive(bookId: Long): Flow<List<InsurancePolicyEntity>>
    @Query("SELECT * FROM insurance_policies WHERE bookId = :bookId AND isArchived = 1 ORDER BY policyName ASC")
    fun getArchived(bookId: Long): Flow<List<InsurancePolicyEntity>>
    @Query("UPDATE insurance_policies SET isArchived = 1 WHERE id = :id")
    suspend fun archive(id: Long)
    @Query("UPDATE insurance_policies SET isArchived = 0 WHERE id = :id")
    suspend fun unarchive(id: Long)
}

@Dao
interface ManualAssetDao {
    @Insert
    suspend fun insert(asset: ManualAssetEntity): Long
    @Update
    suspend fun update(asset: ManualAssetEntity)
    @Query("SELECT * FROM manual_assets WHERE bookId = :bookId AND isArchived = 0 ORDER BY name ASC")
    fun getActive(bookId: Long): Flow<List<ManualAssetEntity>>
    @Query("SELECT * FROM manual_assets WHERE bookId = :bookId AND isArchived = 1 ORDER BY name ASC")
    fun getArchived(bookId: Long): Flow<List<ManualAssetEntity>>
    @Query("UPDATE manual_assets SET isArchived = 1 WHERE id = :id")
    suspend fun archive(id: Long)
    @Query("UPDATE manual_assets SET isArchived = 0 WHERE id = :id")
    suspend fun unarchive(id: Long)
}

@Dao
interface BankBalanceDao {
    @Insert
    suspend fun insert(bank: BankBalanceEntity): Long
    @Update
    suspend fun update(bank: BankBalanceEntity)
    @Query("SELECT * FROM bank_balances WHERE bookId = :bookId AND isArchived = 0 ORDER BY bankName ASC")
    fun getActive(bookId: Long): Flow<List<BankBalanceEntity>>
    @Query("SELECT * FROM bank_balances WHERE bookId = :bookId AND isArchived = 1 ORDER BY bankName ASC")
    fun getArchived(bookId: Long): Flow<List<BankBalanceEntity>>
    @Query("UPDATE bank_balances SET isArchived = 1 WHERE id = :id")
    suspend fun archive(id: Long)
    @Query("UPDATE bank_balances SET isArchived = 0 WHERE id = :id")
    suspend fun unarchive(id: Long)
}

@Dao
interface WealthReceivableDao {
    @Insert
    suspend fun insert(receivable: WealthReceivableEntity): Long
    @Update
    suspend fun update(receivable: WealthReceivableEntity)
    @Query("SELECT * FROM wealth_receivables WHERE bookId = :bookId AND isArchived = 0 ORDER BY personName ASC")
    fun getActive(bookId: Long): Flow<List<WealthReceivableEntity>>
    @Query("SELECT * FROM wealth_receivables WHERE bookId = :bookId AND isArchived = 1 ORDER BY personName ASC")
    fun getArchived(bookId: Long): Flow<List<WealthReceivableEntity>>
    @Query("UPDATE wealth_receivables SET isArchived = 1 WHERE id = :id")
    suspend fun archive(id: Long)
    @Query("UPDATE wealth_receivables SET isArchived = 0 WHERE id = :id")
    suspend fun unarchive(id: Long)
}

@Dao
interface NpsSchemeDao {
    @Insert
    suspend fun insert(scheme: NpsSchemeEntity): Long
    @Query("SELECT * FROM nps_schemes WHERE npsAccountId = :accountId")
    fun getForAccount(accountId: Long): Flow<List<NpsSchemeEntity>>
    @Query("SELECT * FROM nps_schemes WHERE npsAccountId = :accountId")
    suspend fun getForAccountOnce(accountId: Long): List<NpsSchemeEntity>
    @Query("DELETE FROM nps_schemes WHERE npsAccountId = :accountId")
    suspend fun deleteForAccount(accountId: Long)
}
