package com.teamproject1.dailyexpensetracker.feature.wealth.mutualfund

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import com.teamproject1.dailyexpensetracker.core.database.dao.MutualFundDao
import com.teamproject1.dailyexpensetracker.core.database.entity.MutualFundAccountEntity
import com.teamproject1.dailyexpensetracker.core.database.entity.MutualFundEntryEntity
import com.teamproject1.dailyexpensetracker.core.database.entity.MutualFundSipEntity
import com.teamproject1.dailyexpensetracker.core.session.SessionManager
import com.teamproject1.dailyexpensetracker.core.wealth.AmfiNavFetcher
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
class MutualFundDetailViewModel @Inject constructor(
    private val mutualFundDao: MutualFundDao,
    private val navFetcher: AmfiNavFetcher,
    private val session: SessionManager
) : ViewModel() {

    private val _totalUnits = MutableStateFlow(0.0)
    val totalUnits: StateFlow<Double> = _totalUnits
    private val _totalInvested = MutableStateFlow(0.0)
    val totalInvested: StateFlow<Double> = _totalInvested

    fun entries(accountId: Long) = mutualFundDao.getEntries(accountId)
    fun sip(accountId: Long) = mutualFundDao.getSip(accountId)

    suspend fun loadAccount(accountId: Long): MutualFundAccountEntity? {
        val bookId = session.activeBookId.value ?: return null
        return mutualFundDao.getActiveAccounts(bookId).first().find { it.id == accountId }
    }

    fun refreshTotals(accountId: Long) {
        viewModelScope.launch {
            val entries = mutualFundDao.getEntriesOnce(accountId)
            _totalUnits.value = entries.sumOf { it.unitsAllotted }
            _totalInvested.value = entries.sumOf { it.investedAmount }
        }
    }

    fun refreshNav(accountId: Long, schemeCode: String) {
        viewModelScope.launch {
            val nav = navFetcher.getNav(schemeCode)
            if (nav != null) mutualFundDao.updateFetchedNav(accountId, nav, System.currentTimeMillis())
        }
    }

    /** Manual lump-sum entry — amount and NAV are both entered directly;
     *  units are computed and stored at entry time. */
    fun addEntry(accountId: Long, investedAmount: Double, navAtPurchase: Double, date: Long, isSip: Boolean = false, onDone: () -> Unit) {
        viewModelScope.launch {
            val units = if (navAtPurchase > 0) investedAmount / navAtPurchase else 0.0
            mutualFundDao.insertEntry(
                MutualFundEntryEntity(mutualFundAccountId = accountId, investedAmount = investedAmount, navAtPurchase = navAtPurchase, unitsAllotted = units, purchaseDate = date, isSip = isSip)
            )
            refreshTotals(accountId)
            onDone()
        }
    }

    fun updateEntry(entry: MutualFundEntryEntity, investedAmount: Double, navAtPurchase: Double, date: Long, onDone: () -> Unit) {
        viewModelScope.launch {
            val units = if (navAtPurchase > 0) investedAmount / navAtPurchase else 0.0
            mutualFundDao.updateEntry(entry.copy(investedAmount = investedAmount, navAtPurchase = navAtPurchase, unitsAllotted = units, purchaseDate = date))
            refreshTotals(entry.mutualFundAccountId)
            onDone()
        }
    }

    fun deleteEntry(entry: MutualFundEntryEntity, onDone: () -> Unit) {
        viewModelScope.launch {
            mutualFundDao.deleteEntry(entry.id)
            refreshTotals(entry.mutualFundAccountId)
            onDone()
        }
    }

    fun archiveAccount(accountId: Long, onDone: () -> Unit) {
        viewModelScope.launch { mutualFundDao.archiveAccount(accountId); onDone() }
    }

    fun setupSip(accountId: Long, amount: Double, startDate: Long, onDone: () -> Unit) {
        viewModelScope.launch {
            mutualFundDao.insertSip(MutualFundSipEntity(mutualFundAccountId = accountId, sipAmount = amount, startDate = startDate))
            onDone()
        }
    }

    /** Step-up/down is just editing sipAmount directly, per explicit
     *  request — no separate scheduled-increase mechanism. */
    fun updateSip(sip: MutualFundSipEntity, newAmount: Double, newStartDate: Long, onDone: () -> Unit) {
        viewModelScope.launch {
            mutualFundDao.updateSip(sip.copy(sipAmount = newAmount, startDate = newStartDate))
            onDone()
        }
    }

    fun toggleSipHold(sip: MutualFundSipEntity, onDone: () -> Unit) {
        viewModelScope.launch {
            mutualFundDao.updateSip(sip.copy(isHeld = !sip.isHeld))
            onDone()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MutualFundDetailScreen(accountId: Long, onBack: () -> Unit, viewModel: MutualFundDetailViewModel = hiltViewModel()) {
    var account by remember { mutableStateOf<MutualFundAccountEntity?>(null) }
    val entries by viewModel.entries(accountId).collectAsState(initial = emptyList())
    val sip by viewModel.sip(accountId).collectAsState(initial = null)
    val totalUnits by viewModel.totalUnits.collectAsState()
    val totalInvested by viewModel.totalInvested.collectAsState()
    var showAddEntryDialog by remember { mutableStateOf(false) }
    var editingEntry by remember { mutableStateOf<MutualFundEntryEntity?>(null) }
    var showSipDialog by remember { mutableStateOf(false) }
    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy", Locale.US) }

    LaunchedEffect(accountId) {
        account = viewModel.loadAccount(accountId)
        viewModel.refreshTotals(accountId)
    }
    LaunchedEffect(entries) { viewModel.refreshTotals(accountId) }
    LaunchedEffect(accountId) {
        account?.schemeCode?.let { viewModel.refreshNav(accountId, it) }
    }

    val currentValue = totalUnits * (account?.lastFetchedNav ?: 0.0)
    val gainLoss = if (totalInvested > 0) ((currentValue - totalInvested) / totalInvested * 100) else null

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(account?.schemeName ?: "Mutual Fund", maxLines = 1) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState())) {
            account?.let { acc ->
                Text(
                    listOfNotNull(acc.fundHouse, acc.folioNumber?.let { "Folio $it" }).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
            }
            Text("Current Value", style = MaterialTheme.typography.bodyMedium)
            Text("₹%.2f".format(currentValue), style = MaterialTheme.typography.displayLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("₹%.0f units".format(totalUnits), style = MaterialTheme.typography.bodySmall)
                if (gainLoss != null) {
                    Text(
                        "%+.1f%%".format(gainLoss),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (gainLoss >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                }
            }
            account?.lastFetchedNav?.let {
                Text("NAV: ₹%.4f".format(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(16.dp))

            // SIP section
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("SIP", style = MaterialTheme.typography.titleLarge)
                    if (sip == null) {
                        Spacer(Modifier.height(8.dp))
                        Text("No SIP set up for this fund.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { showSipDialog = true }) { Text("Set Up SIP") }
                    } else {
                        val currentSip = sip!!
                        Spacer(Modifier.height(8.dp))
                        Text("₹%.0f / month".format(currentSip.sipAmount), style = MaterialTheme.typography.titleMedium)
                        if (currentSip.isHeld) {
                            Text("On Hold", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { showSipDialog = true }) { Text("Edit") }
                            TextButton(onClick = { viewModel.toggleSipHold(currentSip) {} }) {
                                Text(if (currentSip.isHeld) "Resume" else "Hold")
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))

            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text("Entries", style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = { showAddEntryDialog = true }) { Text("+ Add Entry") }
            }
            Spacer(Modifier.height(8.dp))
            entries.sortedByDescending { it.purchaseDate }.forEach { entry ->
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth().clickable { editingEntry = entry }.padding(vertical = 8.dp)
                ) {
                    Column {
                        Text(dateFormat.format(Date(entry.purchaseDate)), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "%.3f units @ ₹%.4f%s".format(entry.unitsAllotted, entry.navAtPurchase, if (entry.isSip) " (SIP)" else ""),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text("₹%.0f".format(entry.investedAmount), style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(Modifier.height(16.dp))

            account?.let { acc ->
                TextButton(onClick = { viewModel.archiveAccount(acc.id, onBack) }) {
                    Text("Archive this fund", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    if (showAddEntryDialog) {
        MutualFundEntryDialog(
            title = "Add Entry", initialAmount = "", initialNav = account?.lastFetchedNav?.toString() ?: "", initialDate = System.currentTimeMillis(),
            onDismiss = { showAddEntryDialog = false }, onDelete = null,
            onSave = { amount, nav, date -> viewModel.addEntry(accountId, amount, nav, date) { showAddEntryDialog = false } }
        )
    }

    editingEntry?.let { entry ->
        MutualFundEntryDialog(
            title = "Edit Entry", initialAmount = "%.0f".format(entry.investedAmount), initialNav = entry.navAtPurchase.toString(), initialDate = entry.purchaseDate,
            onDismiss = { editingEntry = null }, onDelete = { viewModel.deleteEntry(entry) { editingEntry = null } },
            onSave = { amount, nav, date -> viewModel.updateEntry(entry, amount, nav, date) { editingEntry = null } }
        )
    }

    if (showSipDialog) {
        var amountText by remember { mutableStateOf(sip?.sipAmount?.toString() ?: "") }
        var sipDate by remember { mutableStateOf(sip?.startDate ?: System.currentTimeMillis()) }
        AlertDialog(
            onDismissRequest = { showSipDialog = false },
            title = { Text(if (sip == null) "Set Up SIP" else "Edit SIP") },
            text = {
                Column {
                    OutlinedTextField(
                        value = amountText,
                        onValueChange = { amountText = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text("Monthly SIP amount") },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal)
                    )
                    Spacer(Modifier.height(8.dp))
                    DateField(label = "SIP Date", selectedMillis = sipDate, onDateSelected = { sipDate = it })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val amount = amountText.toDoubleOrNull() ?: return@TextButton
                    val currentSip = sip
                    if (currentSip == null) {
                        viewModel.setupSip(accountId, amount, sipDate) { showSipDialog = false }
                    } else {
                        viewModel.updateSip(currentSip, amount, sipDate) { showSipDialog = false }
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showSipDialog = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun MutualFundEntryDialog(
    title: String, initialAmount: String, initialNav: String, initialDate: Long,
    onDismiss: () -> Unit, onDelete: (() -> Unit)?, onSave: (amount: Double, nav: Double, date: Long) -> Unit
) {
    var amountText by remember { mutableStateOf(initialAmount) }
    var navText by remember { mutableStateOf(initialNav) }
    var date by remember { mutableStateOf(initialDate) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = amountText, onValueChange = { amountText = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Amount Invested") }, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = navText, onValueChange = { navText = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("NAV at Purchase") }, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
                Spacer(Modifier.height(8.dp))
                DateField(label = "Purchase Date", selectedMillis = date, onDateSelected = { date = it })
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val amount = amountText.toDoubleOrNull()
                val nav = navText.toDoubleOrNull()
                if (amount != null && amount > 0 && nav != null && nav > 0) onSave(amount, nav, date)
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
