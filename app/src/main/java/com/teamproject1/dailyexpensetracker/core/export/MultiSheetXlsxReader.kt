package com.teamproject1.dailyexpensetracker.core.export

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Multi-sheet counterpart to XlsxReader. Unlike the single-sheet reader
 * (which can safely assume "sheet1.xml" is the only sheet AND that it was
 * never touched by a real spreadsheet app), this one has to handle files
 * that were opened, filled in, and re-saved in Excel or Google Sheets —
 * that's the entire point of this feature. Two real differences from the
 * simpler reader:
 *   1. Sheet name -> file mapping is resolved via workbook.xml/rels
 *      rather than assumed from file naming, since a resave can renumber
 *      the underlying sheetN.xml files.
 *   2. Cell values are read from BOTH inline strings (t="inlineStr", our
 *      own writer's format) AND the shared-strings table (t="s", which is
 *      what Excel/Sheets normally uses when saving text cells) — a
 *      version that only understood inline strings would silently return
 *      empty/wrong data for any file a real spreadsheet app had touched.
 *
 * NOTE: this parser does NOT enable namespace-aware processing (matching
 * the existing single-sheet reader's approach), so a prefixed attribute
 * like r:id shows up as the literal attribute name "r:id", not "id" —
 * handled explicitly below rather than assumed away.
 */
object MultiSheetXlsxReader {

    /** Returns sheet name -> rows (row 0 of each is the header row). */
    fun read(inputStream: InputStream): Map<String, List<List<String>>> {
        val zipEntries = mutableMapOf<String, ByteArray>()
        ZipInputStream(inputStream).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                zipEntries[entry.name] = zip.readBytes()
                entry = zip.nextEntry
            }
        }

        val workbookXml = zipEntries["xl/workbook.xml"] ?: return emptyMap()
        val workbookRelsXml = zipEntries["xl/_rels/workbook.xml.rels"] ?: return emptyMap()
        val sharedStrings = zipEntries["xl/sharedStrings.xml"]?.let { parseSharedStrings(it) } ?: emptyList()

        val sheetNameToRid = parseWorkbookSheets(workbookXml)
        val ridToTarget = parseWorkbookRels(workbookRelsXml)

        val result = mutableMapOf<String, List<List<String>>>()
        for ((sheetName, rid) in sheetNameToRid) {
            val target = ridToTarget[rid] ?: continue
            // Target can be a relative path like "worksheets/sheet2.xml"
            // or occasionally already include a leading slash depending on
            // which app wrote it — normalize both to the same zip-entry key.
            val sheetPath = if (target.startsWith("/")) target.removePrefix("/") else "xl/$target"
            val sheetBytes = zipEntries[sheetPath] ?: zipEntries["xl/$target"] ?: continue
            result[sheetName] = parseSheetXml(sheetBytes, sharedStrings)
        }
        return result
    }

    private fun parseSharedStrings(xmlBytes: ByteArray): List<String> {
        val strings = mutableListOf<String>()
        val parser: XmlPullParser = Xml.newPullParser()
        parser.setInput(xmlBytes.inputStream(), "UTF-8")

        var inText = false
        var current = StringBuilder()
        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "si" -> current = StringBuilder()
                        "t" -> inText = true
                    }
                }
                XmlPullParser.TEXT -> if (inText) current.append(parser.text)
                XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "t" -> inText = false
                        "si" -> strings.add(current.toString())
                    }
                }
            }
            eventType = parser.next()
        }
        return strings
    }

    private fun parseWorkbookSheets(xmlBytes: ByteArray): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val parser: XmlPullParser = Xml.newPullParser()
        parser.setInput(xmlBytes.inputStream(), "UTF-8")

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG && parser.name == "sheet") {
                var name: String? = null
                var rid: String? = null
                for (i in 0 until parser.attributeCount) {
                    // No namespace processing enabled, so the r:id attribute's
                    // name comes through as the literal string "r:id".
                    when (parser.getAttributeName(i)) {
                        "name" -> name = parser.getAttributeValue(i)
                        "r:id" -> rid = parser.getAttributeValue(i)
                    }
                }
                if (name != null && rid != null) result[name] = rid
            }
            eventType = parser.next()
        }
        return result
    }

    private fun parseWorkbookRels(xmlBytes: ByteArray): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val parser: XmlPullParser = Xml.newPullParser()
        parser.setInput(xmlBytes.inputStream(), "UTF-8")

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG && parser.name == "Relationship") {
                var id: String? = null
                var target: String? = null
                for (i in 0 until parser.attributeCount) {
                    when (parser.getAttributeName(i)) {
                        "Id" -> id = parser.getAttributeValue(i)
                        "Target" -> target = parser.getAttributeValue(i)
                    }
                }
                if (id != null && target != null) result[id] = target
            }
            eventType = parser.next()
        }
        return result
    }

    /**
     * Handles both cell formats: inline strings (our own writer's output)
     * and shared-string references (what a real spreadsheet app writes).
     * A cell's type comes from the `t` attribute on `<c>`: "inlineStr" for
     * inline, "s" for a shared-string index, anything else (absent, "n",
     * "str") is read as a literal value from `<v>`.
     */
    private fun parseSheetXml(xmlBytes: ByteArray, sharedStrings: List<String>): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var currentRow = mutableListOf<String>()
        var currentCellType: String? = null
        var currentText = StringBuilder()
        var inValueOrText = false

        val parser: XmlPullParser = Xml.newPullParser()
        parser.setInput(xmlBytes.inputStream(), "UTF-8")

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "row" -> currentRow = mutableListOf()
                        "c" -> {
                            currentCellType = null
                            for (i in 0 until parser.attributeCount) {
                                if (parser.getAttributeName(i) == "t") currentCellType = parser.getAttributeValue(i)
                            }
                        }
                        "t", "v" -> { inValueOrText = true; currentText = StringBuilder() }
                    }
                }
                XmlPullParser.TEXT -> {
                    if (inValueOrText) currentText.append(parser.text)
                }
                XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "t", "v" -> inValueOrText = false
                        "c" -> {
                            val raw = currentText.toString()
                            val resolved = when (currentCellType) {
                                "s" -> raw.toIntOrNull()?.let { sharedStrings.getOrNull(it) } ?: ""
                                else -> raw
                            }
                            currentRow.add(resolved)
                        }
                        "row" -> rows.add(currentRow)
                    }
                }
            }
            eventType = parser.next()
        }

        return rows
    }
}
