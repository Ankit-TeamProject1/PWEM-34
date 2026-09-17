package com.teamproject1.dailyexpensetracker.core.database.dao

import androidx.room.*
import com.teamproject1.dailyexpensetracker.core.database.entity.TransactionEntity
import kotlinx.coroutines.flow.Flow

data class AccountBalance(
    val accountId: Long,
    val accountName: String,
    val balance: Double
)

data class CategoryTotal(
    val categoryId: Long,
    val total: Double
)

@Dao
interface TransactionDao {

    @Insert
    suspend fun insert(transaction: TransactionEntity): Long

    @Update
    suspend fun update(transaction: TransactionEntity)

    @Query("UPDATE transactions SET isDeleted = 1, updatedAt = :now WHERE id = :id AND bookId = :bookId")
    suspend fun softDelete(id: Long, bookId: Long, now: Long)

    @Query("""
        SELECT * FROM transactions
        WHERE bookId = :bookId AND isDeleted = 0
        ORDER BY occurredAt DESC
    """)
    fun getAll(bookId: Long): Flow<List<TransactionEntity>>

    @Query("""
        SELECT * FROM transactions
        WHERE bookId = :bookId AND isDeleted = 0
        AND occurredAt BETWEEN :startMillis AND :endMillis
        ORDER BY occurredAt DESC
    """)
    fun getByDateRange(bookId: Long, startMillis: Long, endMillis: Long): Flow<List<TransactionEntity>>

    @Query("""
        SELECT * FROM transactions
        WHERE bookId = :bookId AND isDeleted = 0 AND categoryId = :categoryId
        ORDER BY occurredAt DESC
    """)
    fun getByCategory(bookId: Long, categoryId: Long): Flow<List<TransactionEntity>>

    /**
     * THE derived-balance query. No `balance` column exists anywhere in the schema.
     * INCOME into this account: +amount. EXPENSE from this account: -amount.
     * TRANSFER out (accountId = this account): -amount.
     * TRANSFER in (toAccountId = this account): +amount.
     * Any edit or delete to a transaction is reflected here automatically —
     * this Flow re-emits on every underlying table change.
     */
    @Query("""
        SELECT
            a.id AS accountId,
            a.name AS accountName,
            COALESCE(SUM(
                CASE
                    WHEN t.type = 'INCOME' AND t.accountId = a.id THEN t.amount
                    WHEN t.type = 'EXPENSE' AND t.accountId = a.id THEN -t.amount
                    WHEN t.type = 'TRANSFER' AND t.accountId = a.id THEN -t.amount
                    WHEN t.type = 'TRANSFER' AND t.toAccountId = a.id THEN t.amount
                    ELSE 0
                END
            ), 0) AS balance
        FROM accounts a
        LEFT JOIN transactions t
            ON (t.accountId = a.id OR t.toAccountId = a.id)
            AND t.isDeleted = 0
        WHERE a.bookId = :bookId AND a.isArchived = 0
        GROUP BY a.id
    """)
    fun getAccountBalances(bookId: Long): Flow<List<AccountBalance>>

    /** Used by both the Budget screen's progress bars and Analytics' category pie chart —
     *  same query, same Flow, so they can never drift out of sync with each other. */
    @Query("""
        SELECT categoryId, SUM(amount) as total
        FROM transactions
        WHERE bookId = :bookId AND type = 'EXPENSE' AND isDeleted = 0
        AND strftime('%Y-%m', occurredAt / 1000, 'unixepoch') = :month
        GROUP BY categoryId
    """)
    fun getCategoryTotalsForMonth(bookId: Long, month: String): Flow<List<CategoryTotal>>

    @Query("""
        SELECT COALESCE(SUM(amount), 0) FROM transactions
        WHERE bookId = :bookId AND type = 'EXPENSE' AND isDeleted = 0
        AND occurredAt BETWEEN :startOfDayMillis AND :endOfDayMillis
    """)
    fun getTodaySpend(bookId: Long, startOfDayMillis: Long, endOfDayMillis: Long): Flow<Double>

    @Query("""
        SELECT COALESCE(SUM(amount), 0) FROM transactions
        WHERE bookId = :bookId AND type = 'EXPENSE' AND isDeleted = 0
        AND strftime('%Y-%m', occurredAt / 1000, 'unixepoch') = :month
    """)
    fun getMonthSpend(bookId: Long, month: String): Flow<Double>
}
