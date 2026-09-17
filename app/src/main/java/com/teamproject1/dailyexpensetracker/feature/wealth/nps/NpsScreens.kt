package com.teamproject1.dailyexpensetracker.feature.wealth.nps

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
import com.teamproject1.dailyexpensetracker.core.database.dao.NpsDao
import com.teamproject1.dailyexpensetracker.core.database.entity.NpsAccountEntity
import com.teamproject1.dailyexpensetracker.core.session.SessionManager
import com.teamproject1.dailyexpensetracker.feature.wealth.QuickAdjustDialog
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

@HiltViewModel
class NpsListViewModel @Inject constructor(private val npsDao: NpsDao, session: SessionManager) : ViewModel() {
    val accounts = session.activeBookId.filterNotNull()
        .flatMapLatest { bookId -> npsDao.getActive(bookId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Quick adjust — a lighter-weight path than full Edit, per the shared
     *  "log an adjustment" mechanism agreed across NPS/PPF/EPF/APY/ULIP. */
    fun quickAdjust(account: com.teamproject1.dailyexpensetracker.core.database.entity.NpsAccountEntity, newValue: Double) {
        viewModelScope.launch {
            npsDao.update(account.copy(currentValue = newValue, lastUpdatedAt = System.currentTimeMillis()))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NpsListScreen(onBack: () -> Unit, onAddNew: () -> Unit, onEdit: (Long) -> Unit, viewModel: NpsListViewModel = hiltViewModel()) {
    val accounts by viewModel.accounts.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("NPS") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddNew) { Icon(Icons.Default.Add, contentDescription = "Add NPS Account") }
        }
    ) { padding ->
        if (accounts.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = androidx.compose.ui.Alignment.Center) {
                Text("No NPS accounts added yet.", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            val total = accounts.sumOf { it.currentValue }
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Text("Total NPS Value", style = MaterialTheme.typography.bodyMedium)
                    Text("₹%.0f".format(total), style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(8.dp))
                }
                items(accounts) { account ->
                    var showAdjustDialog by remember { mutableStateOf(false) }
                    Card(modifier = Modifier.fillMaxWidth(), onClick = { onEdit(account.id) }) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(account.name, style = MaterialTheme.typography.titleLarge)
                            Text(
                                listOfNotNull(account.pran?.let { "PRAN $it" }, account.pfm, account.tier).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                Text("₹%.0f".format(account.currentValue), style = MaterialTheme.typography.titleMedium)
                                TextButton(onClick = { showAdjustDialog = true }) { Text("Adjust") }
                            }
                        }
                    }
                    if (showAdjustDialog) {
                        QuickAdjustDialog(
                            currentValue = account.currentValue,
                            onDismiss = { showAdjustDialog = false },
                            onSave = { newValue -> viewModel.quickAdjust(account, newValue); showAdjustDialog = false }
                        )
                    }
                }
            }
        }
    }
}

@HiltViewModel
class AddEditNpsViewModel @Inject constructor(
    private val npsDao: NpsDao,
    private val session: SessionManager
) : ViewModel() {
    suspend fun loadExisting(id: Long): NpsAccountEntity? {
        val bookId = session.activeBookId.value ?: return null
        return npsDao.getActive(bookId).first().find { it.id == id }
    }

    fun save(existingId: Long?, name: String, pran: String, pfm: String, tier: String, currentValue: Double, monthlyContribution: Double?, recurringDepositDate: Long?, lastContributionDate: Long?, onDone: () -> Unit) {
        viewModelScope.launch {
            val bookId = session.activeBookId.value ?: return@launch
            val entity = NpsAccountEntity(
                id = existingId ?: 0, bookId = bookId, name = name, pran = pran.ifBlank { null }, pfm = pfm, tier = tier,
                currentValue = currentValue, lastUpdatedAt = System.currentTimeMillis(),
                monthlyContribution = monthlyContribution, recurringDepositDate = recurringDepositDate,
                lastContributionDate = lastContributionDate
            )
            if (existingId == null) npsDao.insert(entity) else npsDao.update(entity)
            onDone()
        }
    }

    fun archive(id: Long, onDone: () -> Unit) {
        viewModelScope.launch { npsDao.archive(id); onDone() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditNpsScreen(existingId: Long?, onBack: () -> Unit, onSaved: () -> Unit, viewModel: AddEditNpsViewModel = hiltViewModel()) {
    var name by remember { mutableStateOf("") }
    var pran by remember { mutableStateOf("") }
    var pfm by remember { mutableStateOf("") }
    var tier by remember { mutableStateOf("TIER_I") }
    var valueText by remember { mutableStateOf("") }
    var monthlyContributionText by remember { mutableStateOf("") }
    var recurringDepositDate by remember { mutableStateOf(System.currentTimeMillis()) }
    var lastContributionDate by remember { mutableStateOf<Long?>(null) }
    var loaded by remember { mutableStateOf(existingId == null) }

    LaunchedEffect(existingId) {
        if (existingId != null) {
            viewModel.loadExisting(existingId)?.let {
                name = it.name; pran = it.pran ?: ""; pfm = it.pfm; tier = it.tier; valueText = it.currentValue.toString()
                monthlyContributionText = it.monthlyContribution?.toString() ?: ""
                recurringDepositDate = it.recurringDepositDate ?: System.currentTimeMillis()
                lastContributionDate = it.lastContributionDate
            }
            loaded = true
        }
    }

    val canSave = name.isNotBlank() && pfm.isNotBlank() && (valueText.toDoubleOrNull() ?: 0.0) >= 0

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (existingId == null) "New NPS Account" else "Edit NPS Account") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } }
            )
        }
    ) { padding ->
        if (!loaded) return@Scaffold
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()).imePadding()
        ) {
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name (e.g. NPS - HDFC Pension)") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = pran, onValueChange = { pran = it }, label = { Text("PRAN") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = pfm, onValueChange = { pfm = it }, label = { Text("Pension Fund Manager") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Text("Tier", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            SingleChoiceSegmentedButtonRow {
                listOf("Tier I" to "TIER_I", "Tier II" to "TIER_II").forEachIndexed { index, (label, t) ->
                    SegmentedButton(
                        selected = tier == t, onClick = { tier = t },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = 2)
                    ) { Text(label) }
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = valueText,
                onValueChange = { valueText = it.filter { c -> c.isDigit() || c == '.' } },
                label = { Text("Current value") },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "No public API exists for NPS NAV — update this manually whenever you check your statement.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))
            Text("Recurring contribution (optional)", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = monthlyContributionText,
                onValueChange = { monthlyContributionText = it.filter { c -> c.isDigit() || c == '.' } },
                label = { Text("Monthly contribution") },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            DateField(label = "Recurring deposit date", selectedMillis = recurringDepositDate, onDateSelected = { recurringDepositDate = it })
            Text(
                "If set, a Dashboard reminder will ask you to confirm this contribution each month.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = {
                    viewModel.save(
                        existingId, name, pran, pfm, tier, valueText.toDoubleOrNull() ?: 0.0,
                        monthlyContributionText.toDoubleOrNull(), recurringDepositDate, lastContributionDate, onSaved
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
}
