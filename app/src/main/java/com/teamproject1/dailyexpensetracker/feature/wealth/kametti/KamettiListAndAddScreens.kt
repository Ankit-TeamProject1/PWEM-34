package com.teamproject1.dailyexpensetracker.feature.wealth.kametti

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class KamettiListViewModel @Inject constructor(
    private val kamettiDao: KamettiDao,
    session: SessionManager
) : ViewModel() {
    val accounts = session.activeBookId.filterNotNull()
        .flatMapLatest { bookId -> kamettiDao.getActiveAccounts(bookId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    suspend fun currentBalance(accountId: Long): Double {
        val entries = kamettiDao.getEntriesOnce(accountId).map { it.entryDate to it.amount }
        return KamettiCalculator.currentBalance(entries)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KamettiListScreen(onBack: () -> Unit, onAddNew: () -> Unit, onOpenAccount: (Long) -> Unit, viewModel: KamettiListViewModel = hiltViewModel()) {
    val accounts by viewModel.accounts.collectAsState()
    val valuesCache = remember { mutableStateMapOf<Long, Double>() }

    LaunchedEffect(accounts) {
        accounts.forEach { account -> valuesCache[account.id] = viewModel.currentBalance(account.id) }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Kametti") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } }) },
        floatingActionButton = { FloatingActionButton(onClick = onAddNew) { Icon(Icons.Default.Add, contentDescription = "Add Kametti") } }
    ) { padding ->
        if (accounts.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("No Kametti accounts added yet.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(24.dp))
            }
        } else {
            val total = valuesCache.values.sum()
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Text("Total Kametti Balance", style = MaterialTheme.typography.bodyMedium)
                    Text("₹%.0f".format(total), style = MaterialTheme.typography.displayLarge)
                    Spacer(Modifier.height(8.dp))
                }
                items(accounts) { account ->
                    Card(modifier = Modifier.fillMaxWidth(), onClick = { onOpenAccount(account.id) }) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(account.name, style = MaterialTheme.typography.titleLarge)
                            Text(
                                "₹%.0f/mo · %d months".format(account.monthlyAmount, account.totalMonths),
                                style = MaterialTheme.typography.bodySmall
                            )
                            Spacer(Modifier.height(8.dp))
                            Text("₹%.0f".format(valuesCache[account.id] ?: 0.0), style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
        }
    }
}

@HiltViewModel
class AddEditKamettiViewModel @Inject constructor(
    private val kamettiDao: KamettiDao,
    private val session: SessionManager
) : ViewModel() {
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage
    fun clearError() { _errorMessage.value = null }

    suspend fun loadExisting(id: Long): KamettiAccountEntity? {
        val bookId = session.activeBookId.value ?: return null
        return kamettiDao.getActiveAccounts(bookId).first().find { it.id == id }
    }

    fun save(
        existingId: Long?, name: String, monthlyAmount: Double, startDate: Long, totalMonths: Int,
        onNeedsBackDateConfirm: (newId: Long, elapsedMonths: Int) -> Unit,
        onDone: () -> Unit
    ) {
        viewModelScope.launch {
            try {
                val bookId = session.activeBookId.value
                if (bookId == null) {
                    _errorMessage.value = "Couldn't save: no active book found. Try reopening this book from the Books list."
                    return@launch
                }
                val entity = KamettiAccountEntity(id = existingId ?: 0, bookId = bookId, name = name, monthlyAmount = monthlyAmount, startDate = startDate, totalMonths = totalMonths)
                if (existingId == null) {
                    val newId = kamettiDao.insertAccount(entity)
                    // Real calendar-month counting, matching RD/EPF's
                    // fix — an earlier average-days approach undercounted
                    // near month boundaries.
                    var elapsedMonths = 0
                    val scheduleCal = Calendar.getInstance().apply { timeInMillis = startDate }
                    val now = System.currentTimeMillis()
                    while (scheduleCal.timeInMillis <= now && elapsedMonths < totalMonths) {
                        elapsedMonths++
                        scheduleCal.add(Calendar.MONTH, 1)
                    }
                    if (elapsedMonths > 0) {
                        onNeedsBackDateConfirm(newId, elapsedMonths)
                        return@launch
                    }
                } else {
                    kamettiDao.updateAccount(entity)
                }
                onDone()
            } catch (e: Exception) {
                _errorMessage.value = "Couldn't save Kametti account: ${e.message}"
            }
        }
    }

    /** Generates back-dated entries only after explicit confirmation, per
     *  the app's "no silent auto-posting" principle. Uses the CONFIRMED
     *  recurring deposit date as the schedule anchor, which may differ
     *  from the account's start date. */
    fun generateBackDatedEntries(newId: Long, monthlyAmount: Double, recurringDepositDate: Long, elapsedMonths: Int, onDone: () -> Unit) {
        viewModelScope.launch {
            try {
                kamettiDao.updateAccount(loadExisting(newId)!!.copy(recurringDepositDate = recurringDepositDate))
                val autoEntries = (0 until elapsedMonths).map { monthIndex ->
                    val date = Calendar.getInstance().apply { timeInMillis = recurringDepositDate; add(Calendar.MONTH, monthIndex) }.timeInMillis
                    KamettiEntryEntity(kamettiAccountId = newId, amount = monthlyAmount, entryDate = date)
                }
                kamettiDao.insertAllEntries(autoEntries)
                onDone()
            } catch (e: Exception) {
                _errorMessage.value = "Couldn't generate entries: ${e.message}"
            }
        }
    }

    fun archive(id: Long, onDone: () -> Unit) {
        viewModelScope.launch { kamettiDao.archiveAccount(id); onDone() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditKamettiScreen(existingId: Long?, onBack: () -> Unit, onSaved: () -> Unit, viewModel: AddEditKamettiViewModel = hiltViewModel()) {
    var name by remember { mutableStateOf("") }
    var monthlyAmountText by remember { mutableStateOf("") }
    var startDate by remember { mutableStateOf(System.currentTimeMillis()) }
    var totalMonthsText by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(existingId == null) }
    var pendingBackDateConfirm by remember { mutableStateOf<Triple<Long, Int, Long>?>(null) }
    val errorMessage by viewModel.errorMessage.collectAsState()

    LaunchedEffect(existingId) {
        if (existingId != null) {
            viewModel.loadExisting(existingId)?.let {
                name = it.name; monthlyAmountText = it.monthlyAmount.toString(); startDate = it.startDate; totalMonthsText = it.totalMonths.toString()
            }
            loaded = true
        }
    }

    val canSave = name.isNotBlank() && (monthlyAmountText.toDoubleOrNull() ?: 0.0) > 0 && (totalMonthsText.toIntOrNull() ?: 0) > 0

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (existingId == null) "New Kametti" else "Edit Kametti") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } }
            )
        }
    ) { padding ->
        if (!loaded) return@Scaffold
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState())) {
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name (e.g. Office Kametti Group)") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = monthlyAmountText, onValueChange = { monthlyAmountText = it.filter { c -> c.isDigit() || c == '.' } },
                label = { Text("Monthly Amount") }, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = totalMonthsText, onValueChange = { totalMonthsText = it.filter { c -> c.isDigit() } },
                label = { Text("Total Months") }, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            DateField(label = "Start Date", selectedMillis = startDate, onDateSelected = { startDate = it })
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = {
                    viewModel.save(
                        existingId, name, monthlyAmountText.toDoubleOrNull() ?: 0.0, startDate, totalMonthsText.toIntOrNull() ?: 0,
                        onNeedsBackDateConfirm = { newId, elapsedMonths -> pendingBackDateConfirm = Triple(newId, elapsedMonths, startDate) },
                        onDone = onSaved
                    )
                },
                enabled = canSave,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Save") }
            if (existingId != null) {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { viewModel.archive(existingId, onSaved) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Archive", color = MaterialTheme.colorScheme.error)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    pendingBackDateConfirm?.let { (newId, elapsedMonths, defaultDate) ->
        var recurringDepositDate by remember { mutableStateOf(defaultDate) }
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Back-dated Kametti") },
            text = {
                Column {
                    Text(
                        "This Kametti started %d month(s) ago. Based on ₹%.0f/month, we can create entries for the months already elapsed — the amount is calculated automatically. What date each month is the deposit actually made on?".format(elapsedMonths, monthlyAmountText.toDoubleOrNull() ?: 0.0)
                    )
                    Spacer(Modifier.height(12.dp))
                    DateField(label = "Recurring deposit date", selectedMillis = recurringDepositDate, onDateSelected = { recurringDepositDate = it })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.generateBackDatedEntries(newId, monthlyAmountText.toDoubleOrNull() ?: 0.0, recurringDepositDate, elapsedMonths) {
                        pendingBackDateConfirm = null
                        onSaved()
                    }
                }) { Text("Generate Entries") }
            },
            dismissButton = {
                TextButton(onClick = { pendingBackDateConfirm = null; onSaved() }) { Text("Skip — I'll add entries myself") }
            }
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
