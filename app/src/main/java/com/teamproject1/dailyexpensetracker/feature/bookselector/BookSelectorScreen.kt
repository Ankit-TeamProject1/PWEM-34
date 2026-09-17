package com.teamproject1.dailyexpensetracker.feature.bookselector

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.teamproject1.dailyexpensetracker.core.database.dao.BookDao
import com.teamproject1.dailyexpensetracker.core.database.entity.BookEntity
import com.teamproject1.dailyexpensetracker.core.database.entity.BookType
import com.teamproject1.dailyexpensetracker.core.session.SessionManager
import com.teamproject1.dailyexpensetracker.ui.theme.AppIcon3D
import com.teamproject1.dailyexpensetracker.ui.theme.DottedTextureBackground
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BookSelectorViewModel @Inject constructor(
    private val bookDao: BookDao,
    private val session: SessionManager
) : ViewModel() {

    val books = bookDao.getActiveBooks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun selectBook(bookId: Long, onSelected: () -> Unit) {
        viewModelScope.launch {
            bookDao.touchLastOpened(bookId, System.currentTimeMillis())
            session.setActiveBook(bookId)
            session.persistLastUsedBook(bookId)
            onSelected()
        }
    }

    fun renameBook(bookId: Long, newName: String) {
        viewModelScope.launch {
            val book = bookDao.getById(bookId) ?: return@launch
            bookDao.update(book.copy(name = newName))
        }
    }

    /** Archive is the safe default for "delete" from this screen — matches
     *  the locked soft-delete rule. True hard-delete, with the "type the
     *  book name to confirm" flow, lives in the Archived Items screen and
     *  is only reachable on an already-archived book. */
    fun archiveBook(bookId: Long, onArchived: () -> Unit) {
        viewModelScope.launch {
            bookDao.archive(bookId)
            // If the archived book was active, clear session so the Dashboard
            // route (if somehow still reachable) redirects back here — reuses
            // the same null-activeBookId path as the zero-books empty state.
            if (session.activeBookId.value == bookId) {
                session.clearActiveBook()
            }
            onArchived()
        }
    }
}

/**
 * Grid of book cards, sorted most-recently-opened first. Long-press OR the
 * visible "⋮" menu icon opens Rename/Archive; permanent Delete lives in
 * Settings → Archived Items, reachable only after archiving a book first
 * (requires typing the book's name to confirm, since it's irreversible).
 *
 * Empty state here is the "lighter" version (no starting-balance prompt) —
 * used both on a fresh install with zero books AND after deleting the last
 * remaining book, per the locked decision to reuse one path for both.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun BookSelectorScreen(
    isCompact: Boolean,
    onBookSelected: () -> Unit,
    onCreateNewBook: () -> Unit,
    viewModel: BookSelectorViewModel = hiltViewModel()
) {
    val books by viewModel.books.collectAsState()
    var managingBook by remember { mutableStateOf<BookEntity?>(null) }
    var renamingBook by remember { mutableStateOf<BookEntity?>(null) }
    var archiveConfirmBook by remember { mutableStateOf<BookEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Your Books") })
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onCreateNewBook) {
                Icon(Icons.Default.Add, contentDescription = "New Book")
            }
        },
        containerColor = androidx.compose.ui.graphics.Color.Transparent
    ) { padding ->
        if (books.isEmpty()) {
            EmptyBookState(modifier = Modifier.padding(padding), onCreateNewBook = onCreateNewBook)
        } else {
            DottedTextureBackground(modifier = Modifier.padding(padding)) {
                val columns = if (isCompact) 3 else 4
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    contentPadding = PaddingValues(20.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(books) { book ->
                        BookCard(
                            book = book,
                            onClick = { viewModel.selectBook(book.id, onBookSelected) },
                            onLongPress = { managingBook = book },
                            onMenuClick = { managingBook = book }
                        )
                    }
                }
            }
        }
    }

    // Long-press management sheet: Rename / Archive
    managingBook?.let { book ->
        ModalBottomSheet(onDismissRequest = { managingBook = null }) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(book.name, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(16.dp))
                TextButton(
                    onClick = {
                        renamingBook = book
                        managingBook = null
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Rename") }
                TextButton(
                    onClick = {
                        archiveConfirmBook = book
                        managingBook = null
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Archive", color = MaterialTheme.colorScheme.error) }
                Spacer(Modifier.height(8.dp))
                Text(
                    "To permanently delete a book, archive it first, then go to Settings → Archived Items.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    // Rename dialog
    renamingBook?.let { book ->
        var newName by remember(book.id) { mutableStateOf(book.name) }
        AlertDialog(
            onDismissRequest = { renamingBook = null },
            title = { Text("Rename Book") },
            text = {
                OutlinedTextField(value = newName, onValueChange = { newName = it }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newName.isNotBlank()) viewModel.renameBook(book.id, newName)
                    renamingBook = null
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { renamingBook = null }) { Text("Cancel") }
            }
        )
    }

    // Archive confirmation — soft-delete only, per the locked rule
    archiveConfirmBook?.let { book ->
        AlertDialog(
            onDismissRequest = { archiveConfirmBook = null },
            title = { Text("Archive \"${book.name}\"?") },
            text = { Text("This book will be hidden from your list. Its data is kept, not deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.archiveBook(book.id) {}
                    archiveConfirmBook = null
                }) { Text("Archive", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { archiveConfirmBook = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun BookCard(book: BookEntity, onClick: () -> Unit, onLongPress: () -> Unit, onMenuClick: () -> Unit) {
    val typeBadge = if (book.bookType == BookType.WEALTH) "📈" else "💰"
    Box {
        AppIcon3D(
            label = book.name,
            emoji = typeBadge,
            onClick = onClick,
            onLongClick = onLongPress
        )
        // Visible menu affordance alongside long-press — per explicit
        // request, since long-press alone wasn't discoverable enough for
        // Rename/Archive/Delete.
        IconButton(
            onClick = onMenuClick,
            modifier = Modifier.align(Alignment.TopEnd).size(28.dp)
        ) {
            Icon(Icons.Default.MoreVert, contentDescription = "Book options", modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun EmptyBookState(modifier: Modifier = Modifier, onCreateNewBook: () -> Unit) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("📖", style = MaterialTheme.typography.displayLarge)
        Spacer(Modifier.height(16.dp))
        Text("No books yet", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Create a book to start tracking your expenses or wealth.",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onCreateNewBook) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Create your first book")
        }
    }
}
