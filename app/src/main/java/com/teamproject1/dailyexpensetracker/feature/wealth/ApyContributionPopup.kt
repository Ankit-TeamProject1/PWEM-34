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
import com.teamproject1.dailyexpensetracker.core.wealth.ApyContributionRepository
import com.teamproject1.dailyexpensetracker.core.wealth.DueApyContribution
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ApyContributionViewModel @Inject constructor(
    private val repository: ApyContributionRepository,
    private val snoozeRepository: com.teamproject1.dailyexpensetracker.core.wealth.SnoozeRepository
) : ViewModel() {
    private val _due = MutableStateFlow<List<DueApyContribution>>(emptyList())
    val due: StateFlow<List<DueApyContribution>> = _due

    fun check() {
        viewModelScope.launch { _due.value = repository.getDueContributions() }
    }

    fun confirm(item: DueApyContribution, amount: Double) {
        viewModelScope.launch {
            repository.confirmContribution(item, amount)
            _due.value = _due.value.filter { it.account.id != item.account.id }
        }
    }

    /** Per explicit request, skipping now suppresses this SPECIFIC
     *  reminder for about 5 hours — previously this only cleared the
     *  in-memory popup queue, so the exact same reminder could reappear
     *  seconds later just by revisiting the Dashboard. */
    fun dismiss(item: DueApyContribution) {
        viewModelScope.launch {
            snoozeRepository.snooze(com.teamproject1.dailyexpensetracker.core.wealth.RecurringTransactionType.APY.name, item.account.id)
            _due.value = _due.value.filter { it.account.id != item.account.id }
        }
    }
}

/**
 * APY contribution due-date reminder — checked at Dashboard level, one
 * account at a time, per the confirmed design. Confirming updates the
 * account's running total directly; APY deliberately has no entry ledger
 * (unlike RD/PPF), per explicit request to keep this simpler.
 */
@Composable
fun ApyContributionPopup(viewModel: ApyContributionViewModel = hiltViewModel()) {
    val due by viewModel.due.collectAsState()
    val current = due.firstOrNull() ?: return

    var amountText by remember(current.account.id) {
        mutableStateOf(current.account.monthlyContribution.let { if (it == it.toLong().toDouble()) it.toLong().toString() else it.toString() })
    }

    AlertDialog(
        onDismissRequest = { }, // must explicitly Confirm or Skip — no accidental dismiss
        title = { Text("${current.account.name} — contribution due") },
        text = {
            Column {
                Text(
                    "This APY account's monthly contribution is due. Confirm the amount to add it to your total contributed so far.",
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
