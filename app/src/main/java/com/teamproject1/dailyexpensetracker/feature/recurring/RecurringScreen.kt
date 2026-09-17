package com.teamproject1.dailyexpensetracker.feature.recurring

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.teamproject1.dailyexpensetracker.core.database.dao.RecurringRuleDao
import com.teamproject1.dailyexpensetracker.core.database.entity.RecurringRuleEntity
import com.teamproject1.dailyexpensetracker.core.session.SessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import androidx.compose.material.icons.automirrored.filled.ArrowBack

@HiltViewModel
class RecurringViewModel @Inject constructor(
    private val recurringRuleDao: RecurringRuleDao,
    session: SessionManager
) : ViewModel() {

    val rules = session.activeBookId.filterNotNull()
        .flatMapLatest { bookId -> recurringRuleDao.getAll(bookId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun togglePaused(rule: RecurringRuleEntity) {
        viewModelScope.launch { recurringRuleDao.setPaused(rule.id, !rule.isPaused) }
    }

    fun deleteRule(rule: RecurringRuleEntity) {
        viewModelScope.launch { recurringRuleDao.delete(rule.id) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecurringScreen(
    onBack: () -> Unit,
    onNewRule: () -> Unit,
    onEditRule: (Long) -> Unit,
    viewModel: RecurringViewModel = hiltViewModel()
) {
    val rules by viewModel.rules.collectAsState()
    val dateFormat = remember { SimpleDateFormat("MMM d", Locale.US) }
    var pendingDelete by remember { mutableStateOf<RecurringRuleEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Recurring Rules") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } }
            )
        },
        floatingActionButton = {
            // Context-aware FAB per the locked decision — opens New Rule here,
            // not Add Expense, since a floating "+" opening the wrong sheet
            // on this specific screen would be a real usability trap.
            FloatingActionButton(onClick = onNewRule) {
                Icon(Icons.Default.Add, contentDescription = "New Rule")
            }
        }
    ) { padding ->
        if (rules.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = androidx.compose.ui.Alignment.Center) {
                Text("No recurring rules yet.", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(rules) { rule ->
                    RuleRow(
                        rule = rule,
                        dateFormat = dateFormat,
                        onToggle = { viewModel.togglePaused(rule) },
                        onEdit = { onEditRule(rule.id) },
                        onDeleteRequest = { pendingDelete = rule }
                    )
                }
            }
        }
    }

    pendingDelete?.let { rule ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete \"${rule.name}\"?") },
            text = { Text("This can't be undone. Transactions this rule already created won't be affected.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteRule(rule)
                    pendingDelete = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun RuleRow(rule: RecurringRuleEntity, dateFormat: SimpleDateFormat, onToggle: () -> Unit, onEdit: () -> Unit, onDeleteRequest: () -> Unit) {
    val rowAlpha = if (rule.isPaused) 0.5f else 1f
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(rowAlpha)
            .clickable(onClick = onEdit)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(rule.name, style = MaterialTheme.typography.titleLarge)
                Text(
                    "₹%.0f · %s · Next: %s".format(rule.amount, rule.frequency.name.lowercase().replaceFirstChar { it.uppercase() }, dateFormat.format(Date(rule.nextRunAt))),
                    style = MaterialTheme.typography.bodyMedium,
                    color = com.teamproject1.dailyexpensetracker.ui.theme.colorForTransactionType(rule.type)
                )
            }
            // Switch and delete icon each consume their own clicks — tapping
            // either doesn't also trigger the row's onEdit, since they're
            // distinct touch targets from the row's clickable area.
            IconButton(onClick = onDeleteRequest) {
                Icon(Icons.Default.Delete, contentDescription = "Delete rule", tint = MaterialTheme.colorScheme.error)
            }
            Switch(checked = !rule.isPaused, onCheckedChange = { onToggle() })
        }
    }
}
