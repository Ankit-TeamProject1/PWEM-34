package com.teamproject1.dailyexpensetracker.feature.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.teamproject1.dailyexpensetracker.core.database.dao.BookDao
import com.teamproject1.dailyexpensetracker.core.database.entity.BookEntity
import com.teamproject1.dailyexpensetracker.core.session.SessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Resolves the active book's details reactively — follows the same
 * flatMapLatest-on-activeBookId pattern as TransactionRepository, so if the
 * active book changes mid-session, this updates automatically along with
 * every other screen reading from SessionManager.
 */
@HiltViewModel
class DashboardHostViewModel @Inject constructor(
    private val bookDao: BookDao,
    session: SessionManager
) : ViewModel() {

    val activeBook = session.activeBookId
        .filterNotNull()
        .flatMapLatest { bookId ->
            flow<BookEntity?> { emit(bookDao.getById(bookId)) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
}
