package com.teamproject1.dailyexpensetracker.feature.wealth.kametti

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.teamproject1.dailyexpensetracker.core.database.dao.KamettiDao
import com.teamproject1.dailyexpensetracker.core.database.entity.KamettiAccountEntity
import com.teamproject1.dailyexpensetracker.core.database.entity.KamettiEntryEntity
import com.teamproject1.dailyexpensetracker.core.session.SessionManager
import com.teamproject1.dailyexpensetracker.core.wealth.KamettiCalculator
import com.teamproject1.dailyexpensetracker.ui.theme.DateField
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class KamettiDetailViewModel @Inject constructor(
    private val kamettiDao: KamettiDao,
    private val session: SessionManager
) : ViewModel() {
    private val _currentBalance = MutableStateFlow(0.0)
    val currentBalance: StateFlow<Double> = _currentBalance

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage
    fun clearError() { _errorMessage.value = null }

    fun entries(accountId: Long) = kamettiDao.getEntries(accountId)

    suspend fun loadAccount(accountId: Long): KamettiAccountEntity? {
        val bookId = session.activeBookId.value ?: return null
        return kamettiDao.getActiveAccounts(bookId).first().find { it.id == accountId }
    }

    private suspend fun refreshBalanceNow(accountId: Long) {
        val entries = kamettiDao.getEntriesOnce(accountId).map { it.entryDate to it.amount }
        _currentBalance.value = KamettiCalculator.currentBalance(entries)
    }

    fun refreshBalance(accountId: Long) {
        viewModelScope.launch { refreshBalanceNow(accountId) }
    }

    fun addEntry(accountId: Long, amount: Double, date: Long, onDone: () -> Unit) {
        viewModelScope.launch {
            try {
                kamettiDao.insertEntry(KamettiEntryEntity(kamettiAccountId = accountId, amount = amount, entryDate = date))
                refreshBalanceNow(accountId)
                onDone()
            } catch (e: Exception) {
                _errorMessage.value = "Couldn't save entry: ${e.message}"
            }
        }
    }

    fun updateEntry(entry: KamettiEntryEntity, amount: Double, date: Long, onDone: () -> Unit) {
        viewModelScope.launch {
            try {
                kamettiDao.updateEntry(entry.copy(amount = amount, entryDate = date))
                refreshBalanceNow(entry.kamettiAccountId)
                onDone()
            } catch (e: Exception) {
                _errorMessage.value = "Couldn't update entry: ${e.message}"
            }
        }
    }

    fun deleteEntry(entry: KamettiEntryEntity, onDone: () -> Unit) {
        viewModelScope.launch {
            try {
                kamettiDao.deleteEntry(entry.id)
                refreshBalanceNow(entry.kamettiAccountId)
                onDone()
            } catch (e: Exception) {
                _errorMessage.value = "Couldn't delete entry: ${e.message}"
            }
        }
    }

    /** "Won" — per explicit request: creates a NEGATIVE entry for the
     *  total pot amount (monthly amount × total months), since receiving
     *  the payout reduces your ongoing position in the pool. "balance
     *  will be calculated as deposit add and won subtract" — this is
     *  exactly what KamettiCalculator's plain sum already does once this
     *  entry exists; no special-casing needed in the calculator itself. */
    fun recordWon(account: KamettiAccountEntity, date: Long, onDone: () -> Unit) {
        viewModelScope.launch {
            try {
                val potAmount = account.monthlyAmount * account.totalMonths
                kamettiDao.insertEntry(KamettiEntryEntity(kamettiAccountId = account.id, amount = -potAmount, entryDate = date, isWon = true))
                refreshBalanceNow(account.id)
                onDone()
            } catch (e: Exception) {
                _errorMessage.value = "Couldn't record Won: ${e.message}"
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KamettiDetailScreen(accountId: Long, onBack: () -> Unit, onEditAccount: (Long) -> Unit, viewModel: KamettiDetailViewModel = hiltViewModel()) {
    var account by remember { mutableStateOf<KamettiAccountEntity?>(null) }
    val entries by viewModel.entries(accountId).collectAsState(initial = emptyList())
    val currentBalance by viewModel.currentBalance.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var editingEntry by remember { mutableStateOf<KamettiEntryEntity?>(null) }
    var showWonDialog by remember { mutableStateOf(false) }
    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy", Locale.US) }

    val alreadyWon = entries.any { it.isWon }

    LaunchedEffect(accountId) {
        account = viewModel.loadAccount(accountId)
        viewModel.refreshBalance(accountId)
    }
    LaunchedEffect(entries) { viewModel.refreshBalance(accountId) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(account?.name ?: "Kametti") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = { TextButton(onClick = { onEditAccount(accountId) }) { Text("Edit") } }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState())) {
            account?.let { acc ->
                Text("₹%.0f/mo · %d months".format(acc.monthlyAmount, acc.totalMonths), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
            }
            Text("Current Balance", style = MaterialTheme.typography.bodyMedium)
            Text("₹%.2f".format(currentBalance), style = MaterialTheme.typography.displayLarge)
            Spacer(Modifier.height(16.dp))

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Won This Pot", style = MaterialTheme.typography.titleLarge)
                    Text(
                        if (alreadyWon) "Already recorded for this Kametti." else "When your turn comes and you receive the pooled amount, record it here — this subtracts the total pot from your balance.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { showWonDialog = true }, enabled = !alreadyWon) { Text("Record Won") }
                }
            }
            Spacer(Modifier.height(16.dp))

            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text("Entries", style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = { showAddDialog = true }) { Text("+ Add Entry") }
            }
            Spacer(Modifier.height(8.dp))
            entries.sortedByDescending { it.entryDate }.forEach { entry ->
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth().clickable { editingEntry = entry }.padding(vertical = 8.dp)
                ) {
                    Text(
                        (if (entry.isWon) "Won — " else "") + dateFormat.format(Date(entry.entryDate)),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "₹%.0f".format(entry.amount),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (entry.amount < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (showAddDialog) {
        KamettiEntryDialog(
            title = "Add Entry", initialAmount = account?.monthlyAmount?.let { "%.0f".format(it) } ?: "", initialDate = System.currentTimeMillis(),
            onDismiss = { showAddDialog = false }, onDelete = null,
            onSave = { amount, date -> viewModel.addEntry(accountId, amount, date) { showAddDialog = false } }
        )
    }

    editingEntry?.let { entry ->
        KamettiEntryDialog(
            title = if (entry.isWon) "Edit Won Entry" else "Edit Entry", initialAmount = "%.0f".format(entry.amount), initialDate = entry.entryDate,
            onDismiss = { editingEntry = null }, onDelete = { viewModel.deleteEntry(entry) { editingEntry = null } },
            onSave = { amount, date -> viewModel.updateEntry(entry, amount, date) { editingEntry = null } }
        )
    }

    if (showWonDialog) {
        var wonDate by remember { mutableStateOf(System.currentTimeMillis()) }
        val potAmount = (account?.monthlyAmount ?: 0.0) * (account?.totalMonths ?: 0)
        AlertDialog(
            onDismissRequest = { showWonDialog = false },
            title = { Text("Record Won") },
            text = {
                Column {
                    Text("This will record receiving ₹%.0f (the full pot: monthly amount × total months) on:".format(potAmount))
                    Spacer(Modifier.height(12.dp))
                    DateField(label = "Date won", selectedMillis = wonDate, onDateSelected = { wonDate = it })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    account?.let { viewModel.recordWon(it, wonDate) { showWonDialog = false } }
                }) { Text("Confirm") }
            },
            dismissButton = { TextButton(onClick = { showWonDialog = false }) { Text("Cancel") } }
        )
    }

    errorMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { viewModel.clearError() },
            title = { Text("Something went wrong") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { viewModel.clearError() }) { Text("OK") } }
        )
    }
}

@Composable
private fun KamettiEntryDialog(
    title: String, initialAmount: String, initialDate: Long,
    onDismiss: () -> Unit, onDelete: (() -> Unit)?, onSave: (amount: Double, date: Long) -> Unit
) {
    var amountText by remember { mutableStateOf(initialAmount) }
    var date by remember { mutableStateOf(initialDate) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = amountText, onValueChange = { amountText = it.filter { c -> c.isDigit() || c == '.' || c == '-' } },
                    label = { Text("Amount") }, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
                Spacer(Modifier.height(8.dp))
                DateField(label = "Date", selectedMillis = date, onDateSelected = { date = it })
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val amount = amountText.toDoubleOrNull()
                if (amount != null && amount != 0.0) onSave(amount, date)
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}
