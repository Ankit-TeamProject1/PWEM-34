package com.teamproject1.dailyexpensetracker.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.teamproject1.dailyexpensetracker.core.database.dao.AccountDao
import com.teamproject1.dailyexpensetracker.core.database.dao.ApyDao
import com.teamproject1.dailyexpensetracker.core.database.dao.BankBalanceDao
import com.teamproject1.dailyexpensetracker.core.database.dao.BookDao
import com.teamproject1.dailyexpensetracker.core.database.dao.CategoryDao
import com.teamproject1.dailyexpensetracker.core.database.dao.DematHoldingDao
import com.teamproject1.dailyexpensetracker.core.database.dao.EpfDao
import com.teamproject1.dailyexpensetracker.core.database.dao.FixedDepositDao
import com.teamproject1.dailyexpensetracker.core.database.dao.InsuranceDao
import com.teamproject1.dailyexpensetracker.core.database.dao.KamettiDao
import com.teamproject1.dailyexpensetracker.core.database.dao.LiabilityDao
import com.teamproject1.dailyexpensetracker.core.database.dao.ManualAssetDao
import com.teamproject1.dailyexpensetracker.core.database.dao.MutualFundDao
import com.teamproject1.dailyexpensetracker.core.database.dao.NpsDao
import com.teamproject1.dailyexpensetracker.core.database.dao.PpfDao
import com.teamproject1.dailyexpensetracker.core.database.dao.RecurringDepositDao
import com.teamproject1.dailyexpensetracker.core.database.dao.WealthReceivableDao
import com.teamproject1.dailyexpensetracker.core.session.SessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ArchivedItemsViewModel @Inject constructor(
    private val bookDao: BookDao,
    private val accountDao: AccountDao,
    private val categoryDao: CategoryDao,
    private val fixedDepositDao: FixedDepositDao,
    private val liabilityDao: LiabilityDao,
    private val dematHoldingDao: DematHoldingDao,
    private val recurringDepositDao: RecurringDepositDao,
    private val ppfDao: PpfDao,
    private val epfDao: EpfDao,
    private val npsDao: NpsDao,
    private val apyDao: ApyDao,
    private val insuranceDao: InsuranceDao,
    private val manualAssetDao: ManualAssetDao,
    private val bankBalanceDao: BankBalanceDao,
    private val wealthReceivableDao: WealthReceivableDao,
    private val mutualFundDao: MutualFundDao,
    private val kamettiDao: KamettiDao,
    private val metalHoldingDao: com.teamproject1.dailyexpensetracker.core.database.dao.MetalHoldingDao,
    private val dematAccountDao: com.teamproject1.dailyexpensetracker.core.database.dao.DematAccountDao,
    private val mutualFundPlatformDao: com.teamproject1.dailyexpensetracker.core.database.dao.MutualFundPlatformDao,
    private val session: SessionManager
) : ViewModel() {

    val archivedBooks = bookDao.getArchivedBooks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val archivedAccounts = session.activeBookId.filterNotNull()
        .flatMapLatest { bookId -> accountDao.getArchivedAccounts(bookId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val archivedCategories = session.activeBookId.filterNotNull()
        .flatMapLatest { bookId -> categoryDao.getArchivedCategories(bookId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Every Wealth category with its own archive mechanism — none of
    // these appeared here before, per explicit request. Each is scoped
    // to the active book, matching how archiving works for each of them.
    val archivedFds = session.activeBookId.filterNotNull().flatMapLatest { fixedDepositDao.getArchived(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val archivedLiabilities = session.activeBookId.filterNotNull().flatMapLatest { liabilityDao.getArchived(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val archivedDemat = session.activeBookId.filterNotNull().flatMapLatest { dematHoldingDao.getArchived(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val archivedRds = session.activeBookId.filterNotNull().flatMapLatest { recurringDepositDao.getArchived(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val archivedPpf = session.activeBookId.filterNotNull().flatMapLatest { ppfDao.getArchivedAccounts(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val archivedEpf = session.activeBookId.filterNotNull().flatMapLatest { epfDao.getArchivedAccounts(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val archivedNps = session.activeBookId.filterNotNull().flatMapLatest { npsDao.getArchived(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val archivedApy = session.activeBookId.filterNotNull().flatMapLatest { apyDao.getArchived(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val archivedInsurance = session.activeBookId.filterNotNull().flatMapLatest { insuranceDao.getArchived(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val archivedManualAssets = session.activeBookId.filterNotNull().flatMapLatest { manualAssetDao.getArchived(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val archivedBanks = session.activeBookId.filterNotNull().flatMapLatest { bankBalanceDao.getArchived(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val archivedReceivables = session.activeBookId.filterNotNull().flatMapLatest { wealthReceivableDao.getArchived(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val archivedMutualFunds = session.activeBookId.filterNotNull().flatMapLatest { mutualFundDao.getArchivedAccounts(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val archivedKametti = session.activeBookId.filterNotNull().flatMapLatest { kamettiDao.getArchivedAccounts(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val archivedMetal = session.activeBookId.filterNotNull().flatMapLatest { metalHoldingDao.getArchived(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val archivedDematAccounts = session.activeBookId.filterNotNull().flatMapLatest { dematAccountDao.getArchived(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val archivedMfPlatforms = session.activeBookId.filterNotNull().flatMapLatest { mutualFundPlatformDao.getArchived(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun unarchiveBook(id: Long) { viewModelScope.launch { bookDao.unarchive(id) } }
    // Only reachable from the Archived Books list, on an already-archived
    // book — never from the active grid — per the locked "no accidental
    // hard-delete" rule. CASCADE on every entity's bookId foreign key
    // means this one call removes all of that book's data.
    fun deleteBookPermanently(id: Long) { viewModelScope.launch { bookDao.hardDelete(id) } }
    fun unarchiveAccount(id: Long) { viewModelScope.launch { accountDao.unarchive(id) } }
    fun unarchiveCategory(id: Long) { viewModelScope.launch { categoryDao.unarchive(id) } }
    fun unarchiveFd(id: Long) { viewModelScope.launch { fixedDepositDao.unarchive(id) } }
    fun unarchiveLiability(id: Long) { viewModelScope.launch { liabilityDao.unarchive(id) } }
    fun unarchiveDemat(id: Long) { viewModelScope.launch { dematHoldingDao.unarchive(id) } }
    fun unarchiveRd(id: Long) { viewModelScope.launch { recurringDepositDao.unarchive(id) } }
    fun unarchivePpf(id: Long) { viewModelScope.launch { ppfDao.unarchiveAccount(id) } }
    fun unarchiveEpf(id: Long) { viewModelScope.launch { epfDao.unarchiveAccount(id) } }
    fun unarchiveNps(id: Long) { viewModelScope.launch { npsDao.unarchive(id) } }
    fun unarchiveApy(id: Long) { viewModelScope.launch { apyDao.unarchive(id) } }
    fun unarchiveInsurance(id: Long) { viewModelScope.launch { insuranceDao.unarchive(id) } }
    fun unarchiveManualAsset(id: Long) { viewModelScope.launch { manualAssetDao.unarchive(id) } }
    fun unarchiveBank(id: Long) { viewModelScope.launch { bankBalanceDao.unarchive(id) } }
    fun unarchiveReceivable(id: Long) { viewModelScope.launch { wealthReceivableDao.unarchive(id) } }
    fun unarchiveMutualFund(id: Long) { viewModelScope.launch { mutualFundDao.unarchiveAccount(id) } }
    fun unarchiveKametti(id: Long) { viewModelScope.launch { kamettiDao.unarchiveAccount(id) } }
    fun unarchiveMetal(id: Long) { viewModelScope.launch { metalHoldingDao.unarchive(id) } }
    fun unarchiveDematAccount(id: Long) { viewModelScope.launch { dematAccountDao.unarchive(id) } }
    fun unarchiveMfPlatform(id: Long) { viewModelScope.launch { mutualFundPlatformDao.unarchive(id) } }
}

/**
 * Closes a real gap — every Wealth category (FD, Liability, Demat, RD,
 * PPF, EPF, NPS, APY, Insurance, Manual Assets, Bank, Receivables,
 * Mutual Fund, Kametti) has its own archive mechanism, but none of them
 * ever appeared here before — only Books, Expense Accounts, and
 * Categories did. All Wealth sections are scoped to the active book,
 * matching how archiving works for each of them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchivedItemsScreen(onBack: () -> Unit, viewModel: ArchivedItemsViewModel = hiltViewModel()) {
    val archivedBooks by viewModel.archivedBooks.collectAsState()
    val archivedAccounts by viewModel.archivedAccounts.collectAsState()
    val archivedCategories by viewModel.archivedCategories.collectAsState()
    val archivedFds by viewModel.archivedFds.collectAsState()
    val archivedLiabilities by viewModel.archivedLiabilities.collectAsState()
    val archivedDemat by viewModel.archivedDemat.collectAsState()
    val archivedRds by viewModel.archivedRds.collectAsState()
    val archivedPpf by viewModel.archivedPpf.collectAsState()
    val archivedEpf by viewModel.archivedEpf.collectAsState()
    val archivedNps by viewModel.archivedNps.collectAsState()
    val archivedApy by viewModel.archivedApy.collectAsState()
    val archivedInsurance by viewModel.archivedInsurance.collectAsState()
    val archivedManualAssets by viewModel.archivedManualAssets.collectAsState()
    val archivedBanks by viewModel.archivedBanks.collectAsState()
    val archivedReceivables by viewModel.archivedReceivables.collectAsState()
    val archivedMutualFunds by viewModel.archivedMutualFunds.collectAsState()
    val archivedKametti by viewModel.archivedKametti.collectAsState()
    val archivedMetal by viewModel.archivedMetal.collectAsState()
    val archivedDematAccounts by viewModel.archivedDematAccounts.collectAsState()
    val archivedMfPlatforms by viewModel.archivedMfPlatforms.collectAsState()
    var bookToDelete by remember { mutableStateOf<com.teamproject1.dailyexpensetracker.core.database.entity.BookEntity?>(null) }

    val isEmpty = archivedBooks.isEmpty() && archivedAccounts.isEmpty() && archivedCategories.isEmpty() &&
        archivedFds.isEmpty() && archivedLiabilities.isEmpty() && archivedDemat.isEmpty() && archivedRds.isEmpty() &&
        archivedPpf.isEmpty() && archivedEpf.isEmpty() && archivedNps.isEmpty() && archivedApy.isEmpty() &&
        archivedInsurance.isEmpty() && archivedManualAssets.isEmpty() && archivedBanks.isEmpty() &&
        archivedReceivables.isEmpty() && archivedMutualFunds.isEmpty() && archivedKametti.isEmpty() && archivedMetal.isEmpty() &&
        archivedDematAccounts.isEmpty() && archivedMfPlatforms.isEmpty()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Archived Items") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } }
            )
        }
    ) { padding ->
        if (isEmpty) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Nothing archived right now.", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (archivedBooks.isNotEmpty()) {
                    item { SectionLabel("Books") }
                    items(archivedBooks) { ArchivedRow(label = it.name, onRestore = { viewModel.unarchiveBook(it.id) }, onDeletePermanently = { bookToDelete = it }) }
                }
                if (archivedAccounts.isNotEmpty()) {
                    item { SectionLabel("Accounts (this book)") }
                    items(archivedAccounts) { ArchivedRow(label = it.name, onRestore = { viewModel.unarchiveAccount(it.id) }) }
                }
                if (archivedCategories.isNotEmpty()) {
                    item { SectionLabel("Categories (this book)") }
                    items(archivedCategories) { ArchivedRow(label = "${it.icon} ${it.name}", onRestore = { viewModel.unarchiveCategory(it.id) }) }
                }
                if (archivedFds.isNotEmpty()) {
                    item { SectionLabel("FD & NSC") }
                    items(archivedFds) { ArchivedRow(label = it.name, onRestore = { viewModel.unarchiveFd(it.id) }) }
                }
                if (archivedLiabilities.isNotEmpty()) {
                    item { SectionLabel("Liabilities") }
                    items(archivedLiabilities) { ArchivedRow(label = it.name, onRestore = { viewModel.unarchiveLiability(it.id) }) }
                }
                if (archivedDemat.isNotEmpty()) {
                    item { SectionLabel("Demat") }
                    items(archivedDemat) { ArchivedRow(label = it.stockSymbol, onRestore = { viewModel.unarchiveDemat(it.id) }) }
                }
                if (archivedRds.isNotEmpty()) {
                    item { SectionLabel("RD") }
                    items(archivedRds) { ArchivedRow(label = it.name, onRestore = { viewModel.unarchiveRd(it.id) }) }
                }
                if (archivedPpf.isNotEmpty()) {
                    item { SectionLabel("PPF") }
                    items(archivedPpf) { ArchivedRow(label = it.name, onRestore = { viewModel.unarchivePpf(it.id) }) }
                }
                if (archivedEpf.isNotEmpty()) {
                    item { SectionLabel("EPF") }
                    items(archivedEpf) { ArchivedRow(label = it.name, onRestore = { viewModel.unarchiveEpf(it.id) }) }
                }
                if (archivedNps.isNotEmpty()) {
                    item { SectionLabel("NPS") }
                    items(archivedNps) { ArchivedRow(label = it.name, onRestore = { viewModel.unarchiveNps(it.id) }) }
                }
                if (archivedApy.isNotEmpty()) {
                    item { SectionLabel("APY") }
                    items(archivedApy) { ArchivedRow(label = it.name, onRestore = { viewModel.unarchiveApy(it.id) }) }
                }
                if (archivedInsurance.isNotEmpty()) {
                    item { SectionLabel("Insurance") }
                    items(archivedInsurance) { ArchivedRow(label = it.policyName, onRestore = { viewModel.unarchiveInsurance(it.id) }) }
                }
                if (archivedManualAssets.isNotEmpty()) {
                    item { SectionLabel("Manual Assets") }
                    items(archivedManualAssets) { ArchivedRow(label = it.name, onRestore = { viewModel.unarchiveManualAsset(it.id) }) }
                }
                if (archivedBanks.isNotEmpty()) {
                    item { SectionLabel("Bank") }
                    items(archivedBanks) { ArchivedRow(label = it.bankName, onRestore = { viewModel.unarchiveBank(it.id) }) }
                }
                if (archivedReceivables.isNotEmpty()) {
                    item { SectionLabel("Receivables") }
                    items(archivedReceivables) { ArchivedRow(label = it.personName, onRestore = { viewModel.unarchiveReceivable(it.id) }) }
                }
                if (archivedMutualFunds.isNotEmpty()) {
                    item { SectionLabel("Mutual Funds") }
                    items(archivedMutualFunds) { ArchivedRow(label = it.schemeName, onRestore = { viewModel.unarchiveMutualFund(it.id) }) }
                }
                if (archivedKametti.isNotEmpty()) {
                    item { SectionLabel("Kametti") }
                    items(archivedKametti) { ArchivedRow(label = it.name, onRestore = { viewModel.unarchiveKametti(it.id) }) }
                }
                if (archivedMetal.isNotEmpty()) {
                    item { SectionLabel("Gold/Silver") }
                    items(archivedMetal) { ArchivedRow(label = "${it.metalType.name} ${it.caratType} (${it.form.name})", onRestore = { viewModel.unarchiveMetal(it.id) }) }
                }
                if (archivedDematAccounts.isNotEmpty()) {
                    item { SectionLabel("Demat Accounts") }
                    items(archivedDematAccounts) { ArchivedRow(label = it.name, onRestore = { viewModel.unarchiveDematAccount(it.id) }) }
                }
                if (archivedMfPlatforms.isNotEmpty()) {
                    item { SectionLabel("Mutual Fund Accounts") }
                    items(archivedMfPlatforms) { ArchivedRow(label = it.name, onRestore = { viewModel.unarchiveMfPlatform(it.id) }) }
                }
            }
        }
    }

    // Permanent delete — only reachable here, on an already-archived
    // book, requires typing the book's exact name to confirm. Per the
    // locked rule: this is genuinely irreversible (unlike Archive, which
    // just hides the book), so it needs a much higher-friction
    // confirmation than a plain Yes/No dialog would give.
    bookToDelete?.let { book ->
        var confirmText by remember(book.id) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { bookToDelete = null },
            title = { Text("Permanently delete \"${book.name}\"?") },
            text = {
                Column {
                    Text("This cannot be undone. Every transaction, account, and category in this book will be permanently erased.")
                    Spacer(Modifier.height(12.dp))
                    Text("Type \"${book.name}\" to confirm:", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(value = confirmText, onValueChange = { confirmText = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(
                    enabled = confirmText == book.name,
                    onClick = { viewModel.deleteBookPermanently(book.id); bookToDelete = null }
                ) { Text("Delete Permanently", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { bookToDelete = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
    )
}

@Composable
private fun ArchivedRow(label: String, onRestore: () -> Unit, onDeletePermanently: (() -> Unit)? = null) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = onRestore) { Text("Restore") }
            // Only books currently pass this — deleting permanently is a
            // much heavier action than every other category's archive
            // entry, so it's opt-in per call site rather than a blanket
            // addition to every archived row in the app.
            if (onDeletePermanently != null) {
                TextButton(onClick = onDeletePermanently) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}
