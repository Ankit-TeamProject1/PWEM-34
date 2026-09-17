package com.teamproject1.dailyexpensetracker.feature.settings

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.teamproject1.dailyexpensetracker.core.backup.BackupImportResult
import com.teamproject1.dailyexpensetracker.core.backup.LocalBackupRepository
import com.teamproject1.dailyexpensetracker.core.database.dao.BookDao
import com.teamproject1.dailyexpensetracker.core.database.dao.CategoryDao
import com.teamproject1.dailyexpensetracker.core.database.entity.BookType
import com.teamproject1.dailyexpensetracker.core.database.entity.CategoryEntity
import com.teamproject1.dailyexpensetracker.core.export.ImportAnalysis
import com.teamproject1.dailyexpensetracker.core.export.ImportResult
import com.teamproject1.dailyexpensetracker.core.export.MonthlyLedgerPdfExporter
import com.teamproject1.dailyexpensetracker.core.export.TransactionExportRepository
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack

@HiltViewModel
class ExportImportViewModel @Inject constructor(
    private val exportRepository: TransactionExportRepository,
    private val wealthExportRepository: com.teamproject1.dailyexpensetracker.core.export.WealthExportRepository,
    private val pdfExporter: MonthlyLedgerPdfExporter,
    private val backupRepository: LocalBackupRepository,
    private val bookDao: BookDao,
    categoryDao: CategoryDao,
    private val session: SessionManager
) : ViewModel() {

    val categories = session.activeBookId.filterNotNull()
        .flatMapLatest { bookId -> categoryDao.getActiveCategories(bookId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val isWealthBook = kotlinx.coroutines.flow.flow {
        val bookId = session.activeBookId.value
        val book = bookId?.let { bookDao.getById(it) }
        emit(book?.bookType == BookType.WEALTH)
    }

    fun exportExcel(context: Context, uri: Uri, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            try {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    exportRepository.exportToXlsx(out)
                }
                onDone(true)
            } catch (e: Exception) {
                onDone(false)
            }
        }
    }

    fun analyzeImport(context: Context, uri: Uri, onResult: (ImportAnalysis) -> Unit) {
        viewModelScope.launch {
            val analysis = context.contentResolver.openInputStream(uri)?.use { input ->
                exportRepository.analyzeXlsx(input)
            } ?: ImportAnalysis.InvalidFile
            onResult(analysis)
        }
    }

    fun commitImport(sheets: Map<String, List<List<String>>>, resolutions: Map<String, Long?>, onResult: (ImportResult) -> Unit) {
        viewModelScope.launch {
            onResult(exportRepository.commitImport(sheets, resolutions))
        }
    }

    fun exportPdf(context: Context, uri: Uri, month: String, monthLabel: String, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            try {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    pdfExporter.export(month, monthLabel, out)
                }
                onDone(true)
            } catch (e: Exception) {
                onDone(false)
            }
        }
    }

    fun createBackup(context: Context, uri: Uri, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            try {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    backupRepository.exportActiveBookBackup(out)
                }
                onDone(true)
            } catch (e: Exception) {
                onDone(false)
            }
        }
    }

    fun createAllBooksBackup(context: Context, uri: Uri, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            try {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    backupRepository.exportAllBooksBackup(out)
                }
                onDone(true)
            } catch (e: Exception) {
                onDone(false)
            }
        }
    }

    fun restoreBackup(context: Context, uri: Uri, onResult: (BackupImportResult) -> Unit) {
        viewModelScope.launch {
            val result = context.contentResolver.openInputStream(uri)?.use { input ->
                backupRepository.importBackup(input)
            } ?: BackupImportResult.InvalidFile
            onResult(result)
        }
    }

    fun exportWealthExcel(context: Context, uri: Uri, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            try {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    wealthExportRepository.exportToXlsx(out)
                }
                onDone(true)
            } catch (e: Exception) {
                onDone(false)
            }
        }
    }

    fun importWealthExcel(context: Context, uri: Uri, onResult: (com.teamproject1.dailyexpensetracker.core.export.WealthImportResult) -> Unit) {
        viewModelScope.launch {
            val result = context.contentResolver.openInputStream(uri)?.use { input ->
                wealthExportRepository.importFromXlsx(input)
            } ?: com.teamproject1.dailyexpensetracker.core.export.WealthImportResult.InvalidFile
            onResult(result)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportImportScreen(
    onBack: () -> Unit,
    viewModel: ExportImportViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val categories by viewModel.categories.collectAsState()
    val isWealthBook by viewModel.isWealthBook.collectAsState(initial = false)
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var selectedMonth by remember { mutableStateOf(SimpleDateFormat("yyyy-MM", Locale.US).format(Date())) }
    val monthLabelFormat = remember { SimpleDateFormat("MMMM yyyy", Locale.US) }

    var pendingRows by remember { mutableStateOf<Map<String, List<List<String>>>?>(null) }
    var pendingUnmatchedNames by remember { mutableStateOf<List<String>>(emptyList()) }
    var resolutions by remember { mutableStateOf<Map<String, Long?>>(emptyMap()) }

    val exportExcelLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        viewModel.exportExcel(context, uri) { success ->
            statusMessage = if (success) "Exported successfully." else "Export failed."
        }
    }

    val importExcelLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        viewModel.analyzeImport(context, uri) { analysis ->
            when (analysis) {
                is ImportAnalysis.InvalidFile ->
                    statusMessage = "This file doesn't match the expected export format."
                is ImportAnalysis.Ready -> {
                    if (analysis.unmatchedCategoryNames.isEmpty()) {
                        viewModel.commitImport(analysis.sheets, emptyMap()) { result ->
                            statusMessage = describeImportResult(result)
                        }
                    } else {
                        pendingRows = analysis.sheets
                        pendingUnmatchedNames = analysis.unmatchedCategoryNames
                        resolutions = analysis.unmatchedCategoryNames.associateWith { null }
                    }
                }
            }
        }
    }

    val exportPdfLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val monthLabel = try {
            monthLabelFormat.format(SimpleDateFormat("yyyy-MM", Locale.US).parse(selectedMonth)!!)
        } catch (e: Exception) { selectedMonth }
        viewModel.exportPdf(context, uri, selectedMonth, monthLabel) { success ->
            statusMessage = if (success) "PDF exported successfully." else "PDF export failed."
        }
    }

    val createBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        viewModel.createBackup(context, uri) { success ->
            statusMessage = if (success) "Backup created successfully." else "Backup failed."
        }
    }

    val createAllBooksBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        viewModel.createAllBooksBackup(context, uri) { success ->
            statusMessage = if (success) "All-books backup created successfully." else "Backup failed."
        }
    }

    val exportWealthExcelLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        viewModel.exportWealthExcel(context, uri) { success ->
            statusMessage = if (success) "Wealth Excel exported successfully." else "Export failed."
        }
    }

    val importWealthExcelLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        viewModel.importWealthExcel(context, uri) { result ->
            statusMessage = when (result) {
                is com.teamproject1.dailyexpensetracker.core.export.WealthImportResult.Success -> {
                    val base = "Imported: ${result.summary}"
                    // Shows exactly which rows were skipped and why, per
                    // explicit request — previously a malformed row just
                    // vanished with no trace at all. Capped so an
                    // extreme case doesn't produce an unreadable wall of
                    // text.
                    if (result.warnings.isEmpty()) {
                        base
                    } else {
                        val shown = result.warnings.take(15)
                        val extra = result.warnings.size - shown.size
                        base + "\n\n${result.warnings.size} row(s) skipped:\n" + shown.joinToString("\n") { "• $it" } +
                            if (extra > 0) "\n…and $extra more" else ""
                    }
                }
                com.teamproject1.dailyexpensetracker.core.export.WealthImportResult.InvalidFile -> "This file doesn't match the expected Wealth template."
            }
        }
    }

    var pendingRestoreUri by remember { mutableStateOf<Uri?>(null) }

    val restoreBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        // Confirmation step, per a real gap found in review — this
        // previously ran the restore immediately on file selection with
        // no "are you sure," despite it being able to merge in a large
        // amount of data or create entirely new books.
        pendingRestoreUri = uri
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isWealthBook) "Backup & Restore" else "Export / Import") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {

            if (!isWealthBook) {
                Text("Excel", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Export all transactions to a spreadsheet, or import from a file previously exported by this app.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { exportExcelLauncher.launch("expense_export.xlsx") },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Export to Excel") }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { importExcelLauncher.launch(arrayOf("*/*")) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Import from Excel") }

                Spacer(Modifier.height(24.dp))
                Text("Monthly PDF Ledger", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text(
                    "A simple table of that month's transactions with a total at the bottom.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = selectedMonth,
                    onValueChange = { selectedMonth = it },
                    label = { Text("Month (YYYY-MM)") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { exportPdfLauncher.launch("ledger_$selectedMonth.pdf") },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Export Monthly PDF") }

                Spacer(Modifier.height(24.dp))
            } else {
                Text("Wealth Excel — Bulk Entry", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Export a multi-sheet template (one tab per category: FD/NSC, RD, Liabilities, Cash in Hand, Bank, Demat, PPF, EPF, NPS, APY, Insurance, Manual Assets, Mutual Funds, Kametti, Gold/Silver), fill it in using Excel or Google Sheets, and import it back — much faster than entering everything field-by-field in the app.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Every sheet checks for duplicates before importing, so re-importing the same file won't create duplicate entries. Any row that can't be imported is reported back with the specific reason.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { exportWealthExcelLauncher.launch("wealth_export.xlsx") },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Export Wealth Excel Template") }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { importWealthExcelLauncher.launch(arrayOf("*/*")) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Import Wealth Excel") }

                Spacer(Modifier.height(24.dp))
            }

            Text("Local Backup", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                if (isWealthBook)
                    "A full snapshot of this book (FDs, liabilities, cash in hand, holdings) as a JSON file — a straight backup, distinct from the Excel bulk-entry template above."
                else
                    "A full snapshot of this book (accounts, categories, recurring rules, transactions) as a JSON file.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Restoring merges into the current book — nothing existing is deleted or overwritten.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { createBackupLauncher.launch("backup_this_book.json") },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Backup This Book") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { createAllBooksBackupLauncher.launch("backup_all_books.json") },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Backup All Books") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { restoreBackupLauncher.launch(arrayOf("*/*")) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Restore from Backup") }

            statusMessage?.let {
                Spacer(Modifier.height(16.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
    }

    val rowsToResolve = pendingRows
    if (rowsToResolve != null) {
        CategoryResolutionDialog(
            unmatchedNames = pendingUnmatchedNames,
            existingCategories = categories,
            resolutions = resolutions,
            onResolutionChange = { name, categoryId -> resolutions = resolutions + (name to categoryId) },
            onCancel = { pendingRows = null },
            onConfirm = {
                viewModel.commitImport(rowsToResolve, resolutions) { result ->
                    statusMessage = describeImportResult(result)
                    pendingRows = null
                }
            }
        )
    }

    pendingRestoreUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingRestoreUri = null },
            title = { Text("Restore from this backup?") },
            text = {
                Text("This will merge the file's data into your books. A single-book file merges into the currently active book; a multi-book file creates new books. Nothing existing is deleted.")
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.restoreBackup(context, uri) { result ->
                        statusMessage = when (result) {
                            is BackupImportResult.Success -> {
                                val base = "Restored ${result.booksImported} book(s): ${result.summary}"
                                // Surfaces exactly which book(s) failed and
                                // why, instead of a silent crash that gave
                                // no indication anything had gone wrong.
                                if (result.warnings.isEmpty()) base else base + "\n\n" + result.warnings.joinToString("\n") { "• $it" }
                            }
                            BackupImportResult.InvalidFile -> "This doesn't look like a valid backup file."
                        }
                    }
                    pendingRestoreUri = null
                }) { Text("Restore") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRestoreUri = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun CategoryResolutionDialog(
    unmatchedNames: List<String>,
    existingCategories: List<CategoryEntity>,
    resolutions: Map<String, Long?>,
    onResolutionChange: (String, Long?) -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Some categories don't match") },
        text = {
            Column {
                Text(
                    "These category names from the file aren't in this book. Choose what each should map to:",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn {
                    items(unmatchedNames) { name ->
                        UnmatchedCategoryRow(
                            fileName = name,
                            existingCategories = existingCategories,
                            selectedCategoryId = resolutions[name],
                            onSelect = { categoryId -> onResolutionChange(name, categoryId) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Import") }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    )
}

@Composable
private fun UnmatchedCategoryRow(
    fileName: String,
    existingCategories: List<CategoryEntity>,
    selectedCategoryId: Long?,
    onSelect: (Long?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = existingCategories.find { it.id == selectedCategoryId }
        ?.let { "${it.icon} ${it.name}" } ?: "Leave uncategorized"

    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text("\"$fileName\"", style = MaterialTheme.typography.bodyLarge)
        Box {
            TextButton(onClick = { expanded = true }) { Text("→ $selectedLabel") }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = { Text("Leave uncategorized") },
                    onClick = { onSelect(null); expanded = false }
                )
                existingCategories.forEach { category ->
                    DropdownMenuItem(
                        text = { Text("${category.icon} ${category.name}") },
                        onClick = { onSelect(category.id); expanded = false }
                    )
                }
            }
        }
    }
}

private fun describeImportResult(result: ImportResult): String = when (result) {
    is ImportResult.Success -> {
        val base = "Imported ${result.importedCount} transactions" + if (result.skippedCount > 0) " (${result.skippedCount} skipped)." else "."
        // Shows the actual reason for each skip instead of assuming
        // "missing account" — that was the only reason ever surfaced
        // before, even though a row could just as easily fail on an
        // invalid type, amount, or date.
        if (result.warnings.isEmpty()) {
            base
        } else {
            val shown = result.warnings.take(15)
            val extra = result.warnings.size - shown.size
            base + "\n\n" + shown.joinToString("\n") { "• $it" } + if (extra > 0) "\n…and $extra more" else ""
        }
    }
    ImportResult.InvalidFile -> "This file doesn't match the expected export format."
}
