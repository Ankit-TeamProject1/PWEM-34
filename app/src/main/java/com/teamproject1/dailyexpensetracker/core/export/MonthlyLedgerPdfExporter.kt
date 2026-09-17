package com.teamproject1.dailyexpensetracker.core.export

import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import com.teamproject1.dailyexpensetracker.core.database.dao.AccountDao
import com.teamproject1.dailyexpensetracker.core.database.dao.CategoryDao
import com.teamproject1.dailyexpensetracker.core.database.dao.TransactionDao
import com.teamproject1.dailyexpensetracker.core.database.entity.TransactionType
import com.teamproject1.dailyexpensetracker.core.session.SessionManager
import kotlinx.coroutines.flow.first
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Simple table + total, per the locked format decision — not a
 * statement-style layout with category subtotals. One PDF page per month;
 * overflows to additional pages if the month has more rows than fit.
 */
@Singleton
class MonthlyLedgerPdfExporter @Inject constructor(
    private val transactionDao: TransactionDao,
    private val accountDao: AccountDao,
    private val categoryDao: CategoryDao,
    private val session: SessionManager
) {
    private val pageWidth = 595  // A4 at 72dpi
    private val pageHeight = 842
    private val margin = 40f
    private val rowHeight = 24f

    suspend fun export(month: String, monthLabel: String, outputStream: OutputStream) {
        val bookId = session.activeBookId.first() ?: return
        val allTransactions = transactionDao.getAll(bookId).first()
        val monthTransactions = allTransactions.filter {
            SimpleDateFormat("yyyy-MM", Locale.US).format(Date(it.occurredAt)) == month
        }.sortedBy { it.occurredAt }

        val accounts = accountDao.getActiveAccounts(bookId).first().associateBy { it.id }
        val categories = categoryDao.getActiveCategories(bookId).first().associateBy { it.id }
        val dateFormat = SimpleDateFormat("MMM d", Locale.US)

        val document = PdfDocument()
        var page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, document.pages.size + 1).create())
        var canvas = page.canvas
        var y = margin

        val titlePaint = Paint().apply { textSize = 18f; isFakeBoldText = true }
        val headerPaint = Paint().apply { textSize = 11f; isFakeBoldText = true }
        val bodyPaint = Paint().apply { textSize = 10f }

        canvas.drawText("Monthly Ledger — $monthLabel", margin, y, titlePaint)
        y += 30f

        val colDate = margin
        val colType = margin + 70f
        val colAccount = margin + 130f
        val colCategory = margin + 250f
        val colNote = margin + 360f
        val colAmount = pageWidth - margin - 70f

        fun drawHeaderRow() {
            canvas.drawText("Date", colDate, y, headerPaint)
            canvas.drawText("Type", colType, y, headerPaint)
            canvas.drawText("Account", colAccount, y, headerPaint)
            canvas.drawText("Category", colCategory, y, headerPaint)
            canvas.drawText("Note", colNote, y, headerPaint)
            canvas.drawText("Amount", colAmount, y, headerPaint)
            y += rowHeight
        }

        drawHeaderRow()

        var totalIncome = 0.0
        var totalExpense = 0.0

        for (tx in monthTransactions) {
            if (y > pageHeight - margin - rowHeight * 2) {
                document.finishPage(page)
                page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, document.pages.size + 1).create())
                canvas = page.canvas
                y = margin
                drawHeaderRow()
            }

            val accountName = accounts[tx.accountId]?.name ?: ""
            val categoryName = tx.categoryId?.let { categories[it]?.name } ?: ""
            val sign = when (tx.type) {
                TransactionType.INCOME -> { totalIncome += tx.amount; "+" }
                TransactionType.EXPENSE -> { totalExpense += tx.amount; "-" }
                TransactionType.TRANSFER -> "⇄"
            }

            canvas.drawText(dateFormat.format(Date(tx.occurredAt)), colDate, y, bodyPaint)
            canvas.drawText(tx.type.name, colType, y, bodyPaint)
            canvas.drawText(accountName, colAccount, y, bodyPaint)
            canvas.drawText(categoryName, colCategory, y, bodyPaint)
            canvas.drawText((tx.note ?: "").take(20), colNote, y, bodyPaint)
            canvas.drawText("$sign${"%.2f".format(tx.amount)}", colAmount, y, bodyPaint)
            y += rowHeight
        }

        y += 16f
        val totalPaint = Paint().apply { textSize = 12f; isFakeBoldText = true }
        canvas.drawText("Total Income: %.2f".format(totalIncome), margin, y, totalPaint)
        y += 20f
        canvas.drawText("Total Expense: %.2f".format(totalExpense), margin, y, totalPaint)
        y += 20f
        canvas.drawText("Net: %.2f".format(totalIncome - totalExpense), margin, y, totalPaint)

        document.finishPage(page)
        document.writeTo(outputStream)
        document.close()
    }
}
