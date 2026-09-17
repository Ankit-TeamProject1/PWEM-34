package com.teamproject1.dailyexpensetracker.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A "book" is a fully isolated ledger — its own accounts, categories,
 * budgets, currency, and transactions. Nothing crosses between books.
 * bookType determines which Dashboard and data tables apply — Expense
 * books use accounts/categories/transactions/budgets; Wealth books use
 * the wealth-specific tables (added in a later shell).
 */
enum class BookType { EXPENSE, WEALTH }

@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val bookType: BookType = BookType.EXPENSE,
    val currencyCode: String,        // ISO 4217, e.g. "INR", "USD"
    val iconColor: String,           // hex string, used on Book Selector card
    val isArchived: Boolean = false,
    val createdAt: Long,
    val lastOpenedAt: Long
)

enum class AccountType { BANK, CASH, CREDIT_CARD, PLUXEE }

@Entity(
    tableName = "accounts",
    foreignKeys = [ForeignKey(
        entity = BookEntity::class,
        parentColumns = ["id"], childColumns = ["bookId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("bookId")]
)
data class AccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val name: String,
    val type: AccountType,
    val isDefault: Boolean = false,
    val isArchived: Boolean = false,
    val createdAt: Long
)

@Entity(
    tableName = "categories",
    foreignKeys = [ForeignKey(
        entity = BookEntity::class,
        parentColumns = ["id"], childColumns = ["bookId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("bookId")]
)
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val name: String,
    val icon: String,
    val colorHex: String = "#2E5E4E", // item 38 — custom category colors, defaults to app accent
    val isArchived: Boolean = false
)

/**
 * Expense-side Account Receivables — an IOU/shared-expense tracker, not a
 * standalone asset category like Wealth's version. Flags an EXISTING
 * Expense transaction as "partly or fully owed back to me" (e.g. you paid
 * for dinner, a friend owes you their share). Linked to the originating
 * transaction via transactionId (SET_NULL rather than CASCADE, since
 * transactions are only ever soft-deleted, not hard-deleted — this is a
 * defensive fallback, not the expected path).
 */
@Entity(
    tableName = "expense_receivables",
    foreignKeys = [
        ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = TransactionEntity::class, parentColumns = ["id"], childColumns = ["transactionId"], onDelete = ForeignKey.SET_NULL)
    ],
    indices = [Index("bookId"), Index("transactionId")]
)
data class ExpenseReceivableEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val transactionId: Long?,
    val personName: String,
    val amount: Double,
    val note: String? = null,
    val isCollected: Boolean = false,
    val createdAt: Long,
    val collectedAt: Long? = null
)
