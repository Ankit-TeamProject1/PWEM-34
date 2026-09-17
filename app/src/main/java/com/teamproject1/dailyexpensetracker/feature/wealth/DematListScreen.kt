package com.teamproject1.dailyexpensetracker.feature.wealth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.teamproject1.dailyexpensetracker.core.database.dao.DematHoldingDao
import com.teamproject1.dailyexpensetracker.core.database.entity.DematHoldingEntity
import com.teamproject1.dailyexpensetracker.core.wealth.StockPriceFetcher
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.IconButton

@HiltViewModel
class DematListViewModel @Inject constructor(
    private val dematHoldingDao: DematHoldingDao,
    private val stockPriceFetcher: StockPriceFetcher
) : ViewModel() {

    fun holdings(dematAccountId: Long) = dematHoldingDao.getActive(dematAccountId)

    /** Refreshes every holding's price for this account — called when the
     *  screen opens and via a manual refresh action. Each fetch failing
     *  independently doesn't block the others; whichever succeed update
     *  their cached price, whichever fail keep showing their last known
     *  value. */
    fun refreshAllPrices(currentHoldings: List<DematHoldingEntity>) {
        viewModelScope.launch {
            currentHoldings.forEach { holding ->
                val price = stockPriceFetcher.fetchPrice(holding.stockSymbol, holding.exchangeCode)
                if (price != null) {
                    dematHoldingDao.updateFetchedPrice(holding.id, price, System.currentTimeMillis())
                }
            }
        }
    }

    fun refreshOne(holding: DematHoldingEntity) {
        viewModelScope.launch {
            val price = stockPriceFetcher.fetchPrice(holding.stockSymbol, holding.exchangeCode)
            if (price != null) {
                dematHoldingDao.updateFetchedPrice(holding.id, price, System.currentTimeMillis())
            }
        }
    }

    /** Manual override, per explicit request — some symbols (corporate
     *  bonds/NCDs like coupon-coded ones, e.g. "84TCHF28") aren't carried
     *  by Yahoo Finance's free equity-quote endpoint at all, so
     *  auto-fetch will never succeed for them no matter how the request
     *  is formed. Stored the same way as a successful fetch (same table,
     *  same "last updated" timestamp), so the rest of the UI treats it
     *  identically — it just doesn't get silently overwritten by the
     *  next auto-refresh unless that refresh actually succeeds. */
    fun setManualPrice(holding: DematHoldingEntity, price: Double) {
        viewModelScope.launch {
            dematHoldingDao.updateFetchedPrice(holding.id, price, System.currentTimeMillis())
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DematListScreen(
    dematAccountId: Long,
    accountName: String,
    onBack: () -> Unit,
    onAddNew: () -> Unit,
    onEdit: (Long) -> Unit,
    viewModel: DematListViewModel = hiltViewModel()
) {
    val holdings by remember(dematAccountId) { viewModel.holdings(dematAccountId) }.collectAsState(initial = emptyList())
    val dateTimeFormat = remember { SimpleDateFormat("MMM d, h:mm a", Locale.US) }

    LaunchedEffect(dematAccountId) { viewModel.refreshAllPrices(viewModel.holdings(dematAccountId).first()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(accountName) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = {
                    TextButton(onClick = { viewModel.refreshAllPrices(holdings) }) { Text("Refresh") }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddNew) {
                Icon(Icons.Default.Add, contentDescription = "Add Holding")
            }
        }
    ) { padding ->
        if (holdings.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("No holdings added yet.", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            // Matches Zerodha Kite's portfolio summary bar, per explicit
            // request — Investment, P&L, Current, in that order.
            val totalInvested = holdings.filter { it.avgBuyPrice > 0 }.sumOf { it.unitsHeld * it.avgBuyPrice }
            // Falls back to invested value when no price has been fetched
            // yet, per explicit request — a holding with no LTP
            // previously contributed zero to the Current total,
            // understating it rather than reflecting "at least worth
            // what was put in" until a real price is available.
            val totalValue = holdings.sumOf { it.unitsHeld * (it.lastFetchedPrice ?: it.avgBuyPrice) }
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
                items(holdings) { holding ->
                    DematCard(
                        holding = holding,
                        dateTimeFormat = dateTimeFormat,
                        onClick = { onEdit(holding.id) },
                        onRefresh = { viewModel.refreshOne(holding) },
                        onSetManualPrice = { price -> viewModel.setManualPrice(holding, price) }
                    )
                }
            }
        }
    }
}

// Kite uses a distinct green for profit rather than the app's primary
// accent color, so profit and loss read unambiguously at a glance
// regardless of theme — matching that convention here.
private val ProfitGreen = androidx.compose.ui.graphics.Color(0xFF1DB954)
// Marks a value that's a fallback (avg buy price standing in for a
// price that couldn't be fetched), not a real live figure — distinct
// from the error-red used elsewhere, since this isn't a failure state
// the user needs to act on, just a "this number isn't live" signal.
private val FallbackAmber = androidx.compose.ui.graphics.Color(0xFFB8860B)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DematCard(
    holding: DematHoldingEntity,
    dateTimeFormat: SimpleDateFormat,
    onClick: () -> Unit,
    onRefresh: () -> Unit,
    onSetManualPrice: (Double) -> Unit
) {
    val ltp = holding.lastFetchedPrice
    // Falls back to the invested amount when no price has been fetched
    // yet, per explicit request — better to show "at least worth what
    // was put in" than a misleading ₹0 for a real holding.
    val currentValue = holding.unitsHeld * (ltp ?: holding.avgBuyPrice)
    val invested = if (holding.avgBuyPrice > 0) holding.unitsHeld * holding.avgBuyPrice else null
    val pnl = if (invested != null && ltp != null) currentValue - invested else null
    val pnlPercent = if (pnl != null && invested != null && invested > 0) (pnl / invested) * 100 else null
    val pnlColor = pnl?.let { if (it >= 0) ProfitGreen else MaterialTheme.colorScheme.error }
    var showManualPriceDialog by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text("${holding.stockSymbol} (${holding.exchangeCode.name})", style = MaterialTheme.typography.titleLarge)
                Text(
                    "₹%.2f".format(currentValue),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (ltp == null) FallbackAmber else MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Qty: ${holding.unitsHeld}" + (invested?.let { "  ·  Avg: ₹%.2f".format(holding.avgBuyPrice) } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    ltp?.let { "LTP: ₹%.2f".format(it) } ?: "LTP: —",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(
                    invested?.let { "Invested: ₹%.0f".format(it) } ?: "Invested: —",
                    style = MaterialTheme.typography.bodySmall
                )
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
            if (ltp == null) {
                Spacer(Modifier.height(4.dp))
                Text("Price not yet fetched", style = MaterialTheme.typography.bodySmall, color = FallbackAmber)
            } else {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Last updated: ${holding.lastFetchedAt?.let { dateTimeFormat.format(Date(it)) } ?: "never"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onRefresh) { Text("Refresh price") }
            TextButton(onClick = { showManualPriceDialog = true }) { Text("Set price manually") }
        }
    }

    if (showManualPriceDialog) {
        var priceText by remember { mutableStateOf(ltp?.let { "%.2f".format(it) } ?: "") }
        AlertDialog(
            onDismissRequest = { showManualPriceDialog = false },
            title = { Text("Set Price Manually") },
            text = {
                Column {
                    Text(
                        "Use this for symbols the app can't auto-fetch a price for — bonds, NCDs, or anything not carried by the free price source.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = priceText,
                        onValueChange = { priceText = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text("Price") },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val price = priceText.toDoubleOrNull()
                    if (price != null && price > 0) {
                        onSetManualPrice(price)
                        showManualPriceDialog = false
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showManualPriceDialog = false }) { Text("Cancel") } }
        )
    }
}
