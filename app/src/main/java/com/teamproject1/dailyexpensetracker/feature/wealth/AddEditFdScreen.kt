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
import com.teamproject1.dailyexpensetracker.core.database.dao.FixedDepositDao
import com.teamproject1.dailyexpensetracker.core.database.entity.AutoRenewMode
import com.teamproject1.dailyexpensetracker.core.database.entity.CompoundingFrequency
import com.teamproject1.dailyexpensetracker.core.database.entity.FixedDepositEntity
import com.teamproject1.dailyexpensetracker.core.database.entity.InterestType
import com.teamproject1.dailyexpensetracker.core.database.entity.WealthInstrumentKind
import com.teamproject1.dailyexpensetracker.core.session.SessionManager
import com.teamproject1.dailyexpensetracker.core.wealth.FdCalculator
import com.teamproject1.dailyexpensetracker.ui.theme.DateField
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack

@HiltViewModel
class AddEditFdViewModel @Inject constructor(
    private val fixedDepositDao: FixedDepositDao,
    private val session: SessionManager
) : ViewModel() {

    suspend fun loadExisting(id: Long): FixedDepositEntity? {
        val bookId = session.activeBookId.value ?: return null
        return fixedDepositDao.getActive(bookId).first().find { it.id == id }
    }

    fun save(
        existingId: Long?,
        name: String,
        kind: WealthInstrumentKind,
        bankName: String,
        accountNumber: String,
        principalAmount: Double,
        startDate: Long,
        tenureMonths: Int,
        tenureExtraDays: Int,
        interestRate: Double,
        interestType: InterestType,
        compoundingFrequency: CompoundingFrequency,
        autoRenewMode: AutoRenewMode,
        onDone: () -> Unit
    ) {
        viewModelScope.launch {
            val bookId = session.activeBookId.value ?: return@launch
            val entity = FixedDepositEntity(
                id = existingId ?: 0,
                bookId = bookId,
                name = name,
                kind = kind,
                bankName = bankName.ifBlank { null },
                accountOrCertificateNumber = accountNumber.ifBlank { null },
                principalAmount = principalAmount,
                startDate = startDate,
                tenureMonths = tenureMonths,
                tenureExtraDays = tenureExtraDays,
                interestRate = interestRate,
                interestType = interestType,
                compoundingFrequency = compoundingFrequency,
                autoRenewMode = autoRenewMode
            )
            if (existingId == null) fixedDepositDao.insert(entity) else fixedDepositDao.update(entity)
            onDone()
        }
    }

    fun archive(id: Long, onDone: () -> Unit) {
        viewModelScope.launch {
            fixedDepositDao.archive(id)
            onDone()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditFdScreen(
    existingId: Long?,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    viewModel: AddEditFdViewModel = hiltViewModel()
) {
    var bankName by remember { mutableStateOf("") }
    var accountNumber by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(WealthInstrumentKind.FD) }
    var principalText by remember { mutableStateOf("") }
    var rateText by remember { mutableStateOf("") }
    // Tenure as Years + Months + Days, per explicit request — composes
    // into tenureMonths (whole months, unchanged semantics for backward
    // compatibility with existing data) + tenureExtraDays (new field).
    var tenureYearsText by remember { mutableStateOf("") }
    var tenureMonthsText by remember { mutableStateOf("") }
    var tenureDaysText by remember { mutableStateOf("") }
    var startDate by remember { mutableStateOf(System.currentTimeMillis()) }
    var interestType by remember { mutableStateOf(InterestType.COMPOUND) }
    var compoundingFrequency by remember { mutableStateOf(CompoundingFrequency.CUMULATIVE) }
    var autoRenewMode by remember { mutableStateOf(AutoRenewMode.DISABLED) }
    var loaded by remember { mutableStateOf(existingId == null) }
    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy", Locale.US) }

    LaunchedEffect(existingId) {
        if (existingId != null) {
            val existing = viewModel.loadExisting(existingId)
            if (existing != null) {
                bankName = existing.bankName ?: ""
                accountNumber = existing.accountOrCertificateNumber ?: ""
                kind = existing.kind
                principalText = existing.principalAmount.toString()
                rateText = existing.interestRate.toString()
                // Decompose stored total months back into Years + Months
                // for editing, matching how they were composed on save.
                tenureYearsText = (existing.tenureMonths / 12).toString()
                tenureMonthsText = (existing.tenureMonths % 12).toString()
                tenureDaysText = existing.tenureExtraDays.toString()
                startDate = existing.startDate
                interestType = existing.interestType
                compoundingFrequency = existing.compoundingFrequency
                autoRenewMode = existing.autoRenewMode
            }
            loaded = true
        }
    }

    val principal = principalText.toDoubleOrNull() ?: 0.0
    val rate = rateText.toDoubleOrNull() ?: 0.0
    val tenureYears = tenureYearsText.toIntOrNull() ?: 0
    val tenureMonthsPart = tenureMonthsText.toIntOrNull() ?: 0
    val tenureExtraDays = tenureDaysText.toIntOrNull() ?: 0
    val tenure = tenureYears * 12 + tenureMonthsPart
    // Name is now always the combination of Bank Name and Account
    // Number, per explicit request, computed rather than typed
    // separately.
    val name = listOf(bankName, accountNumber).filter { it.isNotBlank() }.joinToString(" ")
    val canSave = name.isNotBlank() && principal > 0 && rate > 0 && (tenure > 0 || tenureExtraDays > 0)

    // Live preview using the same FdCalculator the list screen uses — lets
    // the user see the maturity value update as they type, before saving.
    val previewFd = remember(principal, rate, tenure, tenureExtraDays, startDate, interestType, compoundingFrequency) {
        if (canSave) FixedDepositEntity(
            bookId = 0, name = "", kind = kind, principalAmount = principal, startDate = startDate,
            tenureMonths = tenure, tenureExtraDays = tenureExtraDays, interestRate = rate, interestType = interestType,
            compoundingFrequency = compoundingFrequency
        ) else null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (existingId == null) "New FD / NSC" else "Edit FD / NSC") },
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
            Text("Type", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            SingleChoiceSegmentedButtonRow {
                listOf("Fixed Deposit" to WealthInstrumentKind.FD, "NSC" to WealthInstrumentKind.NSC).forEachIndexed { index, (label, k) ->
                    SegmentedButton(
                        selected = kind == k,
                        onClick = { kind = k },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = 2)
                    ) { Text(label) }
                }
            }

            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = bankName,
                onValueChange = { bankName = it },
                label = { Text(if (kind == WealthInstrumentKind.NSC) "Post Office / Bank Name" else "Bank Name") },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = accountNumber,
                onValueChange = { accountNumber = it },
                label = { Text(if (kind == WealthInstrumentKind.NSC) "Certificate Number" else "Account Number") },
                modifier = Modifier.fillMaxWidth()
            )
            // Name field removed — per explicit request, Name is now
            // always the combination of Bank Name and Account Number,
            // computed automatically rather than typed separately.
            if (bankName.isNotBlank() || accountNumber.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Will be saved as: \"$name\"",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
                value = rateText,
                onValueChange = { rateText = it.filter { c -> c.isDigit() || c == '.' } },
                label = { Text("Interest rate %") },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(8.dp))
            Text("Tenure", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = tenureYearsText,
                    onValueChange = { tenureYearsText = it.filter { c -> c.isDigit() } },
                    label = { Text("Years") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = tenureMonthsText,
                    onValueChange = { tenureMonthsText = it.filter { c -> c.isDigit() } },
                    label = { Text("Months") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = tenureDaysText,
                    onValueChange = { tenureDaysText = it.filter { c -> c.isDigit() } },
                    label = { Text("Days") },
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

            Spacer(Modifier.height(8.dp))
            Text("Interest type", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            SingleChoiceSegmentedButtonRow {
                listOf("Simple" to InterestType.SIMPLE, "Compound" to InterestType.COMPOUND).forEachIndexed { index, (label, t) ->
                    SegmentedButton(
                        selected = interestType == t,
                        onClick = { interestType = t },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = 2)
                    ) { Text(label) }
                }
            }

            if (interestType == InterestType.COMPOUND) {
                Spacer(Modifier.height(8.dp))
                Text("Compounding frequency", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    CompoundingFrequency.values().forEach { f ->
                        FilterChip(
                            selected = compoundingFrequency == f,
                            onClick = { compoundingFrequency = f },
                            label = { Text(f.name.lowercase().replaceFirstChar { it.uppercase() }) }
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Text("Auto-Renew on Maturity", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                listOf(
                    "Disabled" to AutoRenewMode.DISABLED,
                    "Principal Only" to AutoRenewMode.PRINCIPAL_ONLY,
                    "Principal + Interest" to AutoRenewMode.PRINCIPAL_PLUS_INTEREST
                ).forEach { (label, mode) ->
                    FilterChip(
                        selected = autoRenewMode == mode,
                        onClick = { autoRenewMode = mode },
                        label = { Text(label) }
                    )
                }
            }
            if (autoRenewMode != AutoRenewMode.DISABLED) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "On maturity, you'll get a confirmation popup with the proposed renewal — nothing renews automatically without your confirmation.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (previewFd != null) {
                Spacer(Modifier.height(16.dp))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Preview", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                            Text("Matures", style = MaterialTheme.typography.bodyMedium)
                            Text(dateFormat.format(Date(FdCalculator.maturityDate(previewFd))), style = MaterialTheme.typography.bodyMedium)
                        }
                        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                            Text("Maturity value", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "₹%.2f".format(FdCalculator.maturityValue(previewFd)),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = {
                    viewModel.save(
                        existingId = existingId,
                        name = name,
                        kind = kind,
                        bankName = bankName,
                        accountNumber = accountNumber,
                        principalAmount = principal,
                        startDate = startDate,
                        tenureMonths = tenure,
                        tenureExtraDays = tenureExtraDays,
                        interestRate = rate,
                        interestType = interestType,
                        compoundingFrequency = compoundingFrequency,
                        autoRenewMode = autoRenewMode,
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
