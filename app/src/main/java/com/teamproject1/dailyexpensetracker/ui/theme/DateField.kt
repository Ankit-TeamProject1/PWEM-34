package com.teamproject1.dailyexpensetracker.ui.theme

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tappable date field — opens a DatePickerDialog on tap. Defaults to
 * whatever `selectedMillis` is passed in (typically "now"), but the user
 * can change it. Used by Add Expense/Income, Transfer, and New Recurring
 * Rule's start date, closing the date gaps flagged for all three.
 *
 * Wrapped in a clickable Box rather than relying on the text field's own
 * click handling, since a read-only OutlinedTextField's tap behavior isn't
 * consistently reliable for opening a dialog across Compose versions —
 * this pattern guarantees the tap always registers.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(
    label: String,
    selectedMillis: Long,
    onDateSelected: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    var showPicker by remember { mutableStateOf(false) }
    val formatter = remember { SimpleDateFormat("EEE, MMM d, yyyy", Locale.getDefault()) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clickable { showPicker = true }
    ) {
        OutlinedTextField(
            value = formatter.format(Date(selectedMillis)),
            onValueChange = {},
            readOnly = true,
            enabled = false,
            label = { Text(label) },
            trailingIcon = { Text("📅") },
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                disabledTextColor = androidx.compose.material3.MaterialTheme.colorScheme.onSurface,
                disabledBorderColor = androidx.compose.material3.MaterialTheme.colorScheme.outline,
                disabledLabelColor = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                disabledTrailingIconColor = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
            ),
            modifier = Modifier.fillMaxWidth()
        )
    }

    if (showPicker) {
        val state = rememberDatePickerState(initialSelectedDateMillis = selectedMillis)
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onDateSelected(it) }
                    showPicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) { Text("Cancel") }
            }
        ) {
            DatePicker(state = state)
        }
    }
}
