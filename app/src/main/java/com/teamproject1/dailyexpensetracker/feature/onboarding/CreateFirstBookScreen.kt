package com.teamproject1.dailyexpensetracker.feature.onboarding

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.teamproject1.dailyexpensetracker.core.database.dao.AccountDao
import com.teamproject1.dailyexpensetracker.core.database.dao.BookDao
import com.teamproject1.dailyexpensetracker.core.database.dao.CategoryDao
import com.teamproject1.dailyexpensetracker.core.database.dao.TransactionDao
import com.teamproject1.dailyexpensetracker.core.database.entity.*
import com.teamproject1.dailyexpensetracker.core.session.SessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CreateBookViewModel @Inject constructor(
    private val bookDao: BookDao,
    private val accountDao: AccountDao,
    private val categoryDao: CategoryDao,
    private val transactionDao: TransactionDao,
    private val backupRepository: com.teamproject1.dailyexpensetracker.core.backup.LocalBackupRepository,
    private val session: SessionManager
) : ViewModel() {

    /** Item 1 — restore from a backup file instead of creating a fresh
     *  book, per the explicit request to avoid re-entering everything by
     *  hand if a backup already has books and data in it. Every book in
     *  the file is created fresh (there's no "current book" to merge into
     *  yet at this point in onboarding). */
    fun restoreFromBackup(inputStream: java.io.InputStream, onResult: (com.teamproject1.dailyexpensetracker.core.backup.BackupImportResult) -> Unit) {
        viewModelScope.launch {
            val result = backupRepository.restoreAsNewBooks(inputStream)
            if (result is com.teamproject1.dailyexpensetracker.core.backup.BackupImportResult.Success && result.firstBookId != null) {
                session.setActiveBook(result.firstBookId)
                session.persistLastUsedBook(result.firstBookId)
                session.unlock()
            }
            onResult(result)
        }
    }

    /**
     * Creates the book, its two default accounts (Bank, Cash), a starter set
     * of categories, and — if a starting balance was entered — an actual
     * "Opening Balance" INCOME transaction per account. This is what keeps
     * the derived-balance rule intact with zero special-casing: opening
     * balances are just normal transactions, visible in history like any
     * other entry, not a hidden stored number.
     *
     * A blank/zero balance = no opening transaction created for that account,
     * per the locked "skip the balance" decision.
     */
    fun createBookAndProceed(
        name: String,
        currencyCode: String,
        bookType: BookType,
        startingBankBalance: Double?,
        startingCashBalance: Double?,
        onDone: (bookId: Long) -> Unit
    ) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()

            val bookId = bookDao.insert(
                BookEntity(
                    name = name.ifBlank { if (bookType == BookType.WEALTH) "Wealth" else "Personal" },
                    bookType = bookType,
                    currencyCode = currencyCode,
                    iconColor = "#2E5E4E",
                    createdAt = now,
                    lastOpenedAt = now
                )
            )

            // Wealth books have no accounts/categories/transactions —
            // entirely separate data model (FD, Liabilities, Cash in Hand,
            // etc.). Only Expense books get the Bank/Cash + starter setup.
            if (bookType == BookType.EXPENSE) {
                val bankAccountId = accountDao.insert(
                    AccountEntity(bookId = bookId, name = "Bank", type = AccountType.BANK, isDefault = true, createdAt = now)
                )
                val cashAccountId = accountDao.insert(
                    AccountEntity(bookId = bookId, name = "Cash", type = AccountType.CASH, isDefault = true, createdAt = now)
                )

                val starterCategories = listOf("Groceries", "Transport", "Entertainment", "Health", "Bills", "Other")
                starterCategories.forEach { catName ->
                    categoryDao.insert(CategoryEntity(bookId = bookId, name = catName, icon = "📦"))
                }

                if (startingBankBalance != null && startingBankBalance > 0) {
                    transactionDao.insert(
                        TransactionEntity(
                            bookId = bookId, type = TransactionType.INCOME, amount = startingBankBalance,
                            accountId = bankAccountId, note = "Opening Balance",
                            occurredAt = now, createdAt = now, updatedAt = now
                        )
                    )
                }
                if (startingCashBalance != null && startingCashBalance > 0) {
                    transactionDao.insert(
                        TransactionEntity(
                            bookId = bookId, type = TransactionType.INCOME, amount = startingCashBalance,
                            accountId = cashAccountId, note = "Opening Balance",
                            occurredAt = now, createdAt = now, updatedAt = now
                        )
                    )
                }
            }

            session.setActiveBook(bookId)
            session.persistLastUsedBook(bookId)
            session.unlock()   // PIN was already just set in the previous step
            onDone(bookId)
        }
    }
}

private val commonCurrencies = listOf("INR", "USD", "EUR", "GBP", "AED", "SGD")

/**
 * Step 3 of onboarding — MANDATORY, cannot bypass. Also reused (lighter
 * version, via `showStartingBalances = false`) when a user deletes their
 * last remaining book and needs to create a new one from the Book Selector's
 * empty state.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun CreateFirstBookScreen(
    showStartingBalances: Boolean = true,
    onBookCreated: (bookId: Long) -> Unit,
    viewModel: CreateBookViewModel = hiltViewModel()
) {
    var bookName by remember { mutableStateOf("Personal") }
    var bookType by remember { mutableStateOf(BookType.EXPENSE) }
    var selectedCurrency by remember { mutableStateOf("INR") }
    var bankBalance by remember { mutableStateOf("") }
    var cashBalance by remember { mutableStateOf("") }
    var currencyMenuExpanded by remember { mutableStateOf(false) }
    var restoreStatus by remember { mutableStateOf<String?>(null) }
    var isRestoring by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current

    val restoreLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        isRestoring = true
        context.contentResolver.openInputStream(uri)?.use { input ->
            viewModel.restoreFromBackup(input) { result ->
                isRestoring = false
                when (result) {
                    is com.teamproject1.dailyexpensetracker.core.backup.BackupImportResult.Success -> {
                        // Uses a Toast rather than the restoreStatus state
                        // variable, since onBookCreated navigates away
                        // immediately below — a Composable state message
                        // would never actually be seen. Surfaces exactly
                        // which book(s) failed and why, instead of the
                        // previous silent failure with no message at all.
                        if (result.warnings.isNotEmpty()) {
                            android.widget.Toast.makeText(
                                context,
                                "Restored ${result.booksImported} book(s), but: " + result.warnings.joinToString("; "),
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                        if (result.firstBookId != null) onBookCreated(result.firstBookId)
                    }
                    com.teamproject1.dailyexpensetracker.core.backup.BackupImportResult.InvalidFile ->
                        restoreStatus = "That doesn't look like a valid backup file."
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.Start
    ) {
        Spacer(Modifier.height(24.dp))
        Text("Let's create your first book", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "A book is your ledger — you can add more later for separate finances.",
            style = MaterialTheme.typography.bodyMedium
        )

        Spacer(Modifier.height(16.dp))
        // Item 1 — restore from a previous backup instead of starting fresh,
        // per the explicit request to avoid re-entering everything by hand
        // when a backup already exists with books and data.
        OutlinedButton(
            onClick = { restoreLauncher.launch(arrayOf("*/*")) },
            enabled = !isRestoring,
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (isRestoring) "Restoring…" else "Restore from a Backup Instead") }
        restoreStatus?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(16.dp))
        Text("— or create a new one —", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))

        // Book type — asked first, per the locked design: a book is either
        // Expense or Wealth, chosen at creation, using entirely separate
        // data models.
        Text("Book type", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        SingleChoiceSegmentedButtonRow {
            listOf("💰 Expense" to BookType.EXPENSE, "📈 Wealth" to BookType.WEALTH).forEachIndexed { index, (label, type) ->
                SegmentedButton(
                    selected = bookType == type,
                    onClick = {
                        bookType = type
                        if (bookName == "Personal" && type == BookType.WEALTH) bookName = "Wealth"
                        else if (bookName == "Wealth" && type == BookType.EXPENSE) bookName = "Personal"
                    },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = 2)
                ) { Text(label) }
            }
        }

        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = bookName,
            onValueChange = { bookName = it },
            label = { Text("Book name") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(16.dp))

        ExposedDropdownMenuBox(
            expanded = currencyMenuExpanded,
            onExpandedChange = { currencyMenuExpanded = it }
        ) {
            OutlinedTextField(
                value = selectedCurrency,
                onValueChange = {},
                readOnly = true,
                label = { Text("Currency") },
                modifier = Modifier.menuAnchor().fillMaxWidth()
            )
            ExposedDropdownMenu(
                expanded = currencyMenuExpanded,
                onDismissRequest = { currencyMenuExpanded = false }
            ) {
                commonCurrencies.forEach { code ->
                    DropdownMenuItem(
                        text = { Text(code) },
                        onClick = { selectedCurrency = code; currencyMenuExpanded = false }
                    )
                }
            }
        }

        if (showStartingBalances && bookType == BookType.EXPENSE) {
            Spacer(Modifier.height(24.dp))
            Text("Starting balances (optional)", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                "Leave blank to start at zero and log it as your first transaction instead.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = bankBalance,
                onValueChange = { bankBalance = it.filter { c -> c.isDigit() || c == '.' } },
                label = { Text("💰 Bank") },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = cashBalance,
                onValueChange = { cashBalance = it.filter { c -> c.isDigit() || c == '.' } },
                label = { Text("💵 Cash") },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(Modifier.height(32.dp))

        Button(
            onClick = {
                viewModel.createBookAndProceed(
                    name = bookName,
                    currencyCode = selectedCurrency,
                    bookType = bookType,
                    startingBankBalance = bankBalance.toDoubleOrNull(),
                    startingCashBalance = cashBalance.toDoubleOrNull(),
                    onDone = onBookCreated
                )
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = bookName.isNotBlank()
        ) {
            Text("Create Book")
        }
    }
}
