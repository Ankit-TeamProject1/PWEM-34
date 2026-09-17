package com.teamproject1.dailyexpensetracker.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.teamproject1.dailyexpensetracker.core.database.entity.MutualFundAccountEntity
import com.teamproject1.dailyexpensetracker.core.database.entity.MutualFundEntryEntity
import com.teamproject1.dailyexpensetracker.core.database.entity.MutualFundPlatformEntity
import com.teamproject1.dailyexpensetracker.core.database.entity.MutualFundSipEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MutualFundPlatformDao {
    @Insert
    suspend fun insert(platform: MutualFundPlatformEntity): Long
    @Update
    suspend fun update(platform: MutualFundPlatformEntity)
    @Query("SELECT * FROM mutual_fund_platforms WHERE bookId = :bookId AND isArchived = 0 ORDER BY name ASC")
    fun getActive(bookId: Long): Flow<List<MutualFundPlatformEntity>>
    @Query("SELECT * FROM mutual_fund_platforms WHERE bookId = :bookId AND isArchived = 1 ORDER BY name ASC")
    fun getArchived(bookId: Long): Flow<List<MutualFundPlatformEntity>>
    @Query("SELECT * FROM mutual_fund_platforms WHERE bookId = :bookId ORDER BY name ASC")
    suspend fun getAllOnce(bookId: Long): List<MutualFundPlatformEntity>
    @Query("UPDATE mutual_fund_platforms SET isArchived = 1 WHERE id = :id")
    suspend fun archive(id: Long)
    @Query("UPDATE mutual_fund_platforms SET isArchived = 0 WHERE id = :id")
    suspend fun unarchive(id: Long)
}

@Dao
interface MutualFundDao {
    @Insert
    suspend fun insertAccount(account: MutualFundAccountEntity): Long
    @Update
    suspend fun updateAccount(account: MutualFundAccountEntity)
    @Query("SELECT * FROM mutual_fund_accounts WHERE platformId = :platformId AND isArchived = 0 ORDER BY schemeName ASC")
    fun getActiveForPlatform(platformId: Long): Flow<List<MutualFundAccountEntity>>
    @Query("SELECT * FROM mutual_fund_accounts WHERE bookId = :bookId AND isArchived = 0 ORDER BY schemeName ASC")
    fun getActiveAccounts(bookId: Long): Flow<List<MutualFundAccountEntity>>
    @Query("SELECT * FROM mutual_fund_accounts WHERE bookId = :bookId AND isArchived = 1 ORDER BY schemeName ASC")
    fun getArchivedAccounts(bookId: Long): Flow<List<MutualFundAccountEntity>>
    @Query("UPDATE mutual_fund_accounts SET isArchived = 1 WHERE id = :id")
    suspend fun archiveAccount(id: Long)
    @Query("UPDATE mutual_fund_accounts SET isArchived = 0 WHERE id = :id")
    suspend fun unarchiveAccount(id: Long)
    @Query("UPDATE mutual_fund_accounts SET lastFetchedNav = :nav, lastFetchedAt = :fetchedAt WHERE id = :id")
    suspend fun updateFetchedNav(id: Long, nav: Double, fetchedAt: Long)

    @Insert
    suspend fun insertEntry(entry: MutualFundEntryEntity): Long
    @Update
    suspend fun updateEntry(entry: MutualFundEntryEntity)
    @Query("DELETE FROM mutual_fund_entries WHERE id = :id")
    suspend fun deleteEntry(id: Long)
    @Query("SELECT * FROM mutual_fund_entries WHERE mutualFundAccountId = :accountId ORDER BY purchaseDate ASC")
    fun getEntries(accountId: Long): Flow<List<MutualFundEntryEntity>>
    @Query("SELECT * FROM mutual_fund_entries WHERE mutualFundAccountId = :accountId ORDER BY purchaseDate ASC")
    suspend fun getEntriesOnce(accountId: Long): List<MutualFundEntryEntity>

    @Insert
    suspend fun insertSip(sip: MutualFundSipEntity): Long
    @Update
    suspend fun updateSip(sip: MutualFundSipEntity)
    @Query("SELECT * FROM mutual_fund_sips WHERE mutualFundAccountId = :accountId LIMIT 1")
    fun getSip(accountId: Long): Flow<MutualFundSipEntity?>
    @Query("SELECT * FROM mutual_fund_sips WHERE mutualFundAccountId = :accountId LIMIT 1")
    suspend fun getSipOnce(accountId: Long): MutualFundSipEntity?
}
