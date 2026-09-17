package com.teamproject1.dailyexpensetracker.core.database.dao

import androidx.room.*
import com.teamproject1.dailyexpensetracker.core.database.entity.BudgetEntity
import com.teamproject1.dailyexpensetracker.core.database.entity.RecurringRuleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BudgetDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(budget: BudgetEntity)

    @Query("SELECT * FROM budgets WHERE bookId = :bookId AND month = :month")
    fun getForMonth(bookId: Long, month: String): Flow<List<BudgetEntity>>
}

@Dao
interface RecurringRuleDao {

    @Insert
    suspend fun insert(rule: RecurringRuleEntity): Long

    @Update
    suspend fun update(rule: RecurringRuleEntity)

    @Query("SELECT * FROM recurring_rules WHERE bookId = :bookId ORDER BY isPaused ASC, nextRunAt ASC")
    fun getAll(bookId: Long): Flow<List<RecurringRuleEntity>>

    @Query("UPDATE recurring_rules SET isPaused = :paused WHERE id = :ruleId")
    suspend fun setPaused(ruleId: Long, paused: Boolean)

    /** Rules are just templates — the transactions they've already created
     *  are independent rows, so a hard delete here is safe and doesn't
     *  touch any transaction history. */
    @Query("DELETE FROM recurring_rules WHERE id = :ruleId")
    suspend fun delete(ruleId: Long)

    /** Used by the daily WorkManager job to find rules due for materialization. */
    @Query("""
        SELECT * FROM recurring_rules
        WHERE isPaused = 0 AND nextRunAt <= :nowMillis
        AND (endDate IS NULL OR endDate >= :nowMillis)
    """)
    suspend fun getDueRules(nowMillis: Long): List<RecurringRuleEntity>

    /** Book-scoped version for the in-app due-reminder popup (locked design:
     *  no silent background posting — the user confirms each due item). */
    @Query("""
        SELECT * FROM recurring_rules
        WHERE bookId = :bookId AND isPaused = 0 AND nextRunAt <= :nowMillis
        AND (endDate IS NULL OR endDate >= :nowMillis)
        ORDER BY nextRunAt ASC
    """)
    suspend fun getDueRulesForBook(bookId: Long, nowMillis: Long): List<RecurringRuleEntity>

    /** Used by Dashboard's "Upcoming" section — rules due within a window,
     *  regardless of whether they're technically overdue yet. */
    @Query("""
        SELECT * FROM recurring_rules
        WHERE bookId = :bookId AND isPaused = 0
        ORDER BY nextRunAt ASC
        LIMIT :limit
    """)
    fun getUpcoming(bookId: Long, limit: Int = 3): Flow<List<RecurringRuleEntity>>

    @Query("UPDATE recurring_rules SET nextRunAt = :nextRunAt WHERE id = :ruleId")
    suspend fun advanceNextRun(ruleId: Long, nextRunAt: Long)

    @Query("SELECT * FROM recurring_rules WHERE (accountId = :accountOrCategoryId OR categoryId = :accountOrCategoryId OR toAccountId = :accountOrCategoryId) AND isPaused = 0")
    suspend fun getActiveRulesReferencing(accountOrCategoryId: Long): List<RecurringRuleEntity>
}
