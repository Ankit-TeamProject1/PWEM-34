package com.teamproject1.dailyexpensetracker.feature.budget

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.teamproject1.dailyexpensetracker.core.database.TransactionRepository
import com.teamproject1.dailyexpensetracker.core.database.dao.BudgetDao
import com.teamproject1.dailyexpensetracker.core.database.dao.CategoryDao
import com.teamproject1.dailyexpensetracker.core.database.entity.BudgetEntity
import com.teamproject1.dailyexpensetracker.core.database.entity.CategoryEntity
import com.teamproject1.dailyexpensetracker.core.session.SessionManager
import com.teamproject1.dailyexpensetracker.ui.theme.parseCategoryColor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack

@HiltViewModel
class BudgetViewModel @Inject constructor(
    private val budgetDao: BudgetDao,
    private val categoryDao: CategoryDao,
    private val transactionRepository: TransactionRepository,
    private val session: SessionManager
) : ViewModel() {

    private val currentMonth = SimpleDateFormat("yyyy-MM", Locale.US).format(Date())
    private val lastMonth = SimpleDateFormat("yyyy-MM", Locale.US).format(
        Calendar.getInstance().apply { add(Calendar.MONTH, -1) }.time
    )

    val categories = session.activeBookId.filterNotNull()
        .flatMapLatest { bookId -> categoryDao.getActiveCategories(bookId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val budgets = session.activeBookId.filterNotNull()
        .flatMapLatest { bookId -> budgetDao.getForMonth(bookId, currentMonth) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val categorySpend = transactionRepository.getCategoryTotalsForMonth(currentMonth)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setBudget(categoryId: Long, capAmount: Double) {
        viewModelScope.launch {
            val activeBookId = session.activeBookId.value ?: return@launch
            budgetDao.upsert(
                BudgetEntity(bookId = activeBookId, categoryId = categoryId, month = currentMonth, capAmount = capAmount)
            )
        }
    }

    /** One-shot fetch for the category detail view — last month's cap and
     *  spend, fetched only when a category is actually opened rather than
     *  kept as a live Flow for every category on the main list. */
    suspend fun getLastMonthComparison(categoryId: Long): Pair<Double?, Double> {
        val bookId = session.activeBookId.value ?: return null to 0.0
        val lastMonthCap = budgetDao.getForMonth(bookId, lastMonth).first().find { it.categoryId == categoryId }?.capAmount
        val lastMonthSpend = transactionRepository.getCategoryTotalsForMonth(lastMonth).first()
            .find { it.categoryId == categoryId }?.total ?: 0.0
        return lastMonthCap to lastMonthSpend
    }
}

/**
 * Merged Budget + Analytics into one screen, per explicit request. The main
 * list keeps this month's cap/spend/progress (Budget's original job) and
 * adds each category's % of the total budget assigned (Analytics'
 * contribution). Last-month comparison — the other piece of the request —
 * lives in the category detail view rather than cluttering the main list,
 * per the follow-up decision on where that specific figure belongs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BudgetScreen(
    onBack: () -> Unit,
    viewModel: BudgetViewModel = hiltViewModel()
) {
    val categories by viewModel.categories.collectAsState()
    val budgets by viewModel.budgets.collectAsState()
    val spend by viewModel.categorySpend.collectAsState()
    var editingCategory by remember { mutableStateOf<CategoryEntity?>(null) }
    var detailCategory by remember { mutableStateOf<CategoryEntity?>(null) }

    val totalBudgeted = budgets.sumOf { it.capAmount }
    val totalSpent = spend.sumOf { it.total }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Budget — This Month") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Total Budgeted vs Spent", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "₹%.0f / ₹%.0f".format(totalSpent, totalBudgeted),
                            style = MaterialTheme.typography.displayLarge
                        )
                    }
                }
            }
            items(categories) { category ->
                val cap = budgets.find { it.categoryId == category.id }?.capAmount
                val spent = spend.find { it.categoryId == category.id }?.total ?: 0.0
                val percentOfTotal = if (cap != null && totalBudgeted > 0) (cap / totalBudgeted * 100) else null
                BudgetRow(
                    category = category, cap = cap, spent = spent, percentOfTotal = percentOfTotal,
                    onClick = { detailCategory = category }
                )
            }
        }
    }

    editingCategory?.let { category ->
        val existingCap = budgets.find { it.categoryId == category.id }?.capAmount
        SetBudgetDialog(
            category = category,
            existingCap = existingCap,
            onDismiss = { editingCategory = null },
            onSave = { cap ->
                viewModel.setBudget(category.id, cap)
                editingCategory = null
            }
        )
    }

    detailCategory?.let { category ->
        CategoryDetailDialog(
            category = category,
            thisMonthCap = budgets.find { it.categoryId == category.id }?.capAmount,
            thisMonthSpend = spend.find { it.categoryId == category.id }?.total ?: 0.0,
            viewModel = viewModel,
            onDismiss = { detailCategory = null },
            onEditBudget = { detailCategory = null; editingCategory = category }
        )
    }
}

@Composable
private fun BudgetRow(category: CategoryEntity, cap: Double?, spent: Double, percentOfTotal: Double?, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "${category.icon} ${category.name}",
                    style = MaterialTheme.typography.titleLarge,
                    color = parseCategoryColor(category.colorHex)
                )
                if (cap != null) {
                    Text("₹%.0f / %.0f".format(spent, cap), style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (percentOfTotal != null) {
                Text(
                    "%.0f%% of total budget".format(percentOfTotal),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(8.dp))
            if (cap == null || cap == 0.0) {
                Text("No cap set — tap for details", style = MaterialTheme.typography.bodyMedium)
            } else {
                val progress = (spent / cap).toFloat().coerceIn(0f, 1.5f)
                val color = when {
                    progress >= 1f -> MaterialTheme.colorScheme.error
                    progress >= 0.75f -> androidx.compose.ui.graphics.Color(0xFFD9A441)
                    else -> MaterialTheme.colorScheme.primary
                }
                LinearProgressIndicator(
                    progress = progress.coerceAtMost(1f),
                    modifier = Modifier.fillMaxWidth(),
                    color = color
                )
                if (progress >= 1f) {
                    Spacer(Modifier.height(8.dp))
                    Text("OVER", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun CategoryDetailDialog(
    category: CategoryEntity,
    thisMonthCap: Double?,
    thisMonthSpend: Double,
    viewModel: BudgetViewModel,
    onDismiss: () -> Unit,
    onEditBudget: () -> Unit
) {
    var lastMonthCap by remember { mutableStateOf<Double?>(null) }
    var lastMonthSpend by remember { mutableStateOf(0.0) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(category.id) {
        val (cap, spend) = viewModel.getLastMonthComparison(category.id)
        lastMonthCap = cap
        lastMonthSpend = spend
        loaded = true
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${category.icon} ${category.name}") },
        text = {
            Column {
                Text("This Month", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("Spent", style = MaterialTheme.typography.bodyMedium)
                    Text("₹%.0f".format(thisMonthSpend), style = MaterialTheme.typography.bodyMedium)
                }
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("Budget", style = MaterialTheme.typography.bodyMedium)
                    Text(thisMonthCap?.let { "₹%.0f".format(it) } ?: "Not set", style = MaterialTheme.typography.bodyMedium)
                }

                Spacer(Modifier.height(16.dp))
                Text("Last Month", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                if (!loaded) {
                    Text("Loading…", style = MaterialTheme.typography.bodySmall)
                } else {
                    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Text("Spent", style = MaterialTheme.typography.bodyMedium)
                        Text("₹%.0f".format(lastMonthSpend), style = MaterialTheme.typography.bodyMedium)
                    }
                    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Text("Budget", style = MaterialTheme.typography.bodyMedium)
                        Text(lastMonthCap?.let { "₹%.0f".format(it) } ?: "Not set", style = MaterialTheme.typography.bodyMedium)
                    }
                    if (lastMonthSpend > 0) {
                        Spacer(Modifier.height(8.dp))
                        val diff = thisMonthSpend - lastMonthSpend
                        val diffPercent = (diff / lastMonthSpend * 100)
                        Text(
                            if (diff >= 0) "Spending %.0f%% more than last month".format(diffPercent)
                            else "Spending %.0f%% less than last month".format(-diffPercent),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (diff >= 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onEditBudget) { Text(if (thisMonthCap == null) "Set Budget" else "Edit Budget") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
private fun SetBudgetDialog(
    category: CategoryEntity,
    existingCap: Double?,
    onDismiss: () -> Unit,
    onSave: (Double) -> Unit
) {
    var capText by remember { mutableStateOf(existingCap?.let { "%.0f".format(it) } ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Budget for ${category.name}") },
        text = {
            OutlinedTextField(
                value = capText,
                onValueChange = { capText = it.filter { c -> c.isDigit() || c == '.' } },
                label = { Text("Monthly cap") },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal)
            )
        },
        confirmButton = {
            TextButton(onClick = {
                capText.toDoubleOrNull()?.let { onSave(it) }
            }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
