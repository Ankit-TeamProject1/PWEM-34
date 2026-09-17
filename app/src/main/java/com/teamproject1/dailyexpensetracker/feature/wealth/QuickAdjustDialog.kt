package com.teamproject1.dailyexpensetracker.feature.wealth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/**
 * Shared "log an adjustment" UI — same calculator pattern as Cash in Hand
 * (current value shown, enter amount, Add/Subtract, running total, Save),
 * reused across NPS, APY, and Insurance/ULIP per the agreed mechanism.
 * Lighter-weight than opening full Edit just to bump a number up or down.
 */
@Composable
fun QuickAdjustDialog(
    currentValue: Double,
    label: String = "Adjust Value",
    onDismiss: () -> Unit,
    onSave: (newValue: Double) -> Unit
) {
    var runningTotal by remember { mutableStateOf(currentValue) }
    var entryText by remember { mutableStateOf("") }

    fun apply(sign: Int) {
        val value = entryText.toDoubleOrNull() ?: return
        runningTotal += sign * value
        entryText = ""
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(label) },
        text = {
            Column {
                Text("Running total: ₹%.2f".format(runningTotal), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = entryText,
                    onValueChange = { entryText = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Amount") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { apply(-1) }) { Text("− Subtract") }
                    OutlinedButton(onClick = { apply(1) }) { Text("+ Add") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(runningTotal) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
