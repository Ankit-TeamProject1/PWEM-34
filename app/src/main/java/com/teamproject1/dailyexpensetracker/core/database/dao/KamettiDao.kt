package com.teamproject1.dailyexpensetracker.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.teamproject1.dailyexpensetracker.core.database.entity.KamettiAccountEntity
import com.teamproject1.dailyexpensetracker.core.database.entity.KamettiEntryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface KamettiDao {
    @Insert
    suspend fun insertAccount(account: KamettiAccountEntity): Long
    @Update
    suspend fun updateAccount(account: KamettiAccountEntity)
    @Query("SELECT * FROM kametti_accounts WHERE bookId = :bookId AND isArchived = 0 ORDER BY startDate DESC")
    fun getActiveAccounts(bookId: Long): Flow<List<KamettiAccountEntity>>
    @Query("SELECT * FROM kametti_accounts WHERE bookId = :bookId AND isArchived = 1 ORDER BY startDate DESC")
    fun getArchivedAccounts(bookId: Long): Flow<List<KamettiAccountEntity>>
    @Query("UPDATE kametti_accounts SET isArchived = 1 WHERE id = :id")
    suspend fun archiveAccount(id: Long)
    @Query("UPDATE kametti_accounts SET isArchived = 0 WHERE id = :id")
    suspend fun unarchiveAccount(id: Long)

    @Insert
    suspend fun insertEntry(entry: KamettiEntryEntity): Long
    @Insert
    suspend fun insertAllEntries(entries: List<KamettiEntryEntity>)
    @Update
    suspend fun updateEntry(entry: KamettiEntryEntity)
    @Query("DELETE FROM kametti_entries WHERE id = :id")
    suspend fun deleteEntry(id: Long)
    @Query("SELECT * FROM kametti_entries WHERE kamettiAccountId = :accountId ORDER BY entryDate ASC")
    fun getEntries(accountId: Long): Flow<List<KamettiEntryEntity>>
    @Query("SELECT * FROM kametti_entries WHERE kamettiAccountId = :accountId ORDER BY entryDate ASC")
    suspend fun getEntriesOnce(accountId: Long): List<KamettiEntryEntity>
}
