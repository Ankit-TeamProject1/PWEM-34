package com.teamproject1.dailyexpensetracker.feature.wealth.insurance

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
import com.teamproject1.dailyexpensetracker.core.database.dao.InsuranceDao
import com.teamproject1.dailyexpensetracker.core.database.entity.InsurancePolicyEntity
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

private val policyTypes = listOf("TERM", "HEALTH", "ENDOWMENT", "MONEY_BACK", "ULIP")
private val premiumFrequencies = listOf("MONTHLY", "QUARTERLY", "ANNUALLY")

@HiltViewModel
class InsuranceListViewModel @Inject constructor(private val insuranceDao: InsuranceDao, session: SessionManager) : ViewModel() {
    val policies = session.activeBookId.filterNotNull()
        .flatMapLatest { bookId -> insuranceDao.getActive(bookId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun quickAdjust(policy: InsurancePolicyEntity, newValue: Double) {
        viewModelScope.launch { insuranceDao.update(policy.copy(currentSurrenderValue = newValue)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InsuranceListScreen(onBack: () -> Unit, onAddNew: () -> Unit, onEdit: (Long) -> Unit, viewModel: InsuranceListViewModel = hiltViewModel()) {
    val policies by viewModel.policies.collectAsState()

    Scaffold(
        topBar = { TopAppBar(title = { Text("Insurance") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } }) },
        floatingActionButton = { FloatingActionButton(onClick = onAddNew) { Icon(Icons.Default.Add, contentDescription = "Add Policy") } }
    ) { padding ->
        if (policies.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = androidx.compose.ui.Alignment.Center) {
                Text("No policies added yet.", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            val cashValueTotal = policies.filter { it.hasCashValue }.sumOf { it.currentSurrenderValue }
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Text("Cash Value Counted Toward Net Worth", style = MaterialTheme.typography.bodyMedium)
                    Text("₹%.0f".format(cashValueTotal), style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Protection-only policies (term, most health) aren't counted — their sum assured only exists on a claim.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                }
                items(policies) { policy ->
                    var showAdjustDialog by remember { mutableStateOf(false) }
                    Card(modifier = Modifier.fillMaxWidth(), onClick = { onEdit(policy.id) }) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                Text(policy.policyName, style = MaterialTheme.typography.titleLarge)
                                if (policy.hasCashValue) {
                                    Text("💰 Counts toward Net Worth", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                            Text("${policy.insurerName} · ${policy.policyType}", style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(8.dp))
                            Text("Sum assured: ₹%.0f".format(policy.sumAssured), style = MaterialTheme.typography.bodyMedium)
                            if (policy.hasCashValue) {
                                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                    Text("Surrender value: ₹%.0f".format(policy.currentSurrenderValue), style = MaterialTheme.typography.bodyMedium)
                                    // Quick-adjust — mainly useful for ULIP, where charges
                                    // (fund management fees, mortality charges) reduce the
                                    // surrender value over time, per the shared "log an
                                    // adjustment" mechanism.
                                    TextButton(onClick = { showAdjustDialog = true }) { Text("Adjust") }
                                }
                            }
                        }
                    }
                    if (showAdjustDialog) {
                        com.teamproject1.dailyexpensetracker.feature.wealth.QuickAdjustDialog(
                            currentValue = policy.currentSurrenderValue,
                            label = "Adjust Surrender Value",
                            onDismiss = { showAdjustDialog = false },
                            onSave = { newValue -> viewModel.quickAdjust(policy, newValue); showAdjustDialog = false }
                        )
                    }
                }
            }
        }
    }
}

@HiltViewModel
class AddEditInsuranceViewModel @Inject constructor(private val insuranceDao: InsuranceDao, private val session: SessionManager) : ViewModel() {
    suspend fun loadExisting(id: Long): InsurancePolicyEntity? {
        val bookId = session.activeBookId.value ?: return null
        return insuranceDao.getActive(bookId).first().find { it.id == id }
    }

    fun save(
        existingId: Long?, policyName: String, insurerName: String, policyType: String,
        sumAssured: Double, premiumAmount: Double, premiumFrequency: String,
        nextDueDate: Long?, hasCashValue: Boolean, currentSurrenderValue: Double, onDone: () -> Unit
    ) {
        viewModelScope.launch {
            val bookId = session.activeBookId.value ?: return@launch
            val entity = InsurancePolicyEntity(
                id = existingId ?: 0, bookId = bookId, policyName = policyName, insurerName = insurerName,
                policyType = policyType, sumAssured = sumAssured, premiumAmount = premiumAmount,
                premiumFrequency = premiumFrequency, nextDueDate = nextDueDate,
                hasCashValue = hasCashValue, currentSurrenderValue = currentSurrenderValue
            )
            if (existingId == null) insuranceDao.insert(entity) else insuranceDao.update(entity)
            onDone()
        }
    }

    fun archive(id: Long, onDone: () -> Unit) {
        viewModelScope.launch { insuranceDao.archive(id); onDone() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditInsuranceScreen(existingId: Long?, onBack: () -> Unit, onSaved: () -> Unit, viewModel: AddEditInsuranceViewModel = hiltViewModel()) {
    var policyName by remember { mutableStateOf("") }
    var insurerName by remember { mutableStateOf("") }
    var policyType by remember { mutableStateOf("TERM") }
    var sumAssuredText by remember { mutableStateOf("") }
    var premiumText by remember { mutableStateOf("") }
    var premiumFrequency by remember { mutableStateOf("ANNUALLY") }
    var nextDueDate by remember { mutableStateOf<Long?>(null) }
    var hasCashValue by remember { mutableStateOf(false) }
    var surrenderValueText by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(existingId == null) }

    LaunchedEffect(existingId) {
        if (existingId != null) {
            viewModel.loadExisting(existingId)?.let {
                policyName = it.policyName; insurerName = it.insurerName; policyType = it.policyType
                sumAssuredText = it.sumAssured.toString(); premiumText = it.premiumAmount.toString()
                premiumFrequency = it.premiumFrequency; nextDueDate = it.nextDueDate
                hasCashValue = it.hasCashValue; surrenderValueText = it.currentSurrenderValue.toString()
            }
            loaded = true
        } else {
            hasCashValue = policyType in listOf("ENDOWMENT", "MONEY_BACK", "ULIP")
        }
    }

    val canSave = policyName.isNotBlank() && insurerName.isNotBlank() && (sumAssuredText.toDoubleOrNull() ?: 0.0) > 0

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (existingId == null) "New Policy" else "Edit Policy") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } }
            )
        }
    ) { padding ->
        if (!loaded) return@Scaffold
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()).imePadding()) {
            OutlinedTextField(value = policyName, onValueChange = { policyName = it }, label = { Text("Policy name") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = insurerName, onValueChange = { insurerName = it }, label = { Text("Insurer") }, modifier = Modifier.fillMaxWidth())

            Spacer(Modifier.height(8.dp))
            Text("Type", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                policyTypes.forEach { t ->
                    FilterChip(
                        selected = policyType == t,
                        onClick = {
                            policyType = t
                            if (existingId == null) hasCashValue = t in listOf("ENDOWMENT", "MONEY_BACK", "ULIP")
                        },
                        label = { Text(t.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }) }
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = sumAssuredText,
                onValueChange = { sumAssuredText = it.filter { c -> c.isDigit() || c == '.' } },
                label = { Text("Sum assured") },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = premiumText,
                    onValueChange = { premiumText = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Premium") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                premiumFrequencies.forEach { f ->
                    FilterChip(
                        selected = premiumFrequency == f, onClick = { premiumFrequency = f },
                        label = { Text(f.lowercase().replaceFirstChar { it.uppercase() }) }
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            DateField(
                label = "Next premium due date",
                selectedMillis = nextDueDate ?: System.currentTimeMillis(),
                onDateSelected = { nextDueDate = it }
            )

            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Switch(checked = hasCashValue, onCheckedChange = { hasCashValue = it })
                Spacer(Modifier.width(8.dp))
                Text("Has cash value (counts toward Net Worth)", style = MaterialTheme.typography.bodyMedium)
            }

            if (hasCashValue) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = surrenderValueText,
                    onValueChange = { surrenderValueText = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Current surrender value") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = {
                    viewModel.save(
                        existingId, policyName, insurerName, policyType,
                        sumAssuredText.toDoubleOrNull() ?: 0.0, premiumText.toDoubleOrNull() ?: 0.0,
                        premiumFrequency, nextDueDate, hasCashValue, surrenderValueText.toDoubleOrNull() ?: 0.0, onSaved
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
