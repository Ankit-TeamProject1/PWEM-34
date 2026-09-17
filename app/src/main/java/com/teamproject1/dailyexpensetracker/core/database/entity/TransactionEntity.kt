package com.teamproject1.dailyexpensetracker.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class TransactionType { EXPENSE, INCOME, TRANSFER }

/**
 * The single ledger. Every money movement — expense, income, or transfer —
 * is one row here. Balances are NEVER stored; they are always derived by
 * summing this table (see TransactionDao.getAccountBalances).
 *
 * TRANSFER rows: accountId = source, toAccountId = destination, categoryId = null.
 * Transfers are excluded from all income/expense reports by `type` filtering.
 */
@Entity(
    tableName = "transactions",
    foreignKeys = [
        ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = AccountEntity::class, parentColumns = ["id"], childColumns = ["accountId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = AccountEntity::class, parentColumns = ["id"], childColumns = ["toAccountId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = CategoryEntity::class, parentColumns = ["id"], childColumns = ["categoryId"], onDelete = ForeignKey.RESTRICT)
    ],
    indices = [Index("bookId"), Index("accountId"), Index("occurredAt"), Index("categoryId")]
)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val type: TransactionType,
    val amount: Double,              // always positive; sign derived from type + account role
    val accountId: Long,             // source account
    val toAccountId: Long? = null,   // destination — TRANSFER only
    val categoryId: Long? = null,    // null for TRANSFER
    val note: String? = null,
    val occurredAt: Long,            // user-set date/time
    val createdAt: Long,
    val updatedAt: Long,
    val isDeleted: Boolean = false   // soft delete only
)
