package com.teamproject1.dailyexpensetracker.core.wealth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

data class MutualFundScheme(val schemeCode: String, val schemeName: String, val nav: Double, val fundHouse: String)

/**
 * AMFI publishes a single free, public, no-API-key file listing every
 * mutual fund scheme's latest NAV, updated once daily — this is why
 * Mutual Funds could be built without being blocked on an API key the
 * way Gold/Silver still is. The file is a few MB of semicolon-delimited
 * plain text grouped under fund-house header lines (lines with no
 * semicolons), not JSON — parsed by hand rather than pulling in a CSV
 * library for one file format.
 *
 * The whole file is fetched and cached in memory for a few hours (AMFI
 * itself only updates once a day, so re-fetching more often than this
 * has no benefit) — both search-by-name and lookup-by-code work off the
 * same cached parse rather than separate network calls.
 */
@Singleton
class AmfiNavFetcher @Inject constructor() {
    private var cachedSchemes: List<MutualFundScheme>? = null
    private var cachedAt: Long = 0
    private val cacheValidityMs = 6 * 60 * 60 * 1000L

    private suspend fun fetchAllSchemes(): List<MutualFundScheme> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        cachedSchemes?.let { if (now - cachedAt < cacheValidityMs) return@withContext it }

        try {
            val connection = URL("https://www.amfiindia.com/spages/NAVAll.txt").openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("User-Agent", "Mozilla/5.0")

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                connection.disconnect()
                return@withContext cachedSchemes ?: emptyList()
            }

            val body = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()

            var currentFundHouse = ""
            val schemes = mutableListOf<MutualFundScheme>()
            body.lineSequence().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty()) return@forEach
                if (!trimmed.contains(";")) {
                    // A line with no semicolons is a fund-house header —
                    // AMFI groups schemes this way rather than repeating
                    // the fund house on every row.
                    currentFundHouse = trimmed
                    return@forEach
                }
                val parts = trimmed.split(";")
                // AMFI's actual current format has 8 columns: Scheme
                // Code;ISIN Div Payout/ISIN Growth;ISIN Div
                // Reinvestment;Scheme Name;Plan;Option;Net Asset
                // Value;Date — NAV is at index 6, not 4. This was the
                // actual bug behind search returning nothing: reading
                // index 4 (the "Plan" text, e.g. "Direct Plan") and
                // trying to parse it as a number always failed, silently
                // skipping every single row.
                if (parts.size < 8 || parts[0].trim() == "Scheme Code") return@forEach
                val schemeCode = parts[0].trim()
                val baseName = parts[3].trim()
                val plan = parts[4].trim()
                val option = parts[5].trim()
                val nav = parts[6].trim().toDoubleOrNull() ?: return@forEach
                if (schemeCode.isBlank() || baseName.isBlank()) return@forEach
                // Plan/Option appended when present, per real AMFI data —
                // the same scheme name repeats many times across Direct
                // vs Regular and Growth vs IDCW variants, which would
                // otherwise show as confusing, indistinguishable
                // duplicates in search results.
                val schemeName = listOfNotNull(baseName, plan.ifBlank { null }, option.ifBlank { null }).joinToString(" - ")
                schemes.add(MutualFundScheme(schemeCode, schemeName, nav, currentFundHouse))
            }
            cachedSchemes = schemes
            cachedAt = now
            schemes
        } catch (e: Exception) {
            // Network failure, malformed response, etc. — fall back to
            // whatever was last successfully cached, even if stale,
            // rather than leaving search/lookup with nothing at all.
            cachedSchemes ?: emptyList()
        }
    }

    /** Search by fund name — per explicit request, this is how a fund is
     *  identified when adding it, rather than manually typing a scheme
     *  code. Capped at 50 results since a generic search term (e.g. a
     *  fund house name) could otherwise match hundreds of schemes. */
    suspend fun search(query: String): List<MutualFundScheme> {
        if (query.isBlank()) return emptyList()
        return fetchAllSchemes().filter { it.schemeName.contains(query, ignoreCase = true) }.take(50)
    }

    /** Current NAV for a specific, already-known scheme code — used for
     *  the auto-refresh-at-Dashboard-load flow, once a fund has been
     *  added. */
    suspend fun getNav(schemeCode: String): Double? {
        return fetchAllSchemes().find { it.schemeCode == schemeCode }?.nav
    }
}
