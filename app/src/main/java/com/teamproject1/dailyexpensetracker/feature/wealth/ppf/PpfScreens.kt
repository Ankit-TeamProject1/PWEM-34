package com.teamproject1.dailyexpensetracker.feature.wealth.ppf

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.teamproject1.dailyexpensetracker.core.database.dao.PpfDao
import com.teamproject1.dailyexpensetracker.core.database.dao.WealthRateHistoryDao
import com.teamproject1.dailyexpensetracker.core.database.entity.PpfAccountEntity
import com.teamproject1.dailyexpensetracker.core.database.entity.PpfDepositEntity
import com.teamproject1.dailyexpensetracker.core.database.entity.PpfEntryType
import com.teamproject1.dailyexpensetracker.core.database.entity.RateInstrument
import com.teamproject1.dailyexpensetracker.core.session.SessionManager
import com.teamproject1.dailyexpensetracker.core.wealth.PpfEpfCalculator
import com.teamproject1.dailyexpensetracker.ui.theme.DateField
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import androidx.compose.material.icons.automirrored.filled.ArrowBack

@HiltViewModel
class PpfListViewModel @Inject constructor(
    private val ppfDao: PpfDao,
    private val rateHistoryDao: WealthRateHistoryDao,
    private val session: SessionManager
) : ViewModel() {
    val accounts = session.activeBookId.filterNotNull()
        .flatMapLatest { bookId -> ppfDao.getActiveAccounts(bookId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Returns (principal, accruedInterest) — per explicit request,
     *  unposted interest isn't part of the main total. Principal includes
     *  already-posted Interest entries (those are "real" once confirmed
     *  via the March 31 popup), but not the live, not-yet-posted portion. */
    suspend fun currentValue(accountId: Long): Pair<Double, Double> {
        val allEntries = ppfDao.getDepositsOnce(accountId)
        val principal = allEntries.sumOf { it.amount }
        val filtered = allEntries.filter { it.type != PpfEntryType.INTEREST }.map { it.depositDate to it.amount }
        val rates = rateHistoryDao.getHistoryOnce(RateInstrument.PPF)
        val liveValue = PpfEpfCalculator.currentValue(filtered, rates)
        return principal to (liveValue - principal).coerceAtLeast(0.0)
    }

    fun addAccount(name: String, accountNumber: String, bankName: String, openDate: Long) {
        viewModelScope.launch {
            val bookId = session.activeBookId.value ?: return@launch
            ppfDao.insertAccount(PpfAccountEntity(bookId = bookId, name = name, accountNumber = accountNumber.ifBlank { null }, bankOrPostOfficeName = bankName.ifBlank { null }, accountOpenDate = openDate))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PpfListScreen(onBack: () -> Unit, onOpenAccount: (Long) -> Unit, viewModel: PpfListViewModel = hiltViewModel()) {
    val accounts by viewModel.accounts.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    val valuesCache = remember { mutableStateMapOf<Long, Pair<Double, Double>>() }

    LaunchedEffect(accounts) {
        accounts.forEach { account -> valuesCache[account.id] = viewModel.currentValue(account.id) }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("PPF") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } }) },
        floatingActionButton = { FloatingActionButton(onClick = { showAddDialog = true }) { Icon(Icons.Default.Add, contentDescription = "Add PPF Account") } }
    ) { padding ->
        if (accounts.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = androidx.compose.ui.Alignment.Center) {
                Text("No PPF accounts added yet.", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            val total = valuesCache.values.sumOf { it.first }
            val totalAccrued = valuesCache.values.sumOf { it.second }
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Text("Total PPF Principal", style = MaterialTheme.typography.bodyMedium)
                    Text("₹%.0f".format(total), style = MaterialTheme.typography.titleLarge)
                    if (totalAccrued > 0) {
                        Text(
                            "+ ₹%.0f accrued interest (not yet posted)".format(totalAccrued),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }
                items(accounts) { account ->
                    Card(modifier = Modifier.fillMaxWidth(), onClick = { onOpenAccount(account.id) }) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(account.name, style = MaterialTheme.typography.titleLarge)
                            val (principal, accrued) = valuesCache[account.id] ?: (0.0 to 0.0)
                            Text("₹%.0f".format(principal), style = MaterialTheme.typography.titleMedium)
                            if (accrued > 0) {
                                Text(
                                    "+ ₹%.0f accrued interest".format(accrued),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        var accountNumber by remember { mutableStateOf("") }
        var bankName by remember { mutableStateOf("") }
        var openDate by remember { mutableStateOf(System.currentTimeMillis()) }
        // Name is now always the combination of Bank/Post Office Name and
        // Account Number, per explicit request, computed rather than
        // typed separately.
        val name = listOf(bankName, accountNumber).filter { it.isNotBlank() }.joinToString(" ")
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("New PPF Account") },
            text = {
                Column {
                    OutlinedTextField(value = bankName, onValueChange = { bankName = it }, label = { Text("Bank / Post Office Name") })
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = accountNumber, onValueChange = { accountNumber = it }, label = { Text("Account Number") })
                    if (name.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text("Will be saved as: \"$name\"", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(8.dp))
                    DateField(label = "Account opened", selectedMillis = openDate, onDateSelected = { openDate = it })
                }
            },
            confirmButton = {
                TextButton(onClick = { if (name.isNotBlank()) { viewModel.addAccount(name, accountNumber, bankName, openDate); showAddDialog = false } }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showAddDialog = false }) { Text("Cancel") } }
        )
    }
}

@HiltViewModel
class PpfDetailViewModel @Inject constructor(
    private val ppfDao: PpfDao,
    private val rateHistoryDao: WealthRateHistoryDao,
    private val session: SessionManager
) : ViewModel() {

    private val _currentValue = MutableStateFlow(0.0)
    val currentValue: StateFlow<Double> = _currentValue

    // Per explicit request: unposted interest isn't part of the main
    // total until it's actually posted (via the March 31 confirmation)
    // — currentValue above now holds principal (including any
    // ALREADY-posted Interest entries), with this holding the separate,
    // still-live, not-yet-posted portion.
    private val _accruedInterest = MutableStateFlow(0.0)
    val accruedInterest: StateFlow<Double> = _accruedInterest

    private val _interestThisYear = MutableStateFlow(0.0)
    val interestThisYear: StateFlow<Double> = _interestThisYear

    // The interest confirmation prompt moved to Dashboard level
    // (PpfInterestPopup), per explicit request — no longer tracked here.
    fun deposits(accountId: Long) = ppfDao.getDeposits(accountId)

    suspend fun loadAccount(accountId: Long): PpfAccountEntity? {
        val bookId = session.activeBookId.value ?: return null
        return ppfDao.getActiveAccounts(bookId).first().find { it.id == accountId }
    }

    fun refreshValue(accountId: Long) {
        viewModelScope.launch {
            val allEntries = ppfDao.getDepositsOnce(accountId)
            // INTEREST-type entries are excluded from the compounding math
            // itself — they're now real, recorded rows (per the confirmed
            // design), but including them here would double-count: the
            // calculator already compounds Deposits/Deductions against the
            // Rate History table, so an Interest row would get compounded
            // again next year as if it were a fresh deposit. Only
            // Deposit/Deduction entries (both already correctly signed)
            // feed the actual current-value calculation.
            val deposits = allEntries.filter { it.type != PpfEntryType.INTEREST }.map { it.depositDate to it.amount }
            val rates = rateHistoryDao.getHistoryOnce(RateInstrument.PPF)
            val liveValue = PpfEpfCalculator.currentValue(deposits, rates)

            // Indian FY runs April 1 – March 31. fyStart here is the FY
            // currently IN PROGRESS — correct for showing "interest
            // earned so far this year" as a live, partial-year display
            // figure.
            val cal = java.util.Calendar.getInstance()
            val currentMonth = cal.get(java.util.Calendar.MONTH) // 0 = January
            val fyStartYear = if (currentMonth >= java.util.Calendar.APRIL) cal.get(java.util.Calendar.YEAR) else cal.get(java.util.Calendar.YEAR) - 1
            val fyStart = java.util.Calendar.getInstance().apply {
                set(fyStartYear, java.util.Calendar.APRIL, 1, 0, 0, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }.timeInMillis

            val valueAtFyStart = PpfEpfCalculator.currentValue(deposits, rates, asOf = fyStart)
            val depositsSinceFyStart = deposits.filter { it.first >= fyStart }.sumOf { it.second }
            _interestThisYear.value = (liveValue - valueAtFyStart - depositsSinceFyStart).coerceAtLeast(0.0)

            // The interest CONFIRMATION popup itself moved to Dashboard
            // level (PpfInterestRepository/PpfInterestPopup), per
            // explicit request, matching APY/EPF/RD/Kametti's pattern —
            // it no longer lives here. interestThisYear above remains as
            // a live, informational display figure on this screen.

            // Principal (shown as the main figure) includes any
            // ALREADY-posted Interest entries — those are real once
            // confirmed. accruedInterest is the separate, live,
            // not-yet-posted remainder.
            val principal = allEntries.sumOf { it.amount }
            _currentValue.value = principal
            _accruedInterest.value = (liveValue - principal).coerceAtLeast(0.0)
        }
    }

    fun addEntry(accountId: Long, amount: Double, date: Long, type: PpfEntryType, note: String?, onDone: () -> Unit) {
        viewModelScope.launch {
            ppfDao.insertDeposit(PpfDepositEntity(ppfAccountId = accountId, amount = amount, depositDate = date, type = type, note = note))
            refreshValue(accountId)
            onDone()
        }
    }

    fun updateEntry(entry: PpfDepositEntity, amount: Double, date: Long, type: PpfEntryType, note: String?, onDone: () -> Unit) {
        viewModelScope.launch {
            ppfDao.updateDeposit(entry.copy(amount = amount, depositDate = date, type = type, note = note))
            refreshValue(entry.ppfAccountId)
            onDone()
        }
    }

    fun deleteEntry(entry: PpfDepositEntity, onDone: () -> Unit) {
        viewModelScope.launch {
            ppfDao.deleteDeposit(entry.id)
            refreshValue(entry.ppfAccountId)
            onDone()
        }
    }


    fun archiveAccount(accountId: Long, onDone: () -> Unit) {
        viewModelScope.launch { ppfDao.archiveAccount(accountId); onDone() }
    }

    fun updateAccountDetails(account: PpfAccountEntity, name: String, accountNumber: String, bankName: String, onDone: () -> Unit) {
        viewModelScope.launch {
            ppfDao.updateAccount(account.copy(name = name, accountNumber = accountNumber.ifBlank { null }, bankOrPostOfficeName = bankName.ifBlank { null }))
            onDone()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PpfDetailScreen(accountId: Long, onBack: () -> Unit, viewModel: PpfDetailViewModel = hiltViewModel()) {
    var account by remember { mutableStateOf<PpfAccountEntity?>(null) }
    val deposits by viewModel.deposits(accountId).collectAsState(initial = emptyList())
    val currentValue by viewModel.currentValue.collectAsState()
    val interestThisYear by viewModel.interestThisYear.collectAsState()
    var showAddDepositDialog by remember { mutableStateOf(false) }
    var showEditAccountDialog by remember { mutableStateOf(false) }
    var editingEntry by remember { mutableStateOf<PpfDepositEntity?>(null) }
    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy", Locale.US) }

    LaunchedEffect(accountId) {
        account = viewModel.loadAccount(accountId)
        viewModel.refreshValue(accountId)
    }
    LaunchedEffect(deposits) { viewModel.refreshValue(accountId) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(account?.name ?: "PPF Account") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = {
                    TextButton(onClick = { showEditAccountDialog = true }) { Text("Edit") }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDepositDialog = true }) { Icon(Icons.Default.Add, contentDescription = "Add Deposit") }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            account?.let { acc ->
                if (!acc.accountNumber.isNullOrBlank() || !acc.bankOrPostOfficeName.isNullOrBlank()) {
                    Text(
                        listOfNotNull(acc.bankOrPostOfficeName, acc.accountNumber?.let { "A/C $it" }).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
            Text("Current Balance", style = MaterialTheme.typography.bodyMedium)
            Text("₹%.2f".format(currentValue), style = MaterialTheme.typography.displayLarge)
            Text(
                "Interest earned YTD: ₹%.2f".format(interestThisYear),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(16.dp))
            Text("Entries", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(deposits.sortedByDescending { it.depositDate }) { deposit ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = { editingEntry = deposit })
                    ) {
                        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                            Column {
                                Text(dateFormat.format(Date(deposit.depositDate)), style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    deposit.type.name.lowercase().replaceFirstChar { it.uppercase() },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                "₹%.0f".format(deposit.amount),
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (deposit.amount < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                            )
                        }
                        deposit.note?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            account?.let { acc ->
                TextButton(onClick = { viewModel.archiveAccount(acc.id, onBack) }) {
                    Text("Archive this account", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    if (showEditAccountDialog && account != null) {
        var editAccountNumber by remember { mutableStateOf(account!!.accountNumber ?: "") }
        var editBankName by remember { mutableStateOf(account!!.bankOrPostOfficeName ?: "") }
        // Name is now always the combination of Bank/Post Office Name
        // and Account Number, per explicit request — editing either
        // field here updates the account's Name to match.
        val editName = listOf(editBankName, editAccountNumber).filter { it.isNotBlank() }.joinToString(" ")
        AlertDialog(
            onDismissRequest = { showEditAccountDialog = false },
            title = { Text("Edit PPF Account") },
            text = {
                Column {
                    OutlinedTextField(value = editBankName, onValueChange = { editBankName = it }, label = { Text("Bank / Post Office Name") })
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = editAccountNumber, onValueChange = { editAccountNumber = it }, label = { Text("Account Number") })
                    if (editName.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text("Will be saved as: \"$editName\"", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.updateAccountDetails(account!!, editName, editAccountNumber, editBankName) {
                        account = account!!.copy(name = editName, accountNumber = editAccountNumber.ifBlank { null }, bankOrPostOfficeName = editBankName.ifBlank { null })
                        showEditAccountDialog = false
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showEditAccountDialog = false }) { Text("Cancel") } }
        )
    }

    if (showAddDepositDialog) {
        PpfEntryDialog(
            title = "Add Entry",
            initialAmount = "",
            initialType = PpfEntryType.DEPOSIT,
            initialDate = System.currentTimeMillis(),
            initialNote = "",
            onDismiss = { showAddDepositDialog = false },
            onDelete = null,
            onSave = { amount, type, date, note ->
                viewModel.addEntry(accountId, amount, date, type, note.ifBlank { null }) { showAddDepositDialog = false }
            }
        )
    }

    editingEntry?.let { entry ->
        PpfEntryDialog(
            title = "Edit Entry",
            initialAmount = kotlin.math.abs(entry.amount).let { if (it == it.toLong().toDouble()) it.toLong().toString() else it.toString() },
            initialType = entry.type,
            initialDate = entry.depositDate,
            initialNote = entry.note ?: "",
            onDismiss = { editingEntry = null },
            onDelete = { viewModel.deleteEntry(entry) { editingEntry = null } },
            onSave = { amount, type, date, note ->
                viewModel.updateEntry(entry, amount, date, type, note.ifBlank { null }) { editingEntry = null }
            }
        )
    }

    // The interest confirmation popup now lives on the Dashboard
    // (PpfInterestPopup), per explicit request, matching APY/EPF/RD/
    // Kametti's pattern — removed from here.
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PpfEntryDialog(
    title: String,
    initialAmount: String,
    initialType: PpfEntryType,
    initialDate: Long,
    initialNote: String,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)?,
    onSave: (amount: Double, type: PpfEntryType, date: Long, note: String) -> Unit
) {
    var amountText by remember { mutableStateOf(initialAmount) }
    var type by remember { mutableStateOf(initialType) }
    var date by remember { mutableStateOf(initialDate) }
    var note by remember { mutableStateOf(initialNote) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                // Deposit/Deduction/Interest — per explicit request. Interest
                // entries are recorded for reference but excluded from the
                // compounding calculation itself (see PpfDetailViewModel).
                SingleChoiceSegmentedButtonRow {
                    listOf("Deposit" to PpfEntryType.DEPOSIT, "Deduction" to PpfEntryType.DEDUCTION, "Interest" to PpfEntryType.INTEREST)
                        .forEachIndexed { index, (label, t) ->
                            SegmentedButton(
                                selected = type == t, onClick = { type = t },
                                shape = SegmentedButtonDefaults.itemShape(index = index, count = 3)
                            ) { Text(label) }
                        }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Amount") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
                Spacer(Modifier.height(8.dp))
                DateField(label = "Date", selectedMillis = date, onDateSelected = { date = it })
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("Note (optional)") })
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val amount = amountText.toDoubleOrNull()
                if (amount != null && amount > 0) {
                    val signedAmount = if (type == PpfEntryType.DEDUCTION) -amount else amount
                    onSave(signedAmount, type, date, note)
                }
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}
