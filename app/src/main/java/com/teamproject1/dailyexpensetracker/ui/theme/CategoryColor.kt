package com.teamproject1.dailyexpensetracker.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Parses a category's stored colorHex string into a Compose Color, with a
 * safe fallback if the string is malformed for any reason. Shared utility
 * so both Manage Categories and Add Expense (where the color now shows on
 * selected category chips, per item 38's propagation) use the same logic.
 */
fun parseCategoryColor(hex: String): Color = try {
    Color(android.graphics.Color.parseColor(hex))
} catch (e: Exception) {
    Color(0xFF2E5E4E)
}
