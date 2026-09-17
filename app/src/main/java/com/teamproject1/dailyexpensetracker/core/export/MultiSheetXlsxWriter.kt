package com.teamproject1.dailyexpensetracker.core.export

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A named sheet's data — header row + data rows, same inline-string
 * format as the single-sheet XlsxWriter.
 */
data class ExcelSheet(val name: String, val headers: List<String>, val rows: List<List<String>>)

/**
 * Multi-sheet counterpart to XlsxWriter, built specifically for the
 * Wealth Excel export — one sheet per category (FD, RD, PPF, EPF, NPS,
 * APY, Insurance, Liabilities, Demat, Manual Assets), all in one file, so
 * bulk data entry can happen in a spreadsheet instead of typing into the
 * app one field at a time. Kept as a SEPARATE class from XlsxWriter
 * (rather than extending it) so the existing, working single-sheet
 * Expense export/import is never put at risk by this change.
 */
object MultiSheetXlsxWriter {

    fun write(outputStream: OutputStream, sheets: List<ExcelSheet>) {
        ZipOutputStream(outputStream).use { zip ->
            writeEntry(zip, "[Content_Types].xml", buildContentTypesXml(sheets.size))
            writeEntry(zip, "_rels/.rels", RELS_XML)
            writeEntry(zip, "xl/workbook.xml", buildWorkbookXml(sheets))
            writeEntry(zip, "xl/_rels/workbook.xml.rels", buildWorkbookRelsXml(sheets.size))
            sheets.forEachIndexed { index, sheet ->
                writeEntry(zip, "xl/worksheets/sheet${index + 1}.xml", buildSheetXml(sheet.headers, sheet.rows))
            }
        }
    }

    private fun writeEntry(zip: ZipOutputStream, path: String, content: String) {
        zip.putNextEntry(ZipEntry(path))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun escapeXml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    /** Every cell was previously written as inline-string text
     *  regardless of content — a real bug, since it made every number
     *  (account numbers, rates, amounts, tenure) show up in Excel/Sheets
     *  as text rather than a usable number: left-aligned, excluded from
     *  SUM()/AVERAGE(), and flagged with Excel's "number stored as text"
     *  warning. Fixed here: a value that parses as a genuine number is
     *  now written as a real numeric cell. One deliberate exception —
     *  a value with a leading zero followed by another digit (e.g. an
     *  account number like "007123456") is kept as text, since writing
     *  it as a number would silently drop the leading zero and corrupt
     *  the actual account number. The reader already handles both cell
     *  types correctly, so no import-side change was needed.
     */
    private fun isSafeToWriteAsNumber(value: String): Boolean {
        if (value.toDoubleOrNull() == null) return false
        if (value.length > 1 && value[0] == '0' && value[1] != '.') return false
        return true
    }

    private fun buildSheetXml(headers: List<String>, rows: List<List<String>>): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""")

        fun rowXml(rowIndex: Int, values: List<String>): String {
            val cells = values.mapIndexed { colIndex, value ->
                val cellRef = "${columnLetter(colIndex)}${rowIndex + 1}"
                if (isSafeToWriteAsNumber(value)) {
                    """<c r="$cellRef"><v>$value</v></c>"""
                } else {
                    """<c r="$cellRef" t="inlineStr"><is><t xml:space="preserve">${escapeXml(value)}</t></is></c>"""
                }
            }.joinToString("")
            return """<row r="${rowIndex + 1}">$cells</row>"""
        }

        sb.append(rowXml(0, headers))
        rows.forEachIndexed { i, row -> sb.append(rowXml(i + 1, row)) }

        sb.append("</sheetData></worksheet>")
        return sb.toString()
    }

    private fun columnLetter(index: Int): String {
        var i = index
        val sb = StringBuilder()
        while (i >= 0) {
            sb.insert(0, ('A' + (i % 26)))
            i = i / 26 - 1
        }
        return sb.toString()
    }

    private fun buildContentTypesXml(sheetCount: Int): String {
        val overrides = (1..sheetCount).joinToString("\n") { n ->
            "<Override PartName=\"/xl/worksheets/sheet$n.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
        }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
            "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">\n" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>\n" +
            "<Default Extension=\"xml\" ContentType=\"application/xml\"/>\n" +
            "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>\n" +
            "$overrides\n</Types>"
    }

    private const val RELS_XML = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">\n" +
        "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>\n" +
        "</Relationships>"

    private fun buildWorkbookXml(sheets: List<ExcelSheet>): String {
        val sheetEntries = sheets.mapIndexed { index, sheet ->
            val n = index + 1
            "<sheet name=\"${escapeXml(sheet.name)}\" sheetId=\"$n\" r:id=\"rId$n\"/>"
        }.joinToString("\n")
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
            "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">\n" +
            "<sheets>\n$sheetEntries\n</sheets>\n</workbook>"
    }

    private fun buildWorkbookRelsXml(sheetCount: Int): String {
        val relationships = (1..sheetCount).joinToString("\n") { n ->
            "<Relationship Id=\"rId$n\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet$n.xml\"/>"
        }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">\n" +
            "$relationships\n</Relationships>"
    }
}
