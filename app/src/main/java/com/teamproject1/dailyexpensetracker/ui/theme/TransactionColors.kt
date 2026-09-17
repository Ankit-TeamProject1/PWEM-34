package com.teamproject1.dailyexpensetracker.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.teamproject1.dailyexpensetracker.core.database.entity.TransactionType

/**
 * Single source of truth for transaction-type coloring, used everywhere a
 * transaction or recurring rule's type is displayed (Accounts, Expenses,
 * Recurring, the due-reminder popup). Fixes a real gap: ExpenseRed and
 * IncomeGreen were defined in Color.kt from the start but never actually
 * referenced anywhere — screens were using generic MaterialTheme role
 * colors (primary/error/onSurfaceVariant) instead, which don't reliably
 * read as distinct depending on the active theme.
 */
@Composable
fun colorForTransactionType(type: TransactionType): Color = when (type) {
    TransactionType.EXPENSE -> ExpenseRed
    TransactionType.INCOME -> IncomeGreen
    TransactionType.TRANSFER -> TransferBlue
}

fun signForTransactionType(type: TransactionType): String = when (type) {
    TransactionType.EXPENSE -> "-"
    TransactionType.INCOME -> "+"
    TransactionType.TRANSFER -> "⇄"
}
