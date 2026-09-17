package com.teamproject1.dailyexpensetracker.feature.wealth.ratehistory

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.teamproject1.dailyexpensetracker.core.database.dao.WealthRateHistoryDao
import com.teamproject1.dailyexpensetracker.core.database.entity.RateInstrument
import com.teamproject1.dailyexpensetracker.core.database.entity.WealthRateHistoryEntity
import com.teamproject1.dailyexpensetracker.ui.theme.DateField
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import androidx.compose.material.icons.automirrored.filled.ArrowBack

@HiltViewModel
class RateHistoryViewModel @Inject constructor(
    private val rateHistoryDao: WealthRateHistoryDao
) : ViewModel() {
    val ppfRates = rateHistoryDao.getHistory(RateInstrument.PPF)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val epfRates = rateHistoryDao.getHistory(RateInstrument.EPF)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun addRate(instrument: RateInstrument, ratePercent: Double, effectiveFrom: Long) {
        viewModelScope.launch {
            rateHistoryDao.insert(WealthRateHistoryEntity(instrument = instrument, ratePercent = ratePercent, effectiveFrom = effectiveFrom))
        }
    }

    fun updateRate(rate: WealthRateHistoryEntity, ratePercent: Double, effectiveFrom: Long) {
        viewModelScope.launch {
            rateHistoryDao.update(rate.copy(ratePercent = ratePercent, effectiveFrom = effectiveFrom))
        }
    }

    fun deleteRate(rate: WealthRateHistoryEntity) {
        viewModelScope.launch { rateHistoryDao.delete(rate.id) }
    }
}

/**
 * Closes the gap flagged since v1.8.0 — PPF/EPF showed principal-only
 * because there was no way to actually enter a rate. This is a shared
 * govt-notification rate table (not per-account), matching the locked
 * design: rates are maintained here as they're announced, not fetched
 * live and not guessed. Both PpfEpfCalculator call sites (PPF and EPF
 * current-value calculations) read from this same table.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RateHistoryScreen(onBack: () -> Unit, viewModel: RateHistoryViewModel = hiltViewModel()) {
    val ppfRates by viewModel.ppfRates.collectAsState()
    val epfRates by viewModel.epfRates.collectAsState()
    var showAddDialog by remember { mutableStateOf<RateInstrument?>(null) }
    var editingRate by remember { mutableStateOf<WealthRateHistoryEntity?>(null) }
    var pendingDelete by remember { mutableStateOf<WealthRateHistoryEntity?>(null) }
    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy", Locale.US) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("PPF & EPF Interest Rates") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Text(
                    "These rates apply to every PPF/EPF account's compound-growth calculation — enter each new rate as it's officially announced. Tap a rate to edit it.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(16.dp))
            }

            item {
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("PPF Rates", style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = { showAddDialog = RateInstrument.PPF }) {
                        Icon(Icons.Default.Add, contentDescription = "Add PPF Rate")
                        Text("Add")
                    }
                }
            }
            if (ppfRates.isEmpty()) {
                item { Text("No PPF rate entered yet.", style = MaterialTheme.typography.bodySmall) }
            } else {
                items(ppfRates.sortedByDescending { it.effectiveFrom }) { rate ->
                    RateRow(rate, dateFormat, onClick = { editingRate = rate }, onDelete = { pendingDelete = rate })
                }
            }

            item {
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("EPF Rates", style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = { showAddDialog = RateInstrument.EPF }) {
                        Icon(Icons.Default.Add, contentDescription = "Add EPF Rate")
                        Text("Add")
                    }
                }
            }
            if (epfRates.isEmpty()) {
                item { Text("No EPF rate entered yet.", style = MaterialTheme.typography.bodySmall) }
            } else {
                items(epfRates.sortedByDescending { it.effectiveFrom }) { rate ->
                    RateRow(rate, dateFormat, onClick = { editingRate = rate }, onDelete = { pendingDelete = rate })
                }
            }
        }
    }

    showAddDialog?.let { instrument ->
        RateEditDialog(
            title = "Add ${instrument.name} Rate",
            initialRate = "",
            initialDate = System.currentTimeMillis(),
            onDismiss = { showAddDialog = null },
            onSave = { rate, date ->
                viewModel.addRate(instrument, rate, date)
                showAddDialog = null
            }
        )
    }

    editingRate?.let { rate ->
        RateEditDialog(
            title = "Edit ${rate.instrument.name} Rate",
            initialRate = rate.ratePercent.toString(),
            initialDate = rate.effectiveFrom,
            onDismiss = { editingRate = null },
            onSave = { newRate, newDate ->
                viewModel.updateRate(rate, newRate, newDate)
                editingRate = null
            }
        )
    }

    pendingDelete?.let { rate ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete this rate?") },
            text = { Text("Removing the %.2f%% rate effective from %s. Accounts using this period will recalculate using whichever rate applies next.".format(rate.ratePercent, dateFormat.format(Date(rate.effectiveFrom)))) },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteRate(rate); pendingDelete = null }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun RateEditDialog(
    title: String,
    initialRate: String,
    initialDate: Long,
    onDismiss: () -> Unit,
    onSave: (rate: Double, effectiveFrom: Long) -> Unit
) {
    var rateText by remember { mutableStateOf(initialRate) }
    var effectiveFrom by remember { mutableStateOf(initialDate) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = rateText,
                    onValueChange = { rateText = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Rate %") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
                Spacer(Modifier.height(8.dp))
                DateField(label = "Effective from", selectedMillis = effectiveFrom, onDateSelected = { effectiveFrom = it })
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val rate = rateText.toDoubleOrNull()
                if (rate != null && rate > 0) onSave(rate, effectiveFrom)
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun RateRow(rate: WealthRateHistoryEntity, dateFormat: SimpleDateFormat, onClick: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        Column {
            Text("From ${dateFormat.format(Date(rate.effectiveFrom))}", style = MaterialTheme.typography.bodyMedium)
        }
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("%.2f%%".format(rate.ratePercent), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete rate", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}
