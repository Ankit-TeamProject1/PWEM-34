package com.teamproject1.dailyexpensetracker.feature.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.teamproject1.dailyexpensetracker.core.database.TransactionRepository
import com.teamproject1.dailyexpensetracker.core.database.dao.AccountBalance
import com.teamproject1.dailyexpensetracker.core.database.dao.BookDao
import com.teamproject1.dailyexpensetracker.core.database.dao.RecurringRuleDao
import com.teamproject1.dailyexpensetracker.core.database.entity.RecurringRuleEntity
import com.teamproject1.dailyexpensetracker.core.session.SessionManager
import com.teamproject1.dailyexpensetracker.ui.theme.AppIcon3D
import com.teamproject1.dailyexpensetracker.ui.theme.DottedTextureBackground
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val transactionRepository: TransactionRepository,
    private val bookDao: BookDao,
    private val recurringRuleDao: RecurringRuleDao,
    private val session: SessionManager
) : ViewModel() {

    private val currentMonth = SimpleDateFormat("yyyy-MM", Locale.US).format(Date())

    val accountBalances = transactionRepository.getAccountBalances()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val todaySpend = transactionRepository.getTodaySpend(startOfToday(), endOfToday())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)

    val monthSpend = transactionRepository.getMonthSpend(currentMonth)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)

    // Upcoming recurring rules — due within the next 7 days, per item 9.
    val upcomingRules = session.activeBookId.filterNotNull()
        .flatMapLatest { bookId -> recurringRuleDao.getUpcoming(bookId, limit = 3) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private fun startOfToday(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun endOfToday(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59)
    }.timeInMillis
}

/**
 * Root screen per session — Auth and Book Selector are never on this
 * back-stack (locked rule: no accidental back-into-lock-screen).
 *
 * Book-switcher is ONLY tappable here (locked decision) — every other
 * screen shows the book name as a static label. Disabled while the Add
 * Expense/Income/Transfer sheet is open, consistent with the "sheet state
 * always survives interruption" rule.
 *
 * Visual redesign per item 8: home-screen-style 3D icons (AppIcon3D) on a
 * subtly textured background (DottedTextureBackground), replacing the
 * earlier card-style tile grid. Same treatment applied to Book Selector for
 * consistency, per the locked scope decision. The Accounts tile used to
 * show an inline balance list inside its card — since icons don't have
 * room for that, balances now show as a compact row above the icon grid
 * instead, so that information isn't lost in the redesign.
 */
@Composable
fun DashboardScreen(
    isCompact: Boolean,
    bookName: String,
    currencySymbol: String,
    isAnySheetOpen: Boolean,
    onBookSwitcherTap: () -> Unit,
    onAddExpense: (com.teamproject1.dailyexpensetracker.core.database.entity.TransactionType?) -> Unit,
    onAccounts: () -> Unit,
    onExpenses: () -> Unit,
    onBudget: () -> Unit,
    onReceivables: () -> Unit,
    onRecurring: () -> Unit,
    onSettings: () -> Unit,
    viewModel: DashboardViewModel = hiltViewModel()
) {
    val balances by viewModel.accountBalances.collectAsState()
    val todaySpend by viewModel.todaySpend.collectAsState()
    val monthSpend by viewModel.monthSpend.collectAsState()
    val upcomingRules by viewModel.upcomingRules.collectAsState()

    // Due-reminder check runs every time Dashboard composes (i.e. every app
    // open / return to Dashboard) — per the locked design, this is what
    // makes unrecorded due items keep reappearing without needing a
    // separate "dismissed" flag anywhere.
    val dueReminderViewModel: DueReminderViewModel = hiltViewModel()
    LaunchedEffect(Unit) { dueReminderViewModel.checkDueRules() }
    DueReminderPopup(viewModel = dueReminderViewModel)

    Scaffold(
        topBar = {
            DashboardHeader(
                bookName = bookName,
                currencySymbol = currencySymbol,
                switcherEnabled = !isAnySheetOpen,
                onBookSwitcherTap = onBookSwitcherTap,
                onSettings = onSettings
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { onAddExpense(null) }) {
                Icon(Icons.Default.Add, contentDescription = "Add Expense, Income, or Transfer")
            }
        },
        containerColor = Color.Transparent
    ) { padding ->
        DottedTextureBackground(modifier = Modifier.padding(padding)) {
            Column(modifier = Modifier.fillMaxSize()) {

                // Net summary strip — today's + this month's spend, per item 3
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("Today's spend", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "$currencySymbol${"%.0f".format(todaySpend)}",
                            style = MaterialTheme.typography.displayLarge
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("This month", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "$currencySymbol${"%.0f".format(monthSpend)}",
                            style = MaterialTheme.typography.headlineMedium
                        )
                    }
                }

                // Compact balances row — replaces the balance list that used
                // to live inside the Accounts tile's card content.
                if (balances.isNotEmpty()) {
                    BalancesRow(balances, currencySymbol)
                }

                // Quick-action icons for Income/Expense/Transfer, per the
                // request to surface these directly on Dashboard rather
                // than only through the "+" FAB's 3-way toggle — the FAB
                // still works too (defaults to Expense mode).
                QuickActionRow(
                    onAddExpense = { onAddExpense(com.teamproject1.dailyexpensetracker.core.database.entity.TransactionType.EXPENSE) },
                    onAddIncome = { onAddExpense(com.teamproject1.dailyexpensetracker.core.database.entity.TransactionType.INCOME) },
                    onAddTransfer = { onAddExpense(com.teamproject1.dailyexpensetracker.core.database.entity.TransactionType.TRANSFER) }
                )

                val gridColumns = if (isCompact) 3 else 4

                LazyVerticalGrid(
                    columns = GridCells.Fixed(gridColumns),
                    contentPadding = PaddingValues(20.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(1) {
                        AppIcon3D(label = "Accounts", emoji = "💰", onClick = onAccounts)
                    }
                    items(1) {
                        AppIcon3D(label = "Expenses", emoji = "🧾", onClick = onExpenses)
                    }
                    items(1) {
                        AppIcon3D(label = "Budget", emoji = "🎯", onClick = onBudget)
                    }
                    items(1) {
                        AppIcon3D(label = "Receivables", emoji = "🤝", onClick = onReceivables)
                    }
                    items(1) {
                        AppIcon3D(label = "Recurring", emoji = "🔂", onClick = onRecurring)
                    }
                }

                // Upcoming recurring section, per item 9
                if (upcomingRules.isNotEmpty()) {
                    UpcomingRecurringSection(upcomingRules, onSeeAll = onRecurring)
                }
            }
        }
    }
}

@Composable
private fun DashboardHeader(
    bookName: String,
    currencySymbol: String,
    switcherEnabled: Boolean,
    onBookSwitcherTap: () -> Unit,
    onSettings: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding() // pushes content below the status bar — was
            // missing entirely, causing the header to draw underneath the
            // battery/network icons on SDK 35's enforced edge-to-edge layout.
            .padding(horizontal = 16.dp, vertical = 20.dp), // bigger, per request
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = java.text.SimpleDateFormat("h:mm a, MMM d").format(java.util.Date()),
            style = MaterialTheme.typography.titleMedium
        )
        // Moved here from the grid, per explicit request, to match the
        // Wealth Dashboard's exact placement — a small icon between the
        // date/time and Book selector, rather than a full grid tile.
        IconButton(onClick = onSettings, modifier = Modifier.size(32.dp)) {
            Text("⚙️", style = MaterialTheme.typography.titleMedium)
        }
        TextButton(onClick = onBookSwitcherTap, enabled = switcherEnabled) {
            Text("$bookName ($currencySymbol) ▾", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun QuickActionRow(onAddExpense: () -> Unit, onAddIncome: () -> Unit, onAddTransfer: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        com.teamproject1.dailyexpensetracker.ui.theme.AppIcon3D(
            label = "Expense", emoji = "➖",
            accentColor = com.teamproject1.dailyexpensetracker.ui.theme.ExpenseRed,
            onClick = onAddExpense
        )
        com.teamproject1.dailyexpensetracker.ui.theme.AppIcon3D(
            label = "Income", emoji = "➕",
            accentColor = com.teamproject1.dailyexpensetracker.ui.theme.IncomeGreen,
            onClick = onAddIncome
        )
        com.teamproject1.dailyexpensetracker.ui.theme.AppIcon3D(
            label = "Transfer", emoji = "🔁",
            accentColor = com.teamproject1.dailyexpensetracker.ui.theme.TransferBlue,
            onClick = onAddTransfer
        )
    }
}

@Composable
private fun BalancesRow(balances: List<AccountBalance>, currencySymbol: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        balances.forEach {
            Text(
                "${it.accountName} $currencySymbol${"%.0f".format(it.balance)}",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun UpcomingRecurringSection(rules: List<RecurringRuleEntity>, onSeeAll: () -> Unit) {
    val dateFormat = remember { SimpleDateFormat("MMM d", Locale.US) }
    Column(
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 16.dp)
            // FAB floats over content rather than pushing it — without this
            // extra bottom clearance, the last row(s) here sit directly
            // under the floating "+" button and become unreadable/untappable.
            .padding(bottom = 72.dp)
    ) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text("Upcoming", style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onSeeAll) { Text("See all") }
        }
        Spacer(Modifier.height(8.dp))
        rules.forEach { rule ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(rule.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    "₹%.0f · %s".format(rule.amount, dateFormat.format(Date(rule.nextRunAt))),
                    style = MaterialTheme.typography.bodyMedium,
                    color = com.teamproject1.dailyexpensetracker.ui.theme.colorForTransactionType(rule.type)
                )
            }
        }
    }
}
