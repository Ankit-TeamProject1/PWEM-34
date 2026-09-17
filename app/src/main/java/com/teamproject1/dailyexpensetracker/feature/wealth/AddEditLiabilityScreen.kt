package com.teamproject1.dailyexpensetracker.feature.wealth

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.teamproject1.dailyexpensetracker.core.database.dao.LiabilityDao
import com.teamproject1.dailyexpensetracker.core.database.entity.LiabilityEntity
import com.teamproject1.dailyexpensetracker.core.session.SessionManager
import com.teamproject1.dailyexpensetracker.ui.theme.DateField
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack

private val liabilityTypes = listOf("HOME_LOAN", "CAR_LOAN", "PERSONAL_LOAN", "CREDIT_CARD", "OTHER")

@HiltViewModel
class AddEditLiabilityViewModel @Inject constructor(
    private val liabilityDao: LiabilityDao,
    private val session: SessionManager
) : ViewModel() {

    suspend fun loadExisting(id: Long): LiabilityEntity? {
        val bookId = session.activeBookId.value ?: return null
        return liabilityDao.getActive(bookId).first().find { it.id == id }
    }

    fun save(
        existingId: Long?,
        name: String,
        liabilityType: String,
        principalAmount: Double,
        interestRate: Double,
        tenureMonths: Int,
        emiAmount: Double,
        startDate: Long,
        outstandingBalance: Double,
        onDone: () -> Unit
    ) {
        viewModelScope.launch {
            val bookId = session.activeBookId.value ?: return@launch
            val entity = LiabilityEntity(
                id = existingId ?: 0,
                bookId = bookId,
                name = name,
                liabilityType = liabilityType,
                principalAmount = principalAmount,
                interestRate = interestRate,
                tenureMonths = tenureMonths,
                emiAmount = emiAmount,
                startDate = startDate,
                outstandingBalance = outstandingBalance
            )
            if (existingId == null) liabilityDao.insert(entity) else liabilityDao.update(entity)
            onDone()
        }
    }

    fun archive(id: Long, onDone: () -> Unit) {
        viewModelScope.launch {
            liabilityDao.archive(id)
            onDone()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditLiabilityScreen(
    existingId: Long?,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    viewModel: AddEditLiabilityViewModel = hiltViewModel()
) {
    var name by remember { mutableStateOf("") }
    var liabilityType by remember { mutableStateOf("HOME_LOAN") }
    var principalText by remember { mutableStateOf("") }
    var rateText by remember { mutableStateOf("") }
    var tenureText by remember { mutableStateOf("") }
    var emiText by remember { mutableStateOf("") }
    var outstandingText by remember { mutableStateOf("") }
    var startDate by remember { mutableStateOf(System.currentTimeMillis()) }
    var loaded by remember { mutableStateOf(existingId == null) }

    LaunchedEffect(existingId) {
        if (existingId != null) {
            val existing = viewModel.loadExisting(existingId)
            if (existing != null) {
                name = existing.name
                liabilityType = existing.liabilityType
                principalText = existing.principalAmount.toString()
                rateText = existing.interestRate.toString()
                tenureText = existing.tenureMonths.toString()
                emiText = existing.emiAmount.toString()
                outstandingText = existing.outstandingBalance.toString()
                startDate = existing.startDate
            }
            loaded = true
        }
    }

    val canSave = name.isNotBlank() && principalText.toDoubleOrNull() != null &&
        outstandingText.toDoubleOrNull() != null

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (existingId == null) "New Liability" else "Edit Liability") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } }
            )
        }
    ) { padding ->
        if (!loaded) return@Scaffold

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
                .imePadding()
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name (e.g. Home Loan - HDFC)") },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(8.dp))
            Text("Type", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                liabilityTypes.forEach { t ->
                    FilterChip(
                        selected = liabilityType == t,
                        onClick = { liabilityType = t },
                        label = { Text(t.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }) }
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = principalText,
                onValueChange = { principalText = it.filter { c -> c.isDigit() || c == '.' } },
                label = { Text("Principal amount") },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = outstandingText,
                onValueChange = { outstandingText = it.filter { c -> c.isDigit() || c == '.' } },
                label = { Text("Outstanding balance") },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = emiText,
                onValueChange = { emiText = it.filter { c -> c.isDigit() || c == '.' } },
                label = { Text("EMI amount (monthly)") },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = rateText,
                    onValueChange = { rateText = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Interest rate %") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = tenureText,
                    onValueChange = { tenureText = it.filter { c -> c.isDigit() } },
                    label = { Text("Tenure (months)") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(8.dp))
            DateField(
                label = "Start date",
                selectedMillis = startDate,
                onDateSelected = { startDate = it }
            )

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = {
                    viewModel.save(
                        existingId = existingId,
                        name = name,
                        liabilityType = liabilityType,
                        principalAmount = principalText.toDoubleOrNull() ?: 0.0,
                        interestRate = rateText.toDoubleOrNull() ?: 0.0,
                        tenureMonths = tenureText.toIntOrNull() ?: 0,
                        emiAmount = emiText.toDoubleOrNull() ?: 0.0,
                        startDate = startDate,
                        outstandingBalance = outstandingText.toDoubleOrNull() ?: 0.0,
                        onDone = onSaved
                    )
                },
                enabled = canSave,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Save") }

            if (existingId != null) {
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = { viewModel.archive(existingId, onSaved) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Archive", color = MaterialTheme.colorScheme.error) }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}
