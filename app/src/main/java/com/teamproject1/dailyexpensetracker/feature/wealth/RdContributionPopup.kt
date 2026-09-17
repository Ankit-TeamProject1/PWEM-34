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
import com.teamproject1.dailyexpensetracker.core.wealth.DueRdInstallment
import com.teamproject1.dailyexpensetracker.core.wealth.RdContributionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class RdContributionViewModel @Inject constructor(
    private val repository: RdContributionRepository,
    private val snoozeRepository: com.teamproject1.dailyexpensetracker.core.wealth.SnoozeRepository
) : ViewModel() {
    private val _due = MutableStateFlow<List<DueRdInstallment>>(emptyList())
    val due: StateFlow<List<DueRdInstallment>> = _due

    fun check() {
        viewModelScope.launch { _due.value = repository.getDueInstallments() }
    }

    fun confirm(item: DueRdInstallment, amount: Double) {
        viewModelScope.launch {
            repository.confirmInstallment(item, amount)
            _due.value = _due.value.filter { it.account.id != item.account.id }
        }
    }

    /** Per explicit request, skipping now suppresses this SPECIFIC
     *  reminder for about 5 hours, not just the in-memory queue. */
    fun dismiss(item: DueRdInstallment) {
        viewModelScope.launch {
            snoozeRepository.snooze(com.teamproject1.dailyexpensetracker.core.wealth.RecurringTransactionType.RD.name, item.account.id)
            _due.value = _due.value.filter { it.account.id != item.account.id }
        }
    }
}

/**
 * RD installment due-date reminder — checked at Dashboard level, one
 * account at a time, mirroring APY/EPF's pattern exactly, per explicit
 * request.
 */
@Composable
fun RdContributionPopup(viewModel: RdContributionViewModel = hiltViewModel()) {
    val due by viewModel.due.collectAsState()
    val current = due.firstOrNull() ?: return

    var amountText by remember(current.account.id) {
        mutableStateOf(current.suggestedAmount.let { if (it == it.toLong().toDouble()) it.toLong().toString() else it.toString() })
    }
    val dateFormat = remember { SimpleDateFormat("MMMM yyyy", Locale.US) }

    AlertDialog(
        onDismissRequest = { },
        title = { Text("${current.account.name} — installment due") },
        text = {
            Column {
                Text(
                    "The ${dateFormat.format(Date(current.dueDate))} installment hasn't been recorded yet. Confirm the amount to log it.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Installment amount") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                val amount = amountText.toDoubleOrNull() ?: return@Button
                viewModel.confirm(current, amount)
            }) { Text("Confirm Installment") }
        },
        dismissButton = {
            TextButton(onClick = { viewModel.dismiss(current) }) { Text("Skip for Now") }
        }
    )
}
