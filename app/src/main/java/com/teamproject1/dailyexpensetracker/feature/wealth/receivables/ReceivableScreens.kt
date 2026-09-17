package com.teamproject1.dailyexpensetracker.feature.wealth.receivables

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import com.teamproject1.dailyexpensetracker.core.database.dao.WealthReceivableDao
import com.teamproject1.dailyexpensetracker.core.database.entity.WealthReceivableEntity
import com.teamproject1.dailyexpensetracker.core.session.SessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ReceivableListViewModel @Inject constructor(
    private val wealthReceivableDao: WealthReceivableDao,
    private val session: SessionManager
) : ViewModel() {

    val receivables = session.activeBookId.filterNotNull()
        .flatMapLatest { bookId -> wealthReceivableDao.getActive(bookId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun addReceivable(personName: String, note: String, openingAmount: Double) {
        viewModelScope.launch {
            val bookId = session.activeBookId.value ?: return@launch
            wealthReceivableDao.insert(
                WealthReceivableEntity(
                    bookId = bookId, personName = personName, note = note.ifBlank { null },
                    currentAmount = openingAmount, lastUpdatedAt = System.currentTimeMillis()
                )
            )
        }
    }

    fun updateAmount(receivable: WealthReceivableEntity, newAmount: Double) {
        viewModelScope.launch {
            wealthReceivableDao.update(receivable.copy(currentAmount = newAmount, lastUpdatedAt = System.currentTimeMillis()))
        }
    }

    fun archive(receivable: WealthReceivableEntity) {
        viewModelScope.launch { wealthReceivableDao.archive(receivable.id) }
    }
}

/**
 * Account Receivables — money owed TO you by others, the mirror of
 * Liabilities. Same entry-style, no-ledger pattern as Bank and Cash in
 * Hand: current amount owed, add/subtract calculator to adjust it when
 * partially repaid, then Save persists the new amount.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceivableListScreen(onBack: () -> Unit, viewModel: ReceivableListViewModel = hiltViewModel()) {
    val receivables by viewModel.receivables.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var adjustingReceivable by remember { mutableStateOf<WealthReceivableEntity?>(null) }
    var pendingDelete by remember { mutableStateOf<WealthReceivableEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Account Receivables") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) { Icon(Icons.Default.Add, contentDescription = "Add Receivable") }
        }
    ) { padding ->
        if (receivables.isEmpty()) {
            Column(modifier = Modifier.fillMaxSize().padding(padding).padding(20.dp)) {
                Text("No receivables added yet.", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            val total = receivables.sumOf { it.currentAmount }
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Text("Total Receivable Amount", style = MaterialTheme.typography.bodyMedium)
                    Text("₹%.2f".format(total), style = MaterialTheme.typography.displayLarge)
                    Spacer(Modifier.height(8.dp))
                }
                items(receivables) { receivable ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(receivable.personName, style = MaterialTheme.typography.titleLarge)
                            receivable.note?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(Modifier.height(4.dp))
                            Text("₹%.2f".format(receivable.currentAmount), style = MaterialTheme.typography.titleLarge)
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { adjustingReceivable = receivable }) { Text("Adjust Amount") }
                                TextButton(onClick = { pendingDelete = receivable }) { Text("Archive", color = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        var personName by remember { mutableStateOf("") }
        var noteText by remember { mutableStateOf("") }
        var openingAmountText by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("New Receivable") },
            text = {
                Column {
                    OutlinedTextField(value = personName, onValueChange = { personName = it }, label = { Text("Person Name") })
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = noteText, onValueChange = { noteText = it }, label = { Text("Note (optional)") })
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = openingAmountText,
                        onValueChange = { openingAmountText = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text("Amount Owed") },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (personName.isNotBlank()) {
                        viewModel.addReceivable(personName, noteText, openingAmountText.toDoubleOrNull() ?: 0.0)
                        showAddDialog = false
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showAddDialog = false }) { Text("Cancel") } }
        )
    }

    adjustingReceivable?.let { receivable ->
        ReceivableAdjustDialog(
            receivable = receivable,
            onDismiss = { adjustingReceivable = null },
            onSave = { newAmount -> viewModel.updateAmount(receivable, newAmount); adjustingReceivable = null }
        )
    }

    pendingDelete?.let { receivable ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Archive ${receivable.personName}?") },
            confirmButton = {
                TextButton(onClick = { viewModel.archive(receivable); pendingDelete = null }) { Text("Archive", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } }
        )
    }
}

/** Same calculator pattern as Bank and Cash in Hand — current amount owed
 *  shown, an amount entered, Add/Subtract updates a running "new amount"
 *  preview, Save persists it. No ledger. */
@Composable
private fun ReceivableAdjustDialog(receivable: WealthReceivableEntity, onDismiss: () -> Unit, onSave: (Double) -> Unit) {
    var runningTotal by remember { mutableStateOf(receivable.currentAmount) }
    var entryText by remember { mutableStateOf("") }

    fun apply(sign: Int) {
        val value = entryText.toDoubleOrNull() ?: return
        runningTotal += sign * value
        entryText = ""
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(receivable.personName) },
        text = {
            Column {
                Text("Current Amount Owed", style = MaterialTheme.typography.bodyMedium)
                Text("₹%.2f".format(receivable.currentAmount), style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(12.dp))
                Text("New Amount", style = MaterialTheme.typography.bodyMedium)
                Text("₹%.2f".format(runningTotal), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = entryText,
                    onValueChange = { entryText = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Amount") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { apply(-1) }) { Text("− Subtract") }
                    Button(onClick = { apply(1) }) { Text("+ Add") }
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
