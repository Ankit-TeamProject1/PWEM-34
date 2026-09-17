package com.teamproject1.dailyexpensetracker.feature.expenses

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.teamproject1.dailyexpensetracker.core.database.TransactionRepository
import com.teamproject1.dailyexpensetracker.core.database.dao.CategoryDao
import com.teamproject1.dailyexpensetracker.core.database.entity.CategoryEntity
import com.teamproject1.dailyexpensetracker.core.database.entity.TransactionEntity
import com.teamproject1.dailyexpensetracker.core.database.entity.TransactionType
import com.teamproject1.dailyexpensetracker.ui.theme.colorForTransactionType
import com.teamproject1.dailyexpensetracker.ui.theme.signForTransactionType
import com.teamproject1.dailyexpensetracker.core.session.SessionManager
import com.teamproject1.dailyexpensetracker.feature.expense.AddExpenseSheet
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack

@HiltViewModel
class ExpensesViewModel @Inject constructor(
    transactionRepository: TransactionRepository,
    private val categoryDao: CategoryDao,
    private val accountDao: com.teamproject1.dailyexpensetracker.core.database.dao.AccountDao,
    session: SessionManager
) : ViewModel() {

    val allTransactions = transactionRepository.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val categories = session.activeBookId.filterNotNull()
        .flatMapLatest { bookId -> categoryDao.getActiveCategories(bookId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val accounts = session.activeBookId.filterNotNull()
        .flatMapLatest { bookId -> accountDao.getActiveAccounts(bookId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}

/**
 * The full browsable ledger — every transaction, filterable by category
 * and grouped date-wise. Distinct from Accounts' simple recent-transactions
 * list (that one's a quick glance; this is the complete searchable record),
 * per item 5.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpensesScreen(
    onBack: () -> Unit,
    viewModel: ExpensesViewModel = hiltViewModel()
) {
    val allTransactions by viewModel.allTransactions.collectAsState()
    val categories by viewModel.categories.collectAsState()
    val accounts by viewModel.accounts.collectAsState()
    var selectedCategoryId by remember { mutableStateOf<Long?>(null) } // null = All
    var selectedAccountId by remember { mutableStateOf<Long?>(null) } // null = All
    var selectedMonth by remember { mutableStateOf(SimpleDateFormat("yyyy-MM", Locale.US).format(Date())) }
    var editingTransaction by remember { mutableStateOf<TransactionEntity?>(null) }
    val monthLabelFormat = remember { SimpleDateFormat("MMMM yyyy", Locale.US) }
    val monthKeyFormat = remember { SimpleDateFormat("yyyy-MM", Locale.US) }

    val monthFiltered = remember(allTransactions, selectedMonth) {
        allTransactions.filter { monthKeyFormat.format(Date(it.occurredAt)) == selectedMonth }
    }

    val filtered = remember(monthFiltered, selectedCategoryId, selectedAccountId) {
        monthFiltered
            .filter { selectedCategoryId == null || it.categoryId == selectedCategoryId }
            .filter { selectedAccountId == null || it.accountId == selectedAccountId || it.toAccountId == selectedAccountId }
    }

    val monthTotalExpense = remember(monthFiltered) {
        monthFiltered.filter { it.type == TransactionType.EXPENSE }.sumOf { it.amount }
    }

    val grouped = remember(filtered) {
        val dayFormat = SimpleDateFormat("EEEE, MMM d", Locale.getDefault())
        filtered.groupBy { dayFormat.format(Date(it.occurredAt)) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Expenses") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {

            // Month selector + that month's total expense, per item 5's
            // added requirement.
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    TextButton(onClick = {
                        selectedMonth = shiftMonth(selectedMonth, -1)
                    }) { Text("‹") }
                    Text(
                        try { monthLabelFormat.format(monthKeyFormat.parse(selectedMonth)!!) } catch (e: Exception) { selectedMonth },
                        style = MaterialTheme.typography.titleMedium
                    )
                    TextButton(onClick = {
                        selectedMonth = shiftMonth(selectedMonth, 1)
                    }) { Text("›") }
                }
                Text(
                    "Total: ₹%.0f".format(monthTotalExpense),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }

            // Category filter row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedCategoryId == null,
                    onClick = { selectedCategoryId = null },
                    label = { Text("All") }
                )
                categories.forEach { category ->
                    FilterChip(
                        selected = selectedCategoryId == category.id,
                        onClick = { selectedCategoryId = category.id },
                        label = { Text("${category.icon} ${category.name}") }
                    )
                }
            }

            // Account filter row — item 3, mirrors the Accounts screen's
            // own account filter, added here alongside the category filter.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedAccountId == null,
                    onClick = { selectedAccountId = null },
                    label = { Text("All Accounts") }
                )
                accounts.forEach { account ->
                    FilterChip(
                        selected = selectedAccountId == account.id,
                        onClick = { selectedAccountId = account.id },
                        label = { Text(account.name) }
                    )
                }
            }

            if (filtered.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    Text("No transactions this month.", style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    grouped.forEach { (dayLabel, dayTransactions) ->
                        item {
                            Text(
                                dayLabel,
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                            )
                        }
                        items(dayTransactions) { tx ->
                            ExpenseRow(tx, categories, onClick = { editingTransaction = tx })
                        }
                    }
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
private fun ExpenseRow(tx: TransactionEntity, categories: List<CategoryEntity>, onClick: () -> Unit) {
    val category = categories.find { it.id == tx.categoryId }
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
            Text(tx.note ?: category?.name ?: tx.type.name, style = MaterialTheme.typography.bodyLarge)
            if (category != null) {
                Text(
                    "${category.icon} ${category.name}",
                    style = MaterialTheme.typography.bodySmall,
                    color = com.teamproject1.dailyexpensetracker.ui.theme.parseCategoryColor(category.colorHex)
                )
            }
        }
        Text("$sign₹%.2f".format(tx.amount), color = color, style = MaterialTheme.typography.bodyLarge)
    }
}

/** Shifts a "yyyy-MM" string by the given number of months, either direction. */
private fun shiftMonth(monthKey: String, delta: Int): String {
    val format = SimpleDateFormat("yyyy-MM", Locale.US)
    val calendar = java.util.Calendar.getInstance()
    calendar.time = format.parse(monthKey) ?: Date()
    calendar.add(java.util.Calendar.MONTH, delta)
    return format.format(calendar.time)
}
