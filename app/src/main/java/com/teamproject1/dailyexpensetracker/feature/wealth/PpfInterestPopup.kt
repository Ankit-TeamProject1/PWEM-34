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
import com.teamproject1.dailyexpensetracker.core.wealth.DuePpfInterest
import com.teamproject1.dailyexpensetracker.core.wealth.PpfInterestRepository
import com.teamproject1.dailyexpensetracker.core.wealth.SnoozeRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PpfInterestViewModel @Inject constructor(
    private val repository: PpfInterestRepository,
    private val snoozeRepository: SnoozeRepository
) : ViewModel() {
    private val _due = MutableStateFlow<List<DuePpfInterest>>(emptyList())
    val due: StateFlow<List<DuePpfInterest>> = _due

    fun check() {
        viewModelScope.launch { _due.value = repository.getDueInterestConfirmations() }
    }

    fun confirm(item: DuePpfInterest, amount: Double) {
        viewModelScope.launch {
            repository.confirmInterest(item, amount)
            _due.value = _due.value.filter { it.account.id != item.account.id }
        }
    }

    /** Per the same 5-hour snooze applied to APY/EPF/RD/Kametti/Mutual
     *  Fund SIP — "Not Now" suppresses this specific reminder for about
     *  5 hours rather than reappearing on the very next Dashboard visit. */
    fun dismiss(item: DuePpfInterest) {
        viewModelScope.launch {
            snoozeRepository.snooze("PPF_INTEREST", item.account.id)
            _due.value = _due.value.filter { it.account.id != item.account.id }
        }
    }
}

/**
 * PPF interest confirmation — checked at Dashboard level, per explicit
 * request, matching APY/EPF/RD/Kametti/Mutual Fund SIP's pattern rather
 * than only checking when that specific account's own detail screen is
 * opened.
 */
@Composable
fun PpfInterestPopup(viewModel: PpfInterestViewModel = hiltViewModel()) {
    val due by viewModel.due.collectAsState()
    val current = due.firstOrNull() ?: return

    var amountText by remember(current.account.id) {
        mutableStateOf(current.suggestedAmount.let { "%.2f".format(it) })
    }

    AlertDialog(
        onDismissRequest = { },
        title = { Text("${current.account.name} — record ${current.fyLabel} interest?") },
        text = {
            Column {
                Text(
                    "Based on your deposits and the applicable PPF rate, this account earned approximately ₹%.2f in interest for ${current.fyLabel}. Review the amount and confirm to record it — nothing is added automatically.".format(current.suggestedAmount)
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Interest amount") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                val amount = amountText.toDoubleOrNull() ?: return@Button
                viewModel.confirm(current, amount)
            }) { Text("Record Interest") }
        },
        dismissButton = {
            TextButton(onClick = { viewModel.dismiss(current) }) { Text("Not Now") }
        }
    )
}
