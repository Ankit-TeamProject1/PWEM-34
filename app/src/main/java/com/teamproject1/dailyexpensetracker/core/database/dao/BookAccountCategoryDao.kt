package com.teamproject1.dailyexpensetracker.core.database.dao

import androidx.room.*
import com.teamproject1.dailyexpensetracker.core.database.entity.AccountEntity
import com.teamproject1.dailyexpensetracker.core.database.entity.BookEntity
import com.teamproject1.dailyexpensetracker.core.database.entity.CategoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {

    @Insert
    suspend fun insert(book: BookEntity): Long

    @Update
    suspend fun update(book: BookEntity)

    @Query("SELECT * FROM books WHERE isArchived = 0 ORDER BY lastOpenedAt DESC")
    fun getActiveBooks(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE isArchived = 1 ORDER BY name ASC")
    fun getArchivedBooks(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE id = :bookId")
    suspend fun getById(bookId: Long): BookEntity?

    @Query("SELECT COUNT(*) FROM books WHERE isArchived = 0")
    fun getActiveBookCount(): Flow<Int>

    @Query("UPDATE books SET lastOpenedAt = :now WHERE id = :bookId")
    suspend fun touchLastOpened(bookId: Long, now: Long)

    @Query("UPDATE books SET isArchived = 1 WHERE id = :bookId")
    suspend fun archive(bookId: Long)

    @Query("UPDATE books SET isArchived = 0 WHERE id = :bookId")
    suspend fun unarchive(bookId: Long)

    /** Hard delete — only reachable via explicit "Empty Trash" flow, never accidental. */
    @Query("DELETE FROM books WHERE id = :bookId")
    suspend fun hardDelete(bookId: Long)
}

@Dao
interface AccountDao {

    @Insert
    suspend fun insert(account: AccountEntity): Long

    @Update
    suspend fun update(account: AccountEntity)

    @Query("SELECT * FROM accounts WHERE bookId = :bookId AND isArchived = 0 ORDER BY isDefault DESC, name ASC")
    fun getActiveAccounts(bookId: Long): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts WHERE bookId = :bookId AND isArchived = 1 ORDER BY name ASC")
    fun getArchivedAccounts(bookId: Long): Flow<List<AccountEntity>>

    @Query("SELECT COUNT(*) FROM accounts WHERE bookId = :bookId AND isArchived = 0")
    fun getActiveAccountCount(bookId: Long): Flow<Int>

    @Query("""
        SELECT EXISTS(
            SELECT 1 FROM recurring_rules
            WHERE (accountId = :accountId OR toAccountId = :accountId)
            AND isPaused = 0
        )
    """)
    suspend fun hasActiveRecurringRules(accountId: Long): Boolean

    @Query("UPDATE accounts SET isArchived = 1 WHERE id = :accountId")
    suspend fun archive(accountId: Long)

    @Query("UPDATE accounts SET isArchived = 0 WHERE id = :accountId")
    suspend fun unarchive(accountId: Long)
}

@Dao
interface CategoryDao {

    @Insert
    suspend fun insert(category: CategoryEntity): Long

    @Update
    suspend fun update(category: CategoryEntity)

    @Query("SELECT * FROM categories WHERE bookId = :bookId AND isArchived = 0 ORDER BY name ASC")
    fun getActiveCategories(bookId: Long): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE bookId = :bookId AND isArchived = 1 ORDER BY name ASC")
    fun getArchivedCategories(bookId: Long): Flow<List<CategoryEntity>>

    @Query("""
        SELECT EXISTS(
            SELECT 1 FROM recurring_rules
            WHERE categoryId = :categoryId AND isPaused = 0
        )
    """)
    suspend fun hasActiveRecurringRules(categoryId: Long): Boolean

    @Query("UPDATE categories SET isArchived = 1 WHERE id = :categoryId")
    suspend fun archive(categoryId: Long)

    @Query("UPDATE categories SET isArchived = 0 WHERE id = :categoryId")
    suspend fun unarchive(categoryId: Long)
}
