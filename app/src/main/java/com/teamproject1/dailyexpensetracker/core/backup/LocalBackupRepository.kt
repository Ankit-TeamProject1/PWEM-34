package com.teamproject1.dailyexpensetracker.core.backup

import com.teamproject1.dailyexpensetracker.core.database.dao.AccountDao
import com.teamproject1.dailyexpensetracker.core.database.dao.ApyDao
import com.teamproject1.dailyexpensetracker.core.database.dao.BankBalanceDao
import com.teamproject1.dailyexpensetracker.core.database.dao.BookDao
import com.teamproject1.dailyexpensetracker.core.database.dao.CashInHandDao
import com.teamproject1.dailyexpensetracker.core.database.dao.CategoryDao
import com.teamproject1.dailyexpensetracker.core.database.dao.DematHoldingDao
import com.teamproject1.dailyexpensetracker.core.database.dao.EpfDao
import com.teamproject1.dailyexpensetracker.core.database.dao.ExpenseReceivableDao
import com.teamproject1.dailyexpensetracker.core.database.dao.FixedDepositDao
import com.teamproject1.dailyexpensetracker.core.database.dao.InsuranceDao
import com.teamproject1.dailyexpensetracker.core.database.dao.LiabilityDao
import com.teamproject1.dailyexpensetracker.core.database.dao.ManualAssetDao
import com.teamproject1.dailyexpensetracker.core.database.dao.NpsDao
import com.teamproject1.dailyexpensetracker.core.database.dao.PpfDao
import com.teamproject1.dailyexpensetracker.core.database.dao.RecurringDepositDao
import com.teamproject1.dailyexpensetracker.core.database.dao.RecurringRuleDao
import com.teamproject1.dailyexpensetracker.core.database.dao.TransactionDao
import com.teamproject1.dailyexpensetracker.core.database.dao.WealthRateHistoryDao
import com.teamproject1.dailyexpensetracker.core.database.dao.WealthReceivableDao
import com.teamproject1.dailyexpensetracker.core.database.entity.*
import com.teamproject1.dailyexpensetracker.core.session.SessionManager
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

sealed class BackupImportResult {
    // warnings surfaces exactly which book(s) failed to restore and why,
    // per the same "no silent failures" standard applied throughout this
    // app — previously an exception restoring ANY single book would
    // crash the whole operation, potentially losing books that had
    // already restored successfully and giving no indication of what
    // went wrong.
    data class Success(val booksImported: Int, val summary: String, val firstBookId: Long? = null, val warnings: List<String> = emptyList()) : BackupImportResult()
    object InvalidFile : BackupImportResult()
}

/**
 * Full local backup. Rewritten to fix two gaps flagged during testing:
 * 1. Wealth books had no backup path at all — Excel export is Expense-only
 *    (per the locked Settings split), so JSON backup is now the ONLY
 *    backup mechanism for a Wealth book. Each book's JSON now includes
 *    either Expense-shaped data (accounts/categories/transactions/
 *    recurring rules) or Wealth-shaped data (FD/Liabilities/CashInHand/
 *    Demat), depending on that book's type.
 * 2. Backup only ever covered the single active book — exportAllBooks
 *    now covers every book, regardless of type.
 *
 * Budgets aren't included — there's no existing DAO method to fetch "all
 * budgets regardless of month," and budgets are cheap to re-set manually.
 *
 * Import behavior:
 * - A single-book file (as produced by "Backup this book") merges into the
 *   CURRENT active book — existing rows matched by name aren't duplicated.
 * - A multi-book file (as produced by "Backup all books") creates a NEW
 *   book for each entry, since there's no unambiguous "current book" to
 *   merge multiple books into.
 */
@Singleton
class LocalBackupRepository @Inject constructor(
    private val bookDao: BookDao,
    private val accountDao: AccountDao,
    private val categoryDao: CategoryDao,
    private val recurringRuleDao: RecurringRuleDao,
    private val transactionDao: TransactionDao,
    private val expenseReceivableDao: ExpenseReceivableDao,
    private val fixedDepositDao: FixedDepositDao,
    private val liabilityDao: LiabilityDao,
    private val cashInHandDao: CashInHandDao,
    private val dematHoldingDao: DematHoldingDao,
    private val ppfDao: PpfDao,
    private val epfDao: EpfDao,
    private val npsDao: NpsDao,
    private val apyDao: ApyDao,
    private val insuranceDao: InsuranceDao,
    private val manualAssetDao: ManualAssetDao,
    private val recurringDepositDao: RecurringDepositDao,
    private val rdInstallmentDao: com.teamproject1.dailyexpensetracker.core.database.dao.RdInstallmentDao,
    private val bankBalanceDao: BankBalanceDao,
    private val wealthReceivableDao: WealthReceivableDao,
    private val mutualFundDao: com.teamproject1.dailyexpensetracker.core.database.dao.MutualFundDao,
    private val rateHistoryDao: WealthRateHistoryDao,
    private val kamettiDao: com.teamproject1.dailyexpensetracker.core.database.dao.KamettiDao,
    private val metalHoldingDao: com.teamproject1.dailyexpensetracker.core.database.dao.MetalHoldingDao,
    private val dematAccountDao: com.teamproject1.dailyexpensetracker.core.database.dao.DematAccountDao,
    private val mutualFundPlatformDao: com.teamproject1.dailyexpensetracker.core.database.dao.MutualFundPlatformDao,
    private val session: SessionManager
) {

    /** Same default-account principle as WealthExportRepository's Excel
     *  import — an old-format backup (from before Demat/Mutual Fund
     *  accounts existed) has no account reference at all, so its
     *  holdings all go into one auto-created default account rather than
     *  failing or being dropped. */
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

    // ---------- EXPORT ----------

    suspend fun exportActiveBookBackup(outputStream: OutputStream) {
        val bookId = session.activeBookId.first() ?: return
        val book = bookDao.getById(bookId) ?: return
        writeRoot(outputStream, listOf(buildBookJson(book)))
    }

    suspend fun exportAllBooksBackup(outputStream: OutputStream) {
        val books = bookDao.getActiveBooks().first()
        val bookJsons = books.map { buildBookJson(it) }
        writeRoot(outputStream, bookJsons)
    }

    private suspend fun writeRoot(outputStream: OutputStream, bookJsons: List<JSONObject>) {
        val root = JSONObject().apply {
            put("schemaVersion", 2)
            put("exportedAt", System.currentTimeMillis())
            put("books", JSONArray().apply { bookJsons.forEach { put(it) } })
            // Rate history is genuinely global (WealthRateHistoryEntity has
            // no bookId — it applies across every book), so it's included
            // once at the root here rather than nested inside any single
            // book's JSON. Previously missing entirely from backup, which
            // meant a restore always came back with rates blank.
            put("rateHistory", JSONArray().apply {
                listOf(RateInstrument.PPF, RateInstrument.EPF).forEach { instrument ->
                    rateHistoryDao.getHistoryOnce(instrument).forEach { rate ->
                        put(JSONObject().apply {
                            put("instrument", rate.instrument.name)
                            put("ratePercent", rate.ratePercent)
                            put("effectiveFrom", rate.effectiveFrom)
                        })
                    }
                }
            })
        }
        outputStream.write(root.toString(2).toByteArray(Charsets.UTF_8))
    }

    private suspend fun buildBookJson(book: BookEntity): JSONObject {
        val bookJson = JSONObject().apply {
            put("name", book.name)
            put("bookType", book.bookType.name)
            put("currencyCode", book.currencyCode)
        }

        if (book.bookType == BookType.EXPENSE) {
            val accounts = accountDao.getActiveAccounts(book.id).first()
            val categories = categoryDao.getActiveCategories(book.id).first()
            val recurringRules = recurringRuleDao.getAll(book.id).first()
            val transactions = transactionDao.getAll(book.id).first()

            bookJson.put("accounts", JSONArray().apply {
                accounts.forEach { a ->
                    put(JSONObject().apply {
                        put("localId", a.id); put("name", a.name); put("type", a.type.name)
                        put("isDefault", a.isDefault); put("createdAt", a.createdAt)
                    })
                }
            })
            bookJson.put("categories", JSONArray().apply {
                categories.forEach { c ->
                    put(JSONObject().apply { put("localId", c.id); put("name", c.name); put("icon", c.icon); put("colorHex", c.colorHex) })
                }
            })
            bookJson.put("recurringRules", JSONArray().apply {
                recurringRules.forEach { r ->
                    put(JSONObject().apply {
                        put("name", r.name); put("type", r.type.name); put("amount", r.amount)
                        put("accountLocalId", r.accountId); put("toAccountLocalId", r.toAccountId ?: JSONObject.NULL)
                        put("categoryLocalId", r.categoryId ?: JSONObject.NULL); put("note", r.note ?: JSONObject.NULL)
                        put("frequency", r.frequency.name); put("interval", r.interval)
                        put("startDate", r.startDate); put("endDate", r.endDate ?: JSONObject.NULL)
                        put("nextRunAt", r.nextRunAt); put("isPaused", r.isPaused)
                    })
                }
            })
            bookJson.put("transactions", JSONArray().apply {
                transactions.forEach { t ->
                    put(JSONObject().apply {
                        put("localId", t.id); put("type", t.type.name); put("amount", t.amount)
                        put("accountLocalId", t.accountId); put("toAccountLocalId", t.toAccountId ?: JSONObject.NULL)
                        put("categoryLocalId", t.categoryId ?: JSONObject.NULL); put("note", t.note ?: JSONObject.NULL)
                        put("occurredAt", t.occurredAt)
                    })
                }
            })

            val expenseReceivables = expenseReceivableDao.getAllOnce(book.id)
            bookJson.put("expenseReceivables", JSONArray().apply {
                expenseReceivables.forEach { r ->
                    put(JSONObject().apply {
                        put("transactionLocalId", r.transactionId ?: JSONObject.NULL)
                        put("personName", r.personName); put("amount", r.amount); put("note", r.note ?: JSONObject.NULL)
                        put("isCollected", r.isCollected); put("createdAt", r.createdAt); put("collectedAt", r.collectedAt ?: JSONObject.NULL)
                    })
                }
            })
        } else {
            val fds = fixedDepositDao.getActive(book.id).first()
            val liabilities = liabilityDao.getActive(book.id).first()
            val cash = cashInHandDao.get(book.id).first()
            val demat = dematHoldingDao.getActiveForBook(book.id).first()
            val dematAccounts = dematAccountDao.getAllOnce(book.id)
            bookJson.put("dematAccounts", JSONArray().apply {
                dematAccounts.forEach { put(JSONObject().apply { put("name", it.name) }) }
            })
            val dematAccountsById = dematAccounts.associateBy { it.id }

            bookJson.put("fixedDeposits", JSONArray().apply {
                fds.forEach { fd ->
                    put(JSONObject().apply {
                        put("name", fd.name); put("kind", fd.kind.name); put("principalAmount", fd.principalAmount)
                        put("startDate", fd.startDate); put("tenureMonths", fd.tenureMonths); put("tenureExtraDays", fd.tenureExtraDays)
                        put("interestRate", fd.interestRate); put("interestType", fd.interestType.name)
                        put("compoundingFrequency", fd.compoundingFrequency.name)
                        // These three were missing entirely — a real,
                        // confirmed gap. Bank Name and Account Number
                        // silently vanished on restore even though the
                        // Name field itself was often just a manual
                        // combination of the two; autoRenewMode was also
                        // silently lost.
                        put("bankName", fd.bankName ?: ""); put("accountOrCertificateNumber", fd.accountOrCertificateNumber ?: "")
                        put("autoRenewMode", fd.autoRenewMode.name)
                    })
                }
            })
            bookJson.put("liabilities", JSONArray().apply {
                liabilities.forEach { l ->
                    put(JSONObject().apply {
                        put("name", l.name); put("liabilityType", l.liabilityType)
                        put("principalAmount", l.principalAmount); put("interestRate", l.interestRate)
                        put("tenureMonths", l.tenureMonths); put("emiAmount", l.emiAmount)
                        put("startDate", l.startDate); put("outstandingBalance", l.outstandingBalance)
                    })
                }
            })
            bookJson.put("cashInHand", cash?.amount ?: 0.0)
            bookJson.put("dematHoldings", JSONArray().apply {
                demat.forEach { d ->
                    put(JSONObject().apply {
                        put("accountName", dematAccountsById[d.dematAccountId]?.name ?: "")
                        put("stockSymbol", d.stockSymbol); put("exchangeCode", d.exchangeCode.name)
                        put("unitsHeld", d.unitsHeld); put("avgBuyPrice", d.avgBuyPrice)
                        // Both were missing — less severe than most gaps
                        // found here since prices self-heal on the next
                        // automatic refresh, but still a real omission,
                        // especially for symbols needing manual pricing
                        // (bonds, SGBs) which never auto-refresh at all.
                        put("lastFetchedPrice", d.lastFetchedPrice ?: JSONObject.NULL)
                        put("lastFetchedAt", d.lastFetchedAt ?: JSONObject.NULL)
                    })
                }
            })

            // The 7 categories below were added after this backup logic was
            // first written and never wired in — a real gap found during
            // review, since backing up a Wealth book using any of these
            // would have silently lost that data entirely.
            val rds = recurringDepositDao.getActive(book.id).first()
            bookJson.put("recurringDeposits", JSONArray().apply {
                rds.forEach { rd ->
                    put(JSONObject().apply {
                        put("name", rd.name); put("bankName", rd.bankName ?: ""); put("accountOrCertificateNumber", rd.accountOrCertificateNumber ?: "")
                        put("monthlyInstallment", rd.monthlyInstallment); put("startDate", rd.startDate)
                        put("tenureMonths", rd.tenureMonths); put("interestRate", rd.interestRate)
                        put("compoundingFrequency", rd.compoundingFrequency.name)
                        // Same gap as FD — autoRenewMode was missing here too.
                        put("autoRenewMode", rd.autoRenewMode.name)
                        put("installments", JSONArray().apply {
                            rdInstallmentDao.getInstallmentsOnce(rd.id).forEach { entry ->
                                put(JSONObject().apply { put("amount", entry.amount); put("installmentDate", entry.installmentDate) })
                            }
                        })
                    })
                }
            })

            val ppfAccounts = ppfDao.getActiveAccounts(book.id).first()
            bookJson.put("ppfAccounts", JSONArray().apply {
                ppfAccounts.forEach { p ->
                    put(JSONObject().apply {
                        put("name", p.name); put("accountNumber", p.accountNumber ?: ""); put("bankOrPostOfficeName", p.bankOrPostOfficeName ?: "")
                        put("accountOpenDate", p.accountOpenDate)
                        put("deposits", JSONArray().apply {
                            ppfDao.getDepositsOnce(p.id).forEach { d ->
                                put(JSONObject().apply {
                                    put("amount", d.amount); put("depositDate", d.depositDate)
                                    put("type", d.type.name); put("note", d.note ?: "")
                                })
                            }
                        })
                    })
                }
            })

            val epfAccounts = epfDao.getActiveAccounts(book.id).first()
            bookJson.put("epfAccounts", JSONArray().apply {
                epfAccounts.forEach { e ->
                    put(JSONObject().apply {
                        put("name", e.name); put("companyName", e.companyName ?: ""); put("pfAccountNumber", e.pfAccountNumber ?: "")
                        put("uan", e.uan ?: ""); put("accountOpenDate", e.accountOpenDate)
                        // These two were missing entirely from backup — a
                        // real, confirmed gap. Step Up/Down amounts set on
                        // either tile were silently lost on restore.
                        put("monthlyEeContribution", e.monthlyEeContribution ?: JSONObject.NULL)
                        put("monthlyPensionContribution", e.monthlyPensionContribution ?: JSONObject.NULL)
                        put("recurringDepositDate", e.recurringDepositDate ?: JSONObject.NULL)
                        put("contributions", JSONArray().apply {
                            epfDao.getContributionsOnce(e.id).forEach { c ->
                                put(JSONObject().apply {
                                    put("amount", c.amount); put("contributionDate", c.contributionDate)
                                    put("tileType", c.tileType.name); put("isTransfer", c.isTransfer)
                                })
                            }
                        })
                    })
                }
            })

            val npsAccounts = npsDao.getActive(book.id).first()
            bookJson.put("npsAccounts", JSONArray().apply {
                npsAccounts.forEach { n ->
                    put(JSONObject().apply {
                        put("name", n.name); put("pran", n.pran ?: ""); put("pfm", n.pfm); put("tier", n.tier)
                        put("currentValue", n.currentValue); put("lastUpdatedAt", n.lastUpdatedAt)
                        put("monthlyContribution", n.monthlyContribution ?: JSONObject.NULL)
                        put("recurringDepositDate", n.recurringDepositDate ?: JSONObject.NULL)
                        put("lastContributionDate", n.lastContributionDate ?: JSONObject.NULL)
                    })
                }
            })

            val apyAccounts = apyDao.getActive(book.id).first()
            bookJson.put("apyAccounts", JSONArray().apply {
                apyAccounts.forEach { a ->
                    put(JSONObject().apply {
                        put("name", a.name); put("pran", a.pran ?: ""); put("bankName", a.bankName ?: "")
                        put("accountNumber", a.accountNumber ?: ""); put("ifsc", a.ifsc ?: "")
                        put("monthlyContribution", a.monthlyContribution); put("pensionSlab", a.pensionSlab)
                        put("startDate", a.startDate); put("totalContributedSoFar", a.totalContributedSoFar)
                        put("lastContributionDate", a.lastContributionDate ?: JSONObject.NULL)
                        put("recurringDepositDate", a.recurringDepositDate ?: JSONObject.NULL)
                    })
                }
            })

            val policies = insuranceDao.getActive(book.id).first()
            bookJson.put("insurancePolicies", JSONArray().apply {
                policies.forEach { p ->
                    put(JSONObject().apply {
                        put("policyName", p.policyName); put("policyNumber", p.policyNumber ?: ""); put("insurerName", p.insurerName)
                        put("policyType", p.policyType); put("startDate", p.startDate ?: JSONObject.NULL); put("maturityDate", p.maturityDate ?: JSONObject.NULL)
                        put("sumAssured", p.sumAssured); put("premiumAmount", p.premiumAmount); put("premiumFrequency", p.premiumFrequency)
                        put("premiumPaymentTermYears", p.premiumPaymentTermYears ?: JSONObject.NULL); put("nextDueDate", p.nextDueDate ?: JSONObject.NULL)
                        put("hasCashValue", p.hasCashValue); put("currentSurrenderValue", p.currentSurrenderValue)
                    })
                }
            })

            val assets = manualAssetDao.getActive(book.id).first()
            bookJson.put("manualAssets", JSONArray().apply {
                assets.forEach { a ->
                    put(JSONObject().apply {
                        put("name", a.name); put("assetType", a.assetType); put("currentValue", a.currentValue)
                        put("lastUpdatedByUserAt", a.lastUpdatedByUserAt)
                    })
                }
            })

            val banks = bankBalanceDao.getActive(book.id).first()
            bookJson.put("bankBalances", JSONArray().apply {
                banks.forEach { b ->
                    put(JSONObject().apply {
                        put("bankName", b.bankName); put("accountDetails", b.accountDetails ?: ""); put("currentBalance", b.currentBalance)
                    })
                }
            })

            val receivables = wealthReceivableDao.getActive(book.id).first()
            bookJson.put("wealthReceivables", JSONArray().apply {
                receivables.forEach { r ->
                    put(JSONObject().apply {
                        put("personName", r.personName); put("note", r.note ?: ""); put("currentAmount", r.currentAmount)
                        put("lastUpdatedAt", r.lastUpdatedAt)
                    })
                }
            })

            val mutualFunds = mutualFundDao.getActiveAccounts(book.id).first()
            val mfPlatforms = mutualFundPlatformDao.getAllOnce(book.id)
            bookJson.put("mutualFundPlatforms", JSONArray().apply {
                mfPlatforms.forEach { put(JSONObject().apply { put("name", it.name) }) }
            })
            val mfPlatformsById = mfPlatforms.associateBy { it.id }
            bookJson.put("mutualFunds", JSONArray().apply {
                mutualFunds.forEach { m ->
                    put(JSONObject().apply {
                        put("platformName", mfPlatformsById[m.platformId]?.name ?: "")
                        put("schemeCode", m.schemeCode); put("schemeName", m.schemeName)
                        put("folioNumber", m.folioNumber ?: ""); put("fundHouse", m.fundHouse ?: "")
                        put("lastFetchedNav", m.lastFetchedNav ?: JSONObject.NULL)
                        put("lastFetchedAt", m.lastFetchedAt ?: JSONObject.NULL)
                        put("entries", JSONArray().apply {
                            mutualFundDao.getEntriesOnce(m.id).forEach { entry ->
                                put(JSONObject().apply {
                                    put("investedAmount", entry.investedAmount); put("navAtPurchase", entry.navAtPurchase)
                                    put("unitsAllotted", entry.unitsAllotted); put("purchaseDate", entry.purchaseDate)
                                    put("isSip", entry.isSip)
                                })
                            }
                        })
                        val sip = mutualFundDao.getSipOnce(m.id)
                        put("sip", if (sip == null) JSONObject.NULL else JSONObject().apply {
                            put("sipAmount", sip.sipAmount); put("startDate", sip.startDate)
                            put("isHeld", sip.isHeld); put("lastConfirmedDate", sip.lastConfirmedDate ?: JSONObject.NULL)
                        })
                    })
                }
            })

            // Kametti was entirely missing from backup — a real gap found
            // during verification, meaning every Kametti account and its
            // entries would be permanently lost on a backup/reinstall/
            // restore cycle. Added here following the same pattern as
            // Mutual Funds (account + nested entries).
            val kamettiAccounts = kamettiDao.getActiveAccounts(book.id).first()
            bookJson.put("kamettiAccounts", JSONArray().apply {
                kamettiAccounts.forEach { k ->
                    put(JSONObject().apply {
                        put("name", k.name); put("monthlyAmount", k.monthlyAmount); put("startDate", k.startDate)
                        put("totalMonths", k.totalMonths); put("recurringDepositDate", k.recurringDepositDate ?: JSONObject.NULL)
                        put("entries", JSONArray().apply {
                            kamettiDao.getEntriesOnce(k.id).forEach { entry ->
                                put(JSONObject().apply {
                                    put("amount", entry.amount); put("entryDate", entry.entryDate); put("isWon", entry.isWon)
                                })
                            }
                        })
                    })
                }
            })

            // Metal holdings, per-book — simplified per explicit request,
            // no separate shared rate table anymore, quantity and price
            // both live directly on each (metal, carat, form) tile.
            val metalHoldings = metalHoldingDao.getActive(book.id).first()
            bookJson.put("metalHoldings", JSONArray().apply {
                metalHoldings.forEach { m ->
                    put(JSONObject().apply {
                        put("metalType", m.metalType.name); put("caratType", m.caratType); put("form", m.form.name)
                        put("quantityGrams", m.quantityGrams)
                        put("currentPricePerGram", m.currentPricePerGram ?: JSONObject.NULL)
                        put("lastUpdatedAt", m.lastUpdatedAt ?: JSONObject.NULL)
                    })
                }
            })
        }

        return bookJson
    }

    // ---------- IMPORT ----------

    /**
     * First-login restore — used when there's no active book yet to merge
     * into (unlike the normal importBackup() below, which merges a
     * single-book file into whatever book is currently active). Every
     * book in the file gets created fresh here, regardless of count,
     * since "merge into the current book" doesn't make sense when there
     * is no current book.
     */
    suspend fun restoreAsNewBooks(inputStream: InputStream): BackupImportResult {
        val text = inputStream.bufferedReader().use { it.readText() }
        val root = try { JSONObject(text) } catch (e: Exception) { return BackupImportResult.InvalidFile }
        if (!root.has("schemaVersion") || !root.has("books")) return BackupImportResult.InvalidFile

        val booksJson = root.getJSONArray("books")
        if (booksJson.length() == 0) return BackupImportResult.InvalidFile

        restoreRateHistory(root)

        var created = 0
        var firstNewBookId: Long? = null
        val warnings = mutableListOf<String>()
        for (i in 0 until booksJson.length()) {
            val bookJson = booksJson.getJSONObject(i)
            val bookName = bookJson.optString("name", "Book ${i + 1}")
            try {
                val now = System.currentTimeMillis()
                val newBookId = bookDao.insert(
                    BookEntity(
                        name = bookJson.getString("name"),
                        bookType = BookType.valueOf(bookJson.getString("bookType")),
                        currencyCode = bookJson.getString("currencyCode"),
                        iconColor = "#2E5E4E",
                        createdAt = now,
                        lastOpenedAt = now
                    )
                )
                if (firstNewBookId == null) firstNewBookId = newBookId
                mergeIntoBook(newBookId, bookJson)
                created++
            } catch (e: Exception) {
                // A single book failing to restore no longer crashes the
                // whole operation or silently drops every book after it —
                // this book is skipped, its exact reason recorded, and
                // every other book still restores normally.
                warnings.add("\"$bookName\" could not be restored: ${e.message}")
            }
        }
        if (created == 0) return BackupImportResult.InvalidFile
        return BackupImportResult.Success(created, "Created $created book(s)", firstNewBookId, warnings)
    }

    suspend fun importBackup(inputStream: InputStream): BackupImportResult {
        val text = inputStream.bufferedReader().use { it.readText() }
        val root = try { JSONObject(text) } catch (e: Exception) { return BackupImportResult.InvalidFile }
        if (!root.has("schemaVersion") || !root.has("books")) return BackupImportResult.InvalidFile

        val booksJson = root.getJSONArray("books")
        if (booksJson.length() == 0) return BackupImportResult.InvalidFile

        restoreRateHistory(root)

        return if (booksJson.length() == 1) {
            // Single-book file — merge into the currently active book.
            val activeBookId = session.activeBookId.first() ?: return BackupImportResult.InvalidFile
            try {
                val counts = mergeIntoBook(activeBookId, booksJson.getJSONObject(0))
                BackupImportResult.Success(1, counts)
            } catch (e: Exception) {
                BackupImportResult.Success(0, "Restore failed: ${e.message}", warnings = listOf("Restore failed: ${e.message}"))
            }
        } else {
            // Multi-book file — create a fresh book per entry.
            var created = 0
            val warnings = mutableListOf<String>()
            for (i in 0 until booksJson.length()) {
                val bookJson = booksJson.getJSONObject(i)
                val bookName = bookJson.optString("name", "Book ${i + 1}")
                try {
                    val now = System.currentTimeMillis()
                    val newBookId = bookDao.insert(
                        BookEntity(
                            name = bookJson.getString("name"),
                            bookType = BookType.valueOf(bookJson.getString("bookType")),
                            currencyCode = bookJson.getString("currencyCode"),
                            iconColor = "#2E5E4E",
                            createdAt = now,
                            lastOpenedAt = now
                        )
                    )
                    mergeIntoBook(newBookId, bookJson)
                    created++
                } catch (e: Exception) {
                    // Same protection as restoreAsNewBooks — one book
                    // failing no longer crashes the whole restore or
                    // silently drops every book after it.
                    warnings.add("\"$bookName\" could not be restored: ${e.message}")
                }
            }
            if (created == 0) BackupImportResult.InvalidFile else BackupImportResult.Success(created, "Created $created new book(s)", warnings = warnings)
        }
    }

    /** Restores the global rate-history section, if present — older backup
     *  files (from before this fix) simply won't have this key, in which
     *  case restoring silently does nothing rather than failing. Skips
     *  entries that already exist (same instrument+rate+date) so repeated
     *  restores from the same file don't pile up duplicates. */
    private suspend fun restoreRateHistory(root: JSONObject) {
        val ratesJson = root.optJSONArray("rateHistory") ?: return
        val existingPpf = rateHistoryDao.getHistoryOnce(RateInstrument.PPF)
        val existingEpf = rateHistoryDao.getHistoryOnce(RateInstrument.EPF)

        for (i in 0 until ratesJson.length()) {
            val rateJson = ratesJson.getJSONObject(i)
            val instrument = RateInstrument.valueOf(rateJson.getString("instrument"))
            val ratePercent = rateJson.getDouble("ratePercent")
            val effectiveFrom = rateJson.getLong("effectiveFrom")

            val existing = if (instrument == RateInstrument.PPF) existingPpf else existingEpf
            val alreadyExists = existing.any { it.ratePercent == ratePercent && it.effectiveFrom == effectiveFrom }
            if (!alreadyExists) {
                rateHistoryDao.insert(WealthRateHistoryEntity(instrument = instrument, ratePercent = ratePercent, effectiveFrom = effectiveFrom))
            }
        }
    }

    private suspend fun mergeIntoBook(bookId: Long, bookJson: JSONObject): String {
        val bookType = BookType.valueOf(bookJson.optString("bookType", "EXPENSE"))
        val now = System.currentTimeMillis()

        return if (bookType == BookType.EXPENSE) {
            val accountIdMap = mutableMapOf<Long, Long>()
            val categoryIdMap = mutableMapOf<Long, Long>()

            val existingAccountsByName = accountDao.getActiveAccounts(bookId).first().associateBy { it.name }
            val accountsJson = bookJson.optJSONArray("accounts") ?: JSONArray()
            for (i in 0 until accountsJson.length()) {
                val a = accountsJson.getJSONObject(i)
                val localId = a.getLong("localId")
                val existing = existingAccountsByName[a.getString("name")]
                val realId = existing?.id ?: accountDao.insert(
                    AccountEntity(
                        bookId = bookId, name = a.getString("name"),
                        type = AccountType.valueOf(a.getString("type")),
                        isDefault = a.optBoolean("isDefault", false), createdAt = a.optLong("createdAt", now)
                    )
                )
                accountIdMap[localId] = realId
            }

            val existingCategoriesByName = categoryDao.getActiveCategories(bookId).first().associateBy { it.name }
            val categoriesJson = bookJson.optJSONArray("categories") ?: JSONArray()
            for (i in 0 until categoriesJson.length()) {
                val c = categoriesJson.getJSONObject(i)
                val localId = c.getLong("localId")
                val existing = existingCategoriesByName[c.getString("name")]
                val realId = existing?.id ?: categoryDao.insert(
                    CategoryEntity(
                        bookId = bookId, name = c.getString("name"), icon = c.optString("icon", "\ud83d\udce6"),
                        colorHex = c.optString("colorHex", "#2E5E4E")
                    )
                )
                categoryIdMap[localId] = realId
            }

            var recurringCount = 0
            val rulesJson = bookJson.optJSONArray("recurringRules") ?: JSONArray()
            for (i in 0 until rulesJson.length()) {
                val r = rulesJson.getJSONObject(i)
                val accountId = accountIdMap[r.getLong("accountLocalId")] ?: continue
                recurringRuleDao.insert(
                    RecurringRuleEntity(
                        bookId = bookId, name = r.getString("name"),
                        type = TransactionType.valueOf(r.getString("type")), amount = r.getDouble("amount"),
                        accountId = accountId,
                        toAccountId = r.optLong("toAccountLocalId", -1).takeIf { it != -1L }?.let { accountIdMap[it] },
                        categoryId = r.optLong("categoryLocalId", -1).takeIf { it != -1L }?.let { categoryIdMap[it] },
                        note = if (r.isNull("note")) null else r.getString("note"),
                        frequency = RecurrenceFrequency.valueOf(r.getString("frequency")), interval = r.optInt("interval", 1),
                        startDate = r.getLong("startDate"), endDate = r.optLong("endDate", -1).takeIf { it != -1L },
                        nextRunAt = r.getLong("nextRunAt"), isPaused = r.optBoolean("isPaused", false)
                    )
                )
                recurringCount++
            }

            var transactionCount = 0
            val transactionIdMap = mutableMapOf<Long, Long>()
            val txJson = bookJson.optJSONArray("transactions") ?: JSONArray()
            for (i in 0 until txJson.length()) {
                val t = txJson.getJSONObject(i)
                val accountId = accountIdMap[t.getLong("accountLocalId")] ?: continue
                val newTransactionId = transactionDao.insert(
                    TransactionEntity(
                        bookId = bookId, type = TransactionType.valueOf(t.getString("type")), amount = t.getDouble("amount"),
                        accountId = accountId,
                        toAccountId = t.optLong("toAccountLocalId", -1).takeIf { it != -1L }?.let { accountIdMap[it] },
                        categoryId = t.optLong("categoryLocalId", -1).takeIf { it != -1L }?.let { categoryIdMap[it] },
                        note = if (t.isNull("note")) null else t.getString("note"), occurredAt = t.getLong("occurredAt"),
                        createdAt = now, updatedAt = now
                    )
                )
                if (t.has("localId")) transactionIdMap[t.getLong("localId")] = newTransactionId
                transactionCount++
            }

            var receivableCount = 0
            val receivablesJson = bookJson.optJSONArray("expenseReceivables") ?: JSONArray()
            for (i in 0 until receivablesJson.length()) {
                val r = receivablesJson.getJSONObject(i)
                val newTransactionId = r.optLong("transactionLocalId", -1).takeIf { it != -1L }?.let { transactionIdMap[it] }
                expenseReceivableDao.insert(
                    ExpenseReceivableEntity(
                        bookId = bookId, transactionId = newTransactionId, personName = r.getString("personName"),
                        amount = r.getDouble("amount"), note = if (r.isNull("note")) null else r.optString("note").ifBlank { null },
                        isCollected = r.optBoolean("isCollected", false), createdAt = r.optLong("createdAt", now),
                        collectedAt = if (r.isNull("collectedAt")) null else r.optLong("collectedAt")
                    )
                )
                receivableCount++
            }

            "$transactionCount transactions, $recurringCount recurring rules, $receivableCount receivables"
        } else {
            var fdCount = 0
            val fdsJson = bookJson.optJSONArray("fixedDeposits") ?: JSONArray()
            for (i in 0 until fdsJson.length()) {
                val fd = fdsJson.getJSONObject(i)
                fixedDepositDao.insert(
                    FixedDepositEntity(
                        bookId = bookId, name = fd.getString("name"),
                        kind = WealthInstrumentKind.valueOf(fd.optString("kind", "FD")),
                        bankName = fd.optString("bankName").ifBlank { null },
                        accountOrCertificateNumber = fd.optString("accountOrCertificateNumber").ifBlank { null },
                        principalAmount = fd.getDouble("principalAmount"),
                        startDate = fd.getLong("startDate"), tenureMonths = fd.getInt("tenureMonths"), tenureExtraDays = fd.optInt("tenureExtraDays", 0),
                        interestRate = fd.getDouble("interestRate"), interestType = InterestType.valueOf(fd.getString("interestType")),
                        compoundingFrequency = CompoundingFrequency.valueOf(fd.getString("compoundingFrequency")),
                        autoRenewMode = runCatching { AutoRenewMode.valueOf(fd.optString("autoRenewMode", "DISABLED")) }.getOrDefault(AutoRenewMode.DISABLED)
                    )
                )
                fdCount++
            }

            var liabilityCount = 0
            val liabilitiesJson = bookJson.optJSONArray("liabilities") ?: JSONArray()
            for (i in 0 until liabilitiesJson.length()) {
                val l = liabilitiesJson.getJSONObject(i)
                liabilityDao.insert(
                    LiabilityEntity(
                        bookId = bookId, name = l.getString("name"), liabilityType = l.getString("liabilityType"),
                        principalAmount = l.getDouble("principalAmount"), interestRate = l.getDouble("interestRate"),
                        tenureMonths = l.getInt("tenureMonths"), emiAmount = l.getDouble("emiAmount"),
                        startDate = l.getLong("startDate"), outstandingBalance = l.getDouble("outstandingBalance")
                    )
                )
                liabilityCount++
            }

            if (bookJson.has("cashInHand")) {
                cashInHandDao.upsert(CashInHandEntity(bookId = bookId, amount = bookJson.getDouble("cashInHand"), lastUpdatedAt = now))
            }

            // Demat Accounts — restored first since holdings reference
            // them by name. Matches by name so restoring into a book
            // that already has some accounts doesn't duplicate them.
            var dematAccountCount = 0
            val dematAccountsJson = bookJson.optJSONArray("dematAccounts") ?: JSONArray()
            val existingDematAccountsForRestore = dematAccountDao.getAllOnce(bookId).associateBy { it.name }.toMutableMap()
            for (i in 0 until dematAccountsJson.length()) {
                val name = dematAccountsJson.getJSONObject(i).optString("name")
                if (name.isBlank() || existingDematAccountsForRestore.containsKey(name)) continue
                val newAccount = com.teamproject1.dailyexpensetracker.core.database.entity.DematAccountEntity(bookId = bookId, name = name)
                val id = dematAccountDao.insert(newAccount)
                existingDematAccountsForRestore[name] = newAccount.copy(id = id)
                dematAccountCount++
            }

            var dematCount = 0
            val dematJson = bookJson.optJSONArray("dematHoldings") ?: JSONArray()
            // Rows with a recognized accountName are routed there; rows
            // with no accountName at all (an old-format backup from
            // before Demat accounts existed) fall back to this book's
            // default Demat account, created only if actually needed —
            // this is the real migration path for existing data.
            var dematDefaultAccountId: Long? = null
            for (i in 0 until dematJson.length()) {
                val d = dematJson.getJSONObject(i)
                val accountName = d.optString("accountName").ifBlank { null }
                val dematAccountId = accountName?.let { existingDematAccountsForRestore[it]?.id }
                    ?: (dematDefaultAccountId ?: defaultDematAccountId(bookId).also { dematDefaultAccountId = it })
                dematHoldingDao.insert(
                    DematHoldingEntity(
                        bookId = bookId, dematAccountId = dematAccountId, stockSymbol = d.getString("stockSymbol"),
                        exchangeCode = ExchangeCode.valueOf(d.getString("exchangeCode")),
                        unitsHeld = d.getDouble("unitsHeld"), avgBuyPrice = d.optDouble("avgBuyPrice", 0.0),
                        lastFetchedPrice = if (d.isNull("lastFetchedPrice")) null else d.optDouble("lastFetchedPrice"),
                        lastFetchedAt = if (d.isNull("lastFetchedAt")) null else d.optLong("lastFetchedAt")
                    )
                )
                dematCount++
            }

            var rdCount = 0
            val rdsJson = bookJson.optJSONArray("recurringDeposits") ?: JSONArray()
            for (i in 0 until rdsJson.length()) {
                val rd = rdsJson.getJSONObject(i)
                val newRdId = recurringDepositDao.insert(
                    RecurringDepositEntity(
                        bookId = bookId, name = rd.getString("name"), bankName = rd.optString("bankName").ifBlank { null },
                        accountOrCertificateNumber = rd.optString("accountOrCertificateNumber").ifBlank { null },
                        monthlyInstallment = rd.getDouble("monthlyInstallment"), startDate = rd.getLong("startDate"),
                        tenureMonths = rd.getInt("tenureMonths"), interestRate = rd.getDouble("interestRate"),
                        compoundingFrequency = CompoundingFrequency.valueOf(rd.optString("compoundingFrequency", "QUARTERLY")),
                        autoRenewMode = runCatching { AutoRenewMode.valueOf(rd.optString("autoRenewMode", "DISABLED")) }.getOrDefault(AutoRenewMode.DISABLED)
                    )
                )
                val installmentsJson = rd.optJSONArray("installments") ?: JSONArray()
                for (j in 0 until installmentsJson.length()) {
                    val inst = installmentsJson.getJSONObject(j)
                    rdInstallmentDao.insert(
                        com.teamproject1.dailyexpensetracker.core.database.entity.RdInstallmentEntity(
                            rdAccountId = newRdId, amount = inst.getDouble("amount"), installmentDate = inst.getLong("installmentDate")
                        )
                    )
                }
                rdCount++
            }

            var ppfCount = 0
            val ppfJson = bookJson.optJSONArray("ppfAccounts") ?: JSONArray()
            for (i in 0 until ppfJson.length()) {
                val p = ppfJson.getJSONObject(i)
                val newPpfId = ppfDao.insertAccount(
                    PpfAccountEntity(
                        bookId = bookId, name = p.getString("name"), accountNumber = p.optString("accountNumber").ifBlank { null },
                        bankOrPostOfficeName = p.optString("bankOrPostOfficeName").ifBlank { null }, accountOpenDate = p.getLong("accountOpenDate")
                    )
                )
                val depositsJson = p.optJSONArray("deposits") ?: JSONArray()
                for (j in 0 until depositsJson.length()) {
                    val d = depositsJson.getJSONObject(j)
                    ppfDao.insertDeposit(
                        PpfDepositEntity(
                            ppfAccountId = newPpfId, amount = d.getDouble("amount"), depositDate = d.getLong("depositDate"),
                            type = runCatching { PpfEntryType.valueOf(d.optString("type", "DEPOSIT")) }.getOrDefault(PpfEntryType.DEPOSIT),
                            note = d.optString("note").ifBlank { null }
                        )
                    )
                }
                ppfCount++
            }

            var epfCount = 0
            val epfJson = bookJson.optJSONArray("epfAccounts") ?: JSONArray()
            for (i in 0 until epfJson.length()) {
                val e = epfJson.getJSONObject(i)
                val newEpfId = epfDao.insertAccount(
                    EpfAccountEntity(
                        bookId = bookId, name = e.getString("name"), companyName = e.optString("companyName").ifBlank { null },
                        pfAccountNumber = e.optString("pfAccountNumber").ifBlank { null }, uan = e.optString("uan").ifBlank { null },
                        accountOpenDate = e.getLong("accountOpenDate"),
                        monthlyEeContribution = if (e.isNull("monthlyEeContribution")) null else e.optDouble("monthlyEeContribution"),
                        monthlyPensionContribution = if (e.isNull("monthlyPensionContribution")) null else e.optDouble("monthlyPensionContribution"),
                        // Falls back to accountOpenDate for backups taken
                        // before this field existed — same default the
                        // app itself uses for a freshly-created account.
                        recurringDepositDate = if (e.isNull("recurringDepositDate")) e.getLong("accountOpenDate") else e.optLong("recurringDepositDate")
                    )
                )
                val contributionsJson = e.optJSONArray("contributions") ?: JSONArray()
                for (j in 0 until contributionsJson.length()) {
                    val c = contributionsJson.getJSONObject(j)
                    epfDao.insertContribution(
                        EpfContributionEntity(
                            epfAccountId = newEpfId, amount = c.getDouble("amount"), contributionDate = c.getLong("contributionDate"),
                            tileType = runCatching { com.teamproject1.dailyexpensetracker.core.database.entity.EpfTileType.valueOf(c.optString("tileType", "EMPLOYEE_EMPLOYER")) }.getOrDefault(com.teamproject1.dailyexpensetracker.core.database.entity.EpfTileType.EMPLOYEE_EMPLOYER),
                            isTransfer = c.optBoolean("isTransfer", false)
                        )
                    )
                }
                epfCount++
            }

            var npsCount = 0
            val npsJson = bookJson.optJSONArray("npsAccounts") ?: JSONArray()
            for (i in 0 until npsJson.length()) {
                val n = npsJson.getJSONObject(i)
                npsDao.insert(
                    NpsAccountEntity(
                        bookId = bookId, name = n.getString("name"), pran = n.optString("pran").ifBlank { null },
                        pfm = n.getString("pfm"), tier = n.optString("tier", "TIER_I"),
                        currentValue = n.getDouble("currentValue"), lastUpdatedAt = n.optLong("lastUpdatedAt", now),
                        monthlyContribution = if (n.isNull("monthlyContribution")) null else n.optDouble("monthlyContribution"),
                        recurringDepositDate = if (n.isNull("recurringDepositDate")) null else n.optLong("recurringDepositDate"),
                        lastContributionDate = if (n.isNull("lastContributionDate")) null else n.optLong("lastContributionDate")
                    )
                )
                npsCount++
            }

            var apyCount = 0
            val apyJson = bookJson.optJSONArray("apyAccounts") ?: JSONArray()
            for (i in 0 until apyJson.length()) {
                val a = apyJson.getJSONObject(i)
                apyDao.insert(
                    ApyAccountEntity(
                        bookId = bookId, name = a.getString("name"), pran = a.optString("pran").ifBlank { null },
                        bankName = a.optString("bankName").ifBlank { null }, accountNumber = a.optString("accountNumber").ifBlank { null },
                        ifsc = a.optString("ifsc").ifBlank { null }, monthlyContribution = a.getDouble("monthlyContribution"),
                        pensionSlab = a.getInt("pensionSlab"), startDate = a.getLong("startDate"),
                        totalContributedSoFar = a.optDouble("totalContributedSoFar", 0.0),
                        lastContributionDate = if (a.isNull("lastContributionDate")) null else a.optLong("lastContributionDate"),
                        recurringDepositDate = if (a.isNull("recurringDepositDate")) null else a.optLong("recurringDepositDate")
                    )
                )
                apyCount++
            }

            var insuranceCount = 0
            val insuranceJson = bookJson.optJSONArray("insurancePolicies") ?: JSONArray()
            for (i in 0 until insuranceJson.length()) {
                val p = insuranceJson.getJSONObject(i)
                insuranceDao.insert(
                    InsurancePolicyEntity(
                        bookId = bookId, policyName = p.getString("policyName"), policyNumber = p.optString("policyNumber").ifBlank { null },
                        insurerName = p.getString("insurerName"), policyType = p.getString("policyType"),
                        startDate = if (p.isNull("startDate")) null else p.getLong("startDate"),
                        maturityDate = if (p.isNull("maturityDate")) null else p.getLong("maturityDate"),
                        sumAssured = p.getDouble("sumAssured"), premiumAmount = p.getDouble("premiumAmount"), premiumFrequency = p.getString("premiumFrequency"),
                        premiumPaymentTermYears = if (p.isNull("premiumPaymentTermYears")) null else p.getInt("premiumPaymentTermYears"),
                        nextDueDate = if (p.isNull("nextDueDate")) null else p.getLong("nextDueDate"),
                        hasCashValue = p.optBoolean("hasCashValue", false), currentSurrenderValue = p.optDouble("currentSurrenderValue", 0.0)
                    )
                )
                insuranceCount++
            }

            var manualAssetCount = 0
            val manualAssetsJson = bookJson.optJSONArray("manualAssets") ?: JSONArray()
            for (i in 0 until manualAssetsJson.length()) {
                val a = manualAssetsJson.getJSONObject(i)
                manualAssetDao.insert(ManualAssetEntity(bookId = bookId, name = a.getString("name"), assetType = a.getString("assetType"), currentValue = a.getDouble("currentValue"), lastUpdatedByUserAt = a.optLong("lastUpdatedByUserAt", now)))
                manualAssetCount++
            }

            var bankCount = 0
            val banksJson = bookJson.optJSONArray("bankBalances") ?: JSONArray()
            for (i in 0 until banksJson.length()) {
                val b = banksJson.getJSONObject(i)
                bankBalanceDao.insert(
                    BankBalanceEntity(
                        bookId = bookId, bankName = b.getString("bankName"), accountDetails = b.optString("accountDetails").ifBlank { null },
                        currentBalance = b.getDouble("currentBalance"), lastUpdatedAt = now
                    )
                )
                bankCount++
            }

            var receivableCount = 0
            val receivablesJson = bookJson.optJSONArray("wealthReceivables") ?: JSONArray()
            for (i in 0 until receivablesJson.length()) {
                val r = receivablesJson.getJSONObject(i)
                wealthReceivableDao.insert(
                    WealthReceivableEntity(
                        bookId = bookId, personName = r.getString("personName"), note = r.optString("note").ifBlank { null },
                        currentAmount = r.getDouble("currentAmount"), lastUpdatedAt = r.optLong("lastUpdatedAt", now)
                    )
                )
                receivableCount++
            }

            // Mutual Fund Platforms — restored first since funds
            // reference them by name. Matches by name so restoring into
            // a book that already has some platforms doesn't duplicate
            // them.
            var mfPlatformCount = 0
            val mfPlatformsJson = bookJson.optJSONArray("mutualFundPlatforms") ?: JSONArray()
            val existingMfPlatformsForRestore = mutualFundPlatformDao.getAllOnce(bookId).associateBy { it.name }.toMutableMap()
            for (i in 0 until mfPlatformsJson.length()) {
                val name = mfPlatformsJson.getJSONObject(i).optString("name")
                if (name.isBlank() || existingMfPlatformsForRestore.containsKey(name)) continue
                val newPlatform = com.teamproject1.dailyexpensetracker.core.database.entity.MutualFundPlatformEntity(bookId = bookId, name = name)
                val id = mutualFundPlatformDao.insert(newPlatform)
                existingMfPlatformsForRestore[name] = newPlatform.copy(id = id)
                mfPlatformCount++
            }

            var mutualFundCount = 0
            var mutualFundEntryCount = 0
            val mutualFundsJson = bookJson.optJSONArray("mutualFunds") ?: JSONArray()
            // Rows with a recognized platformName are routed there; rows
            // with no platformName at all (an old-format backup from
            // before Mutual Fund platforms existed) fall back to this
            // book's default account, created only if actually needed —
            // this is the real migration path for existing data.
            var mfDefaultPlatformId: Long? = null
            for (i in 0 until mutualFundsJson.length()) {
                val m = mutualFundsJson.getJSONObject(i)
                val platformName = m.optString("platformName").ifBlank { null }
                val platformId = platformName?.let { existingMfPlatformsForRestore[it]?.id }
                    ?: (mfDefaultPlatformId ?: defaultMutualFundPlatformId(bookId).also { mfDefaultPlatformId = it })
                val newMfId = mutualFundDao.insertAccount(
                    com.teamproject1.dailyexpensetracker.core.database.entity.MutualFundAccountEntity(
                        bookId = bookId, platformId = platformId, schemeCode = m.getString("schemeCode"), schemeName = m.getString("schemeName"),
                        folioNumber = m.optString("folioNumber").ifBlank { null }, fundHouse = m.optString("fundHouse").ifBlank { null },
                        lastFetchedNav = if (m.isNull("lastFetchedNav")) null else m.optDouble("lastFetchedNav"),
                        lastFetchedAt = if (m.isNull("lastFetchedAt")) null else m.optLong("lastFetchedAt")
                    )
                )
                val entriesJson = m.optJSONArray("entries") ?: JSONArray()
                for (j in 0 until entriesJson.length()) {
                    val entry = entriesJson.getJSONObject(j)
                    mutualFundDao.insertEntry(
                        com.teamproject1.dailyexpensetracker.core.database.entity.MutualFundEntryEntity(
                            mutualFundAccountId = newMfId, investedAmount = entry.getDouble("investedAmount"),
                            navAtPurchase = entry.getDouble("navAtPurchase"), unitsAllotted = entry.getDouble("unitsAllotted"),
                            purchaseDate = entry.getLong("purchaseDate"), isSip = entry.optBoolean("isSip", false)
                        )
                    )
                    mutualFundEntryCount++
                }
                if (!m.isNull("sip")) {
                    val sipJson = m.getJSONObject("sip")
                    mutualFundDao.insertSip(
                        com.teamproject1.dailyexpensetracker.core.database.entity.MutualFundSipEntity(
                            mutualFundAccountId = newMfId, sipAmount = sipJson.getDouble("sipAmount"), startDate = sipJson.getLong("startDate"),
                            isHeld = sipJson.optBoolean("isHeld", false),
                            lastConfirmedDate = if (sipJson.isNull("lastConfirmedDate")) null else sipJson.optLong("lastConfirmedDate")
                        )
                    )
                }
                mutualFundCount++
            }

            // Kametti was missing from restore entirely — added here
            // alongside the export fix, following the same account +
            // nested entries pattern as Mutual Funds.
            var kamettiCount = 0
            var kamettiEntryCount = 0
            val kamettiJson = bookJson.optJSONArray("kamettiAccounts") ?: JSONArray()
            for (i in 0 until kamettiJson.length()) {
                val k = kamettiJson.getJSONObject(i)
                val newKamettiId = kamettiDao.insertAccount(
                    com.teamproject1.dailyexpensetracker.core.database.entity.KamettiAccountEntity(
                        bookId = bookId, name = k.getString("name"), monthlyAmount = k.getDouble("monthlyAmount"),
                        startDate = k.getLong("startDate"), totalMonths = k.getInt("totalMonths"),
                        recurringDepositDate = if (k.isNull("recurringDepositDate")) null else k.optLong("recurringDepositDate")
                    )
                )
                val kamettiEntriesJson = k.optJSONArray("entries") ?: JSONArray()
                for (j in 0 until kamettiEntriesJson.length()) {
                    val entry = kamettiEntriesJson.getJSONObject(j)
                    kamettiDao.insertEntry(
                        com.teamproject1.dailyexpensetracker.core.database.entity.KamettiEntryEntity(
                            kamettiAccountId = newKamettiId, amount = entry.getDouble("amount"),
                            entryDate = entry.getLong("entryDate"), isWon = entry.optBoolean("isWon", false)
                        )
                    )
                    kamettiEntryCount++
                }
                kamettiCount++
            }

            // Metal holdings restored per-book like Kametti — no more
            // separate rate table, quantity and price live directly on
            // each tile now. Upserts by (metal, carat, form) rather than
            // blindly inserting, since that combination is now a unique
            // constraint — restoring into a book that already has some
            // Metal data would otherwise violate it and fail the whole
            // restore.
            var metalHoldingCount = 0
            val existingMetalForRestore = metalHoldingDao.getActiveOnce(bookId).associateBy { Triple(it.metalType, it.caratType, it.form) }.toMutableMap()
            val metalHoldingsJson = bookJson.optJSONArray("metalHoldings") ?: JSONArray()
            for (i in 0 until metalHoldingsJson.length()) {
                val m = metalHoldingsJson.getJSONObject(i)
                val metalType = com.teamproject1.dailyexpensetracker.core.database.entity.MetalType.valueOf(m.getString("metalType"))
                val caratType = m.getString("caratType")
                val form = com.teamproject1.dailyexpensetracker.core.database.entity.MetalForm.valueOf(m.getString("form"))
                val quantityGrams = m.getDouble("quantityGrams")
                val currentPricePerGram = if (m.isNull("currentPricePerGram")) null else m.optDouble("currentPricePerGram")
                val lastUpdatedAt = if (m.isNull("lastUpdatedAt")) null else m.optLong("lastUpdatedAt")
                val key = Triple(metalType, caratType, form)
                val existing = existingMetalForRestore[key]
                if (existing != null) {
                    val updated = existing.copy(quantityGrams = quantityGrams, currentPricePerGram = currentPricePerGram, lastUpdatedAt = lastUpdatedAt)
                    metalHoldingDao.update(updated)
                    existingMetalForRestore[key] = updated
                } else {
                    val newHolding = com.teamproject1.dailyexpensetracker.core.database.entity.MetalHoldingEntity(
                        bookId = bookId, metalType = metalType, caratType = caratType, form = form,
                        quantityGrams = quantityGrams, currentPricePerGram = currentPricePerGram, lastUpdatedAt = lastUpdatedAt
                    )
                    metalHoldingDao.insert(newHolding)
                    existingMetalForRestore[key] = newHolding
                }
                metalHoldingCount++
            }

            "$fdCount FDs, $rdCount RDs, $liabilityCount liabilities, $dematAccountCount demat accounts, $dematCount holdings, $ppfCount PPF, $epfCount EPF, $npsCount NPS, $apyCount APY, $insuranceCount policies, $manualAssetCount assets, $bankCount banks, $receivableCount receivables, $mfPlatformCount MF accounts, $mutualFundCount mutual funds, $mutualFundEntryCount MF entries, $kamettiCount Kametti, $kamettiEntryCount Kametti entries, $metalHoldingCount metal holdings"
        }
    }
}
