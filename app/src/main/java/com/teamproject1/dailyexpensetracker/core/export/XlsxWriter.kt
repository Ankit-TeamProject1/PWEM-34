package com.teamproject1.dailyexpensetracker.core.export

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A minimal, dependency-free XLSX writer. Writes exactly one sheet with a
 * header row and data rows, all as inline strings (no shared-strings table,
 * no styling) — the simplest valid OOXML spreadsheet that Excel, Google
 * Sheets, and LibreOffice all open correctly.
 *
 * This avoids adding a heavy external library (Apache POI is the standard
 * choice but is large, not Android-optimized, and untested against our
 * exact dependency versions — given how many small version-mismatch
 * issues we've already hit in this project, a hand-written minimal writer
 * for OUR OWN fixed template is the lower-risk choice here).
 *
 * Import (ExcelImporter) only ever reads files written by this exact
 * writer, matching the locked "fixed template only" decision — so the
 * two are a matched pair, not general-purpose Excel I/O.
 */
object XlsxWriter {

    fun write(outputStream: OutputStream, headers: List<String>, rows: List<List<String>>) {
        ZipOutputStream(outputStream).use { zip ->
            writeEntry(zip, "[Content_Types].xml", CONTENT_TYPES_XML)
            writeEntry(zip, "_rels/.rels", RELS_XML)
            writeEntry(zip, "xl/workbook.xml", WORKBOOK_XML)
            writeEntry(zip, "xl/_rels/workbook.xml.rels", WORKBOOK_RELS_XML)
            writeEntry(zip, "xl/worksheets/sheet1.xml", buildSheetXml(headers, rows))
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

    private fun buildSheetXml(headers: List<String>, rows: List<List<String>>): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""")

        fun rowXml(rowIndex: Int, values: List<String>): String {
            val cells = values.mapIndexed { colIndex, value ->
                val cellRef = "${columnLetter(colIndex)}${rowIndex + 1}"
                """<c r="$cellRef" t="inlineStr"><is><t xml:space="preserve">${escapeXml(value)}</t></is></c>"""
            }.joinToString("")
            return """<row r="${rowIndex + 1}">$cells</row>"""
        }

        sb.append(rowXml(0, headers))
        rows.forEachIndexed { i, row -> sb.append(rowXml(i + 1, row)) }

        sb.append("</sheetData></worksheet>")
        return sb.toString()
    }

    /** 0 -> A, 1 -> B, ... 25 -> Z, 26 -> AA, etc. */
    private fun columnLetter(index: Int): String {
        var i = index
        val sb = StringBuilder()
        while (i >= 0) {
            sb.insert(0, ('A' + (i % 26)))
            i = i / 26 - 1
        }
        return sb.toString()
    }

    private const val CONTENT_TYPES_XML = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
        "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">\n" +
        "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>\n" +
        "<Default Extension=\"xml\" ContentType=\"application/xml\"/>\n" +
        "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>\n" +
        "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>\n" +
        "</Types>"

    private const val RELS_XML = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">\n" +
        "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>\n" +
        "</Relationships>"

    private const val WORKBOOK_XML = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
        "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">\n" +
        "<sheets><sheet name=\"Transactions\" sheetId=\"1\" r:id=\"rId1\"/></sheets>\n" +
        "</workbook>"

    private const val WORKBOOK_RELS_XML = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">\n" +
        "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>\n" +
        "</Relationships>"
}
