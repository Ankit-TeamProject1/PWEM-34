package com.teamproject1.dailyexpensetracker.feature.accounts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.teamproject1.dailyexpensetracker.core.database.TransactionRepository
import com.teamproject1.dailyexpensetracker.core.database.dao.AccountBalance
import com.teamproject1.dailyexpensetracker.core.database.entity.TransactionEntity
import com.teamproject1.dailyexpensetracker.core.database.entity.TransactionType
import com.teamproject1.dailyexpensetracker.feature.expense.AddExpenseSheet
import com.teamproject1.dailyexpensetracker.ui.theme.colorForTransactionType
import com.teamproject1.dailyexpensetracker.ui.theme.signForTransactionType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack

@HiltViewModel
class AccountsViewModel @Inject constructor(
    transactionRepository: TransactionRepository,
    accountDao: com.teamproject1.dailyexpensetracker.core.database.dao.AccountDao,
    session: com.teamproject1.dailyexpensetracker.core.session.SessionManager
) : ViewModel() {

    val balances = transactionRepository.getAccountBalances()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recentTransactions = transactionRepository.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val accounts = session.activeBookId
        .filterNotNull()
        .flatMapLatest { bookId -> accountDao.getActiveAccounts(bookId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsScreen(
    onBack: () -> Unit,
    viewModel: AccountsViewModel = hiltViewModel()
) {
    val balances by viewModel.balances.collectAsState()
    val transactions by viewModel.recentTransactions.collectAsState()
    val accounts by viewModel.accounts.collectAsState()
    // Edit-in-place, per the "Edit Expense" fix — tapping a row opens the
    // same AddExpenseSheet used for creating new entries, pre-filled.
    var editingTransaction by remember { mutableStateOf<TransactionEntity?>(null) }
    // Item 2 — account-wise filter, per explicit request. null = show all.
    var selectedAccountId by remember { mutableStateOf<Long?>(null) }

    val filteredTransactions = remember(transactions, selectedAccountId) {
        if (selectedAccountId == null) transactions
        else transactions.filter { it.accountId == selectedAccountId || it.toAccountId == selectedAccountId }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Accounts") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(balances) { balance ->
                AccountBalanceCard(
                    balance = balance,
                    isSelected = selectedAccountId == balance.accountId,
                    onClick = {
                        selectedAccountId = if (selectedAccountId == balance.accountId) null else balance.accountId
                    }
                )
            }

            item {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        if (selectedAccountId == null) "Recent Transactions"
                        else "Transactions — ${balances.find { it.accountId == selectedAccountId }?.accountName ?: ""}",
                        style = MaterialTheme.typography.titleLarge
                    )
                    if (selectedAccountId != null) {
                        TextButton(onClick = { selectedAccountId = null }) { Text("Show All") }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            if (filteredTransactions.isEmpty()) {
                item { Text("No transactions yet.", style = MaterialTheme.typography.bodyMedium) }
            } else {
                items(filteredTransactions.take(50)) { tx ->
                    TransactionRow(
                        tx = tx,
                        accountName = if (selectedAccountId == null) accounts.find { it.id == tx.accountId }?.name else null,
                        onClick = { editingTransaction = tx }
                    )
                }
            }
        }
    }

    editingTransaction?.let { tx ->
        AddExpenseSheet(
            onDismiss = { editingTransaction = null },
            onSaved = { editingTransaction = null },
            editTransaction = tx
        )
    }
}

@Composable
private fun AccountBalanceCard(balance: AccountBalance, isSelected: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(balance.accountName, style = MaterialTheme.typography.titleLarge)
            Text("₹%.2f".format(balance.balance), style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun TransactionRow(tx: TransactionEntity, accountName: String?, onClick: () -> Unit) {
    val sign = signForTransactionType(tx.type)
    val color = colorForTransactionType(tx.type)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Text(tx.note ?: tx.type.name, style = MaterialTheme.typography.bodyLarge)
            if (accountName != null) {
                Text(accountName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text("$sign₹%.2f".format(tx.amount), color = color, style = MaterialTheme.typography.bodyLarge)
    }
}
