package com.teamproject1.dailyexpensetracker.feature.wealth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.teamproject1.dailyexpensetracker.core.database.dao.DematHoldingDao
import com.teamproject1.dailyexpensetracker.core.database.entity.DematHoldingEntity
import com.teamproject1.dailyexpensetracker.core.database.entity.ExchangeCode
import com.teamproject1.dailyexpensetracker.core.session.SessionManager
import com.teamproject1.dailyexpensetracker.core.wealth.StockPriceFetcher
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack

@HiltViewModel
class AddEditDematViewModel @Inject constructor(
    private val dematHoldingDao: DematHoldingDao,
    private val stockPriceFetcher: StockPriceFetcher,
    private val session: SessionManager
) : ViewModel() {

    suspend fun loadExisting(id: Long): DematHoldingEntity? {
        val bookId = session.activeBookId.value ?: return null
        return dematHoldingDao.getActiveForBook(bookId).first().find { it.id == id }
    }

    fun save(
        existingId: Long?,
        dematAccountId: Long,
        stockSymbol: String,
        exchangeCode: ExchangeCode,
        unitsHeld: Double,
        avgBuyPrice: Double,
        onDone: () -> Unit
    ) {
        viewModelScope.launch {
            val bookId = session.activeBookId.value ?: return@launch
            val id = if (existingId == null) {
                dematHoldingDao.insert(
                    DematHoldingEntity(
                        bookId = bookId, dematAccountId = dematAccountId, stockSymbol = stockSymbol.uppercase(), exchangeCode = exchangeCode,
                        unitsHeld = unitsHeld, avgBuyPrice = avgBuyPrice
                    )
                )
            } else {
                // Preserves the holding's existing account rather than
                // whatever account this screen was opened from — editing
                // a holding never moves it between accounts.
                val existing = dematHoldingDao.getActiveForBook(bookId).first().find { it.id == existingId }
                dematHoldingDao.update(
                    DematHoldingEntity(
                        id = existingId, bookId = bookId, dematAccountId = existing?.dematAccountId ?: dematAccountId,
                        stockSymbol = stockSymbol.uppercase(), exchangeCode = exchangeCode,
                        unitsHeld = unitsHeld, avgBuyPrice = avgBuyPrice,
                        lastFetchedPrice = existing?.lastFetchedPrice, lastFetchedAt = existing?.lastFetchedAt
                    )
                )
                existingId
            }
            // Try an immediate fetch on save, so the user sees a real value
            // right away rather than waiting for the next screen visit.
            val price = stockPriceFetcher.fetchPrice(stockSymbol.uppercase(), exchangeCode)
            if (price != null) {
                dematHoldingDao.updateFetchedPrice(id, price, System.currentTimeMillis())
            }
            onDone()
        }
    }

    fun archive(id: Long, onDone: () -> Unit) {
        viewModelScope.launch {
            dematHoldingDao.archive(id)
            onDone()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditDematScreen(
    existingId: Long?,
    dematAccountId: Long,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    viewModel: AddEditDematViewModel = hiltViewModel()
) {
    var stockSymbol by remember { mutableStateOf("") }
    var exchangeCode by remember { mutableStateOf(ExchangeCode.NSE) }
    var unitsText by remember { mutableStateOf("") }
    var avgBuyPriceText by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(existingId == null) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(existingId) {
        if (existingId != null) {
            val existing = viewModel.loadExisting(existingId)
            if (existing != null) {
                stockSymbol = existing.stockSymbol
                exchangeCode = existing.exchangeCode
                unitsText = existing.unitsHeld.toString()
                avgBuyPriceText = existing.avgBuyPrice.toString()
            }
            loaded = true
        }
    }

    val canSave = stockSymbol.isNotBlank() && (unitsText.toDoubleOrNull() ?: 0.0) > 0

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (existingId == null) "New Holding" else "Edit Holding") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } }
            )
        }
    ) { padding ->
        if (!loaded) return@Scaffold

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
                .imePadding()
        ) {
            OutlinedTextField(
                value = stockSymbol,
                onValueChange = { stockSymbol = it.uppercase() },
                label = { Text("Stock symbol (e.g. RELIANCE, TCS)") },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(8.dp))
            Text("Exchange", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            SingleChoiceSegmentedButtonRow {
                listOf("NSE" to ExchangeCode.NSE, "BSE" to ExchangeCode.BSE).forEachIndexed { index, (label, code) ->
                    SegmentedButton(
                        selected = exchangeCode == code,
                        onClick = { exchangeCode = code },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = 2)
                    ) { Text(label) }
                }
            }

            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = unitsText,
                onValueChange = { unitsText = it.filter { c -> c.isDigit() || c == '.' } },
                label = { Text("Units held") },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = avgBuyPriceText,
                onValueChange = { avgBuyPriceText = it.filter { c -> c.isDigit() || c == '.' } },
                label = { Text("Average buy price (optional, for gain/loss)") },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(16.dp))
            Text(
                "Current price is fetched automatically when you save and whenever you open the Demat screen.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = {
                    saving = true
                    viewModel.save(
                        existingId = existingId,
                        dematAccountId = dematAccountId,
                        stockSymbol = stockSymbol,
                        exchangeCode = exchangeCode,
                        unitsHeld = unitsText.toDoubleOrNull() ?: 0.0,
                        avgBuyPrice = avgBuyPriceText.toDoubleOrNull() ?: 0.0,
                        onDone = onSaved
                    )
                },
                enabled = canSave && !saving,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (saving) "Saving & fetching price…" else "Save") }

            if (existingId != null) {
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = { viewModel.archive(existingId, onSaved) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Archive", color = MaterialTheme.colorScheme.error) }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}
