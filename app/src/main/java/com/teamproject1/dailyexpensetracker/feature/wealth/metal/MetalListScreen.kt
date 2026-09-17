package com.teamproject1.dailyexpensetracker.feature.wealth.metal

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import com.teamproject1.dailyexpensetracker.core.database.dao.MetalHoldingDao
import com.teamproject1.dailyexpensetracker.core.database.entity.MetalForm
import com.teamproject1.dailyexpensetracker.core.database.entity.MetalHoldingEntity
import com.teamproject1.dailyexpensetracker.core.database.entity.MetalType
import com.teamproject1.dailyexpensetracker.core.session.SessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

private val GOLD_CARATS = listOf("24K", "22K", "18K", "14K")
private val SILVER_CARATS = listOf("999", "925")

@HiltViewModel
class MetalListViewModel @Inject constructor(
    private val metalHoldingDao: MetalHoldingDao,
    private val session: SessionManager
) : ViewModel() {
    val holdings = session.activeBookId.filterNotNull()
        .flatMapLatest { bookId -> metalHoldingDao.getActive(bookId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun addTile(metalType: MetalType, caratType: String, form: MetalForm, quantityGrams: Double, pricePerGram: Double?) {
        viewModelScope.launch {
            val bookId = session.activeBookId.value ?: return@launch
            metalHoldingDao.insert(
                MetalHoldingEntity(
                    bookId = bookId, metalType = metalType, caratType = caratType, form = form,
                    quantityGrams = quantityGrams, currentPricePerGram = pricePerGram,
                    lastUpdatedAt = pricePerGram?.let { System.currentTimeMillis() }
                )
            )
        }
    }

    fun updateTile(holding: MetalHoldingEntity, quantityGrams: Double, pricePerGram: Double?) {
        viewModelScope.launch {
            // lastUpdatedAt only advances when the price actually changed
            // — editing just the quantity with the same price shouldn't
            // make the price look freshly checked when it wasn't.
            val lastUpdatedAt = if (pricePerGram != holding.currentPricePerGram) System.currentTimeMillis() else holding.lastUpdatedAt
            metalHoldingDao.update(holding.copy(quantityGrams = quantityGrams, currentPricePerGram = pricePerGram, lastUpdatedAt = lastUpdatedAt))
        }
    }

    fun archive(id: Long) { viewModelScope.launch { metalHoldingDao.archive(id) } }
}

/**
 * Gold & Silver — simplified per explicit request: one tile per (metal,
 * carat, form) combination, each with its own quantity and manually-set
 * current price. No shared rate table, no separate Name, no
 * profit/loss tracking — just qty × price per tile, with the date the
 * price was last set shown on each one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MetalListScreen(onBack: () -> Unit, viewModel: MetalListViewModel = hiltViewModel()) {
    val holdings by viewModel.holdings.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var holdingToEdit by remember { mutableStateOf<MetalHoldingEntity?>(null) }
    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy", Locale.US) }

    // Existing (metal, carat, form) combinations, so the Add dialog only
    // offers combinations that don't already have a tile.
    val existingKeys = remember(holdings) { holdings.map { Triple(it.metalType, it.caratType, it.form) }.toSet() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Gold & Silver") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) { Icon(Icons.Default.Add, contentDescription = "Add Tile") }
        }
    ) { padding ->
        if (holdings.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("No Gold/Silver tracked yet. Add one to get started.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(24.dp))
            }
        } else {
            val totalValue = holdings.sumOf { it.quantityGrams * (it.currentPricePerGram ?: 0.0) }
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Text("Total Value", style = MaterialTheme.typography.bodyMedium)
                    Text("₹%.0f".format(totalValue), style = MaterialTheme.typography.displayLarge)
                    Spacer(Modifier.height(8.dp))
                }
                items(holdings) { holding ->
                    MetalTileCard(holding, dateFormat, onClick = { holdingToEdit = holding })
                }
            }
        }
    }

    if (showAddDialog) {
        MetalTileDialog(
            title = "New Gold/Silver Tile",
            existingKeys = existingKeys,
            onDismiss = { showAddDialog = false },
            onSave = { metalType, carat, form, qty, price ->
                viewModel.addTile(metalType, carat, form, qty, price)
                showAddDialog = false
            }
        )
    }

    holdingToEdit?.let { holding ->
        MetalTileDialog(
            title = "Edit Tile",
            existingKeys = emptySet(), // editing an existing tile — metal/carat/form are fixed, not re-pickable
            fixedSelection = Triple(holding.metalType, holding.caratType, holding.form),
            initialQuantity = holding.quantityGrams,
            initialPrice = holding.currentPricePerGram,
            onDismiss = { holdingToEdit = null },
            onSave = { _, _, _, qty, price ->
                viewModel.updateTile(holding, qty, price)
                holdingToEdit = null
            },
            onArchive = { viewModel.archive(holding.id); holdingToEdit = null }
        )
    }
}

@Composable
private fun MetalTileCard(holding: MetalHoldingEntity, dateFormat: SimpleDateFormat, onClick: () -> Unit) {
    val value = holding.quantityGrams * (holding.currentPricePerGram ?: 0.0)
    Card(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text("${holding.metalType.name} ${holding.caratType} (${holding.form.name})", style = MaterialTheme.typography.titleMedium)
                Text("₹%.2f".format(value), style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Qty: %.3fg  ·  Price: %s".format(holding.quantityGrams, holding.currentPricePerGram?.let { "₹%.2f/g".format(it) } ?: "not set"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "Last updated: ${holding.lastUpdatedAt?.let { dateFormat.format(Date(it)) } ?: "never"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun MetalTileDialog(
    title: String,
    existingKeys: Set<Triple<MetalType, String, MetalForm>>,
    fixedSelection: Triple<MetalType, String, MetalForm>? = null,
    initialQuantity: Double = 0.0,
    initialPrice: Double? = null,
    onDismiss: () -> Unit,
    onSave: (MetalType, String, MetalForm, Double, Double?) -> Unit,
    onArchive: (() -> Unit)? = null
) {
    var metalType by remember { mutableStateOf(fixedSelection?.first ?: MetalType.GOLD) }
    var caratType by remember { mutableStateOf(fixedSelection?.second ?: "22K") }
    var form by remember { mutableStateOf(fixedSelection?.third ?: MetalForm.PHYSICAL) }
    var quantityText by remember { mutableStateOf(if (initialQuantity > 0) initialQuantity.toString() else "") }
    var priceText by remember { mutableStateOf(initialPrice?.toString() ?: "") }

    val caratOptions = if (metalType == MetalType.GOLD) GOLD_CARATS else SILVER_CARATS
    if (fixedSelection == null && caratType !in caratOptions) caratType = caratOptions.first()
    val isDuplicate = fixedSelection == null && Triple(metalType, caratType, form) in existingKeys
    val canSave = quantityText.toDoubleOrNull() != null && (quantityText.toDoubleOrNull() ?: 0.0) > 0 && !isDuplicate

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                if (fixedSelection == null) {
                    Text("Metal", style = MaterialTheme.typography.labelMedium)
                    Row {
                        FilterChip(selected = metalType == MetalType.GOLD, onClick = { metalType = MetalType.GOLD }, label = { Text("Gold") })
                        Spacer(Modifier.width(8.dp))
                        FilterChip(selected = metalType == MetalType.SILVER, onClick = { metalType = MetalType.SILVER }, label = { Text("Silver") })
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(if (metalType == MetalType.GOLD) "Carat" else "Purity", style = MaterialTheme.typography.labelMedium)
                    Row { caratOptions.forEach { option -> FilterChip(selected = caratType == option, onClick = { caratType = option }, label = { Text(option) }, modifier = Modifier.padding(end = 8.dp)) } }
                    Spacer(Modifier.height(12.dp))
                    Text("Form", style = MaterialTheme.typography.labelMedium)
                    Row {
                        FilterChip(selected = form == MetalForm.PHYSICAL, onClick = { form = MetalForm.PHYSICAL }, label = { Text("Physical") })
                        Spacer(Modifier.width(8.dp))
                        FilterChip(selected = form == MetalForm.DIGITAL, onClick = { form = MetalForm.DIGITAL }, label = { Text("Digital") })
                    }
                    if (isDuplicate) {
                        Spacer(Modifier.height(4.dp))
                        Text("A tile for this combination already exists.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                    Spacer(Modifier.height(12.dp))
                } else {
                    Text("${fixedSelection.first.name} ${fixedSelection.second} (${fixedSelection.third.name})", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                }
                OutlinedTextField(
                    value = quantityText, onValueChange = { quantityText = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Quantity (grams)") }, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = priceText, onValueChange = { priceText = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Current price per gram") }, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = { onSave(metalType, caratType, form, quantityText.toDoubleOrNull() ?: 0.0, priceText.toDoubleOrNull()) }
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (onArchive != null) {
                    TextButton(onClick = onArchive) { Text("Archive", color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}
