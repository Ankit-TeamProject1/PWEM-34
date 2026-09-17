package com.teamproject1.dailyexpensetracker.feature.recurring

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.teamproject1.dailyexpensetracker.core.database.dao.AccountDao
import com.teamproject1.dailyexpensetracker.core.database.dao.CategoryDao
import com.teamproject1.dailyexpensetracker.core.database.dao.RecurringRuleDao
import com.teamproject1.dailyexpensetracker.core.database.entity.RecurrenceFrequency
import com.teamproject1.dailyexpensetracker.core.database.entity.RecurringRuleEntity
import com.teamproject1.dailyexpensetracker.core.database.entity.TransactionType
import com.teamproject1.dailyexpensetracker.core.session.SessionManager
import com.teamproject1.dailyexpensetracker.ui.theme.DateField
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack

@HiltViewModel
class NewRecurringRuleViewModel @Inject constructor(
    private val recurringRuleDao: RecurringRuleDao,
    private val accountDao: AccountDao,
    private val categoryDao: CategoryDao,
    private val session: SessionManager
) : ViewModel() {

    val accounts = session.activeBookId.filterNotNull()
        .flatMapLatest { bookId -> accountDao.getActiveAccounts(bookId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val categories = session.activeBookId.filterNotNull()
        .flatMapLatest { bookId -> categoryDao.getActiveCategories(bookId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun save(
        existingId: Long?,
        name: String,
        type: TransactionType,
        amount: Double,
        accountId: Long,
        toAccountId: Long?,
        categoryId: Long?,
        frequency: RecurrenceFrequency,
        startDate: Long,
        onSaved: () -> Unit
    ) {
        viewModelScope.launch {
            val activeBookId = session.activeBookId.value ?: return@launch
            if (existingId == null) {
                recurringRuleDao.insert(
                    RecurringRuleEntity(
                        bookId = activeBookId, name = name, type = type, amount = amount,
                        accountId = accountId, toAccountId = toAccountId, categoryId = categoryId,
                        frequency = frequency, interval = 1, startDate = startDate,
                        nextRunAt = startDate, isPaused = false
                    )
                )
            } else {
                // Edit mode — item 24. Load the existing row to preserve
                // fields not shown on this form (nextRunAt, isPaused, id),
                // rather than resetting the schedule on every edit.
                val existing = recurringRuleDao.getAll(activeBookId).first().find { it.id == existingId }
                if (existing != null) {
                    recurringRuleDao.update(
                        existing.copy(
                            name = name, type = type, amount = amount, accountId = accountId,
                            toAccountId = toAccountId, categoryId = categoryId,
                            frequency = frequency, startDate = startDate
                            // nextRunAt, isPaused, interval intentionally left as-is —
                            // editing amount/category shouldn't silently reset the
                            // schedule or un-pause a paused rule.
                        )
                    )
                }
            }
            onSaved()
        }
    }

    suspend fun loadExisting(id: Long): RecurringRuleEntity? {
        val bookId = session.activeBookId.value ?: return null
        return recurringRuleDao.getAll(bookId).first().find { it.id == id }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewRecurringRuleScreen(
    existingId: Long? = null,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    viewModel: NewRecurringRuleViewModel = hiltViewModel()
) {
    val accounts by viewModel.accounts.collectAsState()
    val categories by viewModel.categories.collectAsState()

    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(TransactionType.EXPENSE) }
    var amountText by remember { mutableStateOf("") }
    var accountId by remember { mutableStateOf<Long?>(null) }
    var toAccountId by remember { mutableStateOf<Long?>(null) }
    var categoryId by remember { mutableStateOf<Long?>(null) }
    var frequency by remember { mutableStateOf(RecurrenceFrequency.MONTHLY) }
    var startDate by remember { mutableStateOf(System.currentTimeMillis()) }
    var loaded by remember { mutableStateOf(existingId == null) }

    LaunchedEffect(existingId) {
        if (existingId != null) {
            val existing = viewModel.loadExisting(existingId)
            if (existing != null) {
                name = existing.name
                type = existing.type
                amountText = existing.amount.let { if (it == it.toLong().toDouble()) it.toLong().toString() else it.toString() }
                accountId = existing.accountId
                toAccountId = existing.toAccountId
                categoryId = existing.categoryId
                frequency = existing.frequency
                startDate = existing.startDate
            }
            loaded = true
        }
    }

    LaunchedEffect(accounts) { if (existingId == null && accountId == null) accountId = accounts.firstOrNull()?.id }

    val amount = amountText.toDoubleOrNull() ?: 0.0
    val canSave = name.isNotBlank() && amount > 0 && accountId != null &&
        (type != TransactionType.TRANSFER || toAccountId != null) &&
        (type == TransactionType.TRANSFER || categoryId != null)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (existingId == null) "New Rule" else "Edit Rule") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } }
            )
        }
    ) { padding ->
        if (!loaded) return@Scaffold

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
                .imePadding()
        ) {
            SingleChoiceSegmentedButtonRow {
                listOf(TransactionType.EXPENSE, TransactionType.INCOME, TransactionType.TRANSFER).forEachIndexed { index, t ->
                    SegmentedButton(
                        selected = type == t,
                        onClick = { type = t },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = 3)
                    ) { Text(t.name.lowercase().replaceFirstChar { it.uppercase() }) }
                }
            }

            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name (e.g. Netflix, Rent)") },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = amountText,
                onValueChange = { amountText = it.filter { c -> c.isDigit() || c == '.' } },
                label = { Text("Amount") },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(8.dp))
            Text("Account", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                accounts.forEach { account ->
                    FilterChip(
                        selected = accountId == account.id,
                        onClick = { accountId = account.id },
                        label = { Text(account.name) }
                    )
                }
            }

            if (type == TransactionType.TRANSFER) {
                Spacer(Modifier.height(8.dp))
                Text("To Account", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    accounts.filter { it.id != accountId }.forEach { account ->
                        FilterChip(
                            selected = toAccountId == account.id,
                            onClick = { toAccountId = account.id },
                            label = { Text(account.name) }
                        )
                    }
                }
            } else {
                Spacer(Modifier.height(8.dp))
                Text("Category", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                // Fix for reported bug: this was a plain non-scrolling Row,
                // so categories beyond the visible screen width were
                // present in the list but simply unreachable/off-screen.
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    categories.forEach { category ->
                        FilterChip(
                            selected = categoryId == category.id,
                            onClick = { categoryId = category.id },
                            label = { Text("${category.icon} ${category.name}") }
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            DateField(
                label = "Start date",
                selectedMillis = startDate,
                onDateSelected = { startDate = it }
            )

            Spacer(Modifier.height(8.dp))
            Text("Repeats", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                RecurrenceFrequency.values().forEach { freq ->
                    FilterChip(
                        selected = frequency == freq,
                        onClick = { frequency = freq },
                        label = { Text(freq.name.lowercase().replaceFirstChar { it.uppercase() }) }
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = {
                    viewModel.save(
                        existingId = existingId,
                        name = name,
                        type = type,
                        amount = amount,
                        accountId = accountId ?: return@Button,
                        toAccountId = if (type == TransactionType.TRANSFER) toAccountId else null,
                        categoryId = if (type == TransactionType.TRANSFER) null else categoryId,
                        frequency = frequency,
                        startDate = startDate,
                        onSaved = onSaved
                    )
                },
                enabled = canSave,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (existingId == null) "Save Rule" else "Update Rule")
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}
