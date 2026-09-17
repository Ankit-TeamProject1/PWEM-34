package com.teamproject1.dailyexpensetracker.feature.wealth

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.teamproject1.dailyexpensetracker.core.database.entity.EpfTileType
import com.teamproject1.dailyexpensetracker.core.wealth.DueEpfContribution
import com.teamproject1.dailyexpensetracker.core.wealth.EpfContributionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class EpfContributionViewModel @Inject constructor(
    private val repository: EpfContributionRepository,
    private val snoozeRepository: com.teamproject1.dailyexpensetracker.core.wealth.SnoozeRepository
) : ViewModel() {
    private val _due = MutableStateFlow<List<DueEpfContribution>>(emptyList())
    val due: StateFlow<List<DueEpfContribution>> = _due

    fun check() {
        viewModelScope.launch { _due.value = repository.getDueContributions() }
    }

    // Filters by account id AND tileType — an account can have TWO
    // separate due items (one per tile) at once, and confirming or
    // skipping one must not silently remove the other.
    fun confirm(item: DueEpfContribution, amount: Double) {
        viewModelScope.launch {
            repository.confirmContribution(item, amount)
            _due.value = _due.value.filter { !(it.account.id == item.account.id && it.tileType == item.tileType) }
        }
    }

    /** Per explicit request, skipping now suppresses this SPECIFIC
     *  tile's reminder for about 5 hours, not just the in-memory queue —
     *  matches the same fix applied to APY/RD/Kametti/Mutual Fund SIP. */
    fun dismiss(item: DueEpfContribution) {
        viewModelScope.launch {
            val reminderType = if (item.tileType == com.teamproject1.dailyexpensetracker.core.database.entity.EpfTileType.EMPLOYEE_EMPLOYER) {
                com.teamproject1.dailyexpensetracker.core.wealth.RecurringTransactionType.EPF_EMPLOYEE_EMPLOYER.name
            } else {
                com.teamproject1.dailyexpensetracker.core.wealth.RecurringTransactionType.EPF_PENSION.name
            }
            snoozeRepository.snooze(reminderType, item.account.id)
            _due.value = _due.value.filter { !(it.account.id == item.account.id && it.tileType == item.tileType) }
        }
    }
}

/**
 * EPF contribution due-date reminder — checked at Dashboard level, one
 * item at a time, for BOTH Employee+Employer and Pension tiles
 * separately, per explicit request.
 */
@Composable
fun EpfContributionPopup(viewModel: EpfContributionViewModel = hiltViewModel()) {
    val due by viewModel.due.collectAsState()
    val current = due.firstOrNull() ?: return
    val dateFormat = remember { SimpleDateFormat("MMMM yyyy", Locale.US) }

    var amountText by remember(current.account.id, current.tileType) {
        mutableStateOf(current.suggestedAmount.let { if (it == it.toLong().toDouble()) it.toLong().toString() else it.toString() })
    }

    val tileLabel = if (current.tileType == EpfTileType.EMPLOYEE_EMPLOYER) "Employee+Employer" else "Pension"

    AlertDialog(
        onDismissRequest = { },
        title = { Text("${current.account.name} — $tileLabel due") },
        text = {
            Column {
                Text(
                    "The ${dateFormat.format(Date(current.dueDate))} $tileLabel contribution hasn't been recorded yet. Confirm the amount to log it.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Contribution amount") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                val amount = amountText.toDoubleOrNull() ?: return@Button
                viewModel.confirm(current, amount)
            }) { Text("Confirm Contribution") }
        },
        dismissButton = {
            TextButton(onClick = { viewModel.dismiss(current) }) { Text("Skip for Now") }
        }
    )
}
