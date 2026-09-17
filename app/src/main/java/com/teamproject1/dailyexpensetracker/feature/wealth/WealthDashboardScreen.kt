package com.teamproject1.dailyexpensetracker.feature.wealth

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.teamproject1.dailyexpensetracker.core.database.dao.ApyDao
import com.teamproject1.dailyexpensetracker.core.database.dao.CashInHandDao
import com.teamproject1.dailyexpensetracker.core.database.dao.DematHoldingDao
import com.teamproject1.dailyexpensetracker.core.database.dao.EpfDao
import com.teamproject1.dailyexpensetracker.core.database.dao.FixedDepositDao
import com.teamproject1.dailyexpensetracker.core.database.dao.InsuranceDao
import com.teamproject1.dailyexpensetracker.core.database.dao.LiabilityDao
import com.teamproject1.dailyexpensetracker.core.database.dao.ManualAssetDao
import com.teamproject1.dailyexpensetracker.core.database.dao.NpsDao
import com.teamproject1.dailyexpensetracker.core.database.dao.PpfDao
import com.teamproject1.dailyexpensetracker.core.database.dao.WealthRateHistoryDao
import com.teamproject1.dailyexpensetracker.core.database.entity.RateInstrument
import com.teamproject1.dailyexpensetracker.core.session.SessionManager
import com.teamproject1.dailyexpensetracker.core.wealth.FdCalculator
import com.teamproject1.dailyexpensetracker.core.wealth.PpfEpfCalculator
import com.teamproject1.dailyexpensetracker.ui.theme.AppIcon3D
import com.teamproject1.dailyexpensetracker.ui.theme.DottedTextureBackground
import com.teamproject1.dailyexpensetracker.ui.theme.ExpenseRed
import com.teamproject1.dailyexpensetracker.ui.theme.IncomeGreen
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * PPF and EPF are deliberately NOT part of this data class / the combine()
 * below — their value depends on per-account deposit/contribution history
 * run through PpfEpfCalculator, which doesn't fit cleanly into a flat
 * combine() of simple entity lists the way FD/Liabilities/Cash/Demat and
 * the newer simple categories do. They're computed separately (see
 * ppfEpfTotal below) and added into the final displayed Net Worth.
 */
data class NetWorthBreakdown(
    val fdValue: Double,
    val rdValue: Double,
    val dematValue: Double,
    val cashInHand: Double,
    val npsValue: Double,
    val apyValue: Double,
    val insuranceCashValue: Double,
    val manualAssetsValue: Double,
    val bankValue: Double,
    val receivableValue: Double,
    val mutualFundValue: Double,
    val kamettiValue: Double,
    val metalValue: Double,
    val liabilities: Double
) {
    val totalAssets get() = fdValue + rdValue + dematValue + cashInHand + npsValue + apyValue + insuranceCashValue + manualAssetsValue + bankValue + receivableValue + mutualFundValue + kamettiValue + metalValue
    val netWorth get() = totalAssets - liabilities
}

/**
 * Per-category card data for the Wealth Dashboard tiles — per the locked
 * design ("Make the tile card style and show"): each card shows its own
 * current valuation, % gain/loss where a cost basis genuinely exists, and
 * its % of total assets (allocation). Categories with no natural "cost"
 * concept (NPS, APY, Insurance, Manual Assets, Cash in Hand — all manually
 * entered current-value snapshots) show valuation + allocation only, no
 * gain/loss figure, since inventing one would be misleading.
 */
data class WealthCategorySummary(
    val label: String,
    val emoji: String,
    val currentValue: Double,
    val gainLossPercent: Double?,
    val allocationPercent: Double
)

@HiltViewModel
class WealthDashboardViewModel @Inject constructor(
    fixedDepositDao: FixedDepositDao,
    private val recurringDepositDaoRef: com.teamproject1.dailyexpensetracker.core.database.dao.RecurringDepositDao,
    private val rdInstallmentDao: com.teamproject1.dailyexpensetracker.core.database.dao.RdInstallmentDao,
    liabilityDao: LiabilityDao,
    cashInHandDao: CashInHandDao,
    dematHoldingDao: DematHoldingDao,
    private val dematHoldingDaoRef: DematHoldingDao,
    private val stockPriceFetcher: com.teamproject1.dailyexpensetracker.core.wealth.StockPriceFetcher,
    npsDao: NpsDao,
    apyDao: ApyDao,
    insuranceDao: InsuranceDao,
    manualAssetDao: ManualAssetDao,
    private val bankBalanceDao: com.teamproject1.dailyexpensetracker.core.database.dao.BankBalanceDao,
    private val wealthReceivableDao: com.teamproject1.dailyexpensetracker.core.database.dao.WealthReceivableDao,
    private val ppfDao: PpfDao,
    private val epfDao: EpfDao,
    private val mutualFundDao: com.teamproject1.dailyexpensetracker.core.database.dao.MutualFundDao,
    private val kamettiDao: com.teamproject1.dailyexpensetracker.core.database.dao.KamettiDao,
    private val metalHoldingDao: com.teamproject1.dailyexpensetracker.core.database.dao.MetalHoldingDao,
    private val rateHistoryDao: WealthRateHistoryDao,
    private val session: SessionManager
) : ViewModel() {

    // (currentValue, costBasis) pairs — costBasis is what "% gain/loss" is
    // measured against for each category that has a genuine one. RD is
    // deliberately NOT here — its value now depends on real installment
    // entries fetched per-account (same reason PPF/EPF live outside this
    // synchronous combine), computed reactively via rdReactive below.
    // FD's live value is split into principal (used for Net Worth) and
    // accrued-but-unmatured interest (shown separately), per explicit
    // request — once an FD matures, its full value IS realized (maturity
    // is the "posting" event), so the split only applies before that.
    private val simpleBase = session.activeBookId.filterNotNull()
        .flatMapLatest { bookId ->
            combine(
                fixedDepositDao.getActive(bookId),
                liabilityDao.getActive(bookId),
                cashInHandDao.get(bookId),
                dematHoldingDao.getActiveForBook(bookId)
            ) { fds, liabilities, cash, demat ->
                val now = System.currentTimeMillis()
                var fdNetWorthValue = 0.0
                var fdAccruedInterest = 0.0
                fds.forEach { fd ->
                    val liveValue = FdCalculator.currentValue(fd)
                    val isMatured = now >= FdCalculator.maturityDate(fd)
                    if (isMatured) {
                        fdNetWorthValue += liveValue
                    } else {
                        fdNetWorthValue += fd.principalAmount
                        fdAccruedInterest += (liveValue - fd.principalAmount).coerceAtLeast(0.0)
                    }
                }
                listOf(
                    fdNetWorthValue,
                    // Falls back to invested value (units × avg buy
                    // price) when no price has been fetched yet, per
                    // explicit request — a holding with no LTP previously
                    // contributed zero to Net Worth, understating it
                    // rather than reflecting "at least worth what was
                    // put in" until a real price is available.
                    demat.sumOf { it.unitsHeld * (it.lastFetchedPrice ?: it.avgBuyPrice) },
                    cash?.amount ?: 0.0,
                    liabilities.sumOf { it.outstandingBalance },
                    fds.sumOf { it.principalAmount },
                    demat.filter { it.avgBuyPrice > 0 }.sumOf { it.unitsHeld * it.avgBuyPrice },
                    fdAccruedInterest
                )
            }
        }

    private val receivablesFlow = session.activeBookId.filterNotNull()
        .flatMapLatest { bookId -> wealthReceivableDao.getActive(bookId) }
        .map { receivables -> receivables.sumOf { it.currentAmount } }

    private val simpleExtras = session.activeBookId.filterNotNull()
        .flatMapLatest { bookId ->
            combine(
                npsDao.getActive(bookId),
                apyDao.getActive(bookId),
                insuranceDao.getActive(bookId),
                manualAssetDao.getActive(bookId),
                bankBalanceDao.getActive(bookId)
            ) { nps, apy, insurance, assets, banks ->
                listOf(
                    nps.sumOf { it.currentValue },
                    apy.sumOf { it.totalContributedSoFar },
                    insurance.filter { it.hasCashValue }.sumOf { it.currentSurrenderValue },
                    assets.sumOf { it.currentValue },
                    banks.sumOf { it.currentBalance }
                )
            }
        }

    // Cost-basis figures (index-aligned with simpleBase's tail: fdCost,
    // dematCost — RD's cost basis now tracked separately via _rdCost).
    private val costBasis: StateFlow<List<Double>> = simpleBase
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), listOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0))
    val fdAccruedInterest: StateFlow<Double> = costBasis.map { it.getOrElse(6) { 0.0 } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)

    // Reactive per-category totals — this was a real, systemic bug: the
    // old version computed these ONCE via a one-shot suspend call that
    // only re-ran when you switched books, never when an entry was
    // added/edited/deleted within the CURRENT book. The Net Worth figure
    // (and these categories' own Dashboard cards) would silently go
    // stale after any PPF/EPF/RD entry change until the app was
    // restarted or the book was switched away and back. Declared here,
    // BEFORE netWorth, since netWorth's combine() depends on _rdTotal —
    // Kotlin initializes class properties in declaration order, so this
    // ordering is required, not just stylistic.
    // Splits principal from unposted accrued interest, per explicit
    // request — a live-compounded value was previously shown as the full
    // "current value", which included interest that hasn't actually been
    // posted (via a real entry) or realized (via maturity) yet. Once an
    // RD matures, its full value IS realized (maturity is the "posting"
    // event for this instrument), so no further split applies past that
    // point.
    private val rdReactive: kotlinx.coroutines.flow.Flow<Triple<Double, Double, Double>> = session.activeBookId.filterNotNull()
        .flatMapLatest { bookId -> recurringDepositDaoRef.getActive(bookId) }
        .flatMapLatest { accounts ->
            if (accounts.isEmpty()) {
                kotlinx.coroutines.flow.flowOf(Triple(0.0, 0.0, 0.0))
            } else {
                combine(accounts.map { account -> rdInstallmentDao.getInstallments(account.id).map { account to it } }) { pairs ->
                    var netWorthValue = 0.0
                    var cost = 0.0
                    var accruedInterest = 0.0
                    val now = System.currentTimeMillis()
                    pairs.forEach { (account, installments) ->
                        val rawPrincipal = installments.sumOf { it.amount }
                        val liveValue = if (installments.isNotEmpty()) {
                            com.teamproject1.dailyexpensetracker.core.wealth.RdCalculator.currentValueFromEntries(account, installments)
                        } else {
                            com.teamproject1.dailyexpensetracker.core.wealth.RdCalculator.currentValue(account)
                        }
                        val isMatured = now >= com.teamproject1.dailyexpensetracker.core.wealth.RdCalculator.maturityDate(account)
                        if (isMatured) {
                            netWorthValue += liveValue
                        } else {
                            netWorthValue += rawPrincipal
                            accruedInterest += (liveValue - rawPrincipal).coerceAtLeast(0.0)
                        }
                        cost += rawPrincipal
                    }
                    Triple(netWorthValue, cost, accruedInterest)
                }
            }
        }
    private val _rdTotal: StateFlow<Double> = rdReactive.map { it.first }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)
    private val _rdCost: StateFlow<Double> = rdReactive.map { it.second }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)
    val rdAccruedInterest: StateFlow<Double> = rdReactive.map { it.third }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)

    // Splits principal (raw deposits/deductions PLUS any already-posted
    // Interest entries — both are "real" once recorded) from live,
    // not-yet-posted accrued interest, per explicit request. The
    // compounding calculation itself is unchanged; only which figure
    // feeds Net Worth changes.
    private val ppfReactive: kotlinx.coroutines.flow.Flow<Triple<Double, Double, Double>> = session.activeBookId.filterNotNull()
        .flatMapLatest { bookId -> ppfDao.getActiveAccounts(bookId) }
        .flatMapLatest { accounts ->
            if (accounts.isEmpty()) {
                kotlinx.coroutines.flow.flowOf(Triple(0.0, 0.0, 0.0))
            } else {
                combine(accounts.map { account -> ppfDao.getDeposits(account.id) }) { lists ->
                    val ppfRates = rateHistoryDao.getHistoryOnce(RateInstrument.PPF)
                    var netWorthValue = 0.0
                    var deposited = 0.0
                    var accruedInterest = 0.0
                    lists.forEach { entries ->
                        val filtered = entries.filter { it.type != com.teamproject1.dailyexpensetracker.core.database.entity.PpfEntryType.INTEREST }.map { it.depositDate to it.amount }
                        val principal = entries.sumOf { it.amount } // includes posted Interest entries — those are already "real"
                        val liveValue = PpfEpfCalculator.currentValue(filtered, ppfRates)
                        deposited += filtered.sumOf { it.second }
                        netWorthValue += principal
                        accruedInterest += (liveValue - principal).coerceAtLeast(0.0)
                    }
                    Triple(netWorthValue, deposited, accruedInterest)
                }
            }
        }
    private val _ppfTotal: StateFlow<Double> = ppfReactive.map { it.first }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)
    private val _ppfDeposited: StateFlow<Double> = ppfReactive.map { it.second }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)
    val ppfAccruedInterest: StateFlow<Double> = ppfReactive.map { it.third }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)

    // Splits Employee+Employer principal from live, not-yet-posted
    // accrued interest, per explicit request — Pension is unaffected
    // since it was already a plain sum with no interest to split out.
    private val epfReactive: kotlinx.coroutines.flow.Flow<Triple<Double, Double, Double>> = session.activeBookId.filterNotNull()
        .flatMapLatest { bookId -> epfDao.getActiveAccounts(bookId) }
        .flatMapLatest { accounts ->
            if (accounts.isEmpty()) {
                kotlinx.coroutines.flow.flowOf(Triple(0.0, 0.0, 0.0))
            } else {
                combine(accounts.map { account -> epfDao.getContributions(account.id) }) { lists ->
                    val epfRates = rateHistoryDao.getHistoryOnce(RateInstrument.EPF)
                    var netWorthValue = 0.0
                    var contributed = 0.0
                    var accruedInterest = 0.0
                    lists.forEach { all ->
                        val eeContributions = all.filter { it.tileType == com.teamproject1.dailyexpensetracker.core.database.entity.EpfTileType.EMPLOYEE_EMPLOYER }.map { it.contributionDate to it.amount }
                        val pensionTotal = all.filter { it.tileType == com.teamproject1.dailyexpensetracker.core.database.entity.EpfTileType.PENSION }.sumOf { it.amount }
                        val eePrincipal = eeContributions.sumOf { it.second }
                        val eeLiveValue = PpfEpfCalculator.currentValue(eeContributions, epfRates)
                        contributed += eePrincipal
                        netWorthValue += eePrincipal + pensionTotal
                        accruedInterest += (eeLiveValue - eePrincipal).coerceAtLeast(0.0)
                    }
                    Triple(netWorthValue, contributed, accruedInterest)
                }
            }
        }
    private val _epfTotal: StateFlow<Double> = epfReactive.map { it.first }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)
    private val _epfContributed: StateFlow<Double> = epfReactive.map { it.second }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)
    val epfAccruedInterest: StateFlow<Double> = epfReactive.map { it.third }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)

    val ppfEpfTotal: StateFlow<Double> = combine(_ppfTotal, _epfTotal) { p, e -> p + e }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)

    private val mutualFundReactive: kotlinx.coroutines.flow.Flow<Pair<Double, Double>> = session.activeBookId.filterNotNull()
        .flatMapLatest { bookId -> mutualFundDao.getActiveAccounts(bookId) }
        .flatMapLatest { accounts ->
            if (accounts.isEmpty()) {
                kotlinx.coroutines.flow.flowOf(0.0 to 0.0)
            } else {
                combine(accounts.map { account -> mutualFundDao.getEntries(account.id).map { account to it } }) { pairs ->
                    var total = 0.0
                    var invested = 0.0
                    pairs.forEach { (account, entries) ->
                        val units = entries.sumOf { it.unitsAllotted }
                        total += units * (account.lastFetchedNav ?: 0.0)
                        invested += entries.sumOf { it.investedAmount }
                    }
                    total to invested
                }
            }
        }
    private val _mutualFundTotal: StateFlow<Double> = mutualFundReactive.map { it.first }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)
    private val _mutualFundInvested: StateFlow<Double> = mutualFundReactive.map { it.second }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)

    private val kamettiReactive: kotlinx.coroutines.flow.Flow<Double> = session.activeBookId.filterNotNull()
        .flatMapLatest { bookId -> kamettiDao.getActiveAccounts(bookId) }
        .flatMapLatest { accounts ->
            if (accounts.isEmpty()) {
                kotlinx.coroutines.flow.flowOf(0.0)
            } else {
                combine(accounts.map { account -> kamettiDao.getEntries(account.id) }) { lists ->
                    lists.sumOf { entries -> com.teamproject1.dailyexpensetracker.core.wealth.KamettiCalculator.currentBalance(entries.map { it.entryDate to it.amount }) }
                }
            }
        }
    private val _kamettiTotal: StateFlow<Double> = kamettiReactive.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)

    // netWorth's combine() is already at kotlinx.coroutines' 5-flow typed
    // limit, so RD and Kametti are pre-combined into one Pair-valued flow
    // below. Metal's price now lives directly on each holding, so this
    // total is a simple sum — no separate rate table to combine against.
    private val metalReactive: kotlinx.coroutines.flow.Flow<Double> = session.activeBookId.filterNotNull()
        .flatMapLatest { bookId -> metalHoldingDao.getActive(bookId) }
        .map { holdings -> holdings.sumOf { it.quantityGrams * (it.currentPricePerGram ?: 0.0) } }
    private val _metalTotal: StateFlow<Double> = metalReactive.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)

    // netWorth's combine() is already at kotlinx.coroutines' 5-flow typed
    // limit, so RD, Kametti, and Metal are pre-combined into one
    // Triple-valued flow here rather than trying to add more arguments
    // directly.
    private val _rdAndKametti: StateFlow<Triple<Double, Double, Double>> = combine(_rdTotal, _kamettiTotal, _metalTotal) { rd, kametti, metal -> Triple(rd, kametti, metal) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Triple(0.0, 0.0, 0.0))

    val netWorth: StateFlow<NetWorthBreakdown> = combine(simpleBase, simpleExtras, receivablesFlow, _rdAndKametti, _mutualFundTotal) { base, extras, receivableTotal, rdAndKametti, mutualFundTotal ->
        NetWorthBreakdown(
            fdValue = base[0],
            rdValue = rdAndKametti.first,
            dematValue = base[1],
            cashInHand = base[2],
            npsValue = extras[0],
            apyValue = extras[1],
            insuranceCashValue = extras[2],
            manualAssetsValue = extras[3],
            bankValue = extras[4],
            receivableValue = receivableTotal,
            mutualFundValue = mutualFundTotal,
            kamettiValue = rdAndKametti.second,
            metalValue = rdAndKametti.third,
            liabilities = base[3]
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), NetWorthBreakdown(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0))

    /** Card data for every Wealth category with a live value — this is
     *  what actually drives the Dashboard's card-style tiles. */
    private val ppfEpfDetail = combine(_ppfTotal, _epfTotal, _ppfDeposited, _epfContributed) { ppfTotal, epfTotal, ppfDeposited, epfContributed ->
        listOf(ppfTotal, epfTotal, ppfDeposited, epfContributed)
    }

    val categorySummaries: StateFlow<List<WealthCategorySummary>> = combine(netWorth, costBasis, ppfEpfDetail) { nw, cost, ppfEpf ->
        val ppfTotal = ppfEpf[0]
        val epfTotal = ppfEpf[1]
        val ppfDeposited = ppfEpf[2]
        val epfContributed = ppfEpf[3]

        val totalAssets = (nw.totalAssets + ppfTotal + epfTotal).let { if (it <= 0.0) 1.0 else it } // avoid div-by-zero

        fun gainLoss(current: Double, costBasisValue: Double): Double? =
            if (costBasisValue > 0) ((current - costBasisValue) / costBasisValue * 100) else null

        listOf(
            WealthCategorySummary("FD & NSC", "🏦", nw.fdValue, gainLoss(nw.fdValue, cost[4]), nw.fdValue / totalAssets * 100),
            WealthCategorySummary("RD", "💹", nw.rdValue, gainLoss(nw.rdValue, _rdCost.value), nw.rdValue / totalAssets * 100),
            WealthCategorySummary("PPF", "📜", ppfTotal, gainLoss(ppfTotal, ppfDeposited), ppfTotal / totalAssets * 100),
            WealthCategorySummary("EPF", "🏢", epfTotal, gainLoss(epfTotal, epfContributed), epfTotal / totalAssets * 100),
            WealthCategorySummary("NPS", "🧓", nw.npsValue, null, nw.npsValue / totalAssets * 100),
            WealthCategorySummary("APY", "🧾", nw.apyValue, null, nw.apyValue / totalAssets * 100),
            WealthCategorySummary("Demat", "📈", nw.dematValue, gainLoss(nw.dematValue, cost[5]), nw.dematValue / totalAssets * 100),
            WealthCategorySummary("Insurance", "🛡️", nw.insuranceCashValue, null, nw.insuranceCashValue / totalAssets * 100),
            WealthCategorySummary("Manual Assets", "🏠", nw.manualAssetsValue, null, nw.manualAssetsValue / totalAssets * 100),
            WealthCategorySummary("Bank", "🏛️", nw.bankValue, null, nw.bankValue / totalAssets * 100),
            WealthCategorySummary("Receivables", "🤝", nw.receivableValue, null, nw.receivableValue / totalAssets * 100),
            WealthCategorySummary("Mutual Funds", "📊", nw.mutualFundValue, gainLoss(nw.mutualFundValue, _mutualFundInvested.value), nw.mutualFundValue / totalAssets * 100),
            WealthCategorySummary("Kametti", "🤲", nw.kamettiValue, null, nw.kamettiValue / totalAssets * 100),
            WealthCategorySummary("Cash in Hand", "💵", nw.cashInHand, null, nw.cashInHand / totalAssets * 100),
            // Liabilities' "percent" is of total liabilities, not assets —
            // dividing a debt figure by total assets isn't a meaningful
            // composition metric the way it is for the asset categories
            // above. Added so Liabilities can use the same card style as
            // every other category, per explicit request.
            WealthCategorySummary("Liabilities", "📉", nw.liabilities, null, if (nw.liabilities > 0) 100.0 else 0.0),
            WealthCategorySummary("Gold/Silver", "✨", nw.metalValue, null, nw.metalValue / totalAssets * 100)
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Auto-refresh Demat prices at Dashboard load — per explicit request.
     *  Previously prices only refreshed if you specifically opened the
     *  Demat screen; Net Worth (and the Demat card here) could otherwise
     *  keep showing a stale price indefinitely if you never visited it
     *  directly. Each fetch fails independently without blocking others. */
    fun refreshDematPrices() {
        viewModelScope.launch {
            val bookId = session.activeBookId.value ?: return@launch
            val holdings = dematHoldingDaoRef.getActive(bookId).first()
            holdings.forEach { holding ->
                val price = stockPriceFetcher.fetchPrice(holding.stockSymbol, holding.exchangeCode)
                if (price != null) {
                    dematHoldingDaoRef.updateFetchedPrice(holding.id, price, System.currentTimeMillis())
                }
            }
        }
    }
}

/**
 * Grouped-sections layout, per the locked design: Net Worth at top, then
 * Fixed Income / Market-Linked / Other / Liabilities. Only FD, Cash in
 * Hand, and Liabilities are functional in this shell — the rest show a
 * "Coming soon" message on tap rather than navigating nowhere silently,
 * since they need real internet-integration work (auto-fetch rates/NAV/
 * prices) that wasn't attempted in this pass.
 *
 * Each section's icon row uses a plain horizontally-scrollable Row rather
 * than a grid — deliberately avoiding a nested-scrollable-inside-scrollable
 * layout (LazyVerticalGrid inside a LazyColumn item), which risks subtle
 * measurement bugs. This is the same safe pattern already proven
 * throughout the rest of the project for chip rows.
 */
@Composable
fun WealthDashboardScreen(
    isCompact: Boolean,
    bookName: String,
    currencySymbol: String,
    onBookSwitcherTap: () -> Unit,
    onFixedDeposits: () -> Unit,
    onLiabilities: () -> Unit,
    onCashInHand: () -> Unit,
    onDemat: () -> Unit,
    onMutualFunds: () -> Unit,
    onKametti: () -> Unit,
    onRecurringTransactions: () -> Unit,
    onOpenRdAccount: (Long) -> Unit,
    onOpenEpfAccount: (Long) -> Unit,
    onOpenApyAccount: (Long) -> Unit,
    onOpenNpsAccount: (Long) -> Unit,
    onOpenMutualFundAccount: (Long) -> Unit,
    onOpenKamettiAccount: (Long) -> Unit,
    onGoldSilver: () -> Unit,
    onPpf: () -> Unit,
    onEpf: () -> Unit,
    onRd: () -> Unit,
    onNps: () -> Unit,
    onApy: () -> Unit,
    onBank: () -> Unit,
    onReceivables: () -> Unit,
    onInsurance: () -> Unit,
    onManualAssets: () -> Unit,
    onSettings: () -> Unit,
    viewModel: WealthDashboardViewModel = hiltViewModel()
) {
    val netWorth by viewModel.netWorth.collectAsState()
    val ppfEpfTotal by viewModel.ppfEpfTotal.collectAsState()
    val categorySummaries by viewModel.categorySummaries.collectAsState()
    fun summaryFor(label: String) = categorySummaries.find { it.label == label }

    // FD/RD maturity check — same pattern as the Expense-side Recurring
    // due-reminder popup, runs every time this Dashboard loads.
    val maturityViewModel: MaturityRenewalViewModel = hiltViewModel()
    LaunchedEffect(Unit) { maturityViewModel.check() }
    MaturityRenewalPopup(viewModel = maturityViewModel)

    // APY contribution due-date check — same Dashboard-level pattern,
    // per explicit request.
    val apyContributionViewModel: ApyContributionViewModel = hiltViewModel()
    LaunchedEffect(Unit) { apyContributionViewModel.check() }
    ApyContributionPopup(viewModel = apyContributionViewModel)

    val npsContributionViewModel: NpsContributionViewModel = hiltViewModel()
    LaunchedEffect(Unit) { npsContributionViewModel.check() }
    NpsContributionPopup(viewModel = npsContributionViewModel)

    // EPF Employee+Employer contribution due-date check — same pattern.
    val epfContributionViewModel: EpfContributionViewModel = hiltViewModel()
    LaunchedEffect(Unit) { epfContributionViewModel.check() }
    EpfContributionPopup(viewModel = epfContributionViewModel)

    // Demat auto-refresh at Dashboard load, per explicit request.
    LaunchedEffect(Unit) { viewModel.refreshDematPrices() }

    // RD installment due-date check — same pattern as APY/EPF, per
    // explicit request.
    val rdContributionViewModel: RdContributionViewModel = hiltViewModel()
    LaunchedEffect(Unit) { rdContributionViewModel.check() }
    RdContributionPopup(viewModel = rdContributionViewModel)

    // Mutual Fund SIP due-date check — same pattern, per explicit request.
    val mutualFundSipViewModel: MutualFundSipViewModel = hiltViewModel()
    LaunchedEffect(Unit) { mutualFundSipViewModel.check() }
    MutualFundSipPopup(viewModel = mutualFundSipViewModel)

    // Kametti due-date check — same pattern, per explicit request.
    val kamettiContributionViewModel: com.teamproject1.dailyexpensetracker.feature.wealth.KamettiContributionViewModel = hiltViewModel()
    LaunchedEffect(Unit) { kamettiContributionViewModel.check() }
    com.teamproject1.dailyexpensetracker.feature.wealth.KamettiContributionPopup(viewModel = kamettiContributionViewModel)

    // PPF interest confirmation — moved to Dashboard level, per explicit
    // request, matching the other four due-reminder popups rather than
    // only checking inside that specific account's own detail screen.
    val ppfInterestViewModel: com.teamproject1.dailyexpensetracker.feature.wealth.PpfInterestViewModel = hiltViewModel()
    LaunchedEffect(Unit) { ppfInterestViewModel.check() }
    com.teamproject1.dailyexpensetracker.feature.wealth.PpfInterestPopup(viewModel = ppfInterestViewModel)

    var comingSoonLabel by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = java.text.SimpleDateFormat("h:mm a, MMM d").format(java.util.Date()),
                    style = MaterialTheme.typography.titleMedium
                )
                // Settings moved here from the grid, per explicit
                // request — a small icon between the date/time and Book
                // selector, rather than a full grid tile.
                IconButton(onClick = onSettings, modifier = Modifier.size(32.dp)) {
                    Text("⚙️", style = MaterialTheme.typography.titleMedium)
                }
                TextButton(onClick = onBookSwitcherTap) {
                    Text("$bookName ($currencySymbol) ▾", style = MaterialTheme.typography.titleMedium)
                }
            }
        },
        containerColor = Color.Transparent
    ) { padding ->
        DottedTextureBackground(modifier = Modifier.padding(padding)) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                item { NetWorthCard(netWorth, ppfEpfTotal, currencySymbol) }

                item { SectionLabel("Fixed Income") }
                item {
                    IconRow {
                        WealthCategoryCard(summary = summaryFor("FD & NSC"), fallbackLabel = "FD & NSC", fallbackEmoji = "🏦", currencySymbol = currencySymbol, onClick = onFixedDeposits)
                        WealthCategoryCard(summary = summaryFor("RD"), fallbackLabel = "RD", fallbackEmoji = "💹", currencySymbol = currencySymbol, onClick = onRd)
                        WealthCategoryCard(summary = summaryFor("PPF"), fallbackLabel = "PPF", fallbackEmoji = "📜", currencySymbol = currencySymbol, onClick = onPpf)
                        WealthCategoryCard(summary = summaryFor("EPF"), fallbackLabel = "EPF", fallbackEmoji = "🏢", currencySymbol = currencySymbol, onClick = onEpf)
                        WealthCategoryCard(summary = summaryFor("NPS"), fallbackLabel = "NPS", fallbackEmoji = "🧓", currencySymbol = currencySymbol, onClick = onNps)
                        WealthCategoryCard(summary = summaryFor("APY"), fallbackLabel = "APY", fallbackEmoji = "🧾", currencySymbol = currencySymbol, onClick = onApy)
                    }
                }

                item { SectionLabel("Market-Linked") }
                item {
                    IconRow {
                        WealthCategoryCard(summary = summaryFor("Demat"), fallbackLabel = "Demat", fallbackEmoji = "📈", currencySymbol = currencySymbol, onClick = onDemat)
                        WealthCategoryCard(summary = summaryFor("Mutual Funds"), fallbackLabel = "Mutual Funds", fallbackEmoji = "📊", currencySymbol = currencySymbol, onClick = onMutualFunds)
                        WealthCategoryCard(summary = summaryFor("Gold/Silver"), fallbackLabel = "Gold/Silver", fallbackEmoji = "✨", currencySymbol = currencySymbol, onClick = onGoldSilver)
                    }
                }

                item { SectionLabel("Other") }
                item {
                    IconRow {
                        WealthCategoryCard(summary = summaryFor("Bank"), fallbackLabel = "Bank", fallbackEmoji = "🏛️", currencySymbol = currencySymbol, onClick = onBank)
                        WealthCategoryCard(summary = summaryFor("Receivables"), fallbackLabel = "Receivables", fallbackEmoji = "🤝", currencySymbol = currencySymbol, onClick = onReceivables)
                        WealthCategoryCard(summary = summaryFor("Insurance"), fallbackLabel = "Insurance", fallbackEmoji = "🛡️", currencySymbol = currencySymbol, onClick = onInsurance)
                        WealthCategoryCard(summary = summaryFor("Manual Assets"), fallbackLabel = "Manual Assets", fallbackEmoji = "🏠", currencySymbol = currencySymbol, onClick = onManualAssets)
                        WealthCategoryCard(summary = summaryFor("Cash in Hand"), fallbackLabel = "Cash in Hand", fallbackEmoji = "💵", currencySymbol = currencySymbol, onClick = onCashInHand)
                        WealthCategoryCard(summary = summaryFor("Kametti"), fallbackLabel = "Kametti", fallbackEmoji = "🤲", currencySymbol = currencySymbol, onClick = onKametti)
                        // Moved here from its own standalone section, per
                        // explicit request — now uses the same card style
                        // as every other category instead of a plain icon.
                        WealthCategoryCard(summary = summaryFor("Liabilities"), fallbackLabel = "Liabilities", fallbackEmoji = "📉", currencySymbol = currencySymbol, onClick = onLiabilities)
                        WealthCategoryCard(summary = null, fallbackLabel = "Recurring Transactions", fallbackEmoji = "🔁", currencySymbol = currencySymbol, onClick = onRecurringTransactions, showValue = false)
                    }
                }

                item { SectionLabel("Reminders") }
                item { DashboardRemindersSection(onOpenRdAccount, onOpenEpfAccount, onOpenApyAccount, onOpenNpsAccount, onOpenMutualFundAccount, onOpenKamettiAccount) }

                item { Spacer(Modifier.height(80.dp)) }
            }
        }
    }

    comingSoonLabel?.let { label ->
        AlertDialog(
            onDismissRequest = { comingSoonLabel = null },
            title = { Text("$label — coming soon") },
            text = {
                Text(
                    "This category is designed but not built yet — it needs a live rate/price integration that's planned as a follow-up.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = { comingSoonLabel = null }) { Text("OK") }
            }
        )
    }
}

@Composable
private fun IconRow(content: @Composable () -> Unit) {
    // Horizontally scrollable, per explicit request — this replaces an
    // earlier wrap-to-new-row layout, so a section's tiles now stay on
    // one line and scroll sideways rather than wrapping to a second row.
    androidx.compose.foundation.layout.Row(
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(androidx.compose.foundation.rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        content()
    }
}

@Composable
private fun WealthCategoryCard(
    summary: WealthCategorySummary?,
    fallbackLabel: String,
    fallbackEmoji: String,
    currencySymbol: String,
    onClick: () -> Unit,
    showValue: Boolean = true
) {
    Card(
        modifier = Modifier
            .width(140.dp)
            .clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(fallbackEmoji, style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text(fallbackLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // showValue = false skips the monetary line entirely, per
            // explicit request — Settings and the still-unbuilt
            // Gold/Silver placeholder use the same card shape/style as
            // every financial category, but have no amount to show.
            if (showValue) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "$currencySymbol${"%.0f".format(summary?.currentValue ?: 0.0)}",
                    style = MaterialTheme.typography.titleLarge
                )
                if (summary != null) {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        if (summary.gainLossPercent != null) {
                            Text(
                                "%+.1f%%".format(summary.gainLossPercent),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (summary.gainLossPercent >= 0) IncomeGreen else ExpenseRed
                            )
                        } else {
                            Spacer(Modifier)
                        }
                        Text(
                            "%.0f%%".format(summary.allocationPercent),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NetWorthCard(netWorth: NetWorthBreakdown, ppfEpfTotal: Double, currencySymbol: String) {
    // Hidden by default every time this card is freshly composed — per
    // explicit request ("hidden until unhide"). State lives here rather
    // than being persisted, so it resets to hidden again next time you
    // navigate back to the Dashboard, matching how a privacy toggle
    // should behave (not remembering "unhidden" if someone else picks up
    // the phone later).
    var isVisible by remember { mutableStateOf(false) }
    val totalNetWorth = netWorth.netWorth + ppfEpfTotal
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("Net Worth", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = { isVisible = !isVisible }, modifier = Modifier.size(20.dp)) {
                Text(if (isVisible) "🙈" else "👁️")
            }
        }
        Text(
            if (isVisible) "$currencySymbol${"%.0f".format(totalNetWorth)}" else "$currencySymbol••••••",
            style = MaterialTheme.typography.displayLarge
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (isVisible) {
                "Assets $currencySymbol${"%.0f".format(netWorth.totalAssets + ppfEpfTotal)} · Liabilities $currencySymbol${"%.0f".format(netWorth.liabilities)}"
            } else {
                "Assets $currencySymbol•••• · Liabilities $currencySymbol••••"
            },
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@dagger.hilt.android.lifecycle.HiltViewModel
class DashboardRemindersViewModel @javax.inject.Inject constructor(
    private val repository: com.teamproject1.dailyexpensetracker.core.wealth.DashboardRemindersRepository
) : ViewModel() {
    var items by mutableStateOf<List<com.teamproject1.dailyexpensetracker.core.wealth.DashboardReminderItem>>(emptyList())
        private set

    fun load() {
        viewModelScope.launch { items = repository.getAll() }
    }
}

@Composable
private fun DashboardRemindersSection(
    onOpenRdAccount: (Long) -> Unit,
    onOpenEpfAccount: (Long) -> Unit,
    onOpenApyAccount: (Long) -> Unit,
    onOpenNpsAccount: (Long) -> Unit,
    onOpenMutualFundAccount: (Long) -> Unit,
    onOpenKamettiAccount: (Long) -> Unit,
    viewModel: DashboardRemindersViewModel = hiltViewModel()
) {
    LaunchedEffect(Unit) { viewModel.load() }
    val dateFormat = remember { java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.US) }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        if (viewModel.items.isEmpty()) {
            Text(
                "Nothing pending or upcoming yet — RD, EPF, APY, Mutual Fund SIPs, and Kametti will appear here once configured.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            viewModel.items.forEach { reminder ->
                val item = reminder.item
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    onClick = {
                        when (item.type) {
                            com.teamproject1.dailyexpensetracker.core.wealth.RecurringTransactionType.RD -> onOpenRdAccount(item.accountId)
                            com.teamproject1.dailyexpensetracker.core.wealth.RecurringTransactionType.EPF_EMPLOYEE_EMPLOYER,
                            com.teamproject1.dailyexpensetracker.core.wealth.RecurringTransactionType.EPF_PENSION -> onOpenEpfAccount(item.accountId)
                            com.teamproject1.dailyexpensetracker.core.wealth.RecurringTransactionType.APY -> onOpenApyAccount(item.accountId)
                            com.teamproject1.dailyexpensetracker.core.wealth.RecurringTransactionType.NPS -> onOpenNpsAccount(item.accountId)
                            com.teamproject1.dailyexpensetracker.core.wealth.RecurringTransactionType.MUTUAL_FUND_SIP -> onOpenMutualFundAccount(item.accountId)
                            com.teamproject1.dailyexpensetracker.core.wealth.RecurringTransactionType.KAMETTI -> onOpenKamettiAccount(item.accountId)
                        }
                    }
                ) {
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(12.dp)
                    ) {
                        Column {
                            Text(item.name, style = MaterialTheme.typography.bodyMedium)
                            item.scheduleAnchorDate?.let {
                                Text(dateFormat.format(java.util.Date(it)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                            Text("₹%.0f".format(item.amount), style = MaterialTheme.typography.bodyMedium)
                            Text(
                                if (reminder.isPending) "Pending" else "Upcoming",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (reminder.isPending) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}
