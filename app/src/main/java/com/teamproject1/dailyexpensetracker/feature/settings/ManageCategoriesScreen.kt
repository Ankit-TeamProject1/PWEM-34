package com.teamproject1.dailyexpensetracker.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.teamproject1.dailyexpensetracker.core.database.ArchiveResult
import com.teamproject1.dailyexpensetracker.core.database.RecurringRuleRepository
import com.teamproject1.dailyexpensetracker.core.database.dao.CategoryDao
import com.teamproject1.dailyexpensetracker.core.database.entity.CategoryEntity
import com.teamproject1.dailyexpensetracker.core.session.SessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import androidx.compose.material.icons.automirrored.filled.ArrowBack

sealed class CategoryArchiveOutcome {
    object Success : CategoryArchiveOutcome()
    data class Blocked(val ruleNames: List<String>) : CategoryArchiveOutcome()
}

@HiltViewModel
class ManageCategoriesViewModel @Inject constructor(
    private val categoryDao: CategoryDao,
    private val recurringRuleRepository: RecurringRuleRepository,
    private val session: SessionManager
) : ViewModel() {

    val categories = session.activeBookId.filterNotNull()
        .flatMapLatest { bookId -> categoryDao.getActiveCategories(bookId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun addCategory(name: String, icon: String, colorHex: String) {
        viewModelScope.launch {
            val activeBookId = session.activeBookId.value ?: return@launch
            categoryDao.insert(CategoryEntity(bookId = activeBookId, name = name, icon = icon, colorHex = colorHex))
        }
    }

    fun renameCategory(category: CategoryEntity, newName: String, newIcon: String, newColorHex: String) {
        viewModelScope.launch {
            categoryDao.update(category.copy(name = newName, icon = newIcon, colorHex = newColorHex))
        }
    }

    /** Archiving is blocked if an active recurring rule references this
     *  category, per the locked archive-blocking rule — reuses the same
     *  repository check the earlier archive-blocking design specified. */
    fun tryArchive(category: CategoryEntity, onResult: (CategoryArchiveOutcome) -> Unit) {
        viewModelScope.launch {
            when (val result = recurringRuleRepository.tryArchiveCategory(category.id)) {
                is ArchiveResult.Success -> onResult(CategoryArchiveOutcome.Success)
                is ArchiveResult.Blocked -> onResult(CategoryArchiveOutcome.Blocked(result.blockingRules.map { it.name }))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageCategoriesScreen(
    onBack: () -> Unit,
    viewModel: ManageCategoriesViewModel = hiltViewModel()
) {
    val categories by viewModel.categories.collectAsState()
    var editingCategory by remember { mutableStateOf<CategoryEntity?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var blockedDialogRuleNames by remember { mutableStateOf<List<String>?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Manage Categories") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add Category")
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(categories) { category ->
                CategoryRow(
                    category = category,
                    onEdit = { editingCategory = category },
                    onArchive = {
                        viewModel.tryArchive(category) { outcome ->
                            when (outcome) {
                                is CategoryArchiveOutcome.Blocked -> blockedDialogRuleNames = outcome.ruleNames
                                CategoryArchiveOutcome.Success -> Unit
                            }
                        }
                    }
                )
            }
        }
    }

    if (showAddDialog) {
        CategoryEditDialog(
            initialName = "",
            initialIcon = "📦",
            initialColorHex = "#2E5E4E",
            title = "New Category",
            onDismiss = { showAddDialog = false },
            onSave = { name, icon, colorHex ->
                viewModel.addCategory(name, icon, colorHex)
                showAddDialog = false
            }
        )
    }

    editingCategory?.let { category ->
        CategoryEditDialog(
            initialName = category.name,
            initialIcon = category.icon,
            initialColorHex = category.colorHex,
            title = "Edit Category",
            onDismiss = { editingCategory = null },
            onSave = { name, icon, colorHex ->
                viewModel.renameCategory(category, name, icon, colorHex)
                editingCategory = null
            }
        )
    }

    // Archive-blocked dialog — matches the exact copy pattern locked earlier
    // for the recurring-rule archive-blocking decision.
    blockedDialogRuleNames?.let { ruleNames ->
        AlertDialog(
            onDismissRequest = { blockedDialogRuleNames = null },
            title = { Text("Category is used by active recurring transactions") },
            text = {
                Column {
                    Text("These need your attention before you can archive this category:")
                    Spacer(Modifier.height(8.dp))
                    ruleNames.forEach { Text("🔁 $it") }
                }
            },
            confirmButton = {
                TextButton(onClick = { blockedDialogRuleNames = null }) { Text("OK") }
            }
        )
    }
}

@Composable
private fun CategoryRow(category: CategoryEntity, onEdit: () -> Unit, onArchive: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .background(parseColor(category.colorHex), CircleShape)
            )
            Spacer(Modifier.width(8.dp))
            Text("${category.icon} ${category.name}", style = MaterialTheme.typography.bodyLarge)
        }
        Row {
            TextButton(onClick = onEdit) { Text("Edit") }
            TextButton(onClick = onArchive) { Text("Archive", color = MaterialTheme.colorScheme.error) }
        }
    }
}

private val categoryColorSwatches = listOf(
    "#2E5E4E", "#C1473B", "#3D6FB4", "#D9A441", "#7B5EA7", "#2E7D5B", "#B4573D", "#5E7D9A"
)

private fun parseColor(hex: String): Color = try {
    Color(android.graphics.Color.parseColor(hex))
} catch (e: Exception) {
    Color(0xFF2E5E4E)
}

@Composable
private fun CategoryEditDialog(
    initialName: String,
    initialIcon: String,
    initialColorHex: String,
    title: String,
    onDismiss: () -> Unit,
    onSave: (String, String, String) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var icon by remember { mutableStateOf(initialIcon) }
    var colorHex by remember { mutableStateOf(initialColorHex) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(value = icon, onValueChange = { if (it.length <= 2) icon = it }, label = { Text("Icon (emoji)") })
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") })
                Spacer(Modifier.height(8.dp))
                Text("Color", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    categoryColorSwatches.forEach { swatch ->
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .background(parseColor(swatch), CircleShape)
                                .clickable { colorHex = swatch }
                                .then(
                                    if (colorHex == swatch)
                                        Modifier.border(2.dp, Color.Black, CircleShape)
                                    else Modifier
                                )
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) onSave(name, icon.ifBlank { "📦" }, colorHex) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
