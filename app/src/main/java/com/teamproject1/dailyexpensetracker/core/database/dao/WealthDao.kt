package com.teamproject1.dailyexpensetracker.core.database.dao

import androidx.room.*
import com.teamproject1.dailyexpensetracker.core.database.entity.CashInHandEntity
import com.teamproject1.dailyexpensetracker.core.database.entity.DematAccountEntity
import com.teamproject1.dailyexpensetracker.core.database.entity.DematHoldingEntity
import com.teamproject1.dailyexpensetracker.core.database.entity.FixedDepositEntity
import com.teamproject1.dailyexpensetracker.core.database.entity.LiabilityEntity
import com.teamproject1.dailyexpensetracker.core.database.entity.RecurringDepositEntity
import com.teamproject1.dailyexpensetracker.core.database.entity.RdInstallmentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FixedDepositDao {
    @Insert
    suspend fun insert(fd: FixedDepositEntity): Long

    @Update
    suspend fun update(fd: FixedDepositEntity)

    @Query("SELECT * FROM fixed_deposits WHERE bookId = :bookId AND isArchived = 0 ORDER BY startDate DESC")
    fun getActive(bookId: Long): Flow<List<FixedDepositEntity>>

    @Query("SELECT * FROM fixed_deposits WHERE bookId = :bookId AND isArchived = 1 ORDER BY startDate DESC")
    fun getArchived(bookId: Long): Flow<List<FixedDepositEntity>>

    @Query("UPDATE fixed_deposits SET isArchived = 1 WHERE id = :id")
    suspend fun archive(id: Long)

    @Query("UPDATE fixed_deposits SET isArchived = 0 WHERE id = :id")
    suspend fun unarchive(id: Long)
}

@Dao
interface LiabilityDao {
    @Insert
    suspend fun insert(liability: LiabilityEntity): Long

    @Update
    suspend fun update(liability: LiabilityEntity)

    @Query("SELECT * FROM liabilities WHERE bookId = :bookId AND isArchived = 0 ORDER BY startDate DESC")
    fun getActive(bookId: Long): Flow<List<LiabilityEntity>>

    @Query("SELECT * FROM liabilities WHERE bookId = :bookId AND isArchived = 1 ORDER BY startDate DESC")
    fun getArchived(bookId: Long): Flow<List<LiabilityEntity>>

    @Query("UPDATE liabilities SET isArchived = 1 WHERE id = :id")
    suspend fun archive(id: Long)

    @Query("UPDATE liabilities SET isArchived = 0 WHERE id = :id")
    suspend fun unarchive(id: Long)
}

@Dao
interface CashInHandDao {
    @Query("SELECT * FROM cash_in_hand WHERE bookId = :bookId LIMIT 1")
    fun get(bookId: Long): Flow<CashInHandEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CashInHandEntity)
}

@Dao
interface DematAccountDao {
    @Insert
    suspend fun insert(account: DematAccountEntity): Long
    @Update
    suspend fun update(account: DematAccountEntity)
    @Query("SELECT * FROM demat_accounts WHERE bookId = :bookId AND isArchived = 0 ORDER BY name ASC")
    fun getActive(bookId: Long): Flow<List<DematAccountEntity>>
    @Query("SELECT * FROM demat_accounts WHERE bookId = :bookId AND isArchived = 1 ORDER BY name ASC")
    fun getArchived(bookId: Long): Flow<List<DematAccountEntity>>
    @Query("SELECT * FROM demat_accounts WHERE bookId = :bookId ORDER BY name ASC")
    suspend fun getAllOnce(bookId: Long): List<DematAccountEntity>
    @Query("UPDATE demat_accounts SET isArchived = 1 WHERE id = :id")
    suspend fun archive(id: Long)
    @Query("UPDATE demat_accounts SET isArchived = 0 WHERE id = :id")
    suspend fun unarchive(id: Long)
}

@Dao
interface DematHoldingDao {
    @Insert
    suspend fun insert(holding: DematHoldingEntity): Long

    @Update
    suspend fun update(holding: DematHoldingEntity)

    @Query("SELECT * FROM demat_holdings WHERE dematAccountId = :dematAccountId AND isArchived = 0 ORDER BY stockSymbol ASC")
    fun getActive(dematAccountId: Long): Flow<List<DematHoldingEntity>>

    @Query("SELECT * FROM demat_holdings WHERE bookId = :bookId AND isArchived = 0 ORDER BY stockSymbol ASC")
    fun getActiveForBook(bookId: Long): Flow<List<DematHoldingEntity>>

    @Query("SELECT * FROM demat_holdings WHERE bookId = :bookId AND isArchived = 1 ORDER BY stockSymbol ASC")
    fun getArchived(bookId: Long): Flow<List<DematHoldingEntity>>

    @Query("UPDATE demat_holdings SET isArchived = 1 WHERE id = :id")
    suspend fun archive(id: Long)

    @Query("UPDATE demat_holdings SET isArchived = 0 WHERE id = :id")
    suspend fun unarchive(id: Long)

    @Query("UPDATE demat_holdings SET lastFetchedPrice = :price, lastFetchedAt = :fetchedAt WHERE id = :id")
    suspend fun updateFetchedPrice(id: Long, price: Double, fetchedAt: Long)
}

@Dao
interface RecurringDepositDao {
    @Insert
    suspend fun insert(rd: RecurringDepositEntity): Long
    @Update
    suspend fun update(rd: RecurringDepositEntity)
    @Query("SELECT * FROM recurring_deposits WHERE bookId = :bookId AND isArchived = 0 ORDER BY startDate DESC")
    fun getActive(bookId: Long): Flow<List<RecurringDepositEntity>>
    @Query("SELECT * FROM recurring_deposits WHERE bookId = :bookId AND isArchived = 1 ORDER BY startDate DESC")
    fun getArchived(bookId: Long): Flow<List<RecurringDepositEntity>>
    @Query("UPDATE recurring_deposits SET isArchived = 1 WHERE id = :id")
    suspend fun archive(id: Long)
    @Query("UPDATE recurring_deposits SET isArchived = 0 WHERE id = :id")
    suspend fun unarchive(id: Long)
}

@Dao
interface RdInstallmentDao {
    @Insert
    suspend fun insert(installment: RdInstallmentEntity): Long
    @Insert
    suspend fun insertAll(installments: List<RdInstallmentEntity>)
    @Update
    suspend fun update(installment: RdInstallmentEntity)
    @Query("DELETE FROM rd_installments WHERE id = :id")
    suspend fun delete(id: Long)
    @Query("SELECT * FROM rd_installments WHERE rdAccountId = :rdAccountId ORDER BY installmentDate ASC")
    fun getInstallments(rdAccountId: Long): Flow<List<RdInstallmentEntity>>
    @Query("SELECT * FROM rd_installments WHERE rdAccountId = :rdAccountId ORDER BY installmentDate ASC")
    suspend fun getInstallmentsOnce(rdAccountId: Long): List<RdInstallmentEntity>
}
