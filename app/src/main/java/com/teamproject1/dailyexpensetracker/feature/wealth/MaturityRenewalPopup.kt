package com.teamproject1.dailyexpensetracker.feature.wealth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import com.teamproject1.dailyexpensetracker.core.wealth.FdRdAutoRenewalRepository
import com.teamproject1.dailyexpensetracker.core.wealth.MaturedDeposit
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MaturityRenewalViewModel @Inject constructor(
    private val repository: FdRdAutoRenewalRepository
) : ViewModel() {
    private val _matured = MutableStateFlow<List<MaturedDeposit>>(emptyList())
    val matured: StateFlow<List<MaturedDeposit>> = _matured

    fun check() {
        viewModelScope.launch { _matured.value = repository.getMaturedDeposits() }
    }

    fun suggestedPrincipal(m: MaturedDeposit) = repository.suggestedPrincipal(m)

    fun confirm(m: MaturedDeposit, principal: Double, tenure: Int, rate: Double, startDate: Long) {
        viewModelScope.launch {
            repository.confirmRenewal(m, principal, tenure, rate, startDate)
            _matured.value = _matured.value.filter { it.id != m.id || it::class != m::class }
        }
    }

    fun decline(m: MaturedDeposit) {
        viewModelScope.launch {
            repository.declineRenewal(m)
            _matured.value = _matured.value.filter { it.id != m.id || it::class != m::class }
        }
    }
}

/**
 * One matured deposit at a time, per the confirmation-based design — shows
 * the proposed renewal terms (pre-filled based on Principal Only/Principal
 * + Interest, but fully editable) and requires an explicit Confirm or
 * Decline before anything happens. Checked on every Wealth Dashboard load,
 * same pattern as the Recurring due-reminder popup.
 */
@Composable
fun MaturityRenewalPopup(viewModel: MaturityRenewalViewModel = hiltViewModel()) {
    val matured by viewModel.matured.collectAsState()
    val current = matured.firstOrNull() ?: return

    var principalText by remember(current.id) { mutableStateOf(viewModel.suggestedPrincipal(current).let { if (it == it.toLong().toDouble()) it.toLong().toString() else it.toString() }) }
    var tenureText by remember(current.id) {
        mutableStateOf(
            when (current) {
                is MaturedDeposit.Fd -> current.entity.tenureMonths.toString()
                is MaturedDeposit.Rd -> current.entity.tenureMonths.toString()
            }
        )
    }
    var rateText by remember(current.id) {
        mutableStateOf(
            when (current) {
                is MaturedDeposit.Fd -> current.entity.interestRate.toString()
                is MaturedDeposit.Rd -> current.entity.interestRate.toString()
            }
        )
    }

    AlertDialog(
        onDismissRequest = { }, // must explicitly Confirm or Decline — no accidental dismiss
        title = { Text("${current.name} has matured") },
        text = {
            Column {
                Text(
                    "Maturity value: ₹%.2f".format(current.maturityValue),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))
                Text("Review and confirm the renewal terms below:", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = principalText,
                    onValueChange = { principalText = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text(if (current is MaturedDeposit.Rd) "Monthly installment" else "Principal") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = rateText,
                        onValueChange = { rateText = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text("Rate %") },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = tenureText,
                        onValueChange = { tenureText = it.filter { c -> c.isDigit() } },
                        label = { Text("Tenure (months)") },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val principal = principalText.toDoubleOrNull() ?: return@Button
                val tenure = tenureText.toIntOrNull() ?: return@Button
                val rate = rateText.toDoubleOrNull() ?: return@Button
                viewModel.confirm(current, principal, tenure, rate, System.currentTimeMillis())
            }) { Text("Confirm Renewal") }
        },
        dismissButton = {
            TextButton(onClick = { viewModel.decline(current) }) { Text("Don't Renew") }
        }
    )
}
