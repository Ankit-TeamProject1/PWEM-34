package com.teamproject1.dailyexpensetracker.core.database

import com.teamproject1.dailyexpensetracker.core.database.dao.AccountDao
import com.teamproject1.dailyexpensetracker.core.database.dao.CategoryDao
import com.teamproject1.dailyexpensetracker.core.database.dao.RecurringRuleDao
import com.teamproject1.dailyexpensetracker.core.database.entity.RecurringRuleEntity
import javax.inject.Inject
import javax.inject.Singleton

sealed class ArchiveResult {
    object Success : ArchiveResult()
    data class Blocked(val blockingRules: List<RecurringRuleEntity>) : ArchiveResult()
}

/**
 * Implements the locked decision: archiving a category or account referenced
 * by an ACTIVE (non-paused) recurring rule is blocked, not auto-paused.
 * The UI shows the blockingRules list in a dialog naming each rule so the
 * user can review/pause/reassign before retrying.
 */
@Singleton
class RecurringRuleRepository @Inject constructor(
    private val recurringRuleDao: RecurringRuleDao,
    private val accountDao: AccountDao,
    private val categoryDao: CategoryDao
) {
    suspend fun tryArchiveCategory(categoryId: Long): ArchiveResult {
        val blockers = recurringRuleDao.getActiveRulesReferencing(categoryId)
        if (blockers.isNotEmpty()) return ArchiveResult.Blocked(blockers)
        categoryDao.archive(categoryId)
        return ArchiveResult.Success
    }

    suspend fun tryArchiveAccount(accountId: Long): ArchiveResult {
        val blockers = recurringRuleDao.getActiveRulesReferencing(accountId)
        if (blockers.isNotEmpty()) return ArchiveResult.Blocked(blockers)
        accountDao.archive(accountId)
        return ArchiveResult.Success
    }
}
