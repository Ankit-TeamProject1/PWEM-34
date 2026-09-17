package com.teamproject1.dailyexpensetracker.core.export

import com.teamproject1.dailyexpensetracker.core.database.dao.AccountDao
import com.teamproject1.dailyexpensetracker.core.database.dao.CategoryDao
import com.teamproject1.dailyexpensetracker.core.database.dao.ExpenseReceivableDao
import com.teamproject1.dailyexpensetracker.core.database.dao.RecurringRuleDao
import com.teamproject1.dailyexpensetracker.core.database.dao.TransactionDao
import com.teamproject1.dailyexpensetracker.core.database.entity.AccountEntity
import com.teamproject1.dailyexpensetracker.core.database.entity.AccountType
import com.teamproject1.dailyexpensetracker.core.database.entity.CategoryEntity
import com.teamproject1.dailyexpensetracker.core.database.entity.ExpenseReceivableEntity
import com.teamproject1.dailyexpensetracker.core.database.entity.RecurrenceFrequency
import com.teamproject1.dailyexpensetracker.core.database.entity.RecurringRuleEntity
import com.teamproject1.dailyexpensetracker.core.database.entity.TransactionEntity
import com.teamproject1.dailyexpensetracker.core.database.entity.TransactionType
import com.teamproject1.dailyexpensetracker.core.session.SessionManager
import kotlinx.coroutines.flow.first
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

// Date-only, no timestamp — per explicit request. Using time-of-day
// wasn't adding real value for a daily-transaction export, and mixing
// date+time formats is more likely to trip up if the file gets opened
// and re-saved in a real spreadsheet app before being imported back.
private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.US)
private fun parseDate(s: String): Long? = if (s.isBlank()) null else try { DATE_FORMAT.parse(s)?.time } catch (e: Exception) { null }

sealed class ImportResult {
    // warnings mirrors the same fix applied to Wealth's import —
    // previously this only tracked a skipped COUNT with zero explanation
    // for why any given row failed.
    data class Success(val importedCount: Int, val skippedCount: Int, val warnings: List<String> = emptyList()) : ImportResult()
    object InvalidFile : ImportResult()
}

/**
 * Result of scanning a file before committing anything. If
 * `unmatchedCategoryNames` is non-empty, the caller should ask the user to
 * resolve each one (map to an existing category, or leave uncategorized)
 * before calling `commitImport` — this is what prevents transactions from
 * silently losing their category on import, which the unresolved (default)
 * behavior would otherwise do without any warning. Categories are also
 * imported as their own sheet now, so this is a safety net for a
 * transaction referencing a category name that isn't in either the
 * Categories sheet or the book already.
 */
sealed class ImportAnalysis {
    data class Ready(
        val sheets: Map<String, List<List<String>>>,
        val unmatchedCategoryNames: List<String>
    ) : ImportAnalysis()
    object InvalidFile : ImportAnalysis()
}

/**
 * Handles both directions of the Expense Excel format — one sheet per
 * category, matching Wealth's multi-sheet pattern: Categories, Accounts,
 * Transactions, Recurring Rules, Receivables. Import processes sheets in
 * a fixed order (Categories, then Accounts, then Transactions, then
 * Recurring Rules, then Receivables) since later sheets reference
 * accounts/categories by name and need them to exist first. Every
 * category checks for duplicates using identifying fields specific to
 * that sheet, and every malformed row is reported back as a specific
 * warning rather than silently dropped — matching the same standard
 * applied throughout Wealth's import.
 */
@Singleton
class TransactionExportRepository @Inject constructor(
    private val transactionDao: TransactionDao,
    private val accountDao: AccountDao,
    private val categoryDao: CategoryDao,
    private val recurringRuleDao: RecurringRuleDao,
    private val expenseReceivableDao: ExpenseReceivableDao,
    private val session: SessionManager
) {

    suspend fun exportToXlsx(outputStream: OutputStream) {
        val bookId = session.activeBookId.first() ?: return
        val accounts = accountDao.getActiveAccounts(bookId).first().associateBy { it.id }
        val categories = categoryDao.getActiveCategories(bookId).first().associateBy { it.id }

        val sheets = mutableListOf<ExcelSheet>()

        sheets += ExcelSheet(
            "Categories",
            listOf("Name", "Icon", "Color Hex"),
            categories.values.map { c -> listOf(c.name, c.icon, c.colorHex) }
        )

        sheets += ExcelSheet(
            "Accounts",
            listOf("Name", "Type (BANK/CASH/CREDIT_CARD/PLUXEE)", "Is Default"),
            accounts.values.map { a -> listOf(a.name, a.type.name, a.isDefault.toString()) }
        )

        val transactions = transactionDao.getAll(bookId).first()
        sheets += ExcelSheet(
            "Transactions",
            listOf("Date", "Type", "Amount", "Account", "To Account", "Category", "Note"),
            transactions.map { tx ->
                listOf(
                    DATE_FORMAT.format(Date(tx.occurredAt)),
                    tx.type.name,
                    tx.amount.toString(),
                    accounts[tx.accountId]?.name ?: "",
                    tx.toAccountId?.let { accounts[it]?.name } ?: "",
                    tx.categoryId?.let { categories[it]?.name } ?: "",
                    tx.note ?: ""
                )
            }
        )

        val recurringRules = recurringRuleDao.getAll(bookId).first()
        sheets += ExcelSheet(
            "Recurring Rules",
            listOf("Name", "Type", "Amount", "Account", "To Account", "Category", "Note", "Frequency", "Interval", "Start Date", "End Date", "Is Paused"),
            recurringRules.map { r ->
                listOf(
                    r.name, r.type.name, r.amount.toString(), accounts[r.accountId]?.name ?: "",
                    r.toAccountId?.let { accounts[it]?.name } ?: "", r.categoryId?.let { categories[it]?.name } ?: "",
                    r.note ?: "", r.frequency.name, r.interval.toString(), DATE_FORMAT.format(Date(r.startDate)),
                    r.endDate?.let { DATE_FORMAT.format(Date(it)) } ?: "", r.isPaused.toString()
                )
            }
        )

        val receivables = expenseReceivableDao.getAllOnce(bookId)
        sheets += ExcelSheet(
            "Receivables",
            listOf("Person Name", "Amount", "Note", "Is Collected"),
            receivables.map { r -> listOf(r.personName, r.amount.toString(), r.note ?: "", r.isCollected.toString()) }
        )

        MultiSheetXlsxWriter.write(outputStream, sheets)
    }

    /**
     * Phase 1 — parses the file and reports which category names (if any)
     * referenced by Transactions or Recurring Rules don't match either an
     * existing category or one about to be created from the Categories
     * sheet. Doesn't write anything to the database yet.
     */
    suspend fun analyzeXlsx(inputStream: InputStream): ImportAnalysis {
        val bookId = session.activeBookId.first() ?: return ImportAnalysis.InvalidFile
        val sheets = try {
            MultiSheetXlsxReader.read(inputStream)
        } catch (e: Exception) {
            return ImportAnalysis.InvalidFile
        }
        if (sheets.isEmpty()) return ImportAnalysis.InvalidFile

        val existingCategoryNames = categoryDao.getActiveCategories(bookId).first().map { it.name }.toSet()
        val sheetCategoryNames = sheets["Categories"]?.drop(1)?.mapNotNull { it.getOrNull(0)?.takeIf { n -> n.isNotBlank() } }?.toSet() ?: emptySet()
        val knownAfterImport = existingCategoryNames + sheetCategoryNames

        val fromTransactions = sheets["Transactions"]?.drop(1)?.mapNotNull { it.getOrNull(5)?.takeIf { n -> n.isNotBlank() } } ?: emptyList()
        val fromRecurring = sheets["Recurring Rules"]?.drop(1)?.mapNotNull { it.getOrNull(5)?.takeIf { n -> n.isNotBlank() } } ?: emptyList()
        val unmatched = (fromTransactions + fromRecurring).distinct().filter { it !in knownAfterImport }

        return ImportAnalysis.Ready(sheets, unmatched)
    }

    /**
     * Phase 2 — actually writes everything, in a fixed order since later
     * sheets reference accounts/categories by name. `categoryResolutions`
     * maps an unmatched category name (from Transactions/Recurring Rules)
     * to either an existing category's id (user chose to map it) or null
     * (user chose "leave uncategorized").
     */
    suspend fun commitImport(
        sheets: Map<String, List<List<String>>>,
        categoryResolutions: Map<String, Long?>
    ): ImportResult {
        val bookId = session.activeBookId.first() ?: return ImportResult.InvalidFile
        val now = System.currentTimeMillis()
        val warnings = mutableListOf<String>()
        var totalImported = 0
        var totalSkipped = 0

        // Categories first — duplicate check: same Name already existing
        // is skipped, not re-created.
        val existingCategories = categoryDao.getActiveCategories(bookId).first().toMutableList()
        var categoriesImported = 0
        sheets["Categories"]?.drop(1)?.forEachIndexed { i, row ->
            val rowNum = i + 2
            if (row.size < 2 || row[0].isBlank()) { warnings.add("Categories row $rowNum: Name is required — skipped"); totalSkipped++; return@forEachIndexed }
            if (existingCategories.any { it.name == row[0] }) return@forEachIndexed
            val newCategory = CategoryEntity(bookId = bookId, name = row[0], icon = row.getOrNull(1)?.ifBlank { "🏷️" } ?: "🏷️", colorHex = row.getOrNull(2)?.ifBlank { null } ?: "#2E5E4E")
            categoryDao.insert(newCategory)
            existingCategories.add(newCategory)
            categoriesImported++
        }
        totalImported += categoriesImported

        // Accounts — duplicate check: same Name already existing is
        // skipped, not re-created.
        val existingAccounts = accountDao.getActiveAccounts(bookId).first().toMutableList()
        var accountsImported = 0
        sheets["Accounts"]?.drop(1)?.forEachIndexed { i, row ->
            val rowNum = i + 2
            if (row.size < 2 || row[0].isBlank()) { warnings.add("Accounts row $rowNum: Name is required — skipped"); totalSkipped++; return@forEachIndexed }
            val type = runCatching { AccountType.valueOf(row[1]) }.getOrNull()
            if (type == null) { warnings.add("Accounts row $rowNum: '${row[1]}' is not a valid Type (expected BANK/CASH/CREDIT_CARD/PLUXEE) — skipped"); totalSkipped++; return@forEachIndexed }
            if (existingAccounts.any { it.name == row[0] }) return@forEachIndexed
            val newAccount = AccountEntity(bookId = bookId, name = row[0], type = type, isDefault = row.getOrNull(2)?.toBooleanStrictOrNull() ?: false, createdAt = now)
            accountDao.insert(newAccount)
            existingAccounts.add(newAccount)
            accountsImported++
        }
        totalImported += accountsImported

        // Transactions — duplicate check: same Date + Type + Amount +
        // Account + Note already existing is skipped.
        val accountsByName = existingAccounts.associateBy { it.name }
        val categoriesByName = existingCategories.associateBy { it.name }
        val existingTransactions = transactionDao.getAll(bookId).first()
        var transactionsImported = 0
        var transactionDuplicatesSkipped = 0
        sheets["Transactions"]?.drop(1)?.forEachIndexed { i, row ->
            val rowNum = i + 2
            if (row.size < 7) { warnings.add("Transactions row $rowNum: expected 7 columns, found ${row.size} — skipped"); totalSkipped++; return@forEachIndexed }
            val type = runCatching { TransactionType.valueOf(row[1]) }.getOrNull()
            if (type == null) { warnings.add("Transactions row $rowNum: '${row[1]}' is not a valid Type — skipped"); totalSkipped++; return@forEachIndexed }
            val amount = row[2].toDoubleOrNull()
            if (amount == null) { warnings.add("Transactions row $rowNum: '${row[2]}' is not a valid Amount — skipped"); totalSkipped++; return@forEachIndexed }
            val account = accountsByName[row[3]]
            if (account == null) { warnings.add("Transactions row $rowNum: no account named '${row[3]}' found — skipped"); totalSkipped++; return@forEachIndexed }
            val toAccount = row[4].takeIf { it.isNotBlank() }?.let { accountsByName[it] }
            if (row[4].isNotBlank() && toAccount == null) { warnings.add("Transactions row $rowNum: To Account '${row[4]}' not found — skipped"); totalSkipped++; return@forEachIndexed }
            val occurredAt = parseDate(row[0])
            if (occurredAt == null) { warnings.add("Transactions row $rowNum: '${row[0]}' is not a valid date (expected yyyy-MM-dd) — skipped"); totalSkipped++; return@forEachIndexed }

            val categoryName = row[5].takeIf { it.isNotBlank() }
            val categoryId = when {
                categoryName == null -> null
                categoryResolutions.containsKey(categoryName) -> categoryResolutions[categoryName]
                else -> categoriesByName[categoryName]?.id
            }
            val note = row[6].takeIf { it.isNotBlank() }
            val isDuplicate = existingTransactions.any { it.occurredAt == occurredAt && it.type == type && it.amount == amount && it.accountId == account.id && it.note == note }
            if (isDuplicate) { transactionDuplicatesSkipped++; return@forEachIndexed }

            transactionDao.insert(
                TransactionEntity(bookId = bookId, type = type, amount = amount, accountId = account.id, toAccountId = toAccount?.id, categoryId = categoryId, note = note, occurredAt = occurredAt, createdAt = now, updatedAt = now)
            )
            transactionsImported++
        }
        if (transactionDuplicatesSkipped > 0) warnings.add("$transactionDuplicatesSkipped transaction row(s) skipped — already imported previously")
        totalImported += transactionsImported
        totalSkipped += transactionDuplicatesSkipped

        // Recurring Rules — duplicate check: same Name + Account + Start
        // Date already existing is skipped.
        val existingRules = recurringRuleDao.getAll(bookId).first()
        var rulesImported = 0
        var ruleDuplicatesSkipped = 0
        sheets["Recurring Rules"]?.drop(1)?.forEachIndexed { i, row ->
            val rowNum = i + 2
            if (row.size < 11 || row[0].isBlank()) { warnings.add("Recurring Rules row $rowNum: Name is required — skipped"); totalSkipped++; return@forEachIndexed }
            val type = runCatching { TransactionType.valueOf(row[1]) }.getOrNull()
            if (type == null) { warnings.add("Recurring Rules row $rowNum: '${row[1]}' is not a valid Type — skipped"); totalSkipped++; return@forEachIndexed }
            val amount = row[2].toDoubleOrNull()
            if (amount == null) { warnings.add("Recurring Rules row $rowNum: '${row[2]}' is not a valid Amount — skipped"); totalSkipped++; return@forEachIndexed }
            val account = accountsByName[row[3]]
            if (account == null) { warnings.add("Recurring Rules row $rowNum: no account named '${row[3]}' found — skipped"); totalSkipped++; return@forEachIndexed }
            val frequency = runCatching { RecurrenceFrequency.valueOf(row[7]) }.getOrNull()
            if (frequency == null) { warnings.add("Recurring Rules row $rowNum: '${row[7]}' is not a valid Frequency (expected DAILY/WEEKLY/MONTHLY/YEARLY) — skipped"); totalSkipped++; return@forEachIndexed }
            val startDate = parseDate(row[9])
            if (startDate == null) { warnings.add("Recurring Rules row $rowNum: '${row[9]}' is not a valid Start Date — skipped"); totalSkipped++; return@forEachIndexed }

            val isDuplicate = existingRules.any { it.name == row[0] && it.accountId == account.id && it.startDate == startDate }
            if (isDuplicate) { ruleDuplicatesSkipped++; return@forEachIndexed }

            val toAccount = row.getOrNull(4)?.takeIf { it.isNotBlank() }?.let { accountsByName[it] }
            val categoryName = row.getOrNull(5)?.takeIf { it.isNotBlank() }
            val categoryId = when {
                categoryName == null -> null
                categoryResolutions.containsKey(categoryName) -> categoryResolutions[categoryName]
                else -> categoriesByName[categoryName]?.id
            }
            recurringRuleDao.insert(
                RecurringRuleEntity(
                    bookId = bookId, name = row[0], type = type, amount = amount, accountId = account.id, toAccountId = toAccount?.id,
                    categoryId = categoryId, note = row.getOrNull(6)?.ifBlank { null }, frequency = frequency,
                    interval = row.getOrNull(8)?.toIntOrNull() ?: 1, startDate = startDate, endDate = parseDate(row.getOrNull(10) ?: ""),
                    nextRunAt = startDate, isPaused = row.getOrNull(11)?.toBooleanStrictOrNull() ?: false
                )
            )
            rulesImported++
        }
        if (ruleDuplicatesSkipped > 0) warnings.add("$ruleDuplicatesSkipped recurring rule row(s) skipped — already imported previously")
        totalImported += rulesImported
        totalSkipped += ruleDuplicatesSkipped

        // Receivables — no natural unique id from a spreadsheet round-trip,
        // so duplicate check uses Person Name + Amount + Note together.
        // Imported receivables are never tied to a specific transaction
        // (transactionId stays null) since there's no reliable way to
        // match one from a spreadsheet row.
        val existingReceivables = expenseReceivableDao.getAllOnce(bookId)
        var receivablesImported = 0
        var receivableDuplicatesSkipped = 0
        sheets["Receivables"]?.drop(1)?.forEachIndexed { i, row ->
            val rowNum = i + 2
            if (row.size < 2 || row[0].isBlank()) { warnings.add("Receivables row $rowNum: Person Name is required — skipped"); totalSkipped++; return@forEachIndexed }
            val amount = row[1].toDoubleOrNull()
            if (amount == null) { warnings.add("Receivables row $rowNum: '${row[1]}' is not a valid Amount — skipped"); totalSkipped++; return@forEachIndexed }
            val note = row.getOrNull(2)?.ifBlank { null }
            val isDuplicate = existingReceivables.any { it.personName == row[0] && it.amount == amount && it.note == note }
            if (isDuplicate) { receivableDuplicatesSkipped++; return@forEachIndexed }
            expenseReceivableDao.insert(
                ExpenseReceivableEntity(bookId = bookId, transactionId = null, personName = row[0], amount = amount, note = note, isCollected = row.getOrNull(3)?.toBooleanStrictOrNull() ?: false, createdAt = now)
            )
            receivablesImported++
        }
        if (receivableDuplicatesSkipped > 0) warnings.add("$receivableDuplicatesSkipped receivable row(s) skipped — already imported previously")
        totalImported += receivablesImported
        totalSkipped += receivableDuplicatesSkipped

        if (totalImported == 0 && totalSkipped == 0) return ImportResult.InvalidFile
        return ImportResult.Success(totalImported, totalSkipped, warnings)
    }
}
