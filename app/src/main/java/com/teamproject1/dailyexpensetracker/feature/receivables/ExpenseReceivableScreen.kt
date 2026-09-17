package com.teamproject1.dailyexpensetracker.feature.receivables

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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.teamproject1.dailyexpensetracker.core.database.dao.ExpenseReceivableDao
import com.teamproject1.dailyexpensetracker.core.database.entity.ExpenseReceivableEntity
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

@HiltViewModel
class ExpenseReceivableListViewModel @Inject constructor(
    private val receivableDao: ExpenseReceivableDao,
    session: SessionManager
) : ViewModel() {

    val receivables = session.activeBookId.filterNotNull()
        .flatMapLatest { bookId -> receivableDao.getAll(bookId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun markCollected(receivable: ExpenseReceivableEntity) {
        viewModelScope.launch {
            receivableDao.update(receivable.copy(isCollected = true, collectedAt = System.currentTimeMillis()))
        }
    }

    fun markOutstanding(receivable: ExpenseReceivableEntity) {
        viewModelScope.launch {
            receivableDao.update(receivable.copy(isCollected = false, collectedAt = null))
        }
    }
}

/**
 * Expense-side Account Receivables — an IOU/shared-expense tracker, not a
 * standalone asset category. Lists every transaction flagged as "owed
 * back to me" (set from the Add Expense sheet), with a way to mark it
 * collected once the person pays you back. Deliberately does NOT
 * auto-create an Income transaction on collection — that would be a
 * silent auto-post, which this app never does; you can log that income
 * separately yourself if you want it reflected in your balances.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpenseReceivableListScreen(onBack: () -> Unit, viewModel: ExpenseReceivableListViewModel = hiltViewModel()) {
    val receivables by viewModel.receivables.collectAsState()
    var pendingCollect by remember { mutableStateOf<ExpenseReceivableEntity?>(null) }
    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy", Locale.US) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Account Receivables") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } }
            )
        }
    ) { padding ->
        if (receivables.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(
                    "Nothing flagged as owed to you yet. Mark an expense as receivable from the Add Expense sheet.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(24.dp)
                )
            }
        } else {
            val outstanding = receivables.filter { !it.isCollected }
            val totalOutstanding = outstanding.sumOf { it.amount }
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Text("Total Outstanding", style = MaterialTheme.typography.bodyMedium)
                    Text("₹%.0f".format(totalOutstanding), style = MaterialTheme.typography.displayLarge)
                    Spacer(Modifier.height(8.dp))
                }
                items(receivables) { receivable ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                Text(receivable.personName, style = MaterialTheme.typography.titleLarge)
                                Text("₹%.0f".format(receivable.amount), style = MaterialTheme.typography.titleLarge)
                            }
                            receivable.note?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                if (receivable.isCollected) "Collected ${receivable.collectedAt?.let { dateFormat.format(Date(it)) } ?: ""}"
                                else "Logged ${dateFormat.format(Date(receivable.createdAt))}",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (receivable.isCollected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(8.dp))
                            if (receivable.isCollected) {
                                TextButton(onClick = { viewModel.markOutstanding(receivable) }) { Text("Mark as Outstanding Again") }
                            } else {
                                TextButton(onClick = { pendingCollect = receivable }) { Text("Mark as Collected") }
                            }
                        }
                    }
                }
            }
        }
    }

    pendingCollect?.let { receivable ->
        AlertDialog(
            onDismissRequest = { pendingCollect = null },
            title = { Text("Mark as collected?") },
            text = {
                Text(
                    "This only marks the ₹%.0f owed by ${receivable.personName} as collected here — it doesn't create an Income transaction. If they paid you back in a way that should show up in your account balance, log that separately as Income.".format(receivable.amount)
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.markCollected(receivable); pendingCollect = null }) { Text("Mark Collected") }
            },
            dismissButton = {
                TextButton(onClick = { pendingCollect = null }) { Text("Cancel") }
            }
        )
    }
}
