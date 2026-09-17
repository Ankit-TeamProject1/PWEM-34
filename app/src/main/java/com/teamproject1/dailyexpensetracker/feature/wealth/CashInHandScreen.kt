package com.teamproject1.dailyexpensetracker.feature.wealth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.teamproject1.dailyexpensetracker.core.database.dao.CashInHandDao
import com.teamproject1.dailyexpensetracker.core.database.entity.CashInHandEntity
import com.teamproject1.dailyexpensetracker.core.session.SessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton

@HiltViewModel
class CashInHandViewModel @Inject constructor(
    private val cashInHandDao: CashInHandDao,
    private val session: SessionManager
) : ViewModel() {

    val current = session.activeBookId.filterNotNull()
        .flatMapLatest { bookId -> cashInHandDao.get(bookId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun save(amount: Double, onDone: () -> Unit) {
        viewModelScope.launch {
            val bookId = session.activeBookId.value ?: return@launch
            cashInHandDao.upsert(
                CashInHandEntity(bookId = bookId, amount = amount, lastUpdatedAt = System.currentTimeMillis())
            )
            onDone()
        }
    }
}

/**
 * Cash in Hand — still the simplest Wealth category in spirit (no ledger,
 * no history, just a snapshot), but now with a calculator-style entry per
 * item 26: instead of typing one final total, the running total updates as
 * you add or subtract individual amounts, then Save persists the running
 * total as the new snapshot.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CashInHandScreen(
    onBack: () -> Unit,
    viewModel: CashInHandViewModel = hiltViewModel()
) {
    val current by viewModel.current.collectAsState()
    var runningTotal by remember { mutableStateOf(0.0) }
    var entryText by remember { mutableStateOf("") }
    // Tracks whether the user has actually started adjusting — until they
    // do, runningTotal keeps syncing to whatever `current` is. This fixes
    // a real bug: `current` starts as null (StateFlow's initial value)
    // before the real saved balance loads asynchronously from the
    // database. The old "initialize once" flag would lock in that null
    // placeholder (as 0.0) permanently on the very first emission,
    // before the real value ever had a chance to arrive — so Subtract
    // was computing 0 - Y instead of X - Y.
    var userHasAdjusted by remember { mutableStateOf(false) }

    LaunchedEffect(current) {
        if (!userHasAdjusted) {
            runningTotal = current?.amount ?: 0.0
        }
    }

    fun applyEntry(sign: Int) {
        val value = entryText.toDoubleOrNull() ?: return
        userHasAdjusted = true
        runningTotal += sign * value
        entryText = ""
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cash in Hand") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            // Current Balance vs New Balance shown distinctly, rather than
            // one ambiguous "Running Total" that just happens to start
            // equal to the current balance — makes the before/after clear
            // at a glance.
            Text("Current Balance", style = MaterialTheme.typography.bodyMedium)
            Text("₹%.2f".format(current?.amount ?: 0.0), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("New Balance", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "₹%.2f".format(runningTotal),
                        style = MaterialTheme.typography.displayLarge
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = entryText,
                onValueChange = { entryText = it.filter { c -> c.isDigit() || c == '.' } },
                label = { Text("Amount") },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { applyEntry(-1) },
                    modifier = Modifier.weight(1f)
                ) { Text("− Subtract") }
                Button(
                    onClick = { applyEntry(1) },
                    modifier = Modifier.weight(1f)
                ) { Text("+ Add") }
            }

            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { userHasAdjusted = true; runningTotal = 0.0 }) { Text("Reset to ₹0") }

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = { viewModel.save(runningTotal, onBack) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Save") }
        }
    }
}
