package com.teamproject1.dailyexpensetracker.feature.wealth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.teamproject1.dailyexpensetracker.core.wealth.DueMutualFundSip
import com.teamproject1.dailyexpensetracker.core.wealth.MutualFundSipRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MutualFundSipViewModel @Inject constructor(
    private val repository: MutualFundSipRepository,
    private val snoozeRepository: com.teamproject1.dailyexpensetracker.core.wealth.SnoozeRepository
) : ViewModel() {
    private val _due = MutableStateFlow<List<DueMutualFundSip>>(emptyList())
    val due: StateFlow<List<DueMutualFundSip>> = _due

    fun check() {
        viewModelScope.launch { _due.value = repository.getDueSips() }
    }

    fun confirm(item: DueMutualFundSip) {
        viewModelScope.launch {
            repository.confirmSip(item)
            _due.value = _due.value.filter { it.account.id != item.account.id }
        }
    }

    fun hold(item: DueMutualFundSip) {
        viewModelScope.launch {
            repository.holdSip(item)
            _due.value = _due.value.filter { it.account.id != item.account.id }
        }
    }

    /** Per explicit request, skipping now suppresses this SPECIFIC
     *  reminder for about 5 hours, not just the in-memory queue — Hold
     *  above remains the separate, permanent pause. */
    fun dismiss(item: DueMutualFundSip) {
        viewModelScope.launch {
            snoozeRepository.snooze(com.teamproject1.dailyexpensetracker.core.wealth.RecurringTransactionType.MUTUAL_FUND_SIP.name, item.account.id)
            _due.value = _due.value.filter { it.account.id != item.account.id }
        }
    }
}

/**
 * Mutual Fund SIP due-date reminder — checked at Dashboard level, one
 * fund at a time, mirroring APY/EPF/RD's pattern. Unlike those, this
 * fetches today's NAV at confirm time (a SIP buys at the current NAV,
 * not a stale cached one) and offers "Hold" directly from the popup,
 * per the explicit "option to... hold sip" request.
 */
@Composable
fun MutualFundSipPopup(viewModel: MutualFundSipViewModel = hiltViewModel()) {
    val due by viewModel.due.collectAsState()
    val current = due.firstOrNull() ?: return

    AlertDialog(
        onDismissRequest = { },
        title = { Text("${current.account.schemeName} — SIP due") },
        text = {
            Column {
                Text(
                    "This fund's monthly SIP of ₹%.0f looks due. Confirming will invest at today's NAV.".format(current.sip.sipAmount),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        },
        confirmButton = {
            Button(onClick = { viewModel.confirm(current) }) { Text("Confirm SIP") }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { viewModel.hold(current) }) { Text("Hold SIP") }
                TextButton(onClick = { viewModel.dismiss(current) }) { Text("Skip") }
            }
        }
    )
}
