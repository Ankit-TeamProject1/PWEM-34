package com.teamproject1.dailyexpensetracker.feature.wealth.mutualfund

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
import com.teamproject1.dailyexpensetracker.core.database.dao.MutualFundDao
import com.teamproject1.dailyexpensetracker.core.database.entity.MutualFundAccountEntity
import com.teamproject1.dailyexpensetracker.core.database.entity.MutualFundEntryEntity
import com.teamproject1.dailyexpensetracker.core.database.entity.MutualFundSipEntity
import com.teamproject1.dailyexpensetracker.core.session.SessionManager
import com.teamproject1.dailyexpensetracker.core.wealth.AmfiNavFetcher
import com.teamproject1.dailyexpensetracker.core.wealth.MutualFundScheme
import com.teamproject1.dailyexpensetracker.ui.theme.DateField
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class MutualFundListViewModel @Inject constructor(
    private val mutualFundDao: MutualFundDao,
    private val navFetcher: AmfiNavFetcher,
    private val session: SessionManager
) : ViewModel() {
    fun accounts(platformId: Long) = mutualFundDao.getActiveForPlatform(platformId)

    suspend fun currentValue(accountId: Long): Pair<Double, Double> {
        val entries = mutualFundDao.getEntriesOnce(accountId)
        val totalUnits = entries.sumOf { it.unitsAllotted }
        val invested = entries.sumOf { it.investedAmount }
        return totalUnits to invested
    }

    var searchQuery by mutableStateOf("")
    var searchResults by mutableStateOf<List<MutualFundScheme>>(emptyList())
    var isSearching by mutableStateOf(false)

    fun search() {
        viewModelScope.launch {
            isSearching = true
            searchResults = navFetcher.search(searchQuery)
            isSearching = false
        }
    }

    fun addFund(platformId: Long, scheme: MutualFundScheme, folioNumber: String, onDone: (Long) -> Unit) {
        viewModelScope.launch {
            val bookId = session.activeBookId.value ?: return@launch
            val newId = mutualFundDao.insertAccount(
                MutualFundAccountEntity(
                    bookId = bookId, platformId = platformId, schemeCode = scheme.schemeCode, schemeName = scheme.schemeName,
                    folioNumber = folioNumber.ifBlank { null }, fundHouse = scheme.fundHouse,
                    lastFetchedNav = scheme.nav, lastFetchedAt = System.currentTimeMillis()
                )
            )
            onDone(newId)
        }
    }
}

/**
 * Mutual Fund list + search-based add flow — per explicit request, funds
 * are identified by searching the AMFI scheme list by name, not by
 * manually typing a scheme code. Scoped to one platform account (e.g.
 * "Zerodha Coin", "Groww") — a person can hold funds across multiple
 * platforms with entirely separate holdings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MutualFundListScreen(platformId: Long, platformName: String, onBack: () -> Unit, onOpenAccount: (Long) -> Unit, viewModel: MutualFundListViewModel = hiltViewModel()) {
    val accounts by remember(platformId) { viewModel.accounts(platformId) }.collectAsState(initial = emptyList())
    var showAddDialog by remember { mutableStateOf(false) }
    val valuesCache = remember { mutableStateMapOf<Long, Pair<Double, Double>>() }

    LaunchedEffect(accounts) {
        accounts.forEach { account -> valuesCache[account.id] = viewModel.currentValue(account.id) }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(platformName) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } }) },
        floatingActionButton = { FloatingActionButton(onClick = { showAddDialog = true }) { Icon(Icons.Default.Add, contentDescription = "Add Fund") } }
    ) { padding ->
        if (accounts.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("No mutual funds added yet.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(24.dp))
            }
        } else {
            // Matches Demat's Kite-style summary bar, per explicit
            // request — Investment, P&L, Current, in that order.
            var totalInvested = 0.0
            var totalValue = 0.0
            accounts.forEach { account ->
                val (units, invested) = valuesCache[account.id] ?: (0.0 to 0.0)
                val avgNav = if (units > 0) invested / units else 0.0
                // Falls back to invested value when no NAV has been
                // fetched yet, per the same fix applied to Demat — a
                // fund with no fetched NAV previously showed as worth
                // ₹0 rather than "at least worth what was put in".
                totalValue += units * (account.lastFetchedNav ?: avgNav)
                totalInvested += invested
            }
            val totalPnl = totalValue - totalInvested
            val totalPnlPercent = if (totalInvested > 0) (totalPnl / totalInvested) * 100 else 0.0
            val pnlColor = if (totalPnl >= 0) ProfitGreen else MaterialTheme.colorScheme.error

            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Column {
                            Text("Investment", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("₹%.0f".format(totalInvested), style = MaterialTheme.typography.titleMedium)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("P&L", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                "%s₹%.0f (%.2f%%)".format(if (totalPnl >= 0) "+" else "", totalPnl, totalPnlPercent),
                                style = MaterialTheme.typography.titleMedium,
                                color = pnlColor
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("Current", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("₹%.0f".format(totalValue), style = MaterialTheme.typography.titleMedium)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                items(accounts) { account ->
                    val (units, invested) = valuesCache[account.id] ?: (0.0 to 0.0)
                    MutualFundCard(account = account, units = units, invested = invested, onClick = { onOpenAccount(account.id) })
                }
            }
        }
    }

    if (showAddDialog) {
        var folioNumber by remember { mutableStateOf("") }
        var selectedScheme by remember { mutableStateOf<MutualFundScheme?>(null) }
        AlertDialog(
            onDismissRequest = { showAddDialog = false; viewModel.searchQuery = ""; viewModel.searchResults = emptyList(); selectedScheme = null },
            title = { Text("Add Mutual Fund") },
            text = {
                Column {
                    if (selectedScheme == null) {
                        OutlinedTextField(
                            value = viewModel.searchQuery,
                            onValueChange = { viewModel.searchQuery = it },
                            label = { Text("Search fund name") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { viewModel.search() }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (viewModel.isSearching) "Searching…" else "Search")
                        }
                        Spacer(Modifier.height(8.dp))
                        Column(modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                            viewModel.searchResults.forEach { scheme ->
                                Text(
                                    scheme.schemeName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.fillMaxWidth().clickable { selectedScheme = scheme }.padding(vertical = 8.dp)
                                )
                            }
                        }
                    } else {
                        Text("Selected: ${selectedScheme!!.schemeName}", style = MaterialTheme.typography.bodyMedium)
                        Text("Current NAV: ₹%.4f".format(selectedScheme!!.nav), style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(value = folioNumber, onValueChange = { folioNumber = it }, label = { Text("Folio Number (optional)") })
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { selectedScheme = null }) { Text("Choose a different fund") }
                    }
                }
            },
            confirmButton = {
                if (selectedScheme != null) {
                    TextButton(onClick = {
                        viewModel.addFund(platformId, selectedScheme!!, folioNumber) { newId ->
                            showAddDialog = false
                            viewModel.searchQuery = ""; viewModel.searchResults = emptyList()
                            onOpenAccount(newId)
                        }
                    }) { Text("Add") }
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false; viewModel.searchQuery = ""; viewModel.searchResults = emptyList() }) { Text("Cancel") }
            }
        )
    }
}

// Matches Demat's distinct profit green, per explicit request that
// Mutual Fund's interface match Demat's — same color convention so
// profit reads unambiguously green regardless of the app's own accent
// color.
private val ProfitGreen = androidx.compose.ui.graphics.Color(0xFF1DB954)
// Matches Demat's fallback-value marker — same convention, same reason:
// a value using avg NAV in place of a real fetched NAV isn't a failure
// state, just a "this number isn't live" signal.
private val FallbackAmber = androidx.compose.ui.graphics.Color(0xFFB8860B)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MutualFundCard(
    account: MutualFundAccountEntity,
    units: Double,
    invested: Double,
    onClick: () -> Unit
) {
    val nav = account.lastFetchedNav
    val avgNav = if (units > 0) invested / units else 0.0
    // Falls back to invested value when no NAV has been fetched yet,
    // same as Demat — a fund with no fetched NAV shows as "at least
    // worth what was put in" rather than a misleading ₹0.
    val currentValue = units * (nav ?: avgNav)
    val pnl = if (nav != null && invested > 0) currentValue - invested else null
    val pnlPercent = if (pnl != null && invested > 0) (pnl / invested) * 100 else null
    val pnlColor = pnl?.let { if (it >= 0) ProfitGreen else MaterialTheme.colorScheme.error }

    Card(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(account.schemeName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(
                    "₹%.2f".format(currentValue),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (nav == null) FallbackAmber else MaterialTheme.colorScheme.onSurface
                )
            }
            account.folioNumber?.let {
                Text("Folio: $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Units: %.3f  ·  Avg NAV: ₹%.2f".format(units, avgNav),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    nav?.let { "NAV: ₹%.2f".format(it) } ?: "NAV: —",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text("Invested: ₹%.0f".format(invested), style = MaterialTheme.typography.bodySmall)
                if (pnl != null && pnlPercent != null) {
                    Text(
                        "%s₹%.2f (%s%.2f%%)".format(if (pnl >= 0) "+" else "", pnl, if (pnl >= 0) "+" else "", pnlPercent),
                        style = MaterialTheme.typography.bodyMedium,
                        color = pnlColor ?: MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text("P&L: —", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (nav == null) {
                Spacer(Modifier.height(4.dp))
                Text("NAV not yet fetched", style = MaterialTheme.typography.bodySmall, color = FallbackAmber)
            }
        }
    }
}
