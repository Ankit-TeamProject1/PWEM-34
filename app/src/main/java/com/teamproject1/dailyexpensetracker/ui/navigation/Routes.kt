package com.teamproject1.dailyexpensetracker.ui.navigation

/**
 * All routes for the onboarding + core shell.
 * Auth and BookSelector are intentionally kept OFF the Dashboard's
 * back-stack once reached — per the locked rule: "Dashboard is the root
 * per session, no accidental back-into-lock-screen."
 */
object Routes {
    const val SPLASH = "splash"
    const val PIN_SETUP = "pin_setup"
    const val CREATE_FIRST_BOOK = "create_first_book"
    const val AUTH = "auth"
    const val AUTH_FOR_BOOK_SWITCH = "auth_for_book_switch"
    const val BOOK_SELECTOR = "book_selector"
    const val DASHBOARD = "dashboard"
    const val ACCOUNTS = "accounts"
    const val EXPENSES = "expenses"
    const val BUDGET = "budget"
    const val RECURRING = "recurring"
    const val NEW_RECURRING_RULE = "new_recurring_rule"
    const val EDIT_RECURRING_RULE = "edit_recurring_rule/{id}"
    const val SETTINGS = "settings"
    const val MANAGE_CATEGORIES = "manage_categories"
    const val MANAGE_ACCOUNTS = "manage_accounts"
    const val EXPORT_IMPORT = "export_import"
    const val ARCHIVED_ITEMS = "archived_items"
    const val CHANGE_PIN = "change_pin"

    // Wealth module routes
    const val BOOK_ROUTER = "book_router"
    const val WEALTH_DASHBOARD = "wealth_dashboard"
    const val FD_LIST = "fd_list"
    const val ADD_FD = "add_fd"
    const val EDIT_FD = "edit_fd/{id}"
    const val LIABILITY_LIST = "liability_list"
    const val ADD_LIABILITY = "add_liability"
    const val EDIT_LIABILITY = "edit_liability/{id}"
    const val CASH_IN_HAND = "cash_in_hand"
    const val DEMAT_ACCOUNTS = "demat_accounts"
    const val DEMAT_LIST = "demat_list/{accountId}/{accountName}"
    const val ADD_DEMAT = "add_demat/{accountId}"
    const val EDIT_DEMAT = "edit_demat/{accountId}/{id}"

    const val NPS_LIST = "nps_list"
    const val ADD_NPS = "add_nps"
    const val EDIT_NPS = "edit_nps/{id}"

    const val APY_LIST = "apy_list"
    const val ADD_APY = "add_apy"
    const val EDIT_APY = "edit_apy/{id}"

    const val MANUAL_ASSET_LIST = "manual_asset_list"
    const val ADD_MANUAL_ASSET = "add_manual_asset"
    const val EDIT_MANUAL_ASSET = "edit_manual_asset/{id}"

    const val INSURANCE_LIST = "insurance_list"
    const val ADD_INSURANCE = "add_insurance"
    const val EDIT_INSURANCE = "edit_insurance/{id}"

    const val PPF_LIST = "ppf_list"
    const val PPF_DETAIL = "ppf_detail/{id}"

    const val EPF_LIST = "epf_list"
    const val EPF_DETAIL = "epf_detail/{id}"

    const val RD_LIST = "rd_list"
    const val ADD_RD = "add_rd"
    const val EDIT_RD = "edit_rd/{id}"
    const val RD_DETAIL = "rd_detail/{id}"

    const val RATE_HISTORY = "rate_history"
    const val BANK_LIST = "bank_list"
    const val RECEIVABLE_LIST = "receivable_list"
    const val MUTUAL_FUND_PLATFORMS = "mutual_fund_platforms"
    const val MUTUAL_FUND_LIST = "mutual_fund_list/{platformId}/{platformName}"
    const val MUTUAL_FUND_DETAIL = "mutual_fund_detail/{id}"
    const val KAMETTI_LIST = "kametti_list"
    const val ADD_KAMETTI = "add_kametti"
    const val EDIT_KAMETTI = "edit_kametti/{id}"
    const val KAMETTI_DETAIL = "kametti_detail/{id}"
    const val RECURRING_TRANSACTIONS = "recurring_transactions"
    const val METAL_LIST = "metal_list"
    const val EXPENSE_RECEIVABLES = "expense_receivables"
}
