package com.teamproject1.dailyexpensetracker.feature.wealth.apy

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.teamproject1.dailyexpensetracker.core.database.dao.ApyDao
import com.teamproject1.dailyexpensetracker.core.database.entity.ApyAccountEntity
import com.teamproject1.dailyexpensetracker.core.session.SessionManager
import com.teamproject1.dailyexpensetracker.ui.theme.DateField
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import androidx.compose.material.icons.automirrored.filled.ArrowBack

private val apySlabs = listOf(1000, 2000, 3000, 4000, 5000)

@HiltViewModel
class ApyListViewModel @Inject constructor(private val apyDao: ApyDao, session: SessionManager) : ViewModel() {
    val accounts = session.activeBookId.filterNotNull()
        .flatMapLatest { bookId -> apyDao.getActive(bookId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun quickAdjust(account: com.teamproject1.dailyexpensetracker.core.database.entity.ApyAccountEntity, newValue: Double) {
        viewModelScope.launch { apyDao.update(account.copy(totalContributedSoFar = newValue)) }
    }

    /** Step up/down is just editing the fixed monthly amount directly,
     *  per explicit request — same approach as EPF/Mutual Fund SIP. */
    fun updateMonthlyContribution(account: com.teamproject1.dailyexpensetracker.core.database.entity.ApyAccountEntity, newAmount: Double) {
        viewModelScope.launch { apyDao.update(account.copy(monthlyContribution = newAmount)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApyListScreen(onBack: () -> Unit, onAddNew: () -> Unit, onEdit: (Long) -> Unit, viewModel: ApyListViewModel = hiltViewModel()) {
    val accounts by viewModel.accounts.collectAsState()

    Scaffold(
        topBar = { TopAppBar(title = { Text("APY") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } }) },
        floatingActionButton = { FloatingActionButton(onClick = onAddNew) { Icon(Icons.Default.Add, contentDescription = "Add APY") } }
    ) { padding ->
        if (accounts.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = androidx.compose.ui.Alignment.Center) {
                Text("No APY accounts added yet.", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            val total = accounts.sumOf { it.totalContributedSoFar }
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Text("Total Contributed", style = MaterialTheme.typography.bodyMedium)
                    Text("₹%.0f".format(total), style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(8.dp))
                }
                items(accounts) { account ->
                    var showAdjustDialog by remember { mutableStateOf(false) }
                    var showStepDialog by remember { mutableStateOf(false) }
                    Card(modifier = Modifier.fillMaxWidth(), onClick = { onEdit(account.id) }) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(account.name, style = MaterialTheme.typography.titleLarge)
                            Text(
                                "₹${account.monthlyContribution.toInt()}/mo · Pension slab ₹${account.pensionSlab}",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                Text("₹%.0f contributed so far".format(account.totalContributedSoFar), style = MaterialTheme.typography.bodyMedium)
                                Row {
                                    TextButton(onClick = { showStepDialog = true }) { Text("Step Up/Down") }
                                    TextButton(onClick = { showAdjustDialog = true }) { Text("Adjust") }
                                }
                            }
                            // Diagnostic visibility, added specifically to
                            // investigate a reported bug where the due
                            // popup appeared to reappear even after
                            // confirming — this makes the actual stored
                            // state visible instead of needing to guess.
                            // Matches the repository's own anchor priority:
                            // lastContributionDate, then
                            // recurringDepositDate, then startDate.
                            val baseDate = account.lastContributionDate ?: account.recurringDepositDate ?: account.startDate
                            val dateFormat = remember { java.text.SimpleDateFormat("MMM d, yyyy 'at' h:mm a", java.util.Locale.US) }
                            val nextDue = remember(baseDate) {
                                java.util.Calendar.getInstance().apply { timeInMillis = baseDate; add(java.util.Calendar.MONTH, 1) }.timeInMillis
                            }
                            if (account.lastContributionDate != null) {
                                Text(
                                    "Last confirmed: ${dateFormat.format(java.util.Date(account.lastContributionDate))}",
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                Text("Never confirmed yet", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(
                                "Next due: ${dateFormat.format(java.util.Date(nextDue))}",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (showAdjustDialog) {
                        com.teamproject1.dailyexpensetracker.feature.wealth.QuickAdjustDialog(
                            currentValue = account.totalContributedSoFar,
                            label = "Adjust Total Contributed",
                            onDismiss = { showAdjustDialog = false },
                            onSave = { newValue -> viewModel.quickAdjust(account, newValue); showAdjustDialog = false }
                        )
                    }
                    if (showStepDialog) {
                        var amountText by remember { mutableStateOf(account.monthlyContribution.let { if (it == it.toLong().toDouble()) it.toLong().toString() else it.toString() }) }
                        AlertDialog(
                            onDismissRequest = { showStepDialog = false },
                            title = { Text("Step Up/Down Monthly Contribution") },
                            text = {
                                OutlinedTextField(
                                    value = amountText,
                                    onValueChange = { amountText = it.filter { c -> c.isDigit() || c == '.' } },
                                    label = { Text("New monthly contribution") },
                                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal)
                                )
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    val amount = amountText.toDoubleOrNull()
                                    if (amount != null && amount > 0) {
                                        viewModel.updateMonthlyContribution(account, amount)
                                        showStepDialog = false
                                    }
                                }) { Text("Save") }
                            },
                            dismissButton = { TextButton(onClick = { showStepDialog = false }) { Text("Cancel") } }
                        )
                    }
                }
            }
        }
    }
}

@HiltViewModel
class AddEditApyViewModel @Inject constructor(private val apyDao: ApyDao, private val session: SessionManager) : ViewModel() {
    suspend fun loadExisting(id: Long): ApyAccountEntity? {
        val bookId = session.activeBookId.value ?: return null
        return apyDao.getActive(bookId).first().find { it.id == id }
    }

    fun save(existingId: Long?, name: String, pran: String, bankName: String, accountNumber: String, ifsc: String, monthlyContribution: Double, pensionSlab: Int, startDate: Long, recurringDepositDate: Long, totalContributedSoFar: Double, onDone: () -> Unit) {
        viewModelScope.launch {
            val bookId = session.activeBookId.value ?: return@launch
            val entity = ApyAccountEntity(
                id = existingId ?: 0, bookId = bookId, name = name, pran = pran.ifBlank { null }, bankName = bankName.ifBlank { null },
                accountNumber = accountNumber.ifBlank { null }, ifsc = ifsc.ifBlank { null },
                monthlyContribution = monthlyContribution, pensionSlab = pensionSlab, startDate = startDate,
                recurringDepositDate = recurringDepositDate, totalContributedSoFar = totalContributedSoFar
            )
            if (existingId == null) apyDao.insert(entity) else apyDao.update(entity)
            onDone()
        }
    }

    fun archive(id: Long, onDone: () -> Unit) {
        viewModelScope.launch { apyDao.archive(id); onDone() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditApyScreen(existingId: Long?, onBack: () -> Unit, onSaved: () -> Unit, viewModel: AddEditApyViewModel = hiltViewModel()) {
    var name by remember { mutableStateOf("") }
    var pran by remember { mutableStateOf("") }
    var bankName by remember { mutableStateOf("") }
    var accountNumber by remember { mutableStateOf("") }
    var ifsc by remember { mutableStateOf("") }
    var contributionText by remember { mutableStateOf("") }
    var pensionSlab by remember { mutableStateOf(1000) }
    var startDate by remember { mutableStateOf(System.currentTimeMillis()) }
    var recurringDepositDate by remember { mutableStateOf(System.currentTimeMillis()) }
    var totalContributedText by remember { mutableStateOf("") }
    var totalManuallyEdited by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(existingId == null) }

    LaunchedEffect(existingId) {
        if (existingId != null) {
            viewModel.loadExisting(existingId)?.let {
                name = it.name; pran = it.pran ?: ""; bankName = it.bankName ?: ""; accountNumber = it.accountNumber ?: ""; ifsc = it.ifsc ?: ""
                contributionText = it.monthlyContribution.toString()
                pensionSlab = it.pensionSlab; startDate = it.startDate
                recurringDepositDate = it.recurringDepositDate ?: it.startDate
                totalContributedText = it.totalContributedSoFar.toString()
                totalManuallyEdited = true // editing an existing account — never overwrite its real total
            }
            loaded = true
        }
    }

    // Auto-calculates the starting balance from elapsed calendar months
    // (from the recurring deposit date) times the monthly contribution —
    // per explicit request. Still editable afterward: if the user types
    // into the field themselves, their value takes over and this stops
    // recalculating, so a real passbook figure is never silently
    // clobbered.
    LaunchedEffect(startDate, recurringDepositDate, contributionText, existingId) {
        if (existingId == null && !totalManuallyEdited) {
            val amount = contributionText.toDoubleOrNull()
            if (amount != null && amount > 0) {
                var count = 0
                val cal = java.util.Calendar.getInstance().apply { timeInMillis = recurringDepositDate }
                val now = System.currentTimeMillis()
                while (cal.timeInMillis <= now && count < 600) {
                    count++
                    cal.add(java.util.Calendar.MONTH, 1)
                }
                val suggested = count * amount
                totalContributedText = if (suggested == suggested.toLong().toDouble()) suggested.toLong().toString() else suggested.toString()
            }
        }
    }

    val canSave = name.isNotBlank() && (contributionText.toDoubleOrNull() ?: 0.0) > 0

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (existingId == null) "New APY Account" else "Edit APY Account") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } }
            )
        }
    ) { padding ->
        if (!loaded) return@Scaffold
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()).imePadding()) {
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = pran, onValueChange = { pran = it }, label = { Text("PRAN") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = bankName, onValueChange = { bankName = it }, label = { Text("Bank Name") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = accountNumber, onValueChange = { accountNumber = it }, label = { Text("Account Number") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = ifsc, onValueChange = { ifsc = it }, label = { Text("IFSC") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = contributionText,
                onValueChange = { contributionText = it.filter { c -> c.isDigit() || c == '.' } },
                label = { Text("Monthly contribution") },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Text("Pension slab (monthly pension at 60)", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                apySlabs.forEach { slab ->
                    FilterChip(selected = pensionSlab == slab, onClick = { pensionSlab = slab }, label = { Text("₹$slab") })
                }
            }
            Spacer(Modifier.height(8.dp))
            DateField(label = "Start date", selectedMillis = startDate, onDateSelected = { startDate = it })
            Spacer(Modifier.height(8.dp))
            DateField(label = "Recurring deposit date", selectedMillis = recurringDepositDate, onDateSelected = { recurringDepositDate = it })
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = totalContributedText,
                onValueChange = { totalContributedText = it.filter { c -> c.isDigit() || c == '.' }; totalManuallyEdited = true },
                label = { Text("Total contributed so far") },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )
            if (existingId == null) {
                Text(
                    "Auto-calculated from elapsed months × monthly contribution — edit if it doesn't match your passbook.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = { viewModel.save(existingId, name, pran, bankName, accountNumber, ifsc, contributionText.toDoubleOrNull() ?: 0.0, pensionSlab, startDate, recurringDepositDate, totalContributedText.toDoubleOrNull() ?: 0.0, onSaved) },
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
}
