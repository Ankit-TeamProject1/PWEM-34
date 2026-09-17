package com.teamproject1.dailyexpensetracker.feature.wealth.epf

import androidx.compose.foundation.clickable
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
import com.teamproject1.dailyexpensetracker.core.database.dao.EpfDao
import com.teamproject1.dailyexpensetracker.core.database.dao.WealthRateHistoryDao
import com.teamproject1.dailyexpensetracker.core.database.entity.EpfAccountEntity
import com.teamproject1.dailyexpensetracker.core.database.entity.EpfContributionEntity
import com.teamproject1.dailyexpensetracker.core.database.entity.EpfTileType
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
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import androidx.compose.material.icons.automirrored.filled.ArrowBack

@HiltViewModel
class EpfListViewModel @Inject constructor(
    private val epfDao: EpfDao,
    private val rateHistoryDao: WealthRateHistoryDao,
    private val session: SessionManager
) : ViewModel() {
    val accounts = session.activeBookId.filterNotNull()
        .flatMapLatest { bookId -> epfDao.getActiveAccounts(bookId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Value split into (Employee+Employer principal, Employee+Employer
     *  accrued interest, Pension) — per explicit request, unposted
     *  interest isn't part of the main total shown. Pension is
     *  unaffected since it never had interest to split out. */
    suspend fun currentValueSplit(accountId: Long): Triple<Double, Double, Double> {
        val all = epfDao.getContributionsOnce(accountId)
        val eeContributions = all.filter { it.tileType == EpfTileType.EMPLOYEE_EMPLOYER }.map { it.contributionDate to it.amount }
        val eePrincipal = eeContributions.sumOf { it.second }
        val rates = rateHistoryDao.getHistoryOnce(RateInstrument.EPF)
        val eeLiveValue = PpfEpfCalculator.currentValue(eeContributions, rates)
        val pensionTotal = all.filter { it.tileType == EpfTileType.PENSION }.sumOf { it.amount }
        return Triple(eePrincipal, (eeLiveValue - eePrincipal).coerceAtLeast(0.0), pensionTotal)
    }

    /** Real calendar-month counting, matching the fix already applied to
     *  RD — an earlier average-days approach undercounted near month
     *  boundaries. Walks the actual schedule instead of estimating. */
    /** Counts months whose contribution should already exist, per the
     *  real EPF payroll lag: a month's contribution is deposited with
     *  the FOLLOWING month's salary cycle, so the current month itself
     *  is never counted as elapsed yet — only strictly past months are.
     *  If it's currently September, an account opened in August counts
     *  as 1 month elapsed (August only), not 2. */
    fun elapsedMonthsSince(startDate: Long, cappedAt: Int = 600): Int {
        var count = 0
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = startDate }
        val nowCal = java.util.Calendar.getInstance()
        val nowYearMonth = nowCal.get(java.util.Calendar.YEAR) * 12 + nowCal.get(java.util.Calendar.MONTH)
        while (count < cappedAt) {
            val scheduledYearMonth = cal.get(java.util.Calendar.YEAR) * 12 + cal.get(java.util.Calendar.MONTH)
            if (scheduledYearMonth >= nowYearMonth) break
            count++
            cal.add(java.util.Calendar.MONTH, 1)
        }
        return count
    }

    /** Creates the account only — no auto-generated entries. Used
     *  directly for a fresh account, or after the user confirms the
     *  back-dated confirmation popup. */
    fun createAccount(
        name: String, companyName: String, pfAccountNumber: String, uan: String, openDate: Long,
        monthlyEe: Double?, monthlyPension: Double?, onDone: (newId: Long) -> Unit
    ) {
        viewModelScope.launch {
            val bookId = session.activeBookId.value ?: return@launch
            val effectiveName = name.ifBlank {
                listOfNotNull(companyName.ifBlank { null }, pfAccountNumber.ifBlank { null }).joinToString(" - ").ifBlank { "EPF Account" }
            }
            val newId = epfDao.insertAccount(
                EpfAccountEntity(
                    bookId = bookId, name = effectiveName, companyName = companyName.ifBlank { null },
                    pfAccountNumber = pfAccountNumber.ifBlank { null }, uan = uan.ifBlank { null }, accountOpenDate = openDate,
                    monthlyEeContribution = monthlyEe, monthlyPensionContribution = monthlyPension,
                    // Defaults to the account's own open date — for a
                    // freshly-opened (not back-dated) account there's no
                    // real contribution history yet to derive a better
                    // anchor from. The back-dated confirmation flow below
                    // refines this to a more precise, explicitly-confirmed
                    // date when the account has elapsed months.
                    recurringDepositDate = openDate
                )
            )
            onDone(newId)
        }
    }

    /** Generates back-dated entries for BOTH tiles, only after explicit
     *  confirmation — per the app's "no silent auto-posting" principle,
     *  matching RD's redesign. Uses the CONFIRMED recurring contribution
     *  date as the schedule anchor, which may differ from the account's
     *  open date, and persists that same date onto the account's own
     *  recurringDepositDate field — per explicit request, so the ongoing
     *  due-reminder uses this more precise, explicitly-confirmed date
     *  rather than the default it was created with. */
    fun generateBackDatedEntries(
        newId: Long, recurringContributionDate: Long, elapsedMonths: Int,
        monthlyEe: Double?, monthlyPension: Double?, onDone: () -> Unit
    ) {
        viewModelScope.launch {
            try {
                if (monthlyEe != null && monthlyEe > 0) {
                    val eeEntries = (0 until elapsedMonths).map { monthIndex ->
                        val date = java.util.Calendar.getInstance().apply { timeInMillis = recurringContributionDate; add(java.util.Calendar.MONTH, monthIndex) }.timeInMillis
                        EpfContributionEntity(epfAccountId = newId, amount = monthlyEe, contributionDate = date, tileType = EpfTileType.EMPLOYEE_EMPLOYER)
                    }
                    eeEntries.forEach { epfDao.insertContribution(it) }
                }
                if (monthlyPension != null && monthlyPension > 0) {
                    val pensionEntries = (0 until elapsedMonths).map { monthIndex ->
                        val date = java.util.Calendar.getInstance().apply { timeInMillis = recurringContributionDate; add(java.util.Calendar.MONTH, monthIndex) }.timeInMillis
                        EpfContributionEntity(epfAccountId = newId, amount = monthlyPension, contributionDate = date, tileType = EpfTileType.PENSION)
                    }
                    pensionEntries.forEach { epfDao.insertContribution(it) }
                }
                val bookId = session.activeBookId.value
                val account = bookId?.let { epfDao.getActiveAccounts(it).first().find { a -> a.id == newId } }
                if (account != null) epfDao.updateAccount(account.copy(recurringDepositDate = recurringContributionDate))
                onDone()
            } catch (e: Exception) {
                _errorMessage.value = "Couldn't generate entries: ${e.message}"
            }
        }
    }

    private val _errorMessage = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val errorMessage: kotlinx.coroutines.flow.StateFlow<String?> = _errorMessage
    fun clearError() { _errorMessage.value = null }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EpfListScreen(onBack: () -> Unit, onOpenAccount: (Long) -> Unit, viewModel: EpfListViewModel = hiltViewModel()) {
    val accounts by viewModel.accounts.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    val valuesCache = remember { mutableStateMapOf<Long, Triple<Double, Double, Double>>() } // eePrincipal, eeAccruedInterest, pensionValue
    var pendingBackDateConfirm by remember { mutableStateOf<EpfBackDateConfirm?>(null) }
    val errorMessage by viewModel.errorMessage.collectAsState()

    LaunchedEffect(accounts) {
        accounts.forEach { account ->
            // Shown separately per explicit request, since a single
            // combined figure previously hid which part was actually
            // interest-bearing.
            valuesCache[account.id] = viewModel.currentValueSplit(account.id)
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("EPF") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } }) },
        floatingActionButton = { FloatingActionButton(onClick = { showAddDialog = true }) { Icon(Icons.Default.Add, contentDescription = "Add EPF Account") } }
    ) { padding ->
        if (accounts.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = androidx.compose.ui.Alignment.Center) {
                Text("No EPF accounts added yet. Add one for each employer/UAN you have.", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            val totalEe = valuesCache.values.sumOf { it.first }
            val totalEeAccrued = valuesCache.values.sumOf { it.second }
            val totalPension = valuesCache.values.sumOf { it.third }
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Text("Total EPF Value (all accounts)", style = MaterialTheme.typography.bodyMedium)
                    Text("₹%.0f".format(totalEe + totalPension), style = MaterialTheme.typography.titleLarge)
                    if (totalEeAccrued > 0) {
                        Text(
                            "+ ₹%.0f accrued interest (not yet posted)".format(totalEeAccrued),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }
                items(accounts) { account ->
                    val (eeValue, eeAccrued, pensionValue) = valuesCache[account.id] ?: Triple(0.0, 0.0, 0.0)
                    Card(modifier = Modifier.fillMaxWidth(), onClick = { onOpenAccount(account.id) }) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(account.name, style = MaterialTheme.typography.titleLarge)
                            if (account.uan != null) Text("UAN: ${account.uan}", style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(8.dp))
                            // Shown separately per explicit request — was
                            // previously one combined total.
                            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                Column {
                                    Text("Employee+Employer", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("₹%.0f".format(eeValue), style = MaterialTheme.typography.titleMedium)
                                    if (eeAccrued > 0) {
                                        Text(
                                            "+ ₹%.0f accrued".format(eeAccrued),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                                    Text("Pension", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("₹%.0f".format(pensionValue), style = MaterialTheme.typography.titleMedium)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        var companyName by remember { mutableStateOf("") }
        var pfAccountNumber by remember { mutableStateOf("") }
        // Pre-filled from the most recently opened EPF account's UAN,
        // per explicit request — UAN follows the person, not the
        // employer, so it's almost always the same across accounts.
        // Still fully editable in case it needs to differ.
        var uan by remember { mutableStateOf(accounts.firstOrNull()?.uan ?: "") }
        var openDate by remember { mutableStateOf(System.currentTimeMillis()) }
        var monthlyEeText by remember { mutableStateOf("") }
        var monthlyPensionText by remember { mutableStateOf("") }
        // Name is now always the combination of Company Name and PF
        // Account Number, per explicit request, computed rather than
        // typed separately. Previously this was a manual field with a
        // misleading hint claiming a default that was never actually
        // implemented — leaving it blank would have saved an empty name.
        val name = listOf(companyName, pfAccountNumber).filter { it.isNotBlank() }.joinToString(" ")
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("New EPF Account") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    OutlinedTextField(value = companyName, onValueChange = { companyName = it }, label = { Text("Company Name") })
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = pfAccountNumber, onValueChange = { pfAccountNumber = it }, label = { Text("PF Account Number") })
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = uan, onValueChange = { uan = it }, label = { Text("UAN (optional)") })
                    if (name.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text("Will be saved as: \"$name\"", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(8.dp))
                    DateField(label = "Account opened", selectedMillis = openDate, onDateSelected = { openDate = it })
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = monthlyEeText, onValueChange = { monthlyEeText = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text("Fixed Monthly Employee+Employer Amount (optional)") },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal)
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = monthlyPensionText, onValueChange = { monthlyPensionText = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text("Fixed Monthly Pension Amount (optional)") },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal)
                    )
                    Text(
                        "Setting these lets the app remind you monthly and auto-fill past months if this account was opened earlier.",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = {
                        val monthlyEe = monthlyEeText.toDoubleOrNull()
                        val monthlyPension = monthlyPensionText.toDoubleOrNull()
                        viewModel.createAccount(name, companyName, pfAccountNumber, uan, openDate, monthlyEe, monthlyPension) { newId ->
                            showAddDialog = false
                            val elapsedMonths = viewModel.elapsedMonthsSince(openDate)
                            if (elapsedMonths > 0 && (monthlyEe != null || monthlyPension != null)) {
                                pendingBackDateConfirm = EpfBackDateConfirm(newId, elapsedMonths, openDate, monthlyEe, monthlyPension)
                            } else {
                                onOpenAccount(newId)
                            }
                        }
                    }
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showAddDialog = false }) { Text("Cancel") } }
        )
    }

    pendingBackDateConfirm?.let { confirm ->
        var recurringDate by remember { mutableStateOf(confirm.defaultDate) }
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Back-dated account") },
            text = {
                Column {
                    Text(
                        "This account was opened %d month(s) ago. We can create entries for the months already elapsed for the amounts you entered. What date each month are contributions actually made on?".format(confirm.elapsedMonths)
                    )
                    Spacer(Modifier.height(12.dp))
                    DateField(label = "Recurring contribution date", selectedMillis = recurringDate, onDateSelected = { recurringDate = it })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.generateBackDatedEntries(confirm.newId, recurringDate, confirm.elapsedMonths, confirm.monthlyEe, confirm.monthlyPension) {
                        pendingBackDateConfirm = null
                        onOpenAccount(confirm.newId)
                    }
                }) { Text("Generate Entries") }
            },
            dismissButton = {
                TextButton(onClick = { pendingBackDateConfirm = null; onOpenAccount(confirm.newId) }) { Text("Skip — I'll add entries myself") }
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

private data class EpfBackDateConfirm(val newId: Long, val elapsedMonths: Int, val defaultDate: Long, val monthlyEe: Double?, val monthlyPension: Double?)

@HiltViewModel
class EpfDetailViewModel @Inject constructor(
    private val epfDao: EpfDao,
    private val rateHistoryDao: WealthRateHistoryDao,
    private val session: SessionManager
) : ViewModel() {

    private val _eeValue = MutableStateFlow(0.0)
    val eeValue: StateFlow<Double> = _eeValue

    // Per explicit request: unposted interest isn't part of the main
    // total. eeValue above now holds Employee+Employer's principal
    // (raw contributions summed, no compounding); this holds the
    // separate, live, not-yet-posted portion. EPF has no confirmation
    // mechanism like PPF's March 31 popup, so this will generally always
    // show something once contributions have had time to accrue.
    private val _eeAccruedInterest = MutableStateFlow(0.0)
    val eeAccruedInterest: StateFlow<Double> = _eeAccruedInterest

    private val _pensionTotal = MutableStateFlow(0.0)
    val pensionTotal: StateFlow<Double> = _pensionTotal

    fun contributions(accountId: Long) = epfDao.getContributions(accountId)

    suspend fun loadAccount(accountId: Long): EpfAccountEntity? {
        val bookId = session.activeBookId.value ?: return null
        return epfDao.getActiveAccounts(bookId).first().find { it.id == accountId }
    }

    suspend fun otherAccounts(excludingId: Long): List<EpfAccountEntity> {
        val bookId = session.activeBookId.value ?: return emptyList()
        return epfDao.getActiveAccounts(bookId).first().filter { it.id != excludingId }
    }

    fun refreshValue(accountId: Long) {
        viewModelScope.launch {
            val all = epfDao.getContributionsOnce(accountId)
            val eeContributions = all.filter { it.tileType == EpfTileType.EMPLOYEE_EMPLOYER }.map { it.contributionDate to it.amount }
            val eePrincipal = eeContributions.sumOf { it.second }
            val rates = rateHistoryDao.getHistoryOnce(RateInstrument.EPF)
            val liveValue = PpfEpfCalculator.currentValue(eeContributions, rates)
            _eeValue.value = eePrincipal
            _eeAccruedInterest.value = (liveValue - eePrincipal).coerceAtLeast(0.0)
            _pensionTotal.value = all.filter { it.tileType == EpfTileType.PENSION }.sumOf { it.amount }
        }
    }

    fun addEntry(accountId: Long, amount: Double, date: Long, tileType: EpfTileType, isTransfer: Boolean = false, onDone: () -> Unit) {
        viewModelScope.launch {
            epfDao.insertContribution(EpfContributionEntity(epfAccountId = accountId, amount = amount, contributionDate = date, tileType = tileType, isTransfer = isTransfer))
            refreshValue(accountId)
            onDone()
        }
    }

    fun updateEntry(entry: EpfContributionEntity, amount: Double, date: Long, onDone: () -> Unit) {
        viewModelScope.launch {
            epfDao.updateContribution(entry.copy(amount = amount, contributionDate = date))
            refreshValue(entry.epfAccountId)
            onDone()
        }
    }

    fun deleteEntry(entry: EpfContributionEntity, onDone: () -> Unit) {
        viewModelScope.launch {
            epfDao.deleteContribution(entry.id)
            refreshValue(entry.epfAccountId)
            onDone()
        }
    }

    fun archiveAccount(accountId: Long, onDone: () -> Unit) {
        viewModelScope.launch { epfDao.archiveAccount(accountId); onDone() }
    }

    /** Step up/down is just editing the fixed amount directly, per
     *  explicit request — same approach as Mutual Fund SIP, no separate
     *  scheduled-increase mechanism. */
    fun updateFixedAmount(account: EpfAccountEntity, tileType: EpfTileType, newAmount: Double, onDone: () -> Unit) {
        viewModelScope.launch {
            val updated = if (tileType == EpfTileType.EMPLOYEE_EMPLOYER) {
                account.copy(monthlyEeContribution = newAmount)
            } else {
                account.copy(monthlyPensionContribution = newAmount)
            }
            epfDao.updateAccount(updated)
            onDone()
        }
    }

    fun updateAccountDetails(account: EpfAccountEntity, name: String, companyName: String, pfAccountNumber: String, uan: String, recurringDepositDate: Long, onDone: () -> Unit) {
        viewModelScope.launch {
            epfDao.updateAccount(account.copy(name = name, companyName = companyName.ifBlank { null }, pfAccountNumber = pfAccountNumber.ifBlank { null }, uan = uan.ifBlank { null }, recurringDepositDate = recurringDepositDate))
            onDone()
        }
    }

    /** Balance transfer — per explicit request, lives on the receiving
     *  (new) account's detail screen: pick the OLD account as source,
     *  compute its current Employee+Employer value AND Pension total,
     *  create one transfer entry on THIS account for each, then archive
     *  the source account since its balance has now moved. */
    fun transferIn(sourceAccountId: Long, destinationAccountId: Long, onDone: () -> Unit) {
        viewModelScope.launch {
            val sourceContributions = epfDao.getContributionsOnce(sourceAccountId)
            val sourceEe = sourceContributions.filter { it.tileType == EpfTileType.EMPLOYEE_EMPLOYER }.map { it.contributionDate to it.amount }
            val rates = rateHistoryDao.getHistoryOnce(RateInstrument.EPF)
            val sourceEeValue = PpfEpfCalculator.currentValue(sourceEe, rates)
            val sourcePensionTotal = sourceContributions.filter { it.tileType == EpfTileType.PENSION }.sumOf { it.amount }

            val now = System.currentTimeMillis()
            if (sourceEeValue > 0) {
                epfDao.insertContribution(EpfContributionEntity(epfAccountId = destinationAccountId, amount = sourceEeValue, contributionDate = now, tileType = EpfTileType.EMPLOYEE_EMPLOYER, isTransfer = true))
            }
            if (sourcePensionTotal > 0) {
                epfDao.insertContribution(EpfContributionEntity(epfAccountId = destinationAccountId, amount = sourcePensionTotal, contributionDate = now, tileType = EpfTileType.PENSION, isTransfer = true))
            }
            epfDao.archiveAccount(sourceAccountId)
            refreshValue(destinationAccountId)
            onDone()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EpfDetailScreen(accountId: Long, onBack: () -> Unit, viewModel: EpfDetailViewModel = hiltViewModel()) {
    var account by remember { mutableStateOf<EpfAccountEntity?>(null) }
    val contributions by viewModel.contributions(accountId).collectAsState(initial = emptyList())
    val eeValue by viewModel.eeValue.collectAsState()
    val eeAccruedInterest by viewModel.eeAccruedInterest.collectAsState()
    val pensionTotal by viewModel.pensionTotal.collectAsState()
    var showAddDialog by remember { mutableStateOf<EpfTileType?>(null) }
    var editingEntry by remember { mutableStateOf<EpfContributionEntity?>(null) }
    var showEditAccountDialog by remember { mutableStateOf(false) }
    var showTransferDialog by remember { mutableStateOf(false) }
    var showEditFixedAmountFor by remember { mutableStateOf<EpfTileType?>(null) }
    val dateFormat = remember { SimpleDateFormat("MMM yyyy", Locale.US) }

    LaunchedEffect(accountId) {
        account = viewModel.loadAccount(accountId)
        viewModel.refreshValue(accountId)
    }
    LaunchedEffect(contributions) { viewModel.refreshValue(accountId) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(account?.name ?: "EPF Account") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = {
                    TextButton(onClick = { showEditAccountDialog = true }) { Text("Edit") }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState())) {
            account?.let { acc ->
                val infoLine = listOfNotNull(
                    acc.companyName,
                    acc.pfAccountNumber?.let { "PF A/C $it" },
                    acc.uan?.let { "UAN $it" }
                ).joinToString(" · ")
                if (infoLine.isNotBlank()) {
                    Text(infoLine, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                }
            }
            Text("Total EPF Value", style = MaterialTheme.typography.bodyMedium)
            Text("₹%.2f".format(eeValue + pensionTotal), style = MaterialTheme.typography.displayLarge)
            Spacer(Modifier.height(16.dp))

            // Tile 1 — Employee + Employer (interest-bearing)
            EpfTileCard(
                title = "Employee + Employer",
                value = eeValue,
                subtitle = "Interest-bearing",
                fixedMonthlyAmount = account?.monthlyEeContribution,
                entries = contributions.filter { it.tileType == EpfTileType.EMPLOYEE_EMPLOYER },
                dateFormat = dateFormat,
                onAddEntry = { showAddDialog = EpfTileType.EMPLOYEE_EMPLOYER },
                onEntryClick = { editingEntry = it },
                onEditFixedAmount = { showEditFixedAmountFor = EpfTileType.EMPLOYEE_EMPLOYER },
                accruedInterest = eeAccruedInterest
            )
            Spacer(Modifier.height(16.dp))

            // Tile 2 — Pension (no interest, plain running total)
            EpfTileCard(
                title = "Pension",
                value = pensionTotal,
                subtitle = "No interest — plain total",
                fixedMonthlyAmount = account?.monthlyPensionContribution,
                entries = contributions.filter { it.tileType == EpfTileType.PENSION },
                dateFormat = dateFormat,
                onAddEntry = { showAddDialog = EpfTileType.PENSION },
                onEntryClick = { editingEntry = it },
                onEditFixedAmount = { showEditFixedAmountFor = EpfTileType.PENSION }
            )
            Spacer(Modifier.height(16.dp))

            // Tile 3 — Balance Transfer, per explicit request: for when a
            // new PF account is opened and the old account's balance
            // (including interest) needs to move into this one.
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Balance Transfer", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Bring in the balance from an old EPF account when you've opened this one after a job change.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { showTransferDialog = true }) { Text("Transfer In From Another Account") }
                }
            }
            Spacer(Modifier.height(16.dp))

            account?.let { acc ->
                TextButton(onClick = { viewModel.archiveAccount(acc.id, onBack) }) {
                    Text("Archive this account", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    if (showEditAccountDialog && account != null) {
        var editCompanyName by remember { mutableStateOf(account!!.companyName ?: "") }
        var editPfAccountNumber by remember { mutableStateOf(account!!.pfAccountNumber ?: "") }
        var editUan by remember { mutableStateOf(account!!.uan ?: "") }
        var editRecurringDate by remember { mutableStateOf(account!!.recurringDepositDate ?: account!!.accountOpenDate) }
        // Name is now always the combination of Company Name and PF
        // Account Number, per explicit request.
        val editName = listOf(editCompanyName, editPfAccountNumber).filter { it.isNotBlank() }.joinToString(" ")
        AlertDialog(
            onDismissRequest = { showEditAccountDialog = false },
            title = { Text("Edit EPF Account") },
            text = {
                Column {
                    OutlinedTextField(value = editCompanyName, onValueChange = { editCompanyName = it }, label = { Text("Company Name") })
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = editPfAccountNumber, onValueChange = { editPfAccountNumber = it }, label = { Text("PF Account Number") })
                    if (editName.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text("Will be saved as: \"$editName\"", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = editUan, onValueChange = { editUan = it }, label = { Text("UAN") })
                    Spacer(Modifier.height(8.dp))
                    DateField(label = "Recurring deposit date", selectedMillis = editRecurringDate, onDateSelected = { editRecurringDate = it })
                    Text(
                        "The day of the month payroll actually posts your contribution — the reminder waits for this exact day, not just a new month beginning.",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = editName.isNotBlank(),
                    onClick = {
                        viewModel.updateAccountDetails(account!!, editName, editCompanyName, editPfAccountNumber, editUan, editRecurringDate) {
                            account = account!!.copy(name = editName, companyName = editCompanyName.ifBlank { null }, pfAccountNumber = editPfAccountNumber.ifBlank { null }, uan = editUan.ifBlank { null }, recurringDepositDate = editRecurringDate)
                            showEditAccountDialog = false
                        }
                    }
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showEditAccountDialog = false }) { Text("Cancel") } }
        )
    }

    showAddDialog?.let { tileType ->
        // Contribution month defaults to LAST month, per explicit request.
        val lastMonth = remember { Calendar.getInstance().apply { add(Calendar.MONTH, -1) }.timeInMillis }
        EpfEntryDialog(
            title = "Add ${if (tileType == EpfTileType.PENSION) "Pension" else "Contribution"}",
            initialAmount = "",
            initialDate = lastMonth,
            onDismiss = { showAddDialog = null },
            onDelete = null,
            onSave = { amount, date -> viewModel.addEntry(accountId, amount, date, tileType) { showAddDialog = null } }
        )
    }

    editingEntry?.let { entry ->
        EpfEntryDialog(
            title = if (entry.isTransfer) "Edit Transfer Entry" else "Edit Entry",
            initialAmount = "%.0f".format(entry.amount),
            initialDate = entry.contributionDate,
            onDismiss = { editingEntry = null },
            onDelete = { viewModel.deleteEntry(entry) { editingEntry = null } },
            onSave = { amount, date -> viewModel.updateEntry(entry, amount, date) { editingEntry = null } }
        )
    }

    if (showTransferDialog) {
        var otherAccounts by remember { mutableStateOf<List<EpfAccountEntity>>(emptyList()) }
        var selectedSource by remember { mutableStateOf<EpfAccountEntity?>(null) }
        LaunchedEffect(Unit) { otherAccounts = viewModel.otherAccounts(excludingId = accountId) }

        AlertDialog(
            onDismissRequest = { showTransferDialog = false },
            title = { Text("Transfer In") },
            text = {
                Column {
                    if (otherAccounts.isEmpty()) {
                        Text("No other EPF accounts to transfer from.", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Text("Select the old account to transfer the balance from. It will be archived once transferred.", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(8.dp))
                        otherAccounts.forEach { other ->
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable(onClick = { selectedSource = other }).padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(other.name)
                                RadioButton(selected = selectedSource?.id == other.id, onClick = { selectedSource = other })
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    selectedSource?.let { source ->
                        viewModel.transferIn(source.id, accountId) { showTransferDialog = false }
                    }
                }) { Text("Transfer") }
            },
            dismissButton = { TextButton(onClick = { showTransferDialog = false }) { Text("Cancel") } }
        )
    }

    showEditFixedAmountFor?.let { tileType ->
        val currentAmount = if (tileType == EpfTileType.EMPLOYEE_EMPLOYER) account?.monthlyEeContribution else account?.monthlyPensionContribution
        var amountText by remember { mutableStateOf(currentAmount?.let { "%.0f".format(it) } ?: "") }
        AlertDialog(
            onDismissRequest = { showEditFixedAmountFor = null },
            title = { Text(if (tileType == EpfTileType.EMPLOYEE_EMPLOYER) "Employee+Employer Amount" else "Pension Amount") },
            text = {
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Fixed monthly amount") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val amount = amountText.toDoubleOrNull() ?: return@TextButton
                    account?.let { acc ->
                        viewModel.updateFixedAmount(acc, tileType, amount) {
                            account = if (tileType == EpfTileType.EMPLOYEE_EMPLOYER) acc.copy(monthlyEeContribution = amount) else acc.copy(monthlyPensionContribution = amount)
                            showEditFixedAmountFor = null
                        }
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showEditFixedAmountFor = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun EpfTileCard(
    title: String,
    value: Double,
    subtitle: String,
    fixedMonthlyAmount: Double?,
    entries: List<EpfContributionEntity>,
    dateFormat: SimpleDateFormat,
    onAddEntry: () -> Unit,
    onEntryClick: (EpfContributionEntity) -> Unit,
    onEditFixedAmount: () -> Unit,
    accruedInterest: Double = 0.0
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Column {
                    Text(title, style = MaterialTheme.typography.titleLarge)
                    Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = onAddEntry) { Text("+ Add") }
            }
            Text("₹%.2f".format(value), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            if (accruedInterest > 0) {
                Text(
                    "+ ₹%.2f accrued interest (not yet posted)".format(accruedInterest),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(
                    if (fixedMonthlyAmount != null) "Fixed: ₹%.0f/month".format(fixedMonthlyAmount) else "No fixed monthly amount set",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = onEditFixedAmount) { Text(if (fixedMonthlyAmount != null) "Step Up/Down" else "Set Amount") }
            }
            Spacer(Modifier.height(8.dp))
            if (entries.isEmpty()) {
                Text("No entries yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                entries.sortedByDescending { it.contributionDate }.forEach { entry ->
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth().clickable(onClick = { onEntryClick(entry) }).padding(vertical = 4.dp)
                    ) {
                        // Contribution month shown instead of self/employer
                        // type, per explicit request.
                        Text(
                            (if (entry.isTransfer) "Transfer — " else "") + dateFormat.format(Date(entry.contributionDate)),
                            style = MaterialTheme.typography.bodyMedium
                        )
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
}

@Composable
private fun EpfEntryDialog(
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
                DateField(label = "Contribution Month", selectedMillis = date, onDateSelected = { date = it })
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
                if (onDelete != null) {
                    TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}
