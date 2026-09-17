package com.teamproject1.dailyexpensetracker.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.teamproject1.dailyexpensetracker.core.database.entity.ExpenseReceivableEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ExpenseReceivableDao {
    @Insert
    suspend fun insert(receivable: ExpenseReceivableEntity): Long

    @Update
    suspend fun update(receivable: ExpenseReceivableEntity)

    @Query("SELECT * FROM expense_receivables WHERE bookId = :bookId ORDER BY isCollected ASC, createdAt DESC")
    fun getAll(bookId: Long): Flow<List<ExpenseReceivableEntity>>

    @Query("SELECT * FROM expense_receivables WHERE bookId = :bookId ORDER BY isCollected ASC, createdAt DESC")
    suspend fun getAllOnce(bookId: Long): List<ExpenseReceivableEntity>

    @Query("SELECT * FROM expense_receivables WHERE transactionId = :transactionId LIMIT 1")
    suspend fun getForTransaction(transactionId: Long): ExpenseReceivableEntity?

    @Query("DELETE FROM expense_receivables WHERE transactionId = :transactionId")
    suspend fun deleteForTransaction(transactionId: Long)
}
