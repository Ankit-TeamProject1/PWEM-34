package com.teamproject1.dailyexpensetracker.core.export

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Reads back what XlsxWriter produces — but ALSO handles the shared-strings
 * cell format, since a real-world file commonly gets touched by a
 * spreadsheet app (even just opening it can trigger an auto-save in some
 * apps, like Google Sheets) before being re-imported. A reader that only
 * understood our own inline-string format would silently fail on any such
 * file — this was a real, confirmed bug: the Wealth-side multi-sheet
 * reader was already fixed for this, but this single-sheet Expense reader
 * never got the same fix applied.
 */
object XlsxReader {

    /** Returns rows as a list of string-lists; row 0 is the header row. */
    fun read(inputStream: InputStream): List<List<String>> {
        var sheetXmlBytes: ByteArray? = null
        var sharedStringsBytes: ByteArray? = null

        ZipInputStream(inputStream).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                when (entry.name) {
                    "xl/worksheets/sheet1.xml" -> sheetXmlBytes = zip.readBytes()
                    "xl/sharedStrings.xml" -> sharedStringsBytes = zip.readBytes()
                }
                entry = zip.nextEntry
            }
        }

        val xmlBytes = sheetXmlBytes ?: return emptyList()
        val sharedStrings = sharedStringsBytes?.let { parseSharedStrings(it) } ?: emptyList()
        return parseSheetXml(xmlBytes, sharedStrings)
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

    /**
     * Handles both cell formats: inline strings (our own writer's output,
     * t="inlineStr") and shared-string references (t="s", what a real
     * spreadsheet app writes). Cell type comes from the `t` attribute on
     * `<c>`; anything else (absent, "n", "str") is read as a literal value
     * from `<v>`.
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
