package com.teamproject1.dailyexpensetracker.feature.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.teamproject1.dailyexpensetracker.core.database.RecurringDueRepository
import com.teamproject1.dailyexpensetracker.core.database.entity.RecurringRuleEntity
import com.teamproject1.dailyexpensetracker.ui.theme.colorForTransactionType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class DueReminderViewModel @Inject constructor(
    private val repository: RecurringDueRepository
) : ViewModel() {

    private val _dueRules = MutableStateFlow<List<RecurringRuleEntity>>(emptyList())
    val dueRules: StateFlow<List<RecurringRuleEntity>> = _dueRules

    /** Called on Dashboard load — per the locked design, this check happens
     *  every time the app opens, not just once, so anything not yet
     *  recorded naturally keeps reappearing. */
    fun checkDueRules() {
        viewModelScope.launch {
            _dueRules.value = repository.getDueRules()
        }
    }

    fun recordOne(rule: RecurringRuleEntity) {
        viewModelScope.launch {
            repository.recordDue(rule)
            _dueRules.value = _dueRules.value.filter { it.id != rule.id }
        }
    }

    fun recordAll() {
        viewModelScope.launch {
            repository.recordAllDue(_dueRules.value)
            _dueRules.value = emptyList()
        }
    }

    fun dismiss() {
        // Per the locked decision, dismissing doesn't clear anything
        // persistently — the same items will show again next time
        // checkDueRules() runs (i.e. next app open), since nothing here
        // advances a rule's schedule except actually recording it.
        _dueRules.value = emptyList()
    }
}

@Composable
fun DueReminderPopup(viewModel: DueReminderViewModel = hiltViewModel()) {
    val dueRules by viewModel.dueRules.collectAsState()
    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy", Locale.US) }

    if (dueRules.isEmpty()) return

    AlertDialog(
        onDismissRequest = { viewModel.dismiss() },
        title = { Text("Recurring items due") },
        text = {
            Column {
                Text(
                    "These are due — record them now, or come back to this later (they'll show again next time you open the app).",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn {
                    items(dueRules) { rule ->
                        DueRuleRow(
                            rule = rule,
                            dateFormat = dateFormat,
                            onRecord = { viewModel.recordOne(rule) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { viewModel.recordAll() }) { Text("Record All") }
        },
        dismissButton = {
            TextButton(onClick = { viewModel.dismiss() }) { Text("Later") }
        }
    )
}

@Composable
private fun DueRuleRow(rule: RecurringRuleEntity, dateFormat: SimpleDateFormat, onRecord: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Text(rule.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                "₹%.0f · Due %s".format(rule.amount, dateFormat.format(Date(rule.nextRunAt))),
                style = MaterialTheme.typography.bodySmall,
                color = colorForTransactionType(rule.type)
            )
        }
        TextButton(onClick = onRecord) { Text("Record") }
    }
}
