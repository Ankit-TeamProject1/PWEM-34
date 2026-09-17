package com.teamproject1.dailyexpensetracker.feature.wealth.bank

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
import com.teamproject1.dailyexpensetracker.core.database.dao.BankBalanceDao
import com.teamproject1.dailyexpensetracker.core.database.entity.BankBalanceEntity
import com.teamproject1.dailyexpensetracker.core.session.SessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BankListViewModel @Inject constructor(
    private val bankBalanceDao: BankBalanceDao,
    private val session: SessionManager
) : ViewModel() {

    val banks = session.activeBookId.filterNotNull()
        .flatMapLatest { bookId -> bankBalanceDao.getActive(bookId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun addBank(bankName: String, accountDetails: String, openingBalance: Double) {
        viewModelScope.launch {
            val bookId = session.activeBookId.value ?: return@launch
            bankBalanceDao.insert(
                BankBalanceEntity(
                    bookId = bookId, bankName = bankName, accountDetails = accountDetails.ifBlank { null },
                    currentBalance = openingBalance, lastUpdatedAt = System.currentTimeMillis()
                )
            )
        }
    }

    fun updateBalance(bank: BankBalanceEntity, newBalance: Double) {
        viewModelScope.launch {
            bankBalanceDao.update(bank.copy(currentBalance = newBalance, lastUpdatedAt = System.currentTimeMillis()))
        }
    }

    fun archive(bank: BankBalanceEntity) {
        viewModelScope.launch { bankBalanceDao.archive(bank.id) }
    }
}

/**
 * Bank — entry-style, no ledger, per explicit request: each bank shows its
 * current balance, an add/subtract calculator to adjust it, then Save
 * persists the new balance. Same interaction pattern as Cash in Hand,
 * just supporting multiple named bank entries instead of one single value.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BankListScreen(onBack: () -> Unit, viewModel: BankListViewModel = hiltViewModel()) {
    val banks by viewModel.banks.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var adjustingBank by remember { mutableStateOf<BankBalanceEntity?>(null) }
    var pendingDelete by remember { mutableStateOf<BankBalanceEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Bank") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) { Icon(Icons.Default.Add, contentDescription = "Add Bank") }
        }
    ) { padding ->
        if (banks.isEmpty()) {
            Column(modifier = Modifier.fillMaxSize().padding(padding).padding(20.dp)) {
                Text("No bank accounts added yet.", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            val total = banks.sumOf { it.currentBalance }
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Text("Total Bank Balance", style = MaterialTheme.typography.bodyMedium)
                    Text("₹%.2f".format(total), style = MaterialTheme.typography.displayLarge)
                    Spacer(Modifier.height(8.dp))
                }
                items(banks) { bank ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(bank.bankName, style = MaterialTheme.typography.titleLarge)
                            bank.accountDetails?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(Modifier.height(4.dp))
                            Text("₹%.2f".format(bank.currentBalance), style = MaterialTheme.typography.titleLarge)
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { adjustingBank = bank }) { Text("Adjust Balance") }
                                TextButton(onClick = { pendingDelete = bank }) { Text("Archive", color = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        var bankName by remember { mutableStateOf("") }
        var accountDetails by remember { mutableStateOf("") }
        var openingBalanceText by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("New Bank Account") },
            text = {
                Column {
                    OutlinedTextField(value = bankName, onValueChange = { bankName = it }, label = { Text("Bank Name") })
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = accountDetails, onValueChange = { accountDetails = it }, label = { Text("Account Details (optional)") })
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = openingBalanceText,
                        onValueChange = { openingBalanceText = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text("Current Balance") },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (bankName.isNotBlank()) {
                        viewModel.addBank(bankName, accountDetails, openingBalanceText.toDoubleOrNull() ?: 0.0)
                        showAddDialog = false
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showAddDialog = false }) { Text("Cancel") } }
        )
    }

    adjustingBank?.let { bank ->
        BankAdjustDialog(
            bank = bank,
            onDismiss = { adjustingBank = null },
            onSave = { newBalance -> viewModel.updateBalance(bank, newBalance); adjustingBank = null }
        )
    }

    pendingDelete?.let { bank ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Archive ${bank.bankName}?") },
            confirmButton = {
                TextButton(onClick = { viewModel.archive(bank); pendingDelete = null }) { Text("Archive", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } }
        )
    }
}

/** Same calculator pattern as Cash in Hand — current balance shown, an
 *  amount entered, Add/Subtract updates a running "new balance" preview,
 *  Save persists it. No ledger. */
@Composable
private fun BankAdjustDialog(bank: BankBalanceEntity, onDismiss: () -> Unit, onSave: (Double) -> Unit) {
    var runningTotal by remember { mutableStateOf(bank.currentBalance) }
    var entryText by remember { mutableStateOf("") }

    fun apply(sign: Int) {
        val value = entryText.toDoubleOrNull() ?: return
        runningTotal += sign * value
        entryText = ""
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(bank.bankName) },
        text = {
            Column {
                Text("Current Balance", style = MaterialTheme.typography.bodyMedium)
                Text("₹%.2f".format(bank.currentBalance), style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(12.dp))
                Text("New Balance", style = MaterialTheme.typography.bodyMedium)
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
