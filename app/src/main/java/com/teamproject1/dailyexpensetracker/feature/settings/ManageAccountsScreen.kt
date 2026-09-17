package com.teamproject1.dailyexpensetracker.feature.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.teamproject1.dailyexpensetracker.core.database.ArchiveResult
import com.teamproject1.dailyexpensetracker.core.database.RecurringRuleRepository
import com.teamproject1.dailyexpensetracker.core.database.dao.AccountDao
import com.teamproject1.dailyexpensetracker.core.database.entity.AccountEntity
import com.teamproject1.dailyexpensetracker.core.database.entity.AccountType
import com.teamproject1.dailyexpensetracker.core.session.SessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import androidx.compose.material.icons.automirrored.filled.ArrowBack

sealed class AccountArchiveOutcome {
    object Success : AccountArchiveOutcome()
    data class Blocked(val ruleNames: List<String>) : AccountArchiveOutcome()
}

fun AccountType.icon(): String = when (this) {
    AccountType.BANK -> "💰"
    AccountType.CASH -> "💵"
    AccountType.CREDIT_CARD -> "💳"
    AccountType.PLUXEE -> "🍽️"
}

fun AccountType.displayName(): String = when (this) {
    AccountType.BANK -> "Bank"
    AccountType.CASH -> "Cash"
    AccountType.CREDIT_CARD -> "Credit Card"
    AccountType.PLUXEE -> "Pluxee"
}

@HiltViewModel
class ManageAccountsViewModel @Inject constructor(
    private val accountDao: AccountDao,
    private val recurringRuleRepository: RecurringRuleRepository,
    private val session: SessionManager
) : ViewModel() {

    val accounts = session.activeBookId.filterNotNull()
        .flatMapLatest { bookId -> accountDao.getActiveAccounts(bookId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun addAccount(name: String, type: AccountType) {
        viewModelScope.launch {
            val activeBookId = session.activeBookId.value ?: return@launch
            accountDao.insert(
                AccountEntity(bookId = activeBookId, name = name, type = type, createdAt = System.currentTimeMillis())
            )
        }
    }

    fun renameAccount(account: AccountEntity, newName: String) {
        viewModelScope.launch { accountDao.update(account.copy(name = newName)) }
    }

    /** Same archive-blocking pattern as categories — an active recurring
     *  rule referencing this account blocks the archive until resolved. */
    fun tryArchive(account: AccountEntity, onResult: (AccountArchiveOutcome) -> Unit) {
        viewModelScope.launch {
            when (val result = recurringRuleRepository.tryArchiveAccount(account.id)) {
                is ArchiveResult.Success -> onResult(AccountArchiveOutcome.Success)
                is ArchiveResult.Blocked -> onResult(AccountArchiveOutcome.Blocked(result.blockingRules.map { it.name }))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageAccountsScreen(
    onBack: () -> Unit,
    viewModel: ManageAccountsViewModel = hiltViewModel()
) {
    val accounts by viewModel.accounts.collectAsState()
    var editingAccount by remember { mutableStateOf<AccountEntity?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var blockedDialogRuleNames by remember { mutableStateOf<List<String>?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Manage Accounts") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add Account")
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(accounts) { account ->
                AccountRow(
                    account = account,
                    onEdit = { editingAccount = account },
                    onArchive = {
                        viewModel.tryArchive(account) { outcome ->
                            when (outcome) {
                                is AccountArchiveOutcome.Blocked -> blockedDialogRuleNames = outcome.ruleNames
                                AccountArchiveOutcome.Success -> Unit
                            }
                        }
                    }
                )
            }
        }
    }

    if (showAddDialog) {
        AccountEditDialog(
            initialName = "",
            initialType = AccountType.BANK,
            allowTypeChange = true,
            title = "New Account",
            onDismiss = { showAddDialog = false },
            onSave = { name, type ->
                viewModel.addAccount(name, type)
                showAddDialog = false
            }
        )
    }

    editingAccount?.let { account ->
        AccountEditDialog(
            initialName = account.name,
            initialType = account.type,
            allowTypeChange = false, // changing an account's type after transactions exist would be ambiguous — rename only
            title = "Edit Account",
            onDismiss = { editingAccount = null },
            onSave = { name, _ ->
                viewModel.renameAccount(account, name)
                editingAccount = null
            }
        )
    }

    blockedDialogRuleNames?.let { ruleNames ->
        AlertDialog(
            onDismissRequest = { blockedDialogRuleNames = null },
            title = { Text("Account is used by active recurring transactions") },
            text = {
                Column {
                    Text("These need your attention before you can archive this account:")
                    Spacer(Modifier.height(8.dp))
                    ruleNames.forEach { Text("🔁 $it") }
                }
            },
            confirmButton = {
                TextButton(onClick = { blockedDialogRuleNames = null }) { Text("OK") }
            }
        )
    }
}

@Composable
private fun AccountRow(account: AccountEntity, onEdit: () -> Unit, onArchive: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        Column {
            Text("${account.type.icon()} ${account.name}", style = MaterialTheme.typography.bodyLarge)
            Text(account.type.displayName(), style = MaterialTheme.typography.bodySmall)
        }
        Row {
            TextButton(onClick = onEdit) { Text("Rename") }
            TextButton(onClick = onArchive) { Text("Archive", color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun AccountEditDialog(
    initialName: String,
    initialType: AccountType,
    allowTypeChange: Boolean,
    title: String,
    onDismiss: () -> Unit,
    onSave: (String, AccountType) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var type by remember { mutableStateOf(initialType) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Account name") })
                if (allowTypeChange) {
                    Spacer(Modifier.height(8.dp))
                    Text("Type", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState())
                    ) {
                        AccountType.values().forEach { t ->
                            FilterChip(
                                selected = type == t,
                                onClick = { type = t },
                                label = { Text("${t.icon()} ${t.displayName()}") }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) onSave(name, type) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
