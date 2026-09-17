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
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.teamproject1.dailyexpensetracker.core.database.dao.DematAccountDao
import com.teamproject1.dailyexpensetracker.core.database.dao.DematHoldingDao
import com.teamproject1.dailyexpensetracker.core.database.entity.DematAccountEntity
import com.teamproject1.dailyexpensetracker.core.session.SessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DematAccountSelectorViewModel @Inject constructor(
    private val dematAccountDao: DematAccountDao,
    private val dematHoldingDao: DematHoldingDao,
    private val session: SessionManager
) : ViewModel() {
    val accounts = session.activeBookId.filterNotNull()
        .flatMapLatest { bookId -> dematAccountDao.getActive(bookId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Invested and current value per account, for a preview on the
     *  selector card — reuses each holding's already-fetched price,
     *  never fetches anything itself. */
    suspend fun valuesFor(dematAccountId: Long): Pair<Double, Double> {
        val holdings = dematHoldingDao.getActive(dematAccountId).first()
        val invested = holdings.sumOf { it.unitsHeld * it.avgBuyPrice }
        val current = holdings.sumOf { it.unitsHeld * (it.lastFetchedPrice ?: it.avgBuyPrice) }
        return invested to current
    }

    fun addAccount(name: String, onDone: (Long) -> Unit) {
        viewModelScope.launch {
            val bookId = session.activeBookId.value ?: return@launch
            val newId = dematAccountDao.insert(DematAccountEntity(bookId = bookId, name = name))
            onDone(newId)
        }
    }

    fun renameAccount(account: DematAccountEntity, newName: String) {
        viewModelScope.launch { dematAccountDao.update(account.copy(name = newName)) }
    }

    fun archive(id: Long) {
        viewModelScope.launch { dematAccountDao.archive(id) }
    }
}

/**
 * Lists every Demat (broker) account for this book — introduced per
 * explicit request, since a person can hold stocks across multiple
 * brokers with entirely separate holdings. Tapping one enters its own
 * holdings list; this screen only handles selecting/creating accounts.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DematAccountSelectorScreen(onBack: () -> Unit, onOpenAccount: (Long, String) -> Unit, viewModel: DematAccountSelectorViewModel = hiltViewModel()) {
    val accounts by viewModel.accounts.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var accountToRename by remember { mutableStateOf<DematAccountEntity?>(null) }
    val valuesCache = remember { mutableMapOf<Long, Pair<Double, Double>>() }

    LaunchedEffect(accounts) {
        accounts.forEach { account -> valuesCache[account.id] = viewModel.valuesFor(account.id) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Demat Accounts") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) { Icon(Icons.Default.Add, contentDescription = "Add Account") }
        }
    ) { padding ->
        if (accounts.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("No Demat accounts yet. Add one (e.g. \"Zerodha\", \"Groww\") to start tracking holdings.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(24.dp))
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(accounts) { account ->
                    val (invested, current) = valuesCache[account.id] ?: (0.0 to 0.0)
                    Card(modifier = Modifier.fillMaxWidth(), onClick = { onOpenAccount(account.id, account.name) }) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(account.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                IconButton(onClick = { accountToRename = account }) {
                                    Icon(Icons.Default.Edit, contentDescription = "Rename Account")
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                Column {
                                    Text("Invested", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("₹%.0f".format(invested), style = MaterialTheme.typography.titleMedium)
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text("Current", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("₹%.0f".format(current), style = MaterialTheme.typography.titleMedium)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("New Demat Account") },
            text = {
                Column {
                    Text("The name of your broker or platform, e.g. \"Zerodha\" or \"Groww\".", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Account name") }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = { viewModel.addAccount(name) { newId -> showAddDialog = false; onOpenAccount(newId, name) } }
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showAddDialog = false }) { Text("Cancel") } }
        )
    }

    accountToRename?.let { account ->
        var name by remember(account.id) { mutableStateOf(account.name) }
        AlertDialog(
            onDismissRequest = { accountToRename = null },
            title = { Text("Rename Account") },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Account name") }, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = { viewModel.renameAccount(account, name); accountToRename = null }
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { accountToRename = null }) { Text("Cancel") } }
        )
    }
}
