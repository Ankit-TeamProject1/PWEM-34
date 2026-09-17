package com.teamproject1.dailyexpensetracker.feature.wealth.mutualfund

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
import com.teamproject1.dailyexpensetracker.core.database.dao.MutualFundDao
import com.teamproject1.dailyexpensetracker.core.database.dao.MutualFundPlatformDao
import com.teamproject1.dailyexpensetracker.core.database.entity.MutualFundPlatformEntity
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
class MutualFundPlatformSelectorViewModel @Inject constructor(
    private val platformDao: MutualFundPlatformDao,
    private val mutualFundDao: MutualFundDao,
    private val session: SessionManager
) : ViewModel() {
    val platforms = session.activeBookId.filterNotNull()
        .flatMapLatest { bookId -> platformDao.getActive(bookId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Invested and current value per platform, for a preview on the
     *  selector card — reuses each fund's already-fetched NAV and
     *  entries, never fetches anything itself. */
    suspend fun valuesFor(platformId: Long): Pair<Double, Double> {
        val accounts = mutualFundDao.getActiveForPlatform(platformId).first()
        var totalInvested = 0.0
        var totalCurrent = 0.0
        accounts.forEach { account ->
            val entries = mutualFundDao.getEntriesOnce(account.id)
            val units = entries.sumOf { it.unitsAllotted }
            val invested = entries.sumOf { it.investedAmount }
            val avgNav = if (units > 0) invested / units else 0.0
            totalInvested += invested
            totalCurrent += units * (account.lastFetchedNav ?: avgNav)
        }
        return totalInvested to totalCurrent
    }

    fun addPlatform(name: String, onDone: (Long) -> Unit) {
        viewModelScope.launch {
            val bookId = session.activeBookId.value ?: return@launch
            val newId = platformDao.insert(MutualFundPlatformEntity(bookId = bookId, name = name))
            onDone(newId)
        }
    }

    fun renamePlatform(platform: MutualFundPlatformEntity, newName: String) {
        viewModelScope.launch { platformDao.update(platform.copy(name = newName)) }
    }

    fun archive(id: Long) {
        viewModelScope.launch { platformDao.archive(id) }
    }
}

/**
 * Lists every Mutual Fund platform (e.g. "Zerodha Coin", "Groww",
 * "Kuvera", direct with the AMC) for this book — introduced per explicit
 * request, since a person can hold funds across multiple platforms with
 * entirely separate holdings. Tapping one enters its own fund list; this
 * screen only handles selecting/creating platforms.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MutualFundPlatformSelectorScreen(onBack: () -> Unit, onOpenPlatform: (Long, String) -> Unit, viewModel: MutualFundPlatformSelectorViewModel = hiltViewModel()) {
    val platforms by viewModel.platforms.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var platformToRename by remember { mutableStateOf<MutualFundPlatformEntity?>(null) }
    val valuesCache = remember { mutableMapOf<Long, Pair<Double, Double>>() }

    LaunchedEffect(platforms) {
        platforms.forEach { platform -> valuesCache[platform.id] = viewModel.valuesFor(platform.id) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Mutual Fund Accounts") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) { Icon(Icons.Default.Add, contentDescription = "Add Account") }
        }
    ) { padding ->
        if (platforms.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("No Mutual Fund accounts yet. Add one (e.g. \"Zerodha Coin\", \"Groww\") to start tracking funds.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(24.dp))
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(platforms) { platform ->
                    val (invested, current) = valuesCache[platform.id] ?: (0.0 to 0.0)
                    Card(modifier = Modifier.fillMaxWidth(), onClick = { onOpenPlatform(platform.id, platform.name) }) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(platform.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                IconButton(onClick = { platformToRename = platform }) {
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
            title = { Text("New Mutual Fund Account") },
            text = {
                Column {
                    Text("The platform you hold these funds through, e.g. \"Zerodha Coin\", \"Groww\", \"Kuvera\", or the AMC directly.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Account name") }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = { viewModel.addPlatform(name) { newId -> showAddDialog = false; onOpenPlatform(newId, name) } }
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showAddDialog = false }) { Text("Cancel") } }
        )
    }

    platformToRename?.let { platform ->
        var name by remember(platform.id) { mutableStateOf(platform.name) }
        AlertDialog(
            onDismissRequest = { platformToRename = null },
            title = { Text("Rename Account") },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Account name") }, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = { viewModel.renamePlatform(platform, name); platformToRename = null }
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { platformToRename = null }) { Text("Cancel") } }
        )
    }
}
