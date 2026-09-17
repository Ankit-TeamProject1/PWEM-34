package com.teamproject1.dailyexpensetracker.feature.wealth

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.teamproject1.dailyexpensetracker.core.database.dao.FixedDepositDao
import com.teamproject1.dailyexpensetracker.core.database.entity.FixedDepositEntity
import com.teamproject1.dailyexpensetracker.core.session.SessionManager
import com.teamproject1.dailyexpensetracker.core.wealth.FdCalculator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.IconButton

@HiltViewModel
class FdListViewModel @Inject constructor(
    fixedDepositDao: FixedDepositDao,
    session: SessionManager
) : ViewModel() {

    val fds = session.activeBookId.filterNotNull()
        .flatMapLatest { bookId -> fixedDepositDao.getActive(bookId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FdListScreen(
    onBack: () -> Unit,
    onAddNew: () -> Unit,
    onEdit: (Long) -> Unit,
    viewModel: FdListViewModel = hiltViewModel()
) {
    val fds by viewModel.fds.collectAsState()
    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy", Locale.US) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("FD & NSC") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddNew) {
                Icon(Icons.Default.Add, contentDescription = "Add FD")
            }
        }
    ) { padding ->
        if (fds.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("No fixed deposits added yet.", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            val totalPrincipal = fds.sumOf { it.principalAmount }
            val totalMaturity = fds.sumOf { FdCalculator.maturityValue(it) }

            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Column {
                            Text("Total Principal", style = MaterialTheme.typography.bodyMedium)
                            Text("₹%.0f".format(totalPrincipal), style = MaterialTheme.typography.titleLarge)
                        }
                        Column {
                            Text("Total at Maturity", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "₹%.0f".format(totalMaturity),
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                items(fds) { fd ->
                    FdCard(fd, dateFormat, onClick = { onEdit(fd.id) })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FdCard(fd: FixedDepositEntity, dateFormat: SimpleDateFormat, onClick: () -> Unit) {
    val maturityDate = FdCalculator.maturityDate(fd)
    val maturityValue = FdCalculator.maturityValue(fd)
    val currentValue = FdCalculator.currentValue(fd)
    val isMatured = System.currentTimeMillis() >= maturityDate
    // Interest isn't shown as part of the total until it's actually
    // realized — either matured (this FD) or posted as a real entry
    // (PPF's Interest entries) — per explicit request, since showing the
    // live-compounded figure as "current value" overstated what's
    // actually yours to draw on today.
    val accruedInterest = (currentValue - fd.principalAmount).coerceAtLeast(0.0)

    Card(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "${if (fd.kind == com.teamproject1.dailyexpensetracker.core.database.entity.WealthInstrumentKind.NSC) "📋 " else "🏦 "}${fd.name}",
                style = MaterialTheme.typography.titleLarge
            )
            // Bank Name + Account/Certificate Number were captured in the
            // form but never actually shown on the card — a real gap,
            // fixed here.
            if (!fd.bankName.isNullOrBlank() || !fd.accountOrCertificateNumber.isNullOrBlank()) {
                Text(
                    listOfNotNull(fd.bankName, fd.accountOrCertificateNumber).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                "₹%.0f · %.2f%% · %d months".format(fd.principalAmount, fd.interestRate, fd.tenureMonths),
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(8.dp))
            if (isMatured) {
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("Matured value", style = MaterialTheme.typography.bodyMedium)
                    Text("₹%.0f".format(currentValue), style = MaterialTheme.typography.titleMedium)
                }
            } else {
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("Principal", style = MaterialTheme.typography.bodyMedium)
                    Text("₹%.0f".format(fd.principalAmount), style = MaterialTheme.typography.titleMedium)
                }
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("Accrued interest (not yet matured)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("₹%.0f".format(accruedInterest), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text("Matures %s".format(dateFormat.format(Date(maturityDate))), style = MaterialTheme.typography.bodySmall)
                Text(
                    "₹%.0f at maturity".format(maturityValue),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
