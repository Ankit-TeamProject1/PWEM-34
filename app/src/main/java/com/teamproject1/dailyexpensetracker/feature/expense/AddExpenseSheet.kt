package com.teamproject1.dailyexpensetracker.feature.expense

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import com.teamproject1.dailyexpensetracker.core.database.TransactionRepository
import com.teamproject1.dailyexpensetracker.core.database.dao.AccountBalance
import com.teamproject1.dailyexpensetracker.core.database.dao.AccountDao
import com.teamproject1.dailyexpensetracker.core.database.dao.CategoryDao
import com.teamproject1.dailyexpensetracker.core.database.entity.AccountEntity
import com.teamproject1.dailyexpensetracker.core.database.entity.TransactionEntity
import com.teamproject1.dailyexpensetracker.core.database.entity.TransactionType
import com.teamproject1.dailyexpensetracker.core.session.SessionManager
import com.teamproject1.dailyexpensetracker.ui.theme.DateField
import com.teamproject1.dailyexpensetracker.ui.theme.parseCategoryColor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AddExpenseViewModel @Inject constructor(
    private val transactionRepository: TransactionRepository,
    private val accountDao: AccountDao,
    private val categoryDao: CategoryDao,
    private val expenseReceivableDao: com.teamproject1.dailyexpensetracker.core.database.dao.ExpenseReceivableDao,
    private val session: SessionManager
) : ViewModel() {

    val accounts = session.activeBookId.filterNotNull()
        .flatMapLatest { bookId -> accountDao.getActiveAccounts(bookId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val categories = session.activeBookId.filterNotNull()
        .flatMapLatest { bookId -> categoryDao.getActiveCategories(bookId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val balances = transactionRepository.getAccountBalances()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Item 44 — create a category without leaving this sheet. Returns the
     *  new category's id via callback so it can be immediately selected. */
    fun createCategoryInline(name: String, onCreated: (Long) -> Unit) {
        viewModelScope.launch {
            val bookId = session.activeBookId.value ?: return@launch
            val newId = categoryDao.insert(
                com.teamproject1.dailyexpensetracker.core.database.entity.CategoryEntity(
                    bookId = bookId, name = name, icon = "📦"
                )
            )
            onCreated(newId)
        }
    }

    fun save(
        type: TransactionType,
        amount: Double,
        accountId: Long,
        toAccountId: Long?,
        categoryId: Long?,
        note: String,
        occurredAt: Long,
        receivablePersonName: String?,
        receivableAmount: Double?,
        onSaved: () -> Unit
    ) {
        viewModelScope.launch {
            val activeBookId = session.activeBookId.value ?: return@launch
            val now = System.currentTimeMillis()
            val newTransactionId = transactionRepository.addTransaction(
                TransactionEntity(
                    bookId = activeBookId,
                    type = type,
                    amount = amount,
                    accountId = accountId,
                    toAccountId = toAccountId,
                    categoryId = categoryId,
                    note = note.ifBlank { null },
                    occurredAt = occurredAt,
                    createdAt = now,
                    updatedAt = now
                )
            )
            // "Mark as Receivable" — per explicit request, an IOU/shared-expense
            // flag on the transaction, not a separate asset category. Only
            // created when a person name was actually given.
            if (!receivablePersonName.isNullOrBlank() && receivableAmount != null && receivableAmount > 0) {
                expenseReceivableDao.insert(
                    com.teamproject1.dailyexpensetracker.core.database.entity.ExpenseReceivableEntity(
                        bookId = activeBookId, transactionId = newTransactionId, personName = receivablePersonName,
                        amount = receivableAmount, createdAt = now
                    )
                )
            }
            onSaved()
        }
    }

    /** Edit mode — updates the existing row in place rather than inserting
     *  a new one. Preserves the original createdAt, per the locked
     *  "edits recompute, never patch" data-integrity principle: since
     *  balances are derived live from this table, updating a row here is
     *  automatically reflected everywhere with no separate recalculation. */
    fun update(
        existing: TransactionEntity,
        type: TransactionType,
        amount: Double,
        accountId: Long,
        toAccountId: Long?,
        categoryId: Long?,
        note: String,
        occurredAt: Long,
        receivablePersonName: String?,
        receivableAmount: Double?,
        onSaved: () -> Unit
    ) {
        viewModelScope.launch {
            transactionRepository.updateTransaction(
                existing.copy(
                    type = type,
                    amount = amount,
                    accountId = accountId,
                    toAccountId = toAccountId,
                    categoryId = categoryId,
                    note = note.ifBlank { null },
                    occurredAt = occurredAt,
                    updatedAt = System.currentTimeMillis()
                )
            )

            // Sync the receivable flag: create if newly set, update if
            // changed, remove if the toggle was turned back off.
            val existingReceivable = expenseReceivableDao.getForTransaction(existing.id)
            when {
                receivablePersonName.isNullOrBlank() || receivableAmount == null || receivableAmount <= 0 -> {
                    if (existingReceivable != null) expenseReceivableDao.deleteForTransaction(existing.id)
                }
                existingReceivable != null -> {
                    expenseReceivableDao.update(existingReceivable.copy(personName = receivablePersonName, amount = receivableAmount))
                }
                else -> {
                    expenseReceivableDao.insert(
                        com.teamproject1.dailyexpensetracker.core.database.entity.ExpenseReceivableEntity(
                            bookId = existing.bookId, transactionId = existing.id, personName = receivablePersonName,
                            amount = receivableAmount, createdAt = System.currentTimeMillis()
                        )
                    )
                }
            }
            onSaved()
        }
    }

    fun delete(transactionId: Long, onDeleted: () -> Unit) {
        viewModelScope.launch {
            transactionRepository.softDelete(transactionId)
            onDeleted()
        }
    }

    suspend fun loadReceivable(transactionId: Long) = expenseReceivableDao.getForTransaction(transactionId)
}

/**
 * Sheet state lives in plain `remember`, surviving auth interruption for
 * free per the locked design.
 *
 * Now a 3-way Expense / Income / Transfer sheet — Transfer was previously
 * its own separate sheet and Dashboard tile; folded in here per the
 * decision to remove the Transfer tile and access it via the "+" FAB
 * alongside Add Expense/Income instead.
 *
 * `imePadding()` on the sheet's content Column ensures the Save button and
 * fields below the amount stay above the keyboard instead of being covered
 * by it — fixes the keyboard-covering bug.
 *
 * Edit mode: pass `editTransaction` to pre-fill the sheet with an existing
 * transaction's values. Save then updates that row instead of inserting a
 * new one, and a Delete option appears — closes the "Edit Expense not
 * available" gap. Screens that list transactions (Accounts, Expenses) show
 * this same sheet locally with `editTransaction` set, rather than needing
 * a whole separate edit screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddExpenseSheet(
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
    editTransaction: TransactionEntity? = null,
    presetType: TransactionType? = null,
    viewModel: AddExpenseViewModel = hiltViewModel()
) {
    val accounts by viewModel.accounts.collectAsState()
    val categories by viewModel.categories.collectAsState()
    val balances by viewModel.balances.collectAsState()

    var type by remember { mutableStateOf(editTransaction?.type ?: presetType ?: TransactionType.EXPENSE) }
    var amountText by remember { mutableStateOf(editTransaction?.amount?.let { if (it == it.toLong().toDouble()) it.toLong().toString() else it.toString() } ?: "") }
    var selectedAccountId by remember { mutableStateOf(editTransaction?.accountId) }
    var toAccountId by remember { mutableStateOf(editTransaction?.toAccountId) }
    var selectedCategoryId by remember { mutableStateOf(editTransaction?.categoryId) }
    var note by remember { mutableStateOf(editTransaction?.note ?: "") }
    var showInlineCategoryField by remember { mutableStateOf(false) }
    var newCategoryName by remember { mutableStateOf("") }
    var occurredAt by remember { mutableStateOf(editTransaction?.occurredAt ?: System.currentTimeMillis()) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    // "Mark as Receivable" — an IOU/shared-expense flag, per explicit
    // request. Only meaningful for Expense-type transactions.
    var isReceivable by remember { mutableStateOf(false) }
    var receivablePersonName by remember { mutableStateOf("") }
    var receivableAmountText by remember { mutableStateOf("") }

    LaunchedEffect(editTransaction?.id) {
        if (editTransaction != null) {
            val existingReceivable = viewModel.loadReceivable(editTransaction.id)
            if (existingReceivable != null) {
                isReceivable = true
                receivablePersonName = existingReceivable.personName
                receivableAmountText = existingReceivable.amount.let { if (it == it.toLong().toDouble()) it.toLong().toString() else it.toString() }
            }
        }
    }

    LaunchedEffect(accounts) {
        if (editTransaction == null) {
            if (selectedAccountId == null) selectedAccountId = accounts.firstOrNull()?.id
            if (toAccountId == null && accounts.size > 1) toAccountId = accounts[1].id
        }
    }

    val amount = amountText.toDoubleOrNull() ?: 0.0
    val isTransfer = type == TransactionType.TRANSFER
    val canSave = amount > 0 && selectedAccountId != null &&
        (if (isTransfer) (toAccountId != null && toAccountId != selectedAccountId) else selectedCategoryId != null)

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            SingleChoiceSegmentedButtonRow {
                listOf(
                    "Expense" to TransactionType.EXPENSE,
                    "Income" to TransactionType.INCOME,
                    "Transfer" to TransactionType.TRANSFER
                ).forEachIndexed { index, (label, value) ->
                    SegmentedButton(
                        selected = type == value,
                        onClick = { type = value },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = 3)
                    ) { Text(label) }
                }
            }

            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = amountText,
                onValueChange = { amountText = it.filter { c -> c.isDigit() || c == '.' } },
                label = { Text("Amount") },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal),
                textStyle = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(16.dp))

            DateField(
                label = "Date",
                selectedMillis = occurredAt,
                onDateSelected = { occurredAt = it }
            )

            Spacer(Modifier.height(16.dp))

            if (isTransfer) {
                Text("From", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                AccountChipRow(accounts, selectedAccountId, balances) { selectedAccountId = it }

                Spacer(Modifier.height(8.dp))
                TextButton(onClick = {
                    val temp = selectedAccountId
                    selectedAccountId = toAccountId
                    toAccountId = temp
                }) { Text("⇅  Swap") }
                Spacer(Modifier.height(8.dp))

                Text("To", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                AccountChipRow(
                    accounts.filter { it.id != selectedAccountId },
                    toAccountId,
                    balances
                ) { toAccountId = it }
            } else {
                if (accounts.isEmpty()) {
                    Text("No accounts available.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState())
                    ) {
                        accounts.forEach { account ->
                            FilterChip(
                                selected = selectedAccountId == account.id,
                                onClick = { selectedAccountId = account.id },
                                label = { Text(account.name) }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                Text("Category", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))

                if (categories.isEmpty() && !showInlineCategoryField) {
                    Text("No categories available.", style = MaterialTheme.typography.bodyMedium)
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(categories) { category ->
                        FilterChip(
                            selected = selectedCategoryId == category.id,
                            onClick = { selectedCategoryId = category.id },
                            label = { Text("${category.icon} ${category.name}") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = parseCategoryColor(category.colorHex)
                            )
                        )
                    }
                    // Create-on-the-fly, per the explicit request — no need
                    // to leave this sheet and go to Manage Categories first.
                    item {
                        FilterChip(
                            selected = false,
                            onClick = { showInlineCategoryField = true },
                            label = { Text("＋ New") }
                        )
                    }
                }

                if (showInlineCategoryField) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = newCategoryName,
                            onValueChange = { newCategoryName = it },
                            label = { Text("New category name") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = {
                            if (newCategoryName.isNotBlank()) {
                                viewModel.createCategoryInline(newCategoryName) { newId ->
                                    selectedCategoryId = newId
                                    newCategoryName = ""
                                    showInlineCategoryField = false
                                }
                            }
                        }) { Text("Add") }
                        TextButton(onClick = { showInlineCategoryField = false; newCategoryName = "" }) { Text("Cancel") }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("Note (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            if (type == TransactionType.EXPENSE) {
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Switch(checked = isReceivable, onCheckedChange = { isReceivable = it })
                    Spacer(Modifier.width(8.dp))
                    Text("Mark as Receivable (owed back to me)", style = MaterialTheme.typography.bodyMedium)
                }
                if (isReceivable) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = receivablePersonName,
                        onValueChange = { receivablePersonName = it },
                        label = { Text("Who owes this?") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = receivableAmountText,
                        onValueChange = { receivableAmountText = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text("Amount owed (can be less than the full expense)") },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = {
                    val receivableName = if (type == TransactionType.EXPENSE && isReceivable) receivablePersonName.ifBlank { null } else null
                    val receivableAmt = if (type == TransactionType.EXPENSE && isReceivable) receivableAmountText.toDoubleOrNull() else null
                    if (editTransaction != null) {
                        viewModel.update(
                            existing = editTransaction,
                            type = type,
                            amount = amount,
                            accountId = selectedAccountId ?: return@Button,
                            toAccountId = if (isTransfer) toAccountId else null,
                            categoryId = if (isTransfer) null else selectedCategoryId,
                            note = note,
                            occurredAt = occurredAt,
                            receivablePersonName = receivableName,
                            receivableAmount = receivableAmt,
                            onSaved = onSaved
                        )
                    } else {
                        viewModel.save(
                            type = type,
                            amount = amount,
                            accountId = selectedAccountId ?: return@Button,
                            toAccountId = if (isTransfer) toAccountId else null,
                            categoryId = if (isTransfer) null else selectedCategoryId,
                            note = note,
                            occurredAt = occurredAt,
                            receivablePersonName = receivableName,
                            receivableAmount = receivableAmt,
                            onSaved = onSaved
                        )
                    }
                },
                enabled = canSave,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (editTransaction != null) "Update" else when (type) {
                        TransactionType.EXPENSE -> "Save Expense"
                        TransactionType.INCOME -> "Save Income"
                        TransactionType.TRANSFER -> "Confirm Transfer"
                    }
                )
            }

            if (editTransaction != null) {
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = { showDeleteConfirm = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (showDeleteConfirm && editTransaction != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete this entry?") },
            text = { Text("This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(editTransaction.id, onSaved)
                    showDeleteConfirm = false
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun AccountChipRow(
    accounts: List<AccountEntity>,
    selectedId: Long?,
    balances: List<AccountBalance>,
    onSelect: (Long) -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.horizontalScroll(rememberScrollState())
    ) {
        accounts.forEach { account ->
            val balance = balances.find { it.accountId == account.id }?.balance
            FilterChip(
                selected = selectedId == account.id,
                onClick = { onSelect(account.id) },
                label = { Text("${account.name}${balance?.let { " ₹%.0f".format(it) } ?: ""}") }
            )
        }
    }
}
