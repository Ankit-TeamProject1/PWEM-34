package com.teamproject1.dailyexpensetracker.core.export

import com.teamproject1.dailyexpensetracker.core.database.dao.*
import com.teamproject1.dailyexpensetracker.core.database.entity.*
import com.teamproject1.dailyexpensetracker.core.session.SessionManager
import kotlinx.coroutines.flow.first
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

sealed class WealthImportResult {
    // warnings surfaces exactly which rows were skipped and why, per
    // explicit request ("error to import format to be checked") — every
    // malformed row was previously dropped completely silently, with no
    // way to know anything had gone wrong with a specific row.
    data class Success(val summary: String, val warnings: List<String> = emptyList()) : WealthImportResult()
    object InvalidFile : WealthImportResult()
}

private val DATE_FMT = SimpleDateFormat("yyyy-MM-dd", Locale.US)
private fun fmtDate(millis: Long?): String = millis?.let { DATE_FMT.format(Date(it)) } ?: ""
private fun parseDate(s: String): Long? = if (s.isBlank()) null else try { DATE_FMT.parse(s)?.time } catch (e: Exception) { null }

/**
 * Multi-sheet Wealth Excel export/import — one sheet per category, built
 * specifically so bulk data entry can happen in a spreadsheet rather than
 * one field at a time through the app's UI, per the explicit "I don't feel
 * like entering so much data" request.
 *
 * Scope: covers every Wealth category with a working data model as of
 * this shell — FD/NSC, RD (+ RD Entries), Liabilities, Cash in Hand,
 * Bank, Demat, PPF, EPF (+ EPF Entries), NPS (account-level only, not
 * per-scheme detail), APY, Insurance, Manual Assets, Mutual Funds (+ MF
 * Entries), Kametti (+ Kametti Entries), and Metal Holdings (one row per
 * metal/carat/form tile). Every category checks for duplicates on import
 * using identifying fields specific to that category (see each
 * section's own comment for exactly which fields) — re-importing the
 * same file is safe and won't create duplicate rows. Every malformed row
 * is reported
 * back as a specific warning rather than silently dropped.
 */
@Singleton
class WealthExportRepository @Inject constructor(
    private val fixedDepositDao: FixedDepositDao,
    private val recurringDepositDao: RecurringDepositDao,
    private val rdInstallmentDao: com.teamproject1.dailyexpensetracker.core.database.dao.RdInstallmentDao,
    private val liabilityDao: LiabilityDao,
    private val cashInHandDao: CashInHandDao,
    private val dematHoldingDao: DematHoldingDao,
    private val ppfDao: PpfDao,
    private val epfDao: EpfDao,
    private val npsDao: NpsDao,
    private val apyDao: ApyDao,
    private val insuranceDao: InsuranceDao,
    private val manualAssetDao: ManualAssetDao,
    private val bankBalanceDao: com.teamproject1.dailyexpensetracker.core.database.dao.BankBalanceDao,
    private val mutualFundDao: com.teamproject1.dailyexpensetracker.core.database.dao.MutualFundDao,
    private val rateHistoryDao: com.teamproject1.dailyexpensetracker.core.database.dao.WealthRateHistoryDao,
    private val kamettiDao: com.teamproject1.dailyexpensetracker.core.database.dao.KamettiDao,
    private val metalHoldingDao: com.teamproject1.dailyexpensetracker.core.database.dao.MetalHoldingDao,
    private val dematAccountDao: com.teamproject1.dailyexpensetracker.core.database.dao.DematAccountDao,
    private val mutualFundPlatformDao: com.teamproject1.dailyexpensetracker.core.database.dao.MutualFundPlatformDao,
    private val session: SessionManager
) {

    /** Finds this book's first Demat account, or creates one named "My
     *  Demat Account" if none exists yet — used when importing Excel
     *  data that has no per-holding account reference. This is the same
     *  default-account principle approved for backup migration, applied
     *  consistently here too. */
    private suspend fun defaultDematAccountId(bookId: Long): Long {
        val existing = dematAccountDao.getAllOnce(bookId)
        if (existing.isNotEmpty()) return existing.first().id
        return dematAccountDao.insert(com.teamproject1.dailyexpensetracker.core.database.entity.DematAccountEntity(bookId = bookId, name = "My Demat Account"))
    }

    private suspend fun defaultMutualFundPlatformId(bookId: Long): Long {
        val existing = mutualFundPlatformDao.getAllOnce(bookId)
        if (existing.isNotEmpty()) return existing.first().id
        return mutualFundPlatformDao.insert(com.teamproject1.dailyexpensetracker.core.database.entity.MutualFundPlatformEntity(bookId = bookId, name = "My Mutual Fund Account"))
    }

    suspend fun exportToXlsx(outputStream: OutputStream) {
        val bookId = session.activeBookId.first() ?: return
        val sheets = mutableListOf<ExcelSheet>()

        // Rate History is global (no bookId — applies across every Wealth
        // book), so it's included in every export regardless of which
        // book you're exporting, sourced from the shared table rather
        // than filtered by this book.
        val ppfRates = rateHistoryDao.getHistoryOnce(com.teamproject1.dailyexpensetracker.core.database.entity.RateInstrument.PPF)
        val epfRates = rateHistoryDao.getHistoryOnce(com.teamproject1.dailyexpensetracker.core.database.entity.RateInstrument.EPF)
        sheets += ExcelSheet(
            "Rates",
            listOf("Instrument (PPF/EPF)", "Rate %", "Effective From"),
            (ppfRates + epfRates).map { rate -> listOf(rate.instrument.name, rate.ratePercent.toString(), fmtDate(rate.effectiveFrom)) }
        )

        val fds = fixedDepositDao.getActive(bookId).first()
        sheets += ExcelSheet(
            "FD-NSC",
            listOf("Name", "Kind (FD/NSC)", "Bank Name", "Account/Certificate No", "Principal", "Start Date", "Tenure (months)", "Rate %", "Interest Type (SIMPLE/COMPOUND)", "Compounding (MONTHLY/QUARTERLY/ANNUALLY/CUMULATIVE)", "Tenure Extra Days"),
            fds.map { fd ->
                listOf(fd.name, fd.kind.name, fd.bankName ?: "", fd.accountOrCertificateNumber ?: "", fd.principalAmount.toString(), fmtDate(fd.startDate), fd.tenureMonths.toString(), fd.interestRate.toString(), fd.interestType.name, fd.compoundingFrequency.name, fd.tenureExtraDays.toString())
            }
        )

        val rds = recurringDepositDao.getActive(bookId).first()
        sheets += ExcelSheet(
            "RD",
            listOf("Name", "Bank Name", "Account No", "Monthly Installment", "Start Date", "Tenure (months)", "Rate %", "Compounding"),
            rds.map { rd ->
                listOf(rd.name, rd.bankName ?: "", rd.accountOrCertificateNumber ?: "", rd.monthlyInstallment.toString(), fmtDate(rd.startDate), rd.tenureMonths.toString(), rd.interestRate.toString(), rd.compoundingFrequency.name)
            }
        )

        val rdEntries = mutableListOf<List<String>>()
        rds.forEach { rd ->
            rdInstallmentDao.getInstallmentsOnce(rd.id).forEach { entry ->
                rdEntries.add(listOf(rd.name, entry.amount.toString(), fmtDate(entry.installmentDate)))
            }
        }
        sheets += ExcelSheet("RD Entries", listOf("RD Name", "Amount", "Date"), rdEntries)

        val liabilities = liabilityDao.getActive(bookId).first()
        sheets += ExcelSheet(
            "Liabilities",
            listOf("Name", "Type", "Principal", "Rate %", "Tenure (months)", "EMI", "Start Date", "Outstanding Balance"),
            liabilities.map { l ->
                listOf(l.name, l.liabilityType, l.principalAmount.toString(), l.interestRate.toString(), l.tenureMonths.toString(), l.emiAmount.toString(), fmtDate(l.startDate), l.outstandingBalance.toString())
            }
        )

        val cash = cashInHandDao.get(bookId).first()
        sheets += ExcelSheet("Cash-In-Hand", listOf("Amount"), listOfNotNull(cash?.let { listOf(it.amount.toString()) }))

        val banks = bankBalanceDao.getActive(bookId).first()
        sheets += ExcelSheet(
            "Bank",
            listOf("Bank Name", "Account Details", "Current Balance"),
            banks.map { b -> listOf(b.bankName, b.accountDetails ?: "", b.currentBalance.toString()) }
        )

        val dematAccounts = dematAccountDao.getAllOnce(bookId)
        sheets += ExcelSheet("Demat Accounts", listOf("Account Name"), dematAccounts.map { listOf(it.name) })

        val dematAccountsById = dematAccounts.associateBy { it.id }
        val demat = dematHoldingDao.getActiveForBook(bookId).first()
        sheets += ExcelSheet(
            "Demat",
            listOf("Account Name", "Stock Symbol", "Exchange (NSE/BSE)", "Units Held", "Avg Buy Price"),
            demat.map { d -> listOf(dematAccountsById[d.dematAccountId]?.name ?: "", d.stockSymbol, d.exchangeCode.name, d.unitsHeld.toString(), d.avgBuyPrice.toString()) }
        )

        val ppfAccounts = ppfDao.getActiveAccounts(bookId).first()
        sheets += ExcelSheet(
            "PPF",
            listOf("Name", "Account Number", "Bank/Post Office", "Account Open Date"),
            ppfAccounts.map { p -> listOf(p.name, p.accountNumber ?: "", p.bankOrPostOfficeName ?: "", fmtDate(p.accountOpenDate)) }
        )
        val ppfEntries = mutableListOf<List<String>>()
        ppfAccounts.forEach { p ->
            ppfDao.getDepositsOnce(p.id).forEach { entry ->
                ppfEntries.add(listOf(p.name, entry.amount.toString(), fmtDate(entry.depositDate), entry.type.name, entry.note ?: ""))
            }
        }
        sheets += ExcelSheet("PPF Entries", listOf("PPF Name", "Amount", "Deposit Date", "Type (DEPOSIT/DEDUCTION/INTEREST)", "Note"), ppfEntries)

        val epfAccounts = epfDao.getActiveAccounts(bookId).first()
        sheets += ExcelSheet(
            "EPF",
            listOf("Name", "Company Name", "PF Account Number", "UAN", "Account Open Date", "Recurring Deposit Date"),
            epfAccounts.map { e -> listOf(e.name, e.companyName ?: "", e.pfAccountNumber ?: "", e.uan ?: "", fmtDate(e.accountOpenDate), fmtDate(e.recurringDepositDate)) }
        )

        val epfEntries = mutableListOf<List<String>>()
        epfAccounts.forEach { e ->
            epfDao.getContributionsOnce(e.id).forEach { c ->
                epfEntries.add(listOf(e.pfAccountNumber ?: e.name, c.tileType.name, c.amount.toString(), fmtDate(c.contributionDate), c.isTransfer.toString()))
            }
        }
        sheets += ExcelSheet("EPF Entries", listOf("PF Account Number", "Tile (EMPLOYEE_EMPLOYER/PENSION)", "Amount", "Contribution Month", "Is Transfer"), epfEntries)

        val npsAccounts = npsDao.getActive(bookId).first()
        sheets += ExcelSheet(
            "NPS",
            listOf("Name", "PRAN", "PFM", "Tier (TIER_I/TIER_II)", "Current Value", "Monthly Contribution", "Recurring Deposit Date"),
            npsAccounts.map { n -> listOf(n.name, n.pran ?: "", n.pfm, n.tier, n.currentValue.toString(), n.monthlyContribution?.toString() ?: "", fmtDate(n.recurringDepositDate)) }
        )

        val apyAccounts = apyDao.getActive(bookId).first()
        sheets += ExcelSheet(
            "APY",
            listOf("Name", "PRAN", "Bank Name", "Account Number", "IFSC", "Monthly Contribution", "Pension Slab", "Start Date", "Total Contributed"),
            apyAccounts.map { a -> listOf(a.name, a.pran ?: "", a.bankName ?: "", a.accountNumber ?: "", a.ifsc ?: "", a.monthlyContribution.toString(), a.pensionSlab.toString(), fmtDate(a.startDate), a.totalContributedSoFar.toString()) }
        )

        val policies = insuranceDao.getActive(bookId).first()
        sheets += ExcelSheet(
            "Insurance",
            listOf("Policy Name", "Policy Number", "Insurer", "Type (TERM/HEALTH/ENDOWMENT/MONEY_BACK/ULIP)", "Start Date", "Maturity Date", "Sum Assured", "Premium", "Premium Frequency", "Premium Term (years)", "Next Due Date", "Has Cash Value (TRUE/FALSE)", "Current Surrender Value"),
            policies.map { p ->
                listOf(
                    p.policyName, p.policyNumber ?: "", p.insurerName, p.policyType, fmtDate(p.startDate), fmtDate(p.maturityDate),
                    p.sumAssured.toString(), p.premiumAmount.toString(), p.premiumFrequency, p.premiumPaymentTermYears?.toString() ?: "",
                    fmtDate(p.nextDueDate), p.hasCashValue.toString().uppercase(), p.currentSurrenderValue.toString()
                )
            }
        )

        val assets = manualAssetDao.getActive(bookId).first()
        sheets += ExcelSheet(
            "Manual-Assets",
            listOf("Name", "Type (REAL_ESTATE/VEHICLE/OTHER)", "Current Value"),
            assets.map { a -> listOf(a.name, a.assetType, a.currentValue.toString()) }
        )

        val mfPlatforms = mutualFundPlatformDao.getAllOnce(bookId)
        sheets += ExcelSheet("Mutual Fund Accounts", listOf("Account Name"), mfPlatforms.map { listOf(it.name) })

        val mfPlatformsById = mfPlatforms.associateBy { it.id }
        val mutualFunds = mutualFundDao.getActiveAccounts(bookId).first()
        sheets += ExcelSheet(
            "Mutual Funds",
            listOf("Account Name", "Scheme Code", "Scheme Name", "Folio Number", "Fund House"),
            mutualFunds.map { m -> listOf(mfPlatformsById[m.platformId]?.name ?: "", m.schemeCode, m.schemeName, m.folioNumber ?: "", m.fundHouse ?: "") }
        )

        val mutualFundEntries = mutableListOf<List<String>>()
        mutualFunds.forEach { m ->
            mutualFundDao.getEntriesOnce(m.id).forEach { entry ->
                mutualFundEntries.add(listOf(m.schemeCode, entry.investedAmount.toString(), entry.navAtPurchase.toString(), fmtDate(entry.purchaseDate), entry.isSip.toString()))
            }
        }
        sheets += ExcelSheet("MF Entries", listOf("Scheme Code", "Amount Invested", "NAV at Purchase", "Purchase Date", "Is SIP"), mutualFundEntries)

        val kamettiAccounts = kamettiDao.getActiveAccounts(bookId).first()
        sheets += ExcelSheet(
            "Kametti",
            listOf("Name", "Monthly Amount", "Start Date", "Total Months", "Recurring Deposit Date"),
            kamettiAccounts.map { k -> listOf(k.name, k.monthlyAmount.toString(), fmtDate(k.startDate), k.totalMonths.toString(), fmtDate(k.recurringDepositDate)) }
        )
        val kamettiEntries = mutableListOf<List<String>>()
        kamettiAccounts.forEach { k ->
            kamettiDao.getEntriesOnce(k.id).forEach { entry ->
                kamettiEntries.add(listOf(k.name, entry.amount.toString(), fmtDate(entry.entryDate), entry.isWon.toString()))
            }
        }
        sheets += ExcelSheet("Kametti Entries", listOf("Kametti Name", "Amount", "Entry Date", "Is Won"), kamettiEntries)

        val metalHoldings = metalHoldingDao.getActive(bookId).first()
        sheets += ExcelSheet(
            "Metal Holdings",
            listOf("Metal Type (GOLD/SILVER)", "Carat/Purity", "Form (PHYSICAL/DIGITAL)", "Quantity (grams)", "Current Price/gram"),
            metalHoldings.map { m -> listOf(m.metalType.name, m.caratType, m.form.name, m.quantityGrams.toString(), m.currentPricePerGram?.toString() ?: "") }
        )

        MultiSheetXlsxWriter.write(outputStream, sheets)
    }

    suspend fun importFromXlsx(inputStream: InputStream): WealthImportResult {
        val bookId = session.activeBookId.first() ?: return WealthImportResult.InvalidFile
        val sheets = try {
            MultiSheetXlsxReader.read(inputStream)
        } catch (e: Exception) {
            return WealthImportResult.InvalidFile
        }
        if (sheets.isEmpty()) return WealthImportResult.InvalidFile

        val now = System.currentTimeMillis()
        val counts = mutableMapOf<String, Int>()
        // Collects a specific, human-readable reason for every row that
        // couldn't be imported, per explicit request — previously every
        // malformed row was dropped with zero trace.
        val warnings = mutableListOf<String>()

        // Rates — global, not book-scoped. Duplicate check: same
        // instrument + rate% + effective-from date already existing is
        // skipped, matching the same dedup principle used everywhere else.
        var ratesSkipped = 0
        val existingPpfRates = rateHistoryDao.getHistoryOnce(com.teamproject1.dailyexpensetracker.core.database.entity.RateInstrument.PPF)
        val existingEpfRates = rateHistoryDao.getHistoryOnce(com.teamproject1.dailyexpensetracker.core.database.entity.RateInstrument.EPF)
        sheets["Rates"]?.drop(1)?.forEachIndexed { i, row ->
            val rowNum = i + 2
            if (row.size < 3) { warnings.add("Rates row $rowNum: expected at least 3 columns (Instrument, Rate%, Effective From), found ${row.size} — skipped"); return@forEachIndexed }
            val instrument = runCatching { com.teamproject1.dailyexpensetracker.core.database.entity.RateInstrument.valueOf(row[0]) }.getOrNull()
            if (instrument == null) { warnings.add("Rates row $rowNum: '${row[0]}' is not a valid instrument (expected PPF or EPF) — skipped"); return@forEachIndexed }
            val ratePercent = row[1].toDoubleOrNull()
            if (ratePercent == null) { warnings.add("Rates row $rowNum: '${row[1]}' is not a valid rate% — skipped"); return@forEachIndexed }
            val effectiveFrom = parseDate(row[2])
            if (effectiveFrom == null) { warnings.add("Rates row $rowNum: '${row[2]}' is not a valid date (expected yyyy-MM-dd) — skipped"); return@forEachIndexed }
            val existing = if (instrument == com.teamproject1.dailyexpensetracker.core.database.entity.RateInstrument.PPF) existingPpfRates else existingEpfRates
            val isDuplicate = existing.any { it.ratePercent == ratePercent && it.effectiveFrom == effectiveFrom }
            if (isDuplicate) {
                ratesSkipped++
                return@forEachIndexed
            }
            rateHistoryDao.insert(
                com.teamproject1.dailyexpensetracker.core.database.entity.WealthRateHistoryEntity(instrument = instrument, ratePercent = ratePercent, effectiveFrom = effectiveFrom)
            )
            counts["Rates"] = (counts["Rates"] ?: 0) + 1
        }
        if (ratesSkipped > 0) counts["Rates (skipped duplicates)"] = ratesSkipped

        // Duplicate check: same Bank Name + Account/Certificate Number +
        // Start Date already existing is skipped, per the agreed Wealth
        // duplicate rule.
        val existingFds = fixedDepositDao.getActive(bookId).first()
        var fdSkipped = 0
        sheets["FD-NSC"]?.drop(1)?.forEachIndexed { i, row ->
            val rowNum = i + 2
            if (row.size < 10) { warnings.add("FD-NSC row $rowNum: expected at least 10 columns, found ${row.size} — skipped"); return@forEachIndexed }
            val principal = row[4].toDoubleOrNull()
            if (principal == null) { warnings.add("FD-NSC row $rowNum: '${row[4]}' is not a valid Principal Amount — skipped"); return@forEachIndexed }
            val tenure = row[6].toIntOrNull()
            if (tenure == null) { warnings.add("FD-NSC row $rowNum: '${row[6]}' is not a valid Tenure (months) — skipped"); return@forEachIndexed }
            val rate = row[7].toDoubleOrNull()
            if (rate == null) { warnings.add("FD-NSC row $rowNum: '${row[7]}' is not a valid Interest Rate — skipped"); return@forEachIndexed }
            val bankName = row[2].ifBlank { null }
            val accountNumber = row[3].ifBlank { null }
            val startDate = parseDate(row[5]) ?: now
            val isDuplicate = existingFds.any {
                it.bankName == bankName && it.accountOrCertificateNumber == accountNumber && it.startDate == startDate
            }
            if (isDuplicate) {
                fdSkipped++
                return@forEachIndexed
            }
            fixedDepositDao.insert(
                FixedDepositEntity(
                    bookId = bookId, name = row[0], kind = runCatching { WealthInstrumentKind.valueOf(row[1]) }.getOrDefault(WealthInstrumentKind.FD),
                    bankName = bankName, accountOrCertificateNumber = accountNumber,
                    principalAmount = principal, startDate = startDate, tenureMonths = tenure, interestRate = rate,
                    interestType = runCatching { InterestType.valueOf(row[8]) }.getOrDefault(InterestType.COMPOUND),
                    compoundingFrequency = runCatching { CompoundingFrequency.valueOf(row[9]) }.getOrDefault(CompoundingFrequency.CUMULATIVE),
                    tenureExtraDays = row.getOrNull(10)?.toIntOrNull() ?: 0
                )
            )
            counts["FD-NSC"] = (counts["FD-NSC"] ?: 0) + 1
        }
        if (fdSkipped > 0) counts["FD-NSC (skipped duplicates)"] = fdSkipped

        // Duplicate check added — previously RD had none at all, so
        // re-importing the same file created a fresh duplicate account
        // every time. Same identifying-fields approach as FD-NSC.
        val existingRds = recurringDepositDao.getActive(bookId).first()
        var rdSkipped = 0
        sheets["RD"]?.drop(1)?.forEachIndexed { i, row ->
            val rowNum = i + 2
            if (row.size < 8) { warnings.add("RD row $rowNum: expected at least 8 columns, found ${row.size} — skipped"); return@forEachIndexed }
            val installment = row[3].toDoubleOrNull()
            if (installment == null) { warnings.add("RD row $rowNum: '${row[3]}' is not a valid Monthly Installment — skipped"); return@forEachIndexed }
            val tenure = row[5].toIntOrNull()
            if (tenure == null) { warnings.add("RD row $rowNum: '${row[5]}' is not a valid Tenure (months) — skipped"); return@forEachIndexed }
            val rate = row[6].toDoubleOrNull()
            if (rate == null) { warnings.add("RD row $rowNum: '${row[6]}' is not a valid Interest Rate — skipped"); return@forEachIndexed }
            val bankName = row[1].ifBlank { null }
            val accountNumber = row[2].ifBlank { null }
            val startDate = parseDate(row[4]) ?: now
            val isDuplicate = existingRds.any { it.bankName == bankName && it.accountOrCertificateNumber == accountNumber && it.startDate == startDate }
            if (isDuplicate) {
                rdSkipped++
                return@forEachIndexed
            }
            recurringDepositDao.insert(
                RecurringDepositEntity(
                    bookId = bookId, name = row[0], bankName = bankName, accountOrCertificateNumber = accountNumber,
                    monthlyInstallment = installment, startDate = startDate, tenureMonths = tenure, interestRate = rate,
                    compoundingFrequency = runCatching { CompoundingFrequency.valueOf(row[7]) }.getOrDefault(CompoundingFrequency.QUARTERLY)
                )
            )
            counts["RD"] = (counts["RD"] ?: 0) + 1
        }
        if (rdSkipped > 0) counts["RD (skipped duplicates)"] = rdSkipped

        // RD Entries — matched to their account by name (Excel has no
        // relational IDs), checked against ALL current RD accounts for
        // this book, not just ones created in this same import batch.
        var rdEntryCount = 0
        val currentRdAccounts = recurringDepositDao.getActive(bookId).first()
        sheets["RD Entries"]?.drop(1)?.forEachIndexed { i, row ->
            val rowNum = i + 2
            if (row.size < 3) { warnings.add("RD Entries row $rowNum: expected at least 3 columns, found ${row.size} — skipped"); return@forEachIndexed }
            val account = currentRdAccounts.find { it.name == row[0] }
            if (account == null) { warnings.add("RD Entries row $rowNum: no RD account named '${row[0]}' found — skipped"); return@forEachIndexed }
            val amount = row[1].toDoubleOrNull()
            if (amount == null) { warnings.add("RD Entries row $rowNum: '${row[1]}' is not a valid amount — skipped"); return@forEachIndexed }
            val date = parseDate(row[2])
            if (date == null) { warnings.add("RD Entries row $rowNum: '${row[2]}' is not a valid date — skipped"); return@forEachIndexed }
            rdInstallmentDao.insert(com.teamproject1.dailyexpensetracker.core.database.entity.RdInstallmentEntity(rdAccountId = account.id, amount = amount, installmentDate = date))
            rdEntryCount++
        }
        if (rdEntryCount > 0) counts["RD Entries"] = rdEntryCount

        // Duplicate check added — previously Liabilities had none at all.
        val existingLiabilities = liabilityDao.getActive(bookId).first()
        var liabilitySkipped = 0
        sheets["Liabilities"]?.drop(1)?.forEachIndexed { i, row ->
            val rowNum = i + 2
            if (row.size < 8) { warnings.add("Liabilities row $rowNum: expected at least 8 columns, found ${row.size} — skipped"); return@forEachIndexed }
            val principal = row[2].toDoubleOrNull()
            if (principal == null) { warnings.add("Liabilities row $rowNum: '${row[2]}' is not a valid Principal Amount — skipped"); return@forEachIndexed }
            val startDate = parseDate(row[6]) ?: now
            val isDuplicate = existingLiabilities.any { it.name == row[0] && it.liabilityType == row[1] && it.startDate == startDate }
            if (isDuplicate) {
                liabilitySkipped++
                return@forEachIndexed
            }
            val rate = row[3].toDoubleOrNull() ?: 0.0
            val tenure = row[4].toIntOrNull() ?: 0
            val emi = row[5].toDoubleOrNull() ?: 0.0
            val outstanding = row[7].toDoubleOrNull() ?: principal
            liabilityDao.insert(
                LiabilityEntity(
                    bookId = bookId, name = row[0], liabilityType = row[1].ifBlank { "OTHER" },
                    principalAmount = principal, interestRate = rate, tenureMonths = tenure, emiAmount = emi,
                    startDate = startDate, outstandingBalance = outstanding
                )
            )
            counts["Liabilities"] = (counts["Liabilities"] ?: 0) + 1
        }
        if (liabilitySkipped > 0) counts["Liabilities (skipped duplicates)"] = liabilitySkipped

        sheets["Cash-In-Hand"]?.drop(1)?.firstOrNull()?.let { row ->
            val amount = row.getOrNull(0)?.toDoubleOrNull()
            if (amount != null) {
                cashInHandDao.upsert(CashInHandEntity(bookId = bookId, amount = amount, lastUpdatedAt = now))
                counts["Cash-In-Hand"] = 1
            } else if (row.isNotEmpty()) {
                warnings.add("Cash-In-Hand: '${row.getOrNull(0)}' is not a valid amount — skipped")
            }
        }

        // Duplicate check: same Bank Name + Account Details already
        // existing for this book is skipped rather than re-created, per
        // the agreed Wealth duplicate rule (identifying fields matching).
        val existingBanks = bankBalanceDao.getActive(bookId).first()
        var bankSkipped = 0
        sheets["Bank"]?.drop(1)?.forEachIndexed { i, row ->
            val rowNum = i + 2
            if (row.size < 3 || row[0].isBlank()) { warnings.add("Bank row $rowNum: Bank Name is required — skipped"); return@forEachIndexed }
            val balance = row[2].toDoubleOrNull()
            if (balance == null) { warnings.add("Bank row $rowNum: '${row[2]}' is not a valid balance — skipped"); return@forEachIndexed }
            val accountDetails = row[1].ifBlank { null }
            val isDuplicate = existingBanks.any { it.bankName == row[0] && it.accountDetails == accountDetails }
            if (isDuplicate) {
                bankSkipped++
                return@forEachIndexed
            }
            bankBalanceDao.insert(
                BankBalanceEntity(bookId = bookId, bankName = row[0], accountDetails = accountDetails, currentBalance = balance, lastUpdatedAt = now)
            )
            counts["Bank"] = (counts["Bank"] ?: 0) + 1
        }
        if (bankSkipped > 0) counts["Bank (skipped duplicates)"] = bankSkipped

        // Demat Accounts — duplicate check: same Name already existing is
        // skipped, not re-created.
        val existingDematAccounts = dematAccountDao.getAllOnce(bookId).toMutableList()
        var dematAccountsCreated = 0
        sheets["Demat Accounts"]?.drop(1)?.forEachIndexed { i, row ->
            val rowNum = i + 2
            if (row.isEmpty() || row[0].isBlank()) { warnings.add("Demat Accounts row $rowNum: Account Name is required — skipped"); return@forEachIndexed }
            if (existingDematAccounts.any { it.name == row[0] }) return@forEachIndexed
            val newAccount = com.teamproject1.dailyexpensetracker.core.database.entity.DematAccountEntity(bookId = bookId, name = row[0])
            val newId = dematAccountDao.insert(newAccount)
            existingDematAccounts.add(newAccount.copy(id = newId))
            dematAccountsCreated++
        }
        if (dematAccountsCreated > 0) counts["Demat Accounts"] = dematAccountsCreated

        // Demat — matches an existing holding by Stock Symbol + Exchange
        // within the SAME account, and MERGES rather than duplicating:
        // units add together, and the buy price becomes a weighted
        // average of the existing holding and the newly imported units.
        // This matches what actually happens in real life when you buy
        // more shares of a stock you already hold — previously this
        // always created a separate row for the same stock, which was
        // the real gap here. Rows with a blank or unrecognized Account
        // Name go to this book's default Demat account, created only if
        // actually needed.
        val existingDemat = dematHoldingDao.getActiveForBook(bookId).first().toMutableList()
        var dematMerged = 0
        var dematCreated = 0
        val dematRows = sheets["Demat"]?.drop(1) ?: emptyList()
        var dematDefaultAccountId: Long? = null
        dematRows.forEachIndexed { i, row ->
            val rowNum = i + 2
            if (row.size < 5) { warnings.add("Demat row $rowNum: expected at least 5 columns, found ${row.size} — skipped"); return@forEachIndexed }
            val units = row[3].toDoubleOrNull()
            if (units == null) { warnings.add("Demat row $rowNum: '${row[3]}' is not a valid unit count — skipped"); return@forEachIndexed }
            val symbol = row[1].uppercase()
            if (symbol.isBlank()) { warnings.add("Demat row $rowNum: Stock Symbol is required — skipped"); return@forEachIndexed }
            val exchange = runCatching { ExchangeCode.valueOf(row[2].uppercase()) }.getOrDefault(ExchangeCode.NSE)
            val avgBuyPrice = row[4].toDoubleOrNull() ?: 0.0
            val accountName = row[0].ifBlank { null }
            val dematAccountId = accountName?.let { name -> existingDematAccounts.find { it.name == name }?.id }
                ?: (dematDefaultAccountId ?: defaultDematAccountId(bookId).also { dematDefaultAccountId = it })

            val existing = existingDemat.find { it.stockSymbol == symbol && it.exchangeCode == exchange && it.dematAccountId == dematAccountId }
            if (existing != null) {
                val newUnits = existing.unitsHeld + units
                val newAvgBuyPrice = if (newUnits > 0) {
                    ((existing.unitsHeld * existing.avgBuyPrice) + (units * avgBuyPrice)) / newUnits
                } else 0.0
                val updated = existing.copy(unitsHeld = newUnits, avgBuyPrice = newAvgBuyPrice)
                dematHoldingDao.update(updated)
                existingDemat[existingDemat.indexOf(existing)] = updated
                dematMerged++
            } else {
                val newHolding = DematHoldingEntity(bookId = bookId, dematAccountId = dematAccountId, stockSymbol = symbol, exchangeCode = exchange, unitsHeld = units, avgBuyPrice = avgBuyPrice)
                dematHoldingDao.insert(newHolding)
                existingDemat.add(newHolding)
                dematCreated++
            }
        }
        if (dematCreated > 0) counts["Demat"] = dematCreated
        if (dematMerged > 0) counts["Demat (merged into existing)"] = dematMerged

        // Duplicate check added — previously PPF had none at all.
        val existingPpf = ppfDao.getActiveAccounts(bookId).first()
        var ppfSkipped = 0
        sheets["PPF"]?.drop(1)?.forEachIndexed { i, row ->
            val rowNum = i + 2
            if (row.size < 4 || row[0].isBlank()) { warnings.add("PPF row $rowNum: Name is required — skipped"); return@forEachIndexed }
            val openDate = parseDate(row[3]) ?: now
            val isDuplicate = existingPpf.any { it.name == row[0] && it.accountOpenDate == openDate }
            if (isDuplicate) {
                ppfSkipped++
                return@forEachIndexed
            }
            ppfDao.insertAccount(
                PpfAccountEntity(bookId = bookId, name = row[0], accountNumber = row[1].ifBlank { null }, bankOrPostOfficeName = row[2].ifBlank { null }, accountOpenDate = openDate)
            )
            counts["PPF"] = (counts["PPF"] ?: 0) + 1
        }
        if (ppfSkipped > 0) counts["PPF (skipped duplicates)"] = ppfSkipped

        // PPF Entries — matched to their account by name (Excel has no
        // relational IDs), checked against ALL current PPF accounts for
        // this book, not just ones created in this same import batch.
        var ppfEntryCount = 0
        val currentPpfAccounts = ppfDao.getActiveAccounts(bookId).first()
        sheets["PPF Entries"]?.drop(1)?.forEachIndexed { i, row ->
            val rowNum = i + 2
            if (row.size < 3) { warnings.add("PPF Entries row $rowNum: expected at least 3 columns, found ${row.size} — skipped"); return@forEachIndexed }
            val account = currentPpfAccounts.find { it.name == row[0] }
            if (account == null) { warnings.add("PPF Entries row $rowNum: no PPF account named '${row[0]}' found — skipped"); return@forEachIndexed }
            val amount = row[1].toDoubleOrNull()
            if (amount == null) { warnings.add("PPF Entries row $rowNum: '${row[1]}' is not a valid amount — skipped"); return@forEachIndexed }
            val date = parseDate(row[2])
            if (date == null) { warnings.add("PPF Entries row $rowNum: '${row[2]}' is not a valid date — skipped"); return@forEachIndexed }
            val type = runCatching { com.teamproject1.dailyexpensetracker.core.database.entity.PpfEntryType.valueOf(row.getOrNull(3) ?: "DEPOSIT") }.getOrDefault(com.teamproject1.dailyexpensetracker.core.database.entity.PpfEntryType.DEPOSIT)
            ppfDao.insertDeposit(
                com.teamproject1.dailyexpensetracker.core.database.entity.PpfDepositEntity(
                    ppfAccountId = account.id, amount = amount, depositDate = date, type = type, note = row.getOrNull(4)?.ifBlank { null }
                )
            )
            ppfEntryCount++
        }
        if (ppfEntryCount > 0) counts["PPF Entries"] = ppfEntryCount

        // Duplicate check added — previously EPF had none at all.
        val existingEpf = epfDao.getActiveAccounts(bookId).first()
        var epfSkipped = 0
        sheets["EPF"]?.drop(1)?.forEachIndexed { i, row ->
            val rowNum = i + 2
            if (row.size < 5 || row[0].isBlank()) { warnings.add("EPF row $rowNum: Name is required — skipped"); return@forEachIndexed }
            val openDate = parseDate(row[4]) ?: now
            val isDuplicate = existingEpf.any { it.name == row[0] && it.accountOpenDate == openDate }
            if (isDuplicate) {
                epfSkipped++
                return@forEachIndexed
            }
            epfDao.insertAccount(
                EpfAccountEntity(
                    bookId = bookId, name = row[0], companyName = row[1].ifBlank { null }, pfAccountNumber = row[2].ifBlank { null }, uan = row[3].ifBlank { null }, accountOpenDate = openDate,
                    recurringDepositDate = parseDate(row.getOrNull(5) ?: "") ?: openDate
                )
            )
            counts["EPF"] = (counts["EPF"] ?: 0) + 1
        }
        if (epfSkipped > 0) counts["EPF (skipped duplicates)"] = epfSkipped

        // EPF Entries — matched to their account by PF Account Number
        // (falling back to Name if blank), per explicit request that
        // Excel entries be tied to specific PF accounts. Checked against
        // ALL current EPF accounts for this book, not just ones created
        // in this same import batch.
        var epfEntryCount = 0
        val currentEpfAccounts = epfDao.getActiveAccounts(bookId).first()
        sheets["EPF Entries"]?.drop(1)?.forEachIndexed { i, row ->
            val rowNum = i + 2
            if (row.size < 4) { warnings.add("EPF Entries row $rowNum: expected at least 4 columns, found ${row.size} — skipped"); return@forEachIndexed }
            val account = currentEpfAccounts.find { (it.pfAccountNumber?.takeIf { n -> n.isNotBlank() } ?: it.name) == row[0] }
            if (account == null) { warnings.add("EPF Entries row $rowNum: no EPF account matching '${row[0]}' found — skipped"); return@forEachIndexed }
            val tileType = runCatching { com.teamproject1.dailyexpensetracker.core.database.entity.EpfTileType.valueOf(row[1]) }.getOrDefault(com.teamproject1.dailyexpensetracker.core.database.entity.EpfTileType.EMPLOYEE_EMPLOYER)
            val amount = row[2].toDoubleOrNull()
            if (amount == null) { warnings.add("EPF Entries row $rowNum: '${row[2]}' is not a valid amount — skipped"); return@forEachIndexed }
            val date = parseDate(row[3])
            if (date == null) { warnings.add("EPF Entries row $rowNum: '${row[3]}' is not a valid date — skipped"); return@forEachIndexed }
            val isTransfer = row.getOrNull(4)?.toBooleanStrictOrNull() ?: false
            epfDao.insertContribution(
                com.teamproject1.dailyexpensetracker.core.database.entity.EpfContributionEntity(
                    epfAccountId = account.id, amount = amount, contributionDate = date, tileType = tileType, isTransfer = isTransfer
                )
            )
            epfEntryCount++
        }
        if (epfEntryCount > 0) counts["EPF Entries"] = epfEntryCount

        // Duplicate check added — previously NPS had none at all.
        val existingNps = npsDao.getActive(bookId).first()
        var npsSkipped = 0
        sheets["NPS"]?.drop(1)?.forEachIndexed { i, row ->
            val rowNum = i + 2
            if (row.size < 5 || row[0].isBlank()) { warnings.add("NPS row $rowNum: Name is required — skipped"); return@forEachIndexed }
            val currentValue = row[4].toDoubleOrNull()
            if (currentValue == null) { warnings.add("NPS row $rowNum: '${row[4]}' is not a valid current value — skipped"); return@forEachIndexed }
            val pran = row[1].ifBlank { null }
            val isDuplicate = existingNps.any { it.name == row[0] && it.pran == pran }
            if (isDuplicate) {
                npsSkipped++
                return@forEachIndexed
            }
            npsDao.insert(
                NpsAccountEntity(
                    bookId = bookId, name = row[0], pran = pran, pfm = row[2], tier = row[3].ifBlank { "TIER_I" }, currentValue = currentValue, lastUpdatedAt = now,
                    monthlyContribution = row.getOrNull(5)?.toDoubleOrNull(),
                    recurringDepositDate = parseDate(row.getOrNull(6) ?: "")
                )
            )
            counts["NPS"] = (counts["NPS"] ?: 0) + 1
        }
        if (npsSkipped > 0) counts["NPS (skipped duplicates)"] = npsSkipped

        // Duplicate check added — previously APY had none at all.
        val existingApy = apyDao.getActive(bookId).first()
        var apySkipped = 0
        sheets["APY"]?.drop(1)?.forEachIndexed { i, row ->
            val rowNum = i + 2
            if (row.size < 9 || row[0].isBlank()) { warnings.add("APY row $rowNum: Name is required — skipped"); return@forEachIndexed }
            val contribution = row[5].toDoubleOrNull()
            if (contribution == null) { warnings.add("APY row $rowNum: '${row[5]}' is not a valid Monthly Contribution — skipped"); return@forEachIndexed }
            val startDate = parseDate(row[7]) ?: now
            val isDuplicate = existingApy.any { it.name == row[0] && it.startDate == startDate }
            if (isDuplicate) {
                apySkipped++
                return@forEachIndexed
            }
            val slab = row[6].toIntOrNull() ?: 1000
            apyDao.insert(
                ApyAccountEntity(
                    bookId = bookId, name = row[0], pran = row[1].ifBlank { null }, bankName = row[2].ifBlank { null },
                    accountNumber = row[3].ifBlank { null }, ifsc = row[4].ifBlank { null }, monthlyContribution = contribution,
                    pensionSlab = slab, startDate = startDate, totalContributedSoFar = row[8].toDoubleOrNull() ?: 0.0
                )
            )
            counts["APY"] = (counts["APY"] ?: 0) + 1
        }
        if (apySkipped > 0) counts["APY (skipped duplicates)"] = apySkipped

        // Duplicate check added — previously Insurance had none at all.
        val existingInsurance = insuranceDao.getActive(bookId).first()
        var insuranceSkipped = 0
        sheets["Insurance"]?.drop(1)?.forEachIndexed { i, row ->
            val rowNum = i + 2
            if (row.size < 13 || row[0].isBlank()) { warnings.add("Insurance row $rowNum: Policy Name is required — skipped"); return@forEachIndexed }
            val sumAssured = row[6].toDoubleOrNull()
            if (sumAssured == null) { warnings.add("Insurance row $rowNum: '${row[6]}' is not a valid Sum Assured — skipped"); return@forEachIndexed }
            val policyNumber = row[1].ifBlank { null }
            val isDuplicate = existingInsurance.any { it.policyName == row[0] && it.policyNumber == policyNumber }
            if (isDuplicate) {
                insuranceSkipped++
                return@forEachIndexed
            }
            val premium = row[7].toDoubleOrNull() ?: 0.0
            insuranceDao.insert(
                InsurancePolicyEntity(
                    bookId = bookId, policyName = row[0], policyNumber = policyNumber, insurerName = row[2],
                    policyType = row[3].ifBlank { "TERM" }, startDate = parseDate(row[4]), maturityDate = parseDate(row[5]),
                    sumAssured = sumAssured, premiumAmount = premium, premiumFrequency = row[8].ifBlank { "ANNUALLY" },
                    premiumPaymentTermYears = row[9].toIntOrNull(), nextDueDate = parseDate(row[10]),
                    hasCashValue = row[11].equals("TRUE", ignoreCase = true), currentSurrenderValue = row[12].toDoubleOrNull() ?: 0.0
                )
            )
            counts["Insurance"] = (counts["Insurance"] ?: 0) + 1
        }
        if (insuranceSkipped > 0) counts["Insurance (skipped duplicates)"] = insuranceSkipped

        // Duplicate check added — previously Manual-Assets had none at all.
        val existingManualAssets = manualAssetDao.getActive(bookId).first()
        var manualAssetSkipped = 0
        sheets["Manual-Assets"]?.drop(1)?.forEachIndexed { i, row ->
            val rowNum = i + 2
            if (row.size < 3 || row[0].isBlank()) { warnings.add("Manual-Assets row $rowNum: Name is required — skipped"); return@forEachIndexed }
            val value = row[2].toDoubleOrNull()
            if (value == null) { warnings.add("Manual-Assets row $rowNum: '${row[2]}' is not a valid current value — skipped"); return@forEachIndexed }
            val assetType = row[1].ifBlank { "OTHER" }
            val isDuplicate = existingManualAssets.any { it.name == row[0] && it.assetType == assetType }
            if (isDuplicate) {
                manualAssetSkipped++
                return@forEachIndexed
            }
            manualAssetDao.insert(ManualAssetEntity(bookId = bookId, name = row[0], assetType = assetType, currentValue = value, lastUpdatedByUserAt = now))
            counts["Manual-Assets"] = (counts["Manual-Assets"] ?: 0) + 1
        }
        if (manualAssetSkipped > 0) counts["Manual-Assets (skipped duplicates)"] = manualAssetSkipped

        // Mutual Fund Accounts — duplicate check: same Name already
        // existing is skipped, not re-created.
        val existingMfPlatforms = mutualFundPlatformDao.getAllOnce(bookId).toMutableList()
        var mfPlatformsCreated = 0
        sheets["Mutual Fund Accounts"]?.drop(1)?.forEachIndexed { i, row ->
            val rowNum = i + 2
            if (row.isEmpty() || row[0].isBlank()) { warnings.add("Mutual Fund Accounts row $rowNum: Account Name is required — skipped"); return@forEachIndexed }
            if (existingMfPlatforms.any { it.name == row[0] }) return@forEachIndexed
            val newPlatform = com.teamproject1.dailyexpensetracker.core.database.entity.MutualFundPlatformEntity(bookId = bookId, name = row[0])
            val newId = mutualFundPlatformDao.insert(newPlatform)
            existingMfPlatforms.add(newPlatform.copy(id = newId))
            mfPlatformsCreated++
        }
        if (mfPlatformsCreated > 0) counts["Mutual Fund Accounts"] = mfPlatformsCreated

        // Mutual Funds — matched by Scheme Code, the one field that
        // genuinely and uniquely identifies a fund (unlike name, which
        // could theoretically collide or vary slightly). Duplicate check:
        // same Scheme Code already existing for this book is skipped, not
        // re-created. Rows with a blank or unrecognized Account Name go
        // to this book's default Mutual Fund account, created only if
        // actually needed.
        val existingMutualFunds = mutualFundDao.getActiveAccounts(bookId).first().toMutableList()
        var mfSkipped = 0
        val mfRows = sheets["Mutual Funds"]?.drop(1) ?: emptyList()
        var mfDefaultPlatformId: Long? = null
        mfRows.forEachIndexed { i, row ->
            val rowNum = i + 2
            if (row.size < 3 || row[1].isBlank()) { warnings.add("Mutual Funds row $rowNum: Scheme Code is required — skipped"); return@forEachIndexed }
            val schemeCode = row[1]
            val isDuplicate = existingMutualFunds.any { it.schemeCode == schemeCode }
            if (isDuplicate) {
                mfSkipped++
                return@forEachIndexed
            }
            val platformName = row[0].ifBlank { null }
            val platformId = platformName?.let { name -> existingMfPlatforms.find { it.name == name }?.id }
                ?: (mfDefaultPlatformId ?: defaultMutualFundPlatformId(bookId).also { mfDefaultPlatformId = it })
            val newAccount = com.teamproject1.dailyexpensetracker.core.database.entity.MutualFundAccountEntity(
                bookId = bookId, platformId = platformId, schemeCode = schemeCode, schemeName = row[2],
                folioNumber = row.getOrNull(3)?.ifBlank { null }, fundHouse = row.getOrNull(4)?.ifBlank { null }
            )
            mutualFundDao.insertAccount(newAccount)
            existingMutualFunds.add(newAccount)
            counts["Mutual Funds"] = (counts["Mutual Funds"] ?: 0) + 1
        }
        if (mfSkipped > 0) counts["Mutual Funds (skipped duplicates)"] = mfSkipped

        // MF Entries — matched to their fund by Scheme Code, checked
        // against ALL current mutual fund accounts for this book, not
        // just ones created in this same import batch.
        var mfEntryCount = 0
        val currentMutualFunds = mutualFundDao.getActiveAccounts(bookId).first()
        sheets["MF Entries"]?.drop(1)?.forEachIndexed { i, row ->
            val rowNum = i + 2
            if (row.size < 4) { warnings.add("MF Entries row $rowNum: expected at least 4 columns, found ${row.size} — skipped"); return@forEachIndexed }
            val account = currentMutualFunds.find { it.schemeCode == row[0] }
            if (account == null) { warnings.add("MF Entries row $rowNum: no fund with Scheme Code '${row[0]}' found — skipped"); return@forEachIndexed }
            val investedAmount = row[1].toDoubleOrNull()
            if (investedAmount == null) { warnings.add("MF Entries row $rowNum: '${row[1]}' is not a valid invested amount — skipped"); return@forEachIndexed }
            val navAtPurchase = row[2].toDoubleOrNull()
            if (navAtPurchase == null) { warnings.add("MF Entries row $rowNum: '${row[2]}' is not a valid NAV — skipped"); return@forEachIndexed }
            val purchaseDate = parseDate(row[3])
            if (purchaseDate == null) { warnings.add("MF Entries row $rowNum: '${row[3]}' is not a valid date — skipped"); return@forEachIndexed }
            val isSip = row.getOrNull(4)?.toBooleanStrictOrNull() ?: false
            val units = if (navAtPurchase > 0) investedAmount / navAtPurchase else 0.0
            mutualFundDao.insertEntry(
                com.teamproject1.dailyexpensetracker.core.database.entity.MutualFundEntryEntity(
                    mutualFundAccountId = account.id, investedAmount = investedAmount, navAtPurchase = navAtPurchase,
                    unitsAllotted = units, purchaseDate = purchaseDate, isSip = isSip
                )
            )
            mfEntryCount++
        }
        if (mfEntryCount > 0) counts["MF Entries"] = mfEntryCount

        // Kametti — matched to its account by Name (Excel has no
        // relational IDs), same convention as RD Entries. Duplicate
        // check: same Name + Start Date already existing is skipped.
        val existingKametti = kamettiDao.getActiveAccounts(bookId).first()
        var kamettiSkipped = 0
        sheets["Kametti"]?.drop(1)?.forEachIndexed { i, row ->
            val rowNum = i + 2
            if (row.size < 4 || row[0].isBlank()) { warnings.add("Kametti row $rowNum: Name is required — skipped"); return@forEachIndexed }
            val monthlyAmount = row[1].toDoubleOrNull()
            if (monthlyAmount == null) { warnings.add("Kametti row $rowNum: '${row[1]}' is not a valid Monthly Amount — skipped"); return@forEachIndexed }
            val totalMonths = row[3].toIntOrNull()
            if (totalMonths == null) { warnings.add("Kametti row $rowNum: '${row[3]}' is not a valid Total Months — skipped"); return@forEachIndexed }
            val startDate = parseDate(row[2]) ?: now
            val isDuplicate = existingKametti.any { it.name == row[0] && it.startDate == startDate }
            if (isDuplicate) {
                kamettiSkipped++
                return@forEachIndexed
            }
            kamettiDao.insertAccount(
                com.teamproject1.dailyexpensetracker.core.database.entity.KamettiAccountEntity(
                    bookId = bookId, name = row[0], monthlyAmount = monthlyAmount, startDate = startDate, totalMonths = totalMonths,
                    recurringDepositDate = parseDate(row.getOrNull(4) ?: "")
                )
            )
            counts["Kametti"] = (counts["Kametti"] ?: 0) + 1
        }
        if (kamettiSkipped > 0) counts["Kametti (skipped duplicates)"] = kamettiSkipped

        var kamettiEntryCount = 0
        val currentKamettiAccounts = kamettiDao.getActiveAccounts(bookId).first()
        sheets["Kametti Entries"]?.drop(1)?.forEachIndexed { i, row ->
            val rowNum = i + 2
            if (row.size < 3) { warnings.add("Kametti Entries row $rowNum: expected at least 3 columns, found ${row.size} — skipped"); return@forEachIndexed }
            val account = currentKamettiAccounts.find { it.name == row[0] }
            if (account == null) { warnings.add("Kametti Entries row $rowNum: no Kametti account named '${row[0]}' found — skipped"); return@forEachIndexed }
            val amount = row[1].toDoubleOrNull()
            if (amount == null) { warnings.add("Kametti Entries row $rowNum: '${row[1]}' is not a valid amount — skipped"); return@forEachIndexed }
            val date = parseDate(row[2])
            if (date == null) { warnings.add("Kametti Entries row $rowNum: '${row[2]}' is not a valid date — skipped"); return@forEachIndexed }
            val isWon = row.getOrNull(3)?.toBooleanStrictOrNull() ?: false
            kamettiDao.insertEntry(
                com.teamproject1.dailyexpensetracker.core.database.entity.KamettiEntryEntity(kamettiAccountId = account.id, amount = amount, entryDate = date, isWon = isWon)
            )
            kamettiEntryCount++
        }
        if (kamettiEntryCount > 0) counts["Kametti Entries"] = kamettiEntryCount

        // Metal Holdings — one tile per (Metal Type, Carat/Purity, Form),
        // matching the app's own uniqueness rule. A row matching an
        // existing tile UPDATES it (quantity and price replace what was
        // there) rather than being skipped as a duplicate, since a
        // re-export/re-import of the same book is the normal way to
        // bulk-update quantities and prices in a spreadsheet.
        val existingMetalHoldings = metalHoldingDao.getActive(bookId).first().associateBy { Triple(it.metalType, it.caratType, it.form) }.toMutableMap()
        var metalHoldingCreated = 0
        var metalHoldingUpdated = 0
        sheets["Metal Holdings"]?.drop(1)?.forEachIndexed { i, row ->
            val rowNum = i + 2
            if (row.size < 4) { warnings.add("Metal Holdings row $rowNum: expected at least 4 columns, found ${row.size} — skipped"); return@forEachIndexed }
            val metalType = runCatching { com.teamproject1.dailyexpensetracker.core.database.entity.MetalType.valueOf(row[0]) }.getOrNull()
            if (metalType == null) { warnings.add("Metal Holdings row $rowNum: '${row[0]}' is not a valid Metal Type (expected GOLD or SILVER) — skipped"); return@forEachIndexed }
            val form = runCatching { com.teamproject1.dailyexpensetracker.core.database.entity.MetalForm.valueOf(row[2]) }.getOrNull()
            if (form == null) { warnings.add("Metal Holdings row $rowNum: '${row[2]}' is not a valid Form (expected PHYSICAL or DIGITAL) — skipped"); return@forEachIndexed }
            val quantityGrams = row[3].toDoubleOrNull()
            if (quantityGrams == null) { warnings.add("Metal Holdings row $rowNum: '${row[3]}' is not a valid quantity — skipped"); return@forEachIndexed }
            val caratType = row[1]
            val pricePerGram = row.getOrNull(4)?.toDoubleOrNull()
            val key = Triple(metalType, caratType, form)
            val existing = existingMetalHoldings[key]
            if (existing != null) {
                val updated = existing.copy(
                    quantityGrams = quantityGrams,
                    currentPricePerGram = pricePerGram,
                    lastUpdatedAt = if (pricePerGram != existing.currentPricePerGram) now else existing.lastUpdatedAt
                )
                metalHoldingDao.update(updated)
                existingMetalHoldings[key] = updated
                metalHoldingUpdated++
            } else {
                val newHolding = com.teamproject1.dailyexpensetracker.core.database.entity.MetalHoldingEntity(
                    bookId = bookId, metalType = metalType, caratType = caratType, form = form,
                    quantityGrams = quantityGrams, currentPricePerGram = pricePerGram,
                    lastUpdatedAt = pricePerGram?.let { now }
                )
                metalHoldingDao.insert(newHolding)
                existingMetalHoldings[key] = newHolding
                metalHoldingCreated++
            }
        }
        if (metalHoldingCreated > 0) counts["Metal Holdings"] = metalHoldingCreated
        if (metalHoldingUpdated > 0) counts["Metal Holdings (updated)"] = metalHoldingUpdated

        if (counts.isEmpty()) return WealthImportResult.InvalidFile
        val summary = counts.entries.joinToString(", ") { "${it.value} ${it.key}" }
        return WealthImportResult.Success(summary, warnings)
    }
}
