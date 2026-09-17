package com.teamproject1.dailyexpensetracker.core.database

import com.teamproject1.dailyexpensetracker.core.database.dao.AccountBalance
import com.teamproject1.dailyexpensetracker.core.database.dao.CategoryTotal
import com.teamproject1.dailyexpensetracker.core.database.dao.TransactionDao
import com.teamproject1.dailyexpensetracker.core.database.entity.TransactionEntity
import com.teamproject1.dailyexpensetracker.core.session.SessionManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Feature ViewModels NEVER call TransactionDao directly and never pass a
 * bookId themselves. They go through this repository, which always resolves
 * the active book from the live session. This is what makes it structurally
 * impossible to query or write to the wrong book — there is no code path
 * that bypasses SessionManager.activeBookId.
 */
@Singleton
class TransactionRepository @Inject constructor(
    private val dao: TransactionDao,
    private val session: SessionManager
) {
    fun getAll(): Flow<List<TransactionEntity>> =
        session.activeBookId.filterNotNull().flatMapLatest { bookId -> dao.getAll(bookId) }

    fun getAccountBalances(): Flow<List<AccountBalance>> =
        session.activeBookId.filterNotNull().flatMapLatest { bookId -> dao.getAccountBalances(bookId) }

    fun getCategoryTotalsForMonth(month: String): Flow<List<CategoryTotal>> =
        session.activeBookId.filterNotNull().flatMapLatest { bookId ->
            dao.getCategoryTotalsForMonth(bookId, month)
        }

    fun getTodaySpend(startOfDayMillis: Long, endOfDayMillis: Long): Flow<Double> =
        session.activeBookId.filterNotNull().flatMapLatest { bookId ->
            dao.getTodaySpend(bookId, startOfDayMillis, endOfDayMillis)
        }

    fun getMonthSpend(month: String): Flow<Double> =
        session.activeBookId.filterNotNull().flatMapLatest { bookId ->
            dao.getMonthSpend(bookId, month)
        }

    /** Guards against a stale bookId ever being written from a screen that
     *  hasn't picked up a book switch yet — asserts rather than trusting UI state.
     *  Returns the new row's id — needed so a caller (e.g. marking an
     *  expense as an Account Receivable) can link to the transaction it
     *  just created, rather than the return value being silently discarded. */
    suspend fun addTransaction(transaction: TransactionEntity): Long {
        val activeBookId = session.activeBookId.filterNotNull().first()
        require(transaction.bookId == activeBookId) {
            "Transaction bookId (${transaction.bookId}) does not match active session book ($activeBookId)"
        }
        return dao.insert(transaction)
    }

    suspend fun updateTransaction(transaction: TransactionEntity) {
        val activeBookId = session.activeBookId.filterNotNull().first()
        require(transaction.bookId == activeBookId) {
            "Transaction bookId (${transaction.bookId}) does not match active session book ($activeBookId)"
        }
        dao.update(transaction)
    }

    suspend fun softDelete(transactionId: Long) {
        val activeBookId = session.activeBookId.filterNotNull().first()
        dao.softDelete(transactionId, activeBookId, System.currentTimeMillis())
    }
}
