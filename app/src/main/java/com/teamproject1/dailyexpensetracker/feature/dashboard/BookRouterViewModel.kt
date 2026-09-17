package com.teamproject1.dailyexpensetracker.feature.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.teamproject1.dailyexpensetracker.core.database.dao.BookDao
import com.teamproject1.dailyexpensetracker.core.database.entity.BookType
import com.teamproject1.dailyexpensetracker.core.session.SessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * A book is either EXPENSE or WEALTH type, chosen at creation, with
 * entirely separate Dashboards. Both Book Selector and Create First Book
 * land here after setting the active book, rather than needing their own
 * callback signatures changed to carry the book type — this one small
 * redirect screen reads it and routes accordingly.
 */
@HiltViewModel
class BookRouterViewModel @Inject constructor(
    private val bookDao: BookDao,
    private val session: SessionManager
) : ViewModel() {

    fun determineDestination(onResolved: (isWealth: Boolean) -> Unit) {
        viewModelScope.launch {
            val bookId = session.activeBookId.value
            val book = bookId?.let { bookDao.getById(it) }
            onResolved(book?.bookType == BookType.WEALTH)
        }
    }
}
