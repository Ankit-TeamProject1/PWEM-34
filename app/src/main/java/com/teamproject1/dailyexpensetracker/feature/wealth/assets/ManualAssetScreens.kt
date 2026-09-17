package com.teamproject1.dailyexpensetracker.feature.wealth.assets

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.teamproject1.dailyexpensetracker.core.database.dao.ManualAssetDao
import com.teamproject1.dailyexpensetracker.core.database.entity.ManualAssetEntity
import com.teamproject1.dailyexpensetracker.core.session.SessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import androidx.compose.material.icons.automirrored.filled.ArrowBack

private val assetTypes = listOf("REAL_ESTATE", "VEHICLE", "OTHER")

@HiltViewModel
class ManualAssetListViewModel @Inject constructor(manualAssetDao: ManualAssetDao, session: SessionManager) : ViewModel() {
    val assets = session.activeBookId.filterNotNull()
        .flatMapLatest { bookId -> manualAssetDao.getActive(bookId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualAssetListScreen(onBack: () -> Unit, onAddNew: () -> Unit, onEdit: (Long) -> Unit, viewModel: ManualAssetListViewModel = hiltViewModel()) {
    val assets by viewModel.assets.collectAsState()

    Scaffold(
        topBar = { TopAppBar(title = { Text("Manual Assets") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } }) },
        floatingActionButton = { FloatingActionButton(onClick = onAddNew) { Icon(Icons.Default.Add, contentDescription = "Add Asset") } }
    ) { padding ->
        if (assets.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = androidx.compose.ui.Alignment.Center) {
                Text("No manual assets added yet.", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            val total = assets.sumOf { it.currentValue }
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Text("Total Value", style = MaterialTheme.typography.bodyMedium)
                    Text("₹%.0f".format(total), style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(8.dp))
                }
                items(assets) { asset ->
                    Card(modifier = Modifier.fillMaxWidth(), onClick = { onEdit(asset.id) }) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(asset.name, style = MaterialTheme.typography.titleLarge)
                            Text(asset.assetType.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(8.dp))
                            Text("₹%.0f".format(asset.currentValue), style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
        }
    }
}

@HiltViewModel
class AddEditManualAssetViewModel @Inject constructor(private val manualAssetDao: ManualAssetDao, private val session: SessionManager) : ViewModel() {
    suspend fun loadExisting(id: Long): ManualAssetEntity? {
        val bookId = session.activeBookId.value ?: return null
        return manualAssetDao.getActive(bookId).first().find { it.id == id }
    }

    fun save(existingId: Long?, name: String, assetType: String, currentValue: Double, onDone: () -> Unit) {
        viewModelScope.launch {
            val bookId = session.activeBookId.value ?: return@launch
            val entity = ManualAssetEntity(
                id = existingId ?: 0, bookId = bookId, name = name, assetType = assetType,
                currentValue = currentValue, lastUpdatedByUserAt = System.currentTimeMillis()
            )
            if (existingId == null) manualAssetDao.insert(entity) else manualAssetDao.update(entity)
            onDone()
        }
    }

    fun archive(id: Long, onDone: () -> Unit) {
        viewModelScope.launch { manualAssetDao.archive(id); onDone() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditManualAssetScreen(existingId: Long?, onBack: () -> Unit, onSaved: () -> Unit, viewModel: AddEditManualAssetViewModel = hiltViewModel()) {
    var name by remember { mutableStateOf("") }
    var assetType by remember { mutableStateOf("REAL_ESTATE") }
    var valueText by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(existingId == null) }

    LaunchedEffect(existingId) {
        if (existingId != null) {
            viewModel.loadExisting(existingId)?.let {
                name = it.name; assetType = it.assetType; valueText = it.currentValue.toString()
            }
            loaded = true
        }
    }

    val canSave = name.isNotBlank() && (valueText.toDoubleOrNull() ?: 0.0) > 0

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (existingId == null) "New Asset" else "Edit Asset") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } }
            )
        }
    ) { padding ->
        if (!loaded) return@Scaffold
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()).imePadding()) {
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name (e.g. Flat in Pitampura)") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Text("Type", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                assetTypes.forEach { t ->
                    FilterChip(
                        selected = assetType == t, onClick = { assetType = t },
                        label = { Text(t.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }) }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = valueText,
                onValueChange = { valueText = it.filter { c -> c.isDigit() || c == '.' } },
                label = { Text("Current value") },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = { viewModel.save(existingId, name, assetType, valueText.toDoubleOrNull() ?: 0.0, onSaved) },
                enabled = canSave,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Save") }
            if (existingId != null) {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { viewModel.archive(existingId, onSaved) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Archive", color = MaterialTheme.colorScheme.error)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
