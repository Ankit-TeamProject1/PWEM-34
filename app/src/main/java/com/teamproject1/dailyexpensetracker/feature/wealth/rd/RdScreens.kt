package com.teamproject1.dailyexpensetracker.feature.wealth.rd

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.teamproject1.dailyexpensetracker.core.database.dao.RdInstallmentDao
import com.teamproject1.dailyexpensetracker.core.database.dao.RecurringDepositDao
import com.teamproject1.dailyexpensetracker.core.database.entity.AutoRenewMode
import com.teamproject1.dailyexpensetracker.core.database.entity.CompoundingFrequency
import com.teamproject1.dailyexpensetracker.core.database.entity.RdInstallmentEntity
import com.teamproject1.dailyexpensetracker.core.database.entity.RecurringDepositEntity
import com.teamproject1.dailyexpensetracker.core.session.SessionManager
import com.teamproject1.dailyexpensetracker.core.wealth.RdCalculator
import com.teamproject1.dailyexpensetracker.ui.theme.DateField
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import androidx.compose.material.icons.automirrored.filled.ArrowBack

@HiltViewModel
class RdListViewModel @Inject constructor(
    private val recurringDepositDao: RecurringDepositDao,
    private val rdInstallmentDao: RdInstallmentDao,
    session: SessionManager
) : ViewModel() {
    val rds = session.activeBookId.filterNotNull()
        .flatMapLatest { bookId -> recurringDepositDao.getActive(bookId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Fully reactive per-account values — this was a real bug: the old
     *  version cached values via a one-shot suspend fetch triggered only
     *  when the ACCOUNT LIST changed (new RD added/removed). Adding an
     *  installment entry to an EXISTING account never changed that list,
     *  so the cached value on this screen went stale and never updated —
     *  even though the Detail screen's own value was refreshing
     *  correctly. This now recombines whenever ANY account's installment
     *  list changes, not just when accounts are added/removed.
     *
     *  Value is Pair(principalOrMaturedValue, accruedInterest) — per
     *  explicit request, unposted interest isn't shown as part of the
     *  main total until the RD actually matures. */
    val valuesByAccount: StateFlow<Map<Long, Pair<Double, Double>>> = rds
        .flatMapLatest { accounts ->
            if (accounts.isEmpty()) {
                kotlinx.coroutines.flow.flowOf(emptyMap())
            } else {
                combine(accounts.map { account -> rdInstallmentDao.getInstallments(account.id).map { account to it } }) { pairs ->
                    pairs.associate { (account, installments) ->
                        val rawPrincipal = installments.sumOf { it.amount }
                        val liveValue = if (installments.isNotEmpty()) RdCalculator.currentValueFromEntries(account, installments) else RdCalculator.currentValue(account)
                        val isMatured = System.currentTimeMillis() >= RdCalculator.maturityDate(account)
                        account.id to if (isMatured) {
                            liveValue to 0.0
                        } else {
                            rawPrincipal to (liveValue - rawPrincipal).coerceAtLeast(0.0)
                        }
                    }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RdListScreen(onBack: () -> Unit, onAddNew: () -> Unit, onOpenDetail: (Long) -> Unit, viewModel: RdListViewModel = hiltViewModel()) {
    val rds by viewModel.rds.collectAsState()
    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy", Locale.US) }
    val valuesByAccount by viewModel.valuesByAccount.collectAsState()

    Scaffold(
        topBar = { TopAppBar(title = { Text("RD") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } }) },
        floatingActionButton = { FloatingActionButton(onClick = onAddNew) { Icon(Icons.Default.Add, contentDescription = "Add RD") } }
    ) { padding ->
        if (rds.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = androidx.compose.ui.Alignment.Center) {
                Text("No RDs added yet.", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            val totalMaturity = rds.sumOf { RdCalculator.maturityValue(it) }
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Text("Total at Maturity", style = MaterialTheme.typography.bodyMedium)
                    Text("₹%.0f".format(totalMaturity), style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(8.dp))
                }
                items(rds) { rd ->
                    // Tapping opens the entry list (Detail screen) now, per
                    // explicit request — editing the RD's own details
                    // (name, bank, rate...) is reached only via an
                    // explicit Edit action inside that Detail screen.
                    Card(modifier = Modifier.fillMaxWidth(), onClick = { onOpenDetail(rd.id) }) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(rd.name, style = MaterialTheme.typography.titleLarge)
                            Text(
                                "₹%.0f/mo · %.2f%% · %d months".format(rd.monthlyInstallment, rd.interestRate, rd.tenureMonths),
                                style = MaterialTheme.typography.bodySmall
                            )
                            Spacer(Modifier.height(8.dp))
                            val (principalOrMatured, accruedInterest) = valuesByAccount[rd.id] ?: (0.0 to 0.0)
                            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                Text("Principal: ₹%.0f".format(principalOrMatured), style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "Matures %s · ₹%.0f".format(dateFormat.format(Date(RdCalculator.maturityDate(rd))), RdCalculator.maturityValue(rd)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            if (accruedInterest > 0) {
                                Text(
                                    "Accrued interest (not yet matured): ₹%.0f".format(accruedInterest),
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
}

@HiltViewModel
class AddEditRdViewModel @Inject constructor(
    private val recurringDepositDao: RecurringDepositDao,
    private val rdInstallmentDao: RdInstallmentDao,
    private val session: SessionManager
) : ViewModel() {
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage
    fun clearError() { _errorMessage.value = null }

    suspend fun loadExisting(id: Long): RecurringDepositEntity? {
        val bookId = session.activeBookId.value ?: return null
        return recurringDepositDao.getActive(bookId).first().find { it.id == id }
    }

    fun save(
        existingId: Long?, name: String, bankName: String, accountNumber: String,
        monthlyInstallment: Double, startDate: Long, tenureMonths: Int, interestRate: Double,
        compoundingFrequency: CompoundingFrequency, autoRenewMode: AutoRenewMode,
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
                val entity = RecurringDepositEntity(
                    id = existingId ?: 0, bookId = bookId, name = name,
                    bankName = bankName.ifBlank { null }, accountOrCertificateNumber = accountNumber.ifBlank { null },
                    monthlyInstallment = monthlyInstallment, startDate = startDate, tenureMonths = tenureMonths,
                    interestRate = interestRate, compoundingFrequency = compoundingFrequency, autoRenewMode = autoRenewMode
                )
                if (existingId == null) {
                    val newId = recurringDepositDao.insert(entity)
                    // Per explicit request — this used to silently bulk-generate
                    // entries here, which broke the app's own "no silent
                    // auto-posting" principle (every other automated
                    // calculation elsewhere shows a confirmation first). Now
                    // the account is created, and if it's back-dated, control
                    // returns to the caller to show a confirmation popup
                    // BEFORE any entries are actually generated.
                    // Real calendar-month counting, not average-days
                    // division — this was the actual bug: dividing by an
                    // average 30.44-day month undercounts near a month
                    // boundary (e.g. Aug 10 to Sept 11 is 32 real days,
                    // which truncates to "1 month" even though the Sept
                    // 10 installment has genuinely also come due). This
                    // walks the real schedule instead.
                    var elapsedMonths = 0
                    val scheduleCal = Calendar.getInstance().apply { timeInMillis = startDate }
                    val now = System.currentTimeMillis()
                    while (scheduleCal.timeInMillis <= now && elapsedMonths < tenureMonths) {
                        elapsedMonths++
                        scheduleCal.add(Calendar.MONTH, 1)
                    }
                    if (elapsedMonths > 0) {
                        onNeedsBackDateConfirm(newId, elapsedMonths)
                        return@launch
                    }
                } else {
                    recurringDepositDao.update(entity)
                }
                onDone()
            } catch (e: Exception) {
                _errorMessage.value = "Couldn't save RD: ${e.message}"
            }
        }
    }

    /** Generates the back-dated entries — only ever called after the user
     *  has confirmed via the popup, and using the CONFIRMED recurring
     *  deposit date as the schedule anchor (which may differ from the
     *  account's start date — e.g., opened on the 16th, but deposits
     *  actually process on the 5th of each month). The amount is never
     *  asked for again here — it's calculated automatically from the
     *  account's own fixed monthly installment, exactly as requested. */
    fun generateBackDatedEntries(newId: Long, monthlyInstallment: Double, recurringDepositDate: Long, elapsedMonths: Int, onDone: () -> Unit) {
        viewModelScope.launch {
            try {
                val autoEntries = (1..elapsedMonths).map { monthIndex ->
                    val installmentDate = Calendar.getInstance().apply {
                        timeInMillis = recurringDepositDate
                        add(Calendar.MONTH, monthIndex - 1)
                    }.timeInMillis
                    RdInstallmentEntity(rdAccountId = newId, amount = monthlyInstallment, installmentDate = installmentDate)
                }
                rdInstallmentDao.insertAll(autoEntries)
                onDone()
            } catch (e: Exception) {
                _errorMessage.value = "Couldn't generate entries: ${e.message}"
            }
        }
    }

    fun archive(id: Long, onDone: () -> Unit) {
        viewModelScope.launch { recurringDepositDao.archive(id); onDone() }
    }
}

@HiltViewModel
class RdDetailViewModel @Inject constructor(
    private val recurringDepositDao: RecurringDepositDao,
    private val rdInstallmentDao: RdInstallmentDao,
    private val session: SessionManager
) : ViewModel() {

    private val _currentValue = MutableStateFlow(0.0)
    val currentValue: StateFlow<Double> = _currentValue

    // Per explicit request: unposted interest isn't part of the main
    // total until the RD actually matures. currentValue above now holds
    // the principal-or-matured figure; accruedInterest is the separate,
    // not-yet-realized portion.
    private val _accruedInterest = MutableStateFlow(0.0)
    val accruedInterest: StateFlow<Double> = _accruedInterest

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage
    fun clearError() { _errorMessage.value = null }

    fun installments(rdAccountId: Long) = rdInstallmentDao.getInstallments(rdAccountId)

    suspend fun loadAccount(rdAccountId: Long): RecurringDepositEntity? {
        val bookId = session.activeBookId.value ?: return null
        return recurringDepositDao.getActive(bookId).first().find { it.id == rdAccountId }
    }

    /** suspend, not fire-and-forget — addEntry/updateEntry/deleteEntry
     *  need to actually wait for this to finish before calling onDone(),
     *  which the previous fire-and-forget version (launching its own
     *  separate coroutine) did not guarantee. */
    private suspend fun refreshValueNow(rdAccountId: Long) {
        val account = loadAccount(rdAccountId) ?: return
        val installments = rdInstallmentDao.getInstallmentsOnce(rdAccountId)
        val rawPrincipal = installments.sumOf { it.amount }
        val liveValue = if (installments.isNotEmpty()) {
            RdCalculator.currentValueFromEntries(account, installments)
        } else {
            RdCalculator.currentValue(account)
        }
        val isMatured = System.currentTimeMillis() >= RdCalculator.maturityDate(account)
        if (isMatured) {
            _currentValue.value = liveValue
            _accruedInterest.value = 0.0
        } else {
            _currentValue.value = rawPrincipal
            _accruedInterest.value = (liveValue - rawPrincipal).coerceAtLeast(0.0)
        }
    }

    fun refreshValue(rdAccountId: Long) {
        viewModelScope.launch { refreshValueNow(rdAccountId) }
    }

    fun addEntry(rdAccountId: Long, amount: Double, date: Long, onDone: () -> Unit) {
        viewModelScope.launch {
            try {
                rdInstallmentDao.insert(RdInstallmentEntity(rdAccountId = rdAccountId, amount = amount, installmentDate = date))
                refreshValueNow(rdAccountId)
                onDone()
            } catch (e: Exception) {
                _errorMessage.value = "Couldn't save entry: ${e.message}"
            }
        }
    }

    fun updateEntry(entry: RdInstallmentEntity, amount: Double, date: Long, onDone: () -> Unit) {
        viewModelScope.launch {
            try {
                rdInstallmentDao.update(entry.copy(amount = amount, installmentDate = date))
                refreshValueNow(entry.rdAccountId)
                onDone()
            } catch (e: Exception) {
                _errorMessage.value = "Couldn't update entry: ${e.message}"
            }
        }
    }

    fun deleteEntry(entry: RdInstallmentEntity, onDone: () -> Unit) {
        viewModelScope.launch {
            try {
                rdInstallmentDao.delete(entry.id)
                refreshValueNow(entry.rdAccountId)
                onDone()
            } catch (e: Exception) {
                _errorMessage.value = "Couldn't delete entry: ${e.message}"
            }
        }
    }
}

/**
 * RD entry list — per explicit request, tapping an RD card opens THIS
 * screen (entries, edit/delete per entry), never the account-edit screen
 * directly. The Edit icon in the top bar is the only path to changing the
 * RD's own details (name, bank, rate, tenure...).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RdDetailScreen(rdAccountId: Long, onBack: () -> Unit, onEditAccount: (Long) -> Unit, viewModel: RdDetailViewModel = hiltViewModel()) {
    var account by remember { mutableStateOf<RecurringDepositEntity?>(null) }
    val installments by viewModel.installments(rdAccountId).collectAsState(initial = emptyList())
    val currentValue by viewModel.currentValue.collectAsState()
    val accruedInterest by viewModel.accruedInterest.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var editingEntry by remember { mutableStateOf<RdInstallmentEntity?>(null) }
    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy", Locale.US) }

    LaunchedEffect(rdAccountId) {
        account = viewModel.loadAccount(rdAccountId)
        viewModel.refreshValue(rdAccountId)
    }
    // Re-run the value calculation whenever entries change — without
    // this, the displayed value stays stuck at whatever was computed
    // once at screen-open (which falls back to the old theoretical
    // schedule if there were zero entries at that moment), even after
    // adding/editing/deleting a real entry. This was a real bug: EPF's
    // detail screen already had this second trigger, RD's didn't.
    LaunchedEffect(installments) { viewModel.refreshValue(rdAccountId) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(account?.name ?: "RD") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = {
                    IconButton(onClick = { onEditAccount(rdAccountId) }) { Icon(Icons.Default.Edit, contentDescription = "Edit RD Details") }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) { Icon(Icons.Default.Add, contentDescription = "Add Entry") }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Text("Principal", style = MaterialTheme.typography.bodyMedium)
            Text("₹%.2f".format(currentValue), style = MaterialTheme.typography.displayLarge)
            if (accruedInterest > 0) {
                Text(
                    "+ ₹%.2f accrued interest (not yet matured)".format(accruedInterest),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            account?.let {
                Text(
                    "₹%.0f/mo · %.2f%% · %d months".format(it.monthlyInstallment, it.interestRate, it.tenureMonths),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(16.dp))
            Text("Entries", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(installments.sortedByDescending { it.installmentDate }) { entry ->
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth().clickable(onClick = { editingEntry = entry })
                    ) {
                        Text(dateFormat.format(Date(entry.installmentDate)), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "₹%.0f".format(entry.amount),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (entry.amount < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        RdEntryDialog(
            title = "Add Entry",
            initialAmount = account?.monthlyInstallment?.let { "%.0f".format(it) } ?: "",
            initialDate = System.currentTimeMillis(),
            onDismiss = { showAddDialog = false },
            onDelete = null,
            onSave = { amount, date -> viewModel.addEntry(rdAccountId, amount, date) { showAddDialog = false } }
        )
    }

    editingEntry?.let { entry ->
        RdEntryDialog(
            title = "Edit Entry",
            initialAmount = "%.0f".format(entry.amount),
            initialDate = entry.installmentDate,
            onDismiss = { editingEntry = null },
            onDelete = { viewModel.deleteEntry(entry) { editingEntry = null } },
            onSave = { amount, date -> viewModel.updateEntry(entry, amount, date) { editingEntry = null } }
        )
    }

    // Diagnostic error visibility, added specifically to investigate a
    // reported bug where entries appeared to silently do nothing —
    // surfaces any actual exception instead of failing invisibly.
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
private fun RdEntryDialog(
    title: String,
    initialAmount: String,
    initialDate: Long,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)?,
    onSave: (amount: Double, date: Long) -> Unit
) {
    var amountText by remember { mutableStateOf(initialAmount) }
    var date by remember { mutableStateOf(initialDate) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Amount") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
                Spacer(Modifier.height(8.dp))
                DateField(label = "Date", selectedMillis = date, onDateSelected = { date = it })
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val amount = amountText.toDoubleOrNull()
                if (amount != null && amount > 0) onSave(amount, date)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditRdScreen(existingId: Long?, onBack: () -> Unit, onSaved: () -> Unit, viewModel: AddEditRdViewModel = hiltViewModel()) {
    var bankName by remember { mutableStateOf("") }
    var accountNumber by remember { mutableStateOf("") }
    var installmentText by remember { mutableStateOf("") }
    var startDate by remember { mutableStateOf(System.currentTimeMillis()) }
    var tenureText by remember { mutableStateOf("") }
    var rateText by remember { mutableStateOf("") }
    var compoundingFrequency by remember { mutableStateOf(CompoundingFrequency.QUARTERLY) }
    var autoRenewMode by remember { mutableStateOf(AutoRenewMode.DISABLED) }
    // Triple(newRdId, elapsedMonths, defaultRecurringDepositDate) — set
    // when the account was just created back-dated, driving the
    // confirmation popup below. Null means no popup needed.
    var pendingBackDateConfirm by remember { mutableStateOf<Triple<Long, Int, Long>?>(null) }
    val errorMessage by viewModel.errorMessage.collectAsState()
    var loaded by remember { mutableStateOf(existingId == null) }
    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy", Locale.US) }

    LaunchedEffect(existingId) {
        if (existingId != null) {
            viewModel.loadExisting(existingId)?.let {
                bankName = it.bankName ?: ""; accountNumber = it.accountOrCertificateNumber ?: ""
                installmentText = it.monthlyInstallment.toString(); startDate = it.startDate
                tenureText = it.tenureMonths.toString(); rateText = it.interestRate.toString()
                compoundingFrequency = it.compoundingFrequency
                autoRenewMode = it.autoRenewMode
            }
            loaded = true
        }
    }

    val installment = installmentText.toDoubleOrNull() ?: 0.0
    val tenure = tenureText.toIntOrNull() ?: 0
    val rate = rateText.toDoubleOrNull() ?: 0.0
    // Name is now always the combination of Bank Name and Account
    // Number, per explicit request, computed rather than typed
    // separately.
    val name = listOf(bankName, accountNumber).filter { it.isNotBlank() }.joinToString(" ")
    val canSave = name.isNotBlank() && installment > 0 && tenure > 0 && rate > 0

    val previewRd = remember(installment, tenure, rate, startDate, compoundingFrequency) {
        if (canSave) RecurringDepositEntity(
            bookId = 0, name = "", monthlyInstallment = installment, startDate = startDate,
            tenureMonths = tenure, interestRate = rate, compoundingFrequency = compoundingFrequency
        ) else null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (existingId == null) "New RD" else "Edit RD") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } }
            )
        }
    ) { padding ->
        if (!loaded) return@Scaffold
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()).imePadding()
        ) {
            OutlinedTextField(value = bankName, onValueChange = { bankName = it }, label = { Text("Bank Name") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = accountNumber, onValueChange = { accountNumber = it }, label = { Text("Account Number") }, modifier = Modifier.fillMaxWidth())
            // Name field removed — per explicit request, Name is now
            // always the combination of Bank Name and Account Number.
            if (bankName.isNotBlank() || accountNumber.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text("Will be saved as: \"$name\"", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = installmentText,
                onValueChange = { installmentText = it.filter { c -> c.isDigit() || c == '.' } },
                label = { Text("Monthly installment") },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = rateText,
                    onValueChange = { rateText = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Interest rate %") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = tenureText,
                    onValueChange = { tenureText = it.filter { c -> c.isDigit() } },
                    label = { Text("Tenure (months)") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(8.dp))
            DateField(label = "Start date", selectedMillis = startDate, onDateSelected = { startDate = it })

            Spacer(Modifier.height(8.dp))
            Text("Compounding frequency", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            SingleChoiceSegmentedButtonRow {
                listOf("Monthly" to CompoundingFrequency.MONTHLY, "Quarterly" to CompoundingFrequency.QUARTERLY, "Annually" to CompoundingFrequency.ANNUALLY).forEachIndexed { index, (label, f) ->
                    SegmentedButton(
                        selected = compoundingFrequency == f, onClick = { compoundingFrequency = f },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = 3)
                    ) { Text(label) }
                }
            }

            Spacer(Modifier.height(8.dp))
            Text("Auto-Renew on Maturity", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                listOf(
                    "Disabled" to AutoRenewMode.DISABLED,
                    "Principal Only" to AutoRenewMode.PRINCIPAL_ONLY,
                    "Principal + Interest" to AutoRenewMode.PRINCIPAL_PLUS_INTEREST
                ).forEach { (label, mode) ->
                    FilterChip(selected = autoRenewMode == mode, onClick = { autoRenewMode = mode }, label = { Text(label) })
                }
            }

            if (previewRd != null) {
                Spacer(Modifier.height(16.dp))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Preview", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                            Text("Matures", style = MaterialTheme.typography.bodyMedium)
                            Text(dateFormat.format(Date(RdCalculator.maturityDate(previewRd))), style = MaterialTheme.typography.bodyMedium)
                        }
                        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                            Text("Maturity value", style = MaterialTheme.typography.bodyMedium)
                            Text("₹%.2f".format(RdCalculator.maturityValue(previewRd)), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = {
                    viewModel.save(
                        existingId, name, bankName, accountNumber, installment, startDate, tenure, rate, compoundingFrequency, autoRenewMode,
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
            onDismissRequest = { }, // must explicitly confirm or skip
            title = { Text("Back-dated account") },
            text = {
                Column {
                    Text(
                        "This account was opened %d month(s) ago. Based on ₹%.0f/month, we can create entries for the months already elapsed — the amount is calculated automatically. What date each month does the deposit actually happen on?".format(elapsedMonths, installment)
                    )
                    Spacer(Modifier.height(12.dp))
                    DateField(label = "Recurring deposit date", selectedMillis = recurringDepositDate, onDateSelected = { recurringDepositDate = it })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.generateBackDatedEntries(newId, installment, recurringDepositDate, elapsedMonths) {
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
