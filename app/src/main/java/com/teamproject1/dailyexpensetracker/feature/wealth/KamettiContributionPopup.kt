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
import com.teamproject1.dailyexpensetracker.core.wealth.DueKamettiInstallment
import com.teamproject1.dailyexpensetracker.core.wealth.KamettiContributionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class KamettiContributionViewModel @Inject constructor(
    private val repository: KamettiContributionRepository,
    private val snoozeRepository: com.teamproject1.dailyexpensetracker.core.wealth.SnoozeRepository
) : ViewModel() {
    private val _due = MutableStateFlow<List<DueKamettiInstallment>>(emptyList())
    val due: StateFlow<List<DueKamettiInstallment>> = _due

    fun check() {
        viewModelScope.launch { _due.value = repository.getDueInstallments() }
    }

    fun confirm(item: DueKamettiInstallment, amount: Double) {
        viewModelScope.launch {
            repository.confirmInstallment(item, amount)
            _due.value = _due.value.filter { it.account.id != item.account.id }
        }
    }

    /** Per explicit request, skipping now suppresses this SPECIFIC
     *  reminder for about 5 hours, not just the in-memory queue. */
    fun dismiss(item: DueKamettiInstallment) {
        viewModelScope.launch {
            snoozeRepository.snooze(com.teamproject1.dailyexpensetracker.core.wealth.RecurringTransactionType.KAMETTI.name, item.account.id)
            _due.value = _due.value.filter { it.account.id != item.account.id }
        }
    }
}

@Composable
fun KamettiContributionPopup(viewModel: KamettiContributionViewModel = hiltViewModel()) {
    val due by viewModel.due.collectAsState()
    val current = due.firstOrNull() ?: return
    val dateFormat = remember { SimpleDateFormat("MMMM yyyy", Locale.US) }

    var amountText by remember(current.account.id) {
        mutableStateOf(current.suggestedAmount.let { if (it == it.toLong().toDouble()) it.toLong().toString() else it.toString() })
    }

    AlertDialog(
        onDismissRequest = { },
        title = { Text("${current.account.name} — deposit due") },
        text = {
            Column {
                Text(
                    "The ${dateFormat.format(Date(current.dueDate))} deposit hasn't been recorded yet. Confirm the amount to log it.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Deposit amount") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                val amount = amountText.toDoubleOrNull() ?: return@Button
                viewModel.confirm(current, amount)
            }) { Text("Confirm Deposit") }
        },
        dismissButton = {
            TextButton(onClick = { viewModel.dismiss(current) }) { Text("Skip for Now") }
        }
    )
}
