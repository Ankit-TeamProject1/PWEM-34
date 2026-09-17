package com.teamproject1.dailyexpensetracker.core.database

import com.teamproject1.dailyexpensetracker.core.database.dao.RecurringRuleDao
import com.teamproject1.dailyexpensetracker.core.database.dao.TransactionDao
import com.teamproject1.dailyexpensetracker.core.database.entity.RecurrenceFrequency
import com.teamproject1.dailyexpensetracker.core.database.entity.RecurringRuleEntity
import com.teamproject1.dailyexpensetracker.core.database.entity.TransactionEntity
import com.teamproject1.dailyexpensetracker.core.session.SessionManager
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Powers the due-reminder popup, per the locked decision to replace silent
 * background auto-posting entirely: recurring rules never post themselves.
 * On app open, the caller checks getDueRules() for the active book — if
 * anything is due (including overdue/back-dated items), it's shown to the
 * user to confirm individually or all at once via recordDue(). Nothing
 * is written until the user taps to record it.
 *
 * Since nothing auto-advances a rule's nextRunAt in the background
 * anymore, a due item naturally keeps reappearing on every app open until
 * it's recorded — no extra "dismissed" flag is needed to satisfy the
 * locked "keep reminding until acted on" decision.
 */
@Singleton
class RecurringDueRepository @Inject constructor(
    private val recurringRuleDao: RecurringRuleDao,
    private val transactionDao: TransactionDao,
    private val session: SessionManager
) {

    suspend fun getDueRules(): List<RecurringRuleEntity> {
        val bookId = session.activeBookId.value ?: return emptyList()
        return recurringRuleDao.getDueRulesForBook(bookId, System.currentTimeMillis())
    }

    /**
     * Records one due rule as a real transaction, dated to the rule's
     * ORIGINAL due date (not "now") — this preserves the true intended
     * date for a back-dated item rather than misdating it to today, which
     * was part of the original bug report. Then advances the rule's
     * schedule to its next occurrence.
     */
    suspend fun recordDue(rule: RecurringRuleEntity) {
        val now = System.currentTimeMillis()
        transactionDao.insert(
            TransactionEntity(
                bookId = rule.bookId,
                type = rule.type,
                amount = rule.amount,
                accountId = rule.accountId,
                toAccountId = rule.toAccountId,
                categoryId = rule.categoryId,
                note = rule.note ?: rule.name,
                occurredAt = rule.nextRunAt,
                createdAt = now,
                updatedAt = now
            )
        )
        recurringRuleDao.advanceNextRun(rule.id, computeNextRun(rule))
    }

    suspend fun recordAllDue(rules: List<RecurringRuleEntity>) {
        rules.forEach { recordDue(it) }
    }

    private fun computeNextRun(rule: RecurringRuleEntity): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = rule.nextRunAt }
        when (rule.frequency) {
            RecurrenceFrequency.DAILY -> cal.add(Calendar.DAY_OF_MONTH, rule.interval)
            RecurrenceFrequency.WEEKLY -> cal.add(Calendar.WEEK_OF_YEAR, rule.interval)
            RecurrenceFrequency.MONTHLY -> cal.add(Calendar.MONTH, rule.interval)
            RecurrenceFrequency.YEARLY -> cal.add(Calendar.YEAR, rule.interval)
        }
        return cal.timeInMillis
    }
}
