package com.teamproject1.dailyexpensetracker

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.teamproject1.dailyexpensetracker.feature.accounts.AccountsScreen
import com.teamproject1.dailyexpensetracker.feature.auth.AuthScreen
import com.teamproject1.dailyexpensetracker.feature.auth.SessionLockViewModel
import com.teamproject1.dailyexpensetracker.feature.bookselector.BookSelectorScreen
import com.teamproject1.dailyexpensetracker.feature.budget.BudgetScreen
import com.teamproject1.dailyexpensetracker.feature.dashboard.BookRouterViewModel
import com.teamproject1.dailyexpensetracker.feature.dashboard.DashboardHostViewModel
import com.teamproject1.dailyexpensetracker.feature.dashboard.DashboardScreen
import com.teamproject1.dailyexpensetracker.feature.expense.AddExpenseSheet
import com.teamproject1.dailyexpensetracker.feature.expenses.ExpensesScreen
import com.teamproject1.dailyexpensetracker.feature.onboarding.CreateFirstBookScreen
import com.teamproject1.dailyexpensetracker.feature.onboarding.PinSetupScreen
import com.teamproject1.dailyexpensetracker.feature.recurring.NewRecurringRuleScreen
import com.teamproject1.dailyexpensetracker.feature.recurring.RecurringScreen
import com.teamproject1.dailyexpensetracker.feature.settings.ArchivedItemsScreen
import com.teamproject1.dailyexpensetracker.feature.settings.ChangePinScreen
import com.teamproject1.dailyexpensetracker.feature.settings.ExportImportScreen
import com.teamproject1.dailyexpensetracker.feature.settings.ManageAccountsScreen
import com.teamproject1.dailyexpensetracker.feature.settings.ManageCategoriesScreen
import com.teamproject1.dailyexpensetracker.feature.settings.SettingsScreen
import com.teamproject1.dailyexpensetracker.feature.splash.SplashDestination
import com.teamproject1.dailyexpensetracker.feature.splash.SplashScreen
import com.teamproject1.dailyexpensetracker.feature.wealth.AddEditDematScreen
import com.teamproject1.dailyexpensetracker.feature.wealth.AddEditFdScreen
import com.teamproject1.dailyexpensetracker.feature.wealth.AddEditLiabilityScreen
import com.teamproject1.dailyexpensetracker.feature.wealth.CashInHandScreen
import com.teamproject1.dailyexpensetracker.feature.wealth.DematListScreen
import com.teamproject1.dailyexpensetracker.feature.wealth.FdListScreen
import com.teamproject1.dailyexpensetracker.feature.wealth.LiabilityListScreen
import com.teamproject1.dailyexpensetracker.feature.wealth.WealthDashboardScreen
import com.teamproject1.dailyexpensetracker.feature.wealth.apy.AddEditApyScreen
import com.teamproject1.dailyexpensetracker.feature.wealth.apy.ApyListScreen
import com.teamproject1.dailyexpensetracker.feature.wealth.assets.AddEditManualAssetScreen
import com.teamproject1.dailyexpensetracker.feature.wealth.assets.ManualAssetListScreen
import com.teamproject1.dailyexpensetracker.feature.wealth.epf.EpfDetailScreen
import com.teamproject1.dailyexpensetracker.feature.wealth.epf.EpfListScreen
import com.teamproject1.dailyexpensetracker.feature.wealth.insurance.AddEditInsuranceScreen
import com.teamproject1.dailyexpensetracker.feature.wealth.insurance.InsuranceListScreen
import com.teamproject1.dailyexpensetracker.feature.wealth.nps.AddEditNpsScreen
import com.teamproject1.dailyexpensetracker.feature.wealth.nps.NpsListScreen
import com.teamproject1.dailyexpensetracker.feature.wealth.ppf.PpfDetailScreen
import com.teamproject1.dailyexpensetracker.feature.wealth.ppf.PpfListScreen
import com.teamproject1.dailyexpensetracker.feature.wealth.rd.AddEditRdScreen
import com.teamproject1.dailyexpensetracker.feature.wealth.rd.RdListScreen
import com.teamproject1.dailyexpensetracker.feature.wealth.ratehistory.RateHistoryScreen
import com.teamproject1.dailyexpensetracker.feature.wealth.bank.BankListScreen
import com.teamproject1.dailyexpensetracker.feature.wealth.receivables.ReceivableListScreen
import com.teamproject1.dailyexpensetracker.feature.receivables.ExpenseReceivableListScreen
import com.teamproject1.dailyexpensetracker.ui.navigation.Routes

/**
 * Root navigation host. Transfer no longer has its own sheet/tile — it's a
 * mode inside AddExpenseSheet's 3-way Expense/Income/Transfer toggle, per
 * item 7 of the pending-changes batch. Expenses (full ledger) and Manage
 * Categories are new full-screen routes, per items 5 and 1.
 *
 * Also observes SessionManager (via SessionLockViewModel) and pushes the
 * Auth screen on top of whatever's currently showing if the session gets
 * locked mid-use — this is the fix for the "app doesn't lock until fully
 * closed" bug: the underlying grace-period logic existed, but nothing was
 * watching for it to actually flip and react. Auth is PUSHED, not used to
 * replace the back stack, so the screen underneath survives untouched and
 * reappears exactly as it was on successful unlock — matching the locked
 * "auth is a gate, not a navigation reset" rule.
 */
@Composable
fun ExpenseTrackerNavHost(isCompact: Boolean) {
    val navController = rememberNavController()
    val sessionLockViewModel: SessionLockViewModel = hiltViewModel()
    val isUnlocked by sessionLockViewModel.isUnlocked.collectAsState()
    val currentEntry by navController.currentBackStackEntryAsState()

    LaunchedEffect(isUnlocked, currentEntry) {
        val currentRoute = currentEntry?.destination?.route
        val alreadyOnAuthOrOnboarding = currentRoute in listOf(
            Routes.SPLASH, Routes.AUTH, Routes.AUTH_FOR_BOOK_SWITCH, Routes.PIN_SETUP, Routes.CREATE_FIRST_BOOK
        )
        if (!isUnlocked && !alreadyOnAuthOrOnboarding && currentRoute != null) {
            navController.navigate(Routes.AUTH)
        }
    }

    NavHost(navController = navController, startDestination = Routes.SPLASH) {

        composable(Routes.SPLASH) {
            SplashScreen(onNavigate = { destination ->
                val route = when (destination) {
                    SplashDestination.PinSetup -> Routes.PIN_SETUP
                    SplashDestination.CreateFirstBook -> Routes.CREATE_FIRST_BOOK
                    SplashDestination.Auth -> Routes.AUTH
                    SplashDestination.Loading -> return@SplashScreen
                }
                navController.navigate(route) {
                    popUpTo(Routes.SPLASH) { inclusive = true }
                }
            })
        }

        composable(Routes.AUTH) {
            // Two entry paths share this one destination:
            //  (a) cold-start returning user: Splash -> Auth -> should land
            //      on Book Selector.
            //  (b) mid-session re-lock (grace period expired): Auth was
            //      PUSHED on top of an existing screen (e.g. Dashboard) ->
            //      should just pop back to reveal that screen exactly as it
            //      was, per the locked "auth is a gate, not a reset" rule.
            // previousBackStackEntry at composition time tells us which case
            // this is: SPLASH (or null) means cold start; anything else
            // (DASHBOARD, ACCOUNTS, etc.) means a mid-session re-lock.
            val previousRoute = navController.previousBackStackEntry?.destination?.route
            val isMidSessionRelock = previousRoute != null && previousRoute != Routes.SPLASH

            AuthScreen(onUnlocked = {
                if (isMidSessionRelock) {
                    navController.popBackStack()
                } else {
                    navController.navigate(Routes.BOOK_SELECTOR) {
                        popUpTo(Routes.AUTH) { inclusive = true }
                    }
                }
            })
        }

        composable(Routes.AUTH_FOR_BOOK_SWITCH) {
            // Distinct destination for the "switching books requires
            // re-authentication" rule — deliberately separate from the
            // general re-lock AUTH route above, since this one always
            // proceeds to Book Selector on success rather than just
            // revealing whatever screen was underneath. The user is
            // already unlocked into the app; this is an extra confirmation
            // specifically for the sensitive act of switching books.
            AuthScreen(onUnlocked = {
                navController.navigate(Routes.BOOK_SELECTOR) {
                    popUpTo(Routes.AUTH_FOR_BOOK_SWITCH) { inclusive = true }
                }
            })
        }

        composable(Routes.BOOK_SELECTOR) {
            BookSelectorScreen(
                isCompact = isCompact,
                onBookSelected = {
                    navController.navigate(Routes.BOOK_ROUTER) {
                        popUpTo(0) { inclusive = true }
                    }
                },
                onCreateNewBook = { navController.navigate(Routes.CREATE_FIRST_BOOK) }
            )
        }

        composable(Routes.PIN_SETUP) {
            PinSetupScreen(onComplete = {
                navController.navigate(Routes.CREATE_FIRST_BOOK) {
                    popUpTo(Routes.PIN_SETUP) { inclusive = true }
                }
            })
        }

        composable(Routes.CREATE_FIRST_BOOK) {
            CreateFirstBookScreen(
                showStartingBalances = true,
                onBookCreated = {
                    navController.navigate(Routes.BOOK_ROUTER) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.BOOK_ROUTER) {
            // Reads the active book's type and redirects — Book Selector and
            // Create First Book both land here rather than needing their own
            // callbacks changed to carry book type through.
            val routerViewModel: BookRouterViewModel = hiltViewModel()
            LaunchedEffect(Unit) {
                routerViewModel.determineDestination { isWealth ->
                    val target = if (isWealth) Routes.WEALTH_DASHBOARD else Routes.DASHBOARD
                    navController.navigate(target) {
                        popUpTo(Routes.BOOK_ROUTER) { inclusive = true }
                    }
                }
            }
        }

        composable(Routes.DASHBOARD) {
            DashboardHost(isCompact = isCompact, navController = navController)
        }

        composable(Routes.WEALTH_DASHBOARD) {
            WealthDashboardHost(isCompact = isCompact, navController = navController)
        }

        composable(Routes.ACCOUNTS) {
            AccountsScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.EXPENSES) {
            ExpensesScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.BUDGET) {
            BudgetScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.RECURRING) {
            RecurringScreen(
                onBack = { navController.popBackStack() },
                onNewRule = { navController.navigate(Routes.NEW_RECURRING_RULE) },
                onEditRule = { id -> navController.navigate("edit_recurring_rule/$id") }
            )
        }

        composable(Routes.NEW_RECURRING_RULE) {
            NewRecurringRuleScreen(
                existingId = null,
                onBack = { navController.popBackStack() },
                onSaved = { navController.popBackStack() }
            )
        }

        composable(
            Routes.EDIT_RECURRING_RULE,
            arguments = listOf(androidx.navigation.navArgument("id") { type = androidx.navigation.NavType.LongType })
        ) { backStackEntry ->
            val id = backStackEntry.arguments?.getLong("id")
            NewRecurringRuleScreen(
                existingId = id,
                onBack = { navController.popBackStack() },
                onSaved = { navController.popBackStack() }
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onManageBooks = { navController.navigate(Routes.AUTH_FOR_BOOK_SWITCH) },
                onManageCategories = { navController.navigate(Routes.MANAGE_CATEGORIES) },
                onManageAccounts = { navController.navigate(Routes.MANAGE_ACCOUNTS) },
                onExportImport = { navController.navigate(Routes.EXPORT_IMPORT) },
                onArchivedItems = { navController.navigate(Routes.ARCHIVED_ITEMS) },
                onChangePin = { navController.navigate(Routes.CHANGE_PIN) },
                onRateHistory = { navController.navigate(Routes.RATE_HISTORY) }
            )
        }

        composable(Routes.MANAGE_CATEGORIES) {
            ManageCategoriesScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.MANAGE_ACCOUNTS) {
            ManageAccountsScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.EXPORT_IMPORT) {
            ExportImportScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.ARCHIVED_ITEMS) {
            ArchivedItemsScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.CHANGE_PIN) {
            ChangePinScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.FD_LIST) {
            FdListScreen(
                onBack = { navController.popBackStack() },
                onAddNew = { navController.navigate(Routes.ADD_FD) },
                onEdit = { id -> navController.navigate("edit_fd/$id") }
            )
        }

        composable(Routes.ADD_FD) {
            AddEditFdScreen(
                existingId = null,
                onBack = { navController.popBackStack() },
                onSaved = { navController.popBackStack() }
            )
        }

        composable(
            Routes.EDIT_FD,
            arguments = listOf(androidx.navigation.navArgument("id") { type = androidx.navigation.NavType.LongType })
        ) { backStackEntry ->
            val id = backStackEntry.arguments?.getLong("id")
            AddEditFdScreen(
                existingId = id,
                onBack = { navController.popBackStack() },
                onSaved = { navController.popBackStack() }
            )
        }

        composable(Routes.LIABILITY_LIST) {
            LiabilityListScreen(
                onBack = { navController.popBackStack() },
                onAddNew = { navController.navigate(Routes.ADD_LIABILITY) },
                onEdit = { id -> navController.navigate("edit_liability/$id") }
            )
        }

        composable(Routes.ADD_LIABILITY) {
            AddEditLiabilityScreen(
                existingId = null,
                onBack = { navController.popBackStack() },
                onSaved = { navController.popBackStack() }
            )
        }

        composable(
            Routes.EDIT_LIABILITY,
            arguments = listOf(androidx.navigation.navArgument("id") { type = androidx.navigation.NavType.LongType })
        ) { backStackEntry ->
            val id = backStackEntry.arguments?.getLong("id")
            AddEditLiabilityScreen(
                existingId = id,
                onBack = { navController.popBackStack() },
                onSaved = { navController.popBackStack() }
            )
        }

        composable(Routes.CASH_IN_HAND) {
            CashInHandScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.DEMAT_ACCOUNTS) {
            com.teamproject1.dailyexpensetracker.feature.wealth.DematAccountSelectorScreen(
                onBack = { navController.popBackStack() },
                onOpenAccount = { accountId, accountName -> navController.navigate("demat_list/$accountId/${java.net.URLEncoder.encode(accountName, "UTF-8")}") }
            )
        }

        composable(
            Routes.DEMAT_LIST,
            arguments = listOf(
                androidx.navigation.navArgument("accountId") { type = androidx.navigation.NavType.LongType },
                androidx.navigation.navArgument("accountName") { type = androidx.navigation.NavType.StringType }
            )
        ) { backStackEntry ->
            val accountId = backStackEntry.arguments?.getLong("accountId") ?: 0L
            val accountName = java.net.URLDecoder.decode(backStackEntry.arguments?.getString("accountName") ?: "", "UTF-8")
            DematListScreen(
                dematAccountId = accountId,
                accountName = accountName,
                onBack = { navController.popBackStack() },
                onAddNew = { navController.navigate("add_demat/$accountId") },
                onEdit = { id -> navController.navigate("edit_demat/$accountId/$id") }
            )
        }

        composable(
            Routes.ADD_DEMAT,
            arguments = listOf(androidx.navigation.navArgument("accountId") { type = androidx.navigation.NavType.LongType })
        ) { backStackEntry ->
            val accountId = backStackEntry.arguments?.getLong("accountId") ?: 0L
            AddEditDematScreen(
                existingId = null,
                dematAccountId = accountId,
                onBack = { navController.popBackStack() },
                onSaved = { navController.popBackStack() }
            )
        }

        composable(
            Routes.EDIT_DEMAT,
            arguments = listOf(
                androidx.navigation.navArgument("accountId") { type = androidx.navigation.NavType.LongType },
                androidx.navigation.navArgument("id") { type = androidx.navigation.NavType.LongType }
            )
        ) { backStackEntry ->
            val accountId = backStackEntry.arguments?.getLong("accountId") ?: 0L
            val id = backStackEntry.arguments?.getLong("id")
            AddEditDematScreen(
                existingId = id,
                dematAccountId = accountId,
                onBack = { navController.popBackStack() },
                onSaved = { navController.popBackStack() }
            )
        }

        // --- NPS ---
        composable(Routes.NPS_LIST) {
            NpsListScreen(
                onBack = { navController.popBackStack() },
                onAddNew = { navController.navigate(Routes.ADD_NPS) },
                onEdit = { id -> navController.navigate("edit_nps/$id") }
            )
        }
        composable(Routes.ADD_NPS) {
            AddEditNpsScreen(existingId = null, onBack = { navController.popBackStack() }, onSaved = { navController.popBackStack() })
        }
        composable(
            Routes.EDIT_NPS,
            arguments = listOf(androidx.navigation.navArgument("id") { type = androidx.navigation.NavType.LongType })
        ) { backStackEntry ->
            AddEditNpsScreen(
                existingId = backStackEntry.arguments?.getLong("id"),
                onBack = { navController.popBackStack() },
                onSaved = { navController.popBackStack() }
            )
        }

        // --- APY ---
        composable(Routes.APY_LIST) {
            ApyListScreen(
                onBack = { navController.popBackStack() },
                onAddNew = { navController.navigate(Routes.ADD_APY) },
                onEdit = { id -> navController.navigate("edit_apy/$id") }
            )
        }
        composable(Routes.ADD_APY) {
            AddEditApyScreen(existingId = null, onBack = { navController.popBackStack() }, onSaved = { navController.popBackStack() })
        }
        composable(
            Routes.EDIT_APY,
            arguments = listOf(androidx.navigation.navArgument("id") { type = androidx.navigation.NavType.LongType })
        ) { backStackEntry ->
            AddEditApyScreen(
                existingId = backStackEntry.arguments?.getLong("id"),
                onBack = { navController.popBackStack() },
                onSaved = { navController.popBackStack() }
            )
        }

        // --- Manual Assets ---
        composable(Routes.MANUAL_ASSET_LIST) {
            ManualAssetListScreen(
                onBack = { navController.popBackStack() },
                onAddNew = { navController.navigate(Routes.ADD_MANUAL_ASSET) },
                onEdit = { id -> navController.navigate("edit_manual_asset/$id") }
            )
        }
        composable(Routes.ADD_MANUAL_ASSET) {
            AddEditManualAssetScreen(existingId = null, onBack = { navController.popBackStack() }, onSaved = { navController.popBackStack() })
        }
        composable(
            Routes.EDIT_MANUAL_ASSET,
            arguments = listOf(androidx.navigation.navArgument("id") { type = androidx.navigation.NavType.LongType })
        ) { backStackEntry ->
            AddEditManualAssetScreen(
                existingId = backStackEntry.arguments?.getLong("id"),
                onBack = { navController.popBackStack() },
                onSaved = { navController.popBackStack() }
            )
        }

        // --- Insurance ---
        composable(Routes.INSURANCE_LIST) {
            InsuranceListScreen(
                onBack = { navController.popBackStack() },
                onAddNew = { navController.navigate(Routes.ADD_INSURANCE) },
                onEdit = { id -> navController.navigate("edit_insurance/$id") }
            )
        }
        composable(Routes.ADD_INSURANCE) {
            AddEditInsuranceScreen(existingId = null, onBack = { navController.popBackStack() }, onSaved = { navController.popBackStack() })
        }
        composable(
            Routes.EDIT_INSURANCE,
            arguments = listOf(androidx.navigation.navArgument("id") { type = androidx.navigation.NavType.LongType })
        ) { backStackEntry ->
            AddEditInsuranceScreen(
                existingId = backStackEntry.arguments?.getLong("id"),
                onBack = { navController.popBackStack() },
                onSaved = { navController.popBackStack() }
            )
        }

        // --- PPF ---
        composable(Routes.PPF_LIST) {
            PpfListScreen(
                onBack = { navController.popBackStack() },
                onOpenAccount = { id -> navController.navigate("ppf_detail/$id") }
            )
        }
        composable(
            Routes.PPF_DETAIL,
            arguments = listOf(androidx.navigation.navArgument("id") { type = androidx.navigation.NavType.LongType })
        ) { backStackEntry ->
            PpfDetailScreen(
                accountId = backStackEntry.arguments?.getLong("id") ?: 0,
                onBack = { navController.popBackStack() }
            )
        }

        // --- EPF ---
        composable(Routes.EPF_LIST) {
            EpfListScreen(
                onBack = { navController.popBackStack() },
                onOpenAccount = { id -> navController.navigate("epf_detail/$id") }
            )
        }
        composable(
            Routes.EPF_DETAIL,
            arguments = listOf(androidx.navigation.navArgument("id") { type = androidx.navigation.NavType.LongType })
        ) { backStackEntry ->
            EpfDetailScreen(
                accountId = backStackEntry.arguments?.getLong("id") ?: 0,
                onBack = { navController.popBackStack() }
            )
        }

        // --- RD ---
        composable(Routes.RD_LIST) {
            RdListScreen(
                onBack = { navController.popBackStack() },
                onAddNew = { navController.navigate(Routes.ADD_RD) },
                onOpenDetail = { id -> navController.navigate("rd_detail/$id") }
            )
        }
        composable(Routes.ADD_RD) {
            AddEditRdScreen(existingId = null, onBack = { navController.popBackStack() }, onSaved = { navController.popBackStack() })
        }
        composable(
            Routes.EDIT_RD,
            arguments = listOf(androidx.navigation.navArgument("id") { type = androidx.navigation.NavType.LongType })
        ) { backStackEntry ->
            AddEditRdScreen(
                existingId = backStackEntry.arguments?.getLong("id"),
                onBack = { navController.popBackStack() },
                onSaved = { navController.popBackStack() }
            )
        }
        composable(
            Routes.RD_DETAIL,
            arguments = listOf(androidx.navigation.navArgument("id") { type = androidx.navigation.NavType.LongType })
        ) { backStackEntry ->
            com.teamproject1.dailyexpensetracker.feature.wealth.rd.RdDetailScreen(
                rdAccountId = backStackEntry.arguments?.getLong("id") ?: 0,
                onBack = { navController.popBackStack() },
                onEditAccount = { id -> navController.navigate("edit_rd/$id") }
            )
        }

        composable(Routes.RATE_HISTORY) {
            RateHistoryScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.BANK_LIST) {
            BankListScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.RECEIVABLE_LIST) {
            ReceivableListScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.MUTUAL_FUND_PLATFORMS) {
            com.teamproject1.dailyexpensetracker.feature.wealth.mutualfund.MutualFundPlatformSelectorScreen(
                onBack = { navController.popBackStack() },
                onOpenPlatform = { platformId, platformName -> navController.navigate("mutual_fund_list/$platformId/${java.net.URLEncoder.encode(platformName, "UTF-8")}") }
            )
        }
        composable(
            Routes.MUTUAL_FUND_LIST,
            arguments = listOf(
                androidx.navigation.navArgument("platformId") { type = androidx.navigation.NavType.LongType },
                androidx.navigation.navArgument("platformName") { type = androidx.navigation.NavType.StringType }
            )
        ) { backStackEntry ->
            val platformId = backStackEntry.arguments?.getLong("platformId") ?: 0L
            val platformName = java.net.URLDecoder.decode(backStackEntry.arguments?.getString("platformName") ?: "", "UTF-8")
            com.teamproject1.dailyexpensetracker.feature.wealth.mutualfund.MutualFundListScreen(
                platformId = platformId,
                platformName = platformName,
                onBack = { navController.popBackStack() },
                onOpenAccount = { id -> navController.navigate("mutual_fund_detail/$id") }
            )
        }
        composable(
            Routes.MUTUAL_FUND_DETAIL,
            arguments = listOf(androidx.navigation.navArgument("id") { type = androidx.navigation.NavType.LongType })
        ) { backStackEntry ->
            com.teamproject1.dailyexpensetracker.feature.wealth.mutualfund.MutualFundDetailScreen(
                accountId = backStackEntry.arguments?.getLong("id") ?: 0,
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.KAMETTI_LIST) {
            com.teamproject1.dailyexpensetracker.feature.wealth.kametti.KamettiListScreen(
                onBack = { navController.popBackStack() },
                onAddNew = { navController.navigate(Routes.ADD_KAMETTI) },
                onOpenAccount = { id -> navController.navigate("kametti_detail/$id") }
            )
        }
        composable(Routes.ADD_KAMETTI) {
            com.teamproject1.dailyexpensetracker.feature.wealth.kametti.AddEditKamettiScreen(
                existingId = null, onBack = { navController.popBackStack() }, onSaved = { navController.popBackStack() }
            )
        }
        composable(
            Routes.EDIT_KAMETTI,
            arguments = listOf(androidx.navigation.navArgument("id") { type = androidx.navigation.NavType.LongType })
        ) { backStackEntry ->
            com.teamproject1.dailyexpensetracker.feature.wealth.kametti.AddEditKamettiScreen(
                existingId = backStackEntry.arguments?.getLong("id"),
                onBack = { navController.popBackStack() }, onSaved = { navController.popBackStack() }
            )
        }
        composable(
            Routes.KAMETTI_DETAIL,
            arguments = listOf(androidx.navigation.navArgument("id") { type = androidx.navigation.NavType.LongType })
        ) { backStackEntry ->
            com.teamproject1.dailyexpensetracker.feature.wealth.kametti.KamettiDetailScreen(
                accountId = backStackEntry.arguments?.getLong("id") ?: 0,
                onBack = { navController.popBackStack() },
                onEditAccount = { id -> navController.navigate("edit_kametti/$id") }
            )
        }

        composable(Routes.RECURRING_TRANSACTIONS) {
            com.teamproject1.dailyexpensetracker.feature.wealth.RecurringTransactionsScreen(
                onBack = { navController.popBackStack() },
                onOpenRd = { id -> navController.navigate("rd_detail/$id") },
                onOpenEpf = { id -> navController.navigate("epf_detail/$id") },
                onOpenApy = { navController.navigate(Routes.APY_LIST) },
                onOpenNps = { navController.navigate(Routes.NPS_LIST) },
                onOpenMutualFund = { id -> navController.navigate("mutual_fund_detail/$id") },
                onOpenKametti = { id -> navController.navigate("kametti_detail/$id") }
            )
        }

        composable(Routes.METAL_LIST) {
            com.teamproject1.dailyexpensetracker.feature.wealth.metal.MetalListScreen(
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.EXPENSE_RECEIVABLES) {
            ExpenseReceivableListScreen(onBack = { navController.popBackStack() })
        }
    }
}

/**
 * Hosts Dashboard plus the Add Expense/Income/Transfer sheet as local
 * overlay state. isAnySheetOpen disables the book-switcher while it's
 * showing, per the locked decision. Sheet-entered data survives an auth
 * interruption automatically since this composable isn't torn down by the
 * Auth screen appearing as an overlay above it.
 */
@Composable
private fun DashboardHost(
    isCompact: Boolean,
    navController: NavHostController,
    viewModel: DashboardHostViewModel = hiltViewModel()
) {
    var showAddExpense by remember { mutableStateOf(false) }
    var presetType by remember { mutableStateOf<com.teamproject1.dailyexpensetracker.core.database.entity.TransactionType?>(null) }
    val activeBook by viewModel.activeBook.collectAsState()

    DashboardScreen(
        isCompact = isCompact,
        bookName = activeBook?.name ?: "",
        currencySymbol = currencySymbolFor(activeBook?.currencyCode ?: "INR"),
        isAnySheetOpen = showAddExpense,
        onBookSwitcherTap = {
            // Switching books now requires re-authentication (PIN/biometric),
            // per the locked decision — even though the user is already
            // unlocked into the app, this adds a confirmation step before
            // exposing a different book's data.
            if (!showAddExpense) navController.navigate(Routes.AUTH_FOR_BOOK_SWITCH)
        },
        onAddExpense = { type -> presetType = type; showAddExpense = true },
        onAccounts = { navController.navigate(Routes.ACCOUNTS) },
        onExpenses = { navController.navigate(Routes.EXPENSES) },
        onBudget = { navController.navigate(Routes.BUDGET) },
        onReceivables = { navController.navigate(Routes.EXPENSE_RECEIVABLES) },
        onRecurring = { navController.navigate(Routes.RECURRING) },
        onSettings = { navController.navigate(Routes.SETTINGS) }
    )

    if (showAddExpense) {
        AddExpenseSheet(
            onDismiss = { showAddExpense = false },
            onSaved = { showAddExpense = false },
            presetType = presetType
        )
    }
}

private fun currencySymbolFor(code: String): String = when (code) {
    "INR" -> "₹"
    "USD" -> "$"
    "EUR" -> "€"
    "GBP" -> "£"
    "SGD" -> "S$"
    "AED" -> "AED "
    else -> code
}

/** Same pattern as DashboardHost, for Wealth-type books — reuses
 *  DashboardHostViewModel since resolving the active book's name/currency
 *  is identical logic for either book type. */
@Composable
private fun WealthDashboardHost(
    isCompact: Boolean,
    navController: NavHostController,
    viewModel: DashboardHostViewModel = hiltViewModel()
) {
    val activeBook by viewModel.activeBook.collectAsState()

    WealthDashboardScreen(
        isCompact = isCompact,
        bookName = activeBook?.name ?: "",
        currencySymbol = currencySymbolFor(activeBook?.currencyCode ?: "INR"),
        onBookSwitcherTap = { navController.navigate(Routes.AUTH_FOR_BOOK_SWITCH) },
        onFixedDeposits = { navController.navigate(Routes.FD_LIST) },
        onLiabilities = { navController.navigate(Routes.LIABILITY_LIST) },
        onCashInHand = { navController.navigate(Routes.CASH_IN_HAND) },
        onDemat = { navController.navigate(Routes.DEMAT_ACCOUNTS) },
        onMutualFunds = { navController.navigate(Routes.MUTUAL_FUND_PLATFORMS) },
        onKametti = { navController.navigate(Routes.KAMETTI_LIST) },
        onRecurringTransactions = { navController.navigate(Routes.RECURRING_TRANSACTIONS) },
        onOpenRdAccount = { id -> navController.navigate("rd_detail/$id") },
        onOpenEpfAccount = { id -> navController.navigate("epf_detail/$id") },
        onOpenApyAccount = { navController.navigate(Routes.APY_LIST) },
        onOpenNpsAccount = { navController.navigate(Routes.NPS_LIST) },
        onOpenMutualFundAccount = { id -> navController.navigate("mutual_fund_detail/$id") },
        onOpenKamettiAccount = { id -> navController.navigate("kametti_detail/$id") },
        onGoldSilver = { navController.navigate(Routes.METAL_LIST) },
        onPpf = { navController.navigate(Routes.PPF_LIST) },
        onEpf = { navController.navigate(Routes.EPF_LIST) },
        onRd = { navController.navigate(Routes.RD_LIST) },
        onNps = { navController.navigate(Routes.NPS_LIST) },
        onApy = { navController.navigate(Routes.APY_LIST) },
        onBank = { navController.navigate(Routes.BANK_LIST) },
        onReceivables = { navController.navigate(Routes.RECEIVABLE_LIST) },
        onInsurance = { navController.navigate(Routes.INSURANCE_LIST) },
        onManualAssets = { navController.navigate(Routes.MANUAL_ASSET_LIST) },
        onSettings = { navController.navigate(Routes.SETTINGS) }
    )
}
