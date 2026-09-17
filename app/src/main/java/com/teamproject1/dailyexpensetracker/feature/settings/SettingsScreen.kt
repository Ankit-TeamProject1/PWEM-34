package com.teamproject1.dailyexpensetracker.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.teamproject1.dailyexpensetracker.core.security.PinManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val pinManager: PinManager,
    private val themePreferenceManager: com.teamproject1.dailyexpensetracker.core.theme.ThemePreferenceManager,
    private val bookDao: com.teamproject1.dailyexpensetracker.core.database.dao.BookDao,
    private val session: com.teamproject1.dailyexpensetracker.core.session.SessionManager
) : ViewModel() {

    fun isBiometricEnabled(): Boolean = pinManager.isBiometricEnabled()
    fun setBiometricEnabled(enabled: Boolean) = pinManager.setBiometricEnabled(enabled)

    val autoLockGraceMinutes = session.autoLockGraceMinutes

    fun setAutoLockGraceMinutes(minutes: Int) {
        viewModelScope.launch { session.setAutoLockGraceMinutes(minutes) }
    }

    val themeMode = themePreferenceManager.themeMode

    fun setThemeMode(mode: com.teamproject1.dailyexpensetracker.core.theme.ThemeMode) {
        viewModelScope.launch { themePreferenceManager.setThemeMode(mode) }
    }

    /** Settings previously showed the same options regardless of which
     *  book type was active — Manage Categories/Accounts and Excel Export
     *  don't apply to a Wealth book at all. This resolves the active
     *  book's type so the UI can hide what's irrelevant. */
    val isWealthBook = kotlinx.coroutines.flow.flow {
        val bookId = session.activeBookId.value
        val book = bookId?.let { bookDao.getById(it) }
        emit(book?.bookType == com.teamproject1.dailyexpensetracker.core.database.entity.BookType.WEALTH)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onManageBooks: () -> Unit,
    onManageCategories: () -> Unit,
    onManageAccounts: () -> Unit,
    onExportImport: () -> Unit,
    onArchivedItems: () -> Unit,
    onChangePin: () -> Unit,
    onRateHistory: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    var biometricEnabled by remember { mutableStateOf(viewModel.isBiometricEnabled()) }
    val autoLockMinutes by viewModel.autoLockGraceMinutes.collectAsState(initial = 5)
    val themeMode by viewModel.themeMode.collectAsState(initial = com.teamproject1.dailyexpensetracker.core.theme.ThemeMode.AUTOMATIC)
    val isWealthBook by viewModel.isWealthBook.collectAsState(initial = false)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {

            SectionHeader("Security")
            NavigationRow(label = "Change PIN", onClick = onChangePin)
            SettingsRow(label = "Biometric unlock") {
                Switch(
                    checked = biometricEnabled,
                    onCheckedChange = {
                        biometricEnabled = it
                        viewModel.setBiometricEnabled(it)
                    }
                )
            }
            SettingsRow(label = "Auto-lock after") {
                var expanded by remember { mutableStateOf(false) }
                Box {
                    TextButton(onClick = { expanded = true }) { Text("$autoLockMinutes min") }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        listOf(1, 5, 15, 30).forEach { minutes ->
                            DropdownMenuItem(
                                text = { Text("$minutes minute${if (minutes == 1) "" else "s"}") },
                                onClick = {
                                    viewModel.setAutoLockGraceMinutes(minutes)
                                    expanded = false
                                }
                            )
                        }
                    }
                }
            }

            // Excel export/import only makes sense for Expense books —
            // Wealth books get local JSON backup only (covers all Wealth
            // categories generically, unlike the Expense-specific Excel
            // template). This directly fixes the confirmed bug where
            // Wealth books were showing (and attempting) the Expense
            // transaction export.
            if (!isWealthBook) {
                SectionHeader("Backup & Export")
                NavigationRow(label = "Backup, Export & Import", onClick = onExportImport)
                SettingsInfoRow("Local JSON backup, Excel export/import, and monthly PDF ledger are all here")
            } else {
                SectionHeader("Backup")
                NavigationRow(label = "Backup & Restore", onClick = onExportImport)
                SettingsInfoRow("Local JSON backup, plus a bulk-entry Excel template for this book")
            }
            SettingsInfoRow("Google Drive sync — planned for v1.1")

            SectionHeader("Books")
            NavigationRow(label = "Manage Books", onClick = onManageBooks)
            NavigationRow(label = "Archived Items (Restore)", onClick = onArchivedItems)

            // Categories and Accounts are Expense-book concepts entirely —
            // a Wealth book has neither, per the locked data model split.
            if (!isWealthBook) {
                SectionHeader("Categories")
                NavigationRow(label = "Manage Categories", onClick = onManageCategories)

                SectionHeader("Accounts")
                NavigationRow(label = "Manage Accounts", onClick = onManageAccounts)
            } else {
                // Rate History moved here from the Wealth Dashboard tile
                // grid, per explicit request — it's a shared reference
                // table (not book-scoped), so Settings is a better fit
                // than sitting alongside per-book asset categories.
                SectionHeader("Rates")
                NavigationRow(label = "PPF & EPF Interest Rates", onClick = onRateHistory)
            }

            SectionHeader("Appearance")
            AppearanceSelector(
                current = themeMode,
                onSelect = { viewModel.setThemeMode(it) }
            )

            SectionHeader("About")
            SettingsInfoRow("Personal Wealth and Expense Manager — v${com.teamproject1.dailyexpensetracker.BuildConfig.VERSION_NAME}")

            Spacer(Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppearanceSelector(
    current: com.teamproject1.dailyexpensetracker.core.theme.ThemeMode,
    onSelect: (com.teamproject1.dailyexpensetracker.core.theme.ThemeMode) -> Unit
) {
    val options = listOf(
        "Light" to com.teamproject1.dailyexpensetracker.core.theme.ThemeMode.LIGHT,
        "Dark" to com.teamproject1.dailyexpensetracker.core.theme.ThemeMode.DARK,
        "Automatic" to com.teamproject1.dailyexpensetracker.core.theme.ThemeMode.AUTOMATIC
    )
    SingleChoiceSegmentedButtonRow(modifier = Modifier.padding(vertical = 8.dp)) {
        options.forEachIndexed { index, (label, mode) ->
            SegmentedButton(
                selected = current == mode,
                onClick = { onSelect(mode) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size)
            ) { Text(label) }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Spacer(Modifier.height(16.dp))
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun SettingsRow(label: String, trailing: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        trailing()
    }
}

@Composable
private fun SettingsInfoRow(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp))
}

@Composable
private fun NavigationRow(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Text("›", style = MaterialTheme.typography.bodyLarge)
    }
}
