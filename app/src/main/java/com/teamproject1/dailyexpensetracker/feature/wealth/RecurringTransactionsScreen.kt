package com.teamproject1.dailyexpensetracker.feature.wealth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.teamproject1.dailyexpensetracker.core.wealth.RecurringTransactionItem
import com.teamproject1.dailyexpensetracker.core.wealth.RecurringTransactionType
import com.teamproject1.dailyexpensetracker.core.wealth.RecurringTransactionsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class RecurringTransactionsViewModel @Inject constructor(
    private val repository: RecurringTransactionsRepository
) : ViewModel() {
    var items by mutableStateOf<List<RecurringTransactionItem>>(emptyList())
        private set

    fun load() {
        viewModelScope.launch { items = repository.getAll() }
    }
}

private fun typeLabel(type: RecurringTransactionType): String = when (type) {
    RecurringTransactionType.RD -> "RD"
    RecurringTransactionType.EPF_EMPLOYEE_EMPLOYER -> "EPF"
    RecurringTransactionType.EPF_PENSION -> "EPF"
    RecurringTransactionType.APY -> "APY"
    RecurringTransactionType.NPS -> "NPS"
    RecurringTransactionType.MUTUAL_FUND_SIP -> "Mutual Fund SIP"
    RecurringTransactionType.KAMETTI -> "Kametti"
}

/**
 * Consolidated view of every recurring commitment across Wealth
 * categories, per explicit request. Deliberately does not duplicate
 * edit/step-up/hold actions inline — tapping a row navigates to that
 * item's own screen, where whatever actions are actually applicable for
 * that type already exist (RD's entry list, EPF's Step Up/Down per tile,
 * APY's Step Up/Down, Mutual Fund's SIP edit/hold, Kametti's entries and
 * Won action). This avoids maintaining the same action logic in two
 * places.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecurringTransactionsScreen(
    onBack: () -> Unit,
    onOpenRd: (Long) -> Unit,
    onOpenEpf: (Long) -> Unit,
    onOpenApy: (Long) -> Unit,
    onOpenNps: (Long) -> Unit,
    onOpenMutualFund: (Long) -> Unit,
    onOpenKametti: (Long) -> Unit,
    viewModel: RecurringTransactionsViewModel = hiltViewModel()
) {
    LaunchedEffect(Unit) { viewModel.load() }
    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy", Locale.US) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Recurring Transactions") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } }
            )
        }
    ) { padding ->
        if (viewModel.items.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(
                    "No recurring transactions set up yet — RD, EPF, APY, Mutual Fund SIPs, and Kametti all appear here once configured.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(24.dp)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(viewModel.items) { item ->
                    Card(
                        modifier = Modifier.fillMaxWidth().height(96.dp),
                        onClick = {
                            when (item.type) {
                                RecurringTransactionType.RD -> onOpenRd(item.accountId)
                                RecurringTransactionType.EPF_EMPLOYEE_EMPLOYER, RecurringTransactionType.EPF_PENSION -> onOpenEpf(item.accountId)
                                RecurringTransactionType.APY -> onOpenApy(item.accountId)
                                RecurringTransactionType.NPS -> onOpenNps(item.accountId)
                                RecurringTransactionType.MUTUAL_FUND_SIP -> onOpenMutualFund(item.accountId)
                                RecurringTransactionType.KAMETTI -> onOpenKametti(item.accountId)
                            }
                        }
                    ) {
                        Column(modifier = Modifier.padding(16.dp).fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                // A long account/scheme name (e.g. a full
                                // EPF or Mutual Fund name) previously could
                                // wrap to two lines, making that one card
                                // taller than the rest — every tile is now
                                // a fixed height regardless of content, so
                                // a single-line truncation here keeps the
                                // name from pushing past its row.
                                Text(
                                    item.name, style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(typeLabel(item.type), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                Text("₹%.0f/month".format(item.amount), style = MaterialTheme.typography.bodyMedium)
                                item.scheduleAnchorDate?.let {
                                    Text(dateFormat.format(Date(it)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            // Always reserves this line's vertical space,
                            // even when empty — this (along with the fixed
                            // card height above) is what makes every tile
                            // genuinely the same size, not just "usually"
                            // the same size for items that happen to share
                            // the same content shape.
                            Text(
                                if (item.isHeld) "On Hold" else "",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }
    }
}
