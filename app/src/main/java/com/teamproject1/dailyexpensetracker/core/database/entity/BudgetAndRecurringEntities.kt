package com.teamproject1.dailyexpensetracker.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "budgets",
    foreignKeys = [
        ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = CategoryEntity::class, parentColumns = ["id"], childColumns = ["categoryId"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("bookId"), Index(value = ["categoryId", "month"], unique = true)]
)
data class BudgetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val categoryId: Long,
    val month: String,               // "2026-08"
    val capAmount: Double
)

enum class RecurrenceFrequency { DAILY, WEEKLY, MONTHLY, YEARLY }

/**
 * A schedule that a WorkManager job materializes into real `transactions` rows.
 * Reports never query this table directly — only the transactions it produces.
 *
 * Integrity rule: archiving a category/account referenced by an active
 * (non-paused) rule is BLOCKED at the repository layer — see
 * RecurringRuleRepository.checkArchiveBlockers().
 */
@Entity(
    tableName = "recurring_rules",
    foreignKeys = [ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("bookId"), Index("nextRunAt"), Index("accountId"), Index("categoryId")]
)
data class RecurringRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val name: String,                // e.g. "Netflix", "Rent"
    val type: TransactionType,
    val amount: Double,
    val accountId: Long,
    val toAccountId: Long? = null,
    val categoryId: Long? = null,
    val note: String? = null,
    val frequency: RecurrenceFrequency,
    val interval: Int = 1,
    val startDate: Long,
    val endDate: Long? = null,       // null = "Never"
    val nextRunAt: Long,
    val isPaused: Boolean = false
)
