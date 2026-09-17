package com.teamproject1.dailyexpensetracker.core.wealth

import com.teamproject1.dailyexpensetracker.core.database.entity.ExchangeCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Demat is the first Wealth category needing LIVE internet data — the
 * other categories built so far (FD, Liabilities, Cash in Hand) are pure
 * math or manual, deliberately avoiding this risk until now.
 *
 * Uses Yahoo Finance's public (unofficial, no API key required) quote
 * endpoint rather than adding a networking library like Retrofit — this
 * project has hit enough dependency-version issues that a plain
 * HttpURLConnection + the org.json parsing already proven in the backup
 * feature felt like the lower-risk choice for one endpoint.
 *
 * IMPORTANT CAVEAT: this endpoint is unofficial and Yahoo could change or
 * restrict it without notice — this is a known fragility of any free stock
 * price source (NSE/BSE don't offer a clean free public API either, which
 * was flagged back when Demat was first designed). Every failure mode
 * (network error, unexpected response shape, symbol not found) returns
 * null rather than crashing, so the UI always has a safe fallback: show
 * the last successfully fetched price with its timestamp, per the locked
 * "last updated" design for all auto-fetched Wealth categories.
 */
@Singleton
class StockPriceFetcher @Inject constructor() {

    suspend fun fetchPrice(symbol: String, exchange: ExchangeCode): Double? = withContext(Dispatchers.IO) {
        val suffix = when (exchange) {
            ExchangeCode.NSE -> ".NS"
            ExchangeCode.BSE -> ".BO"
        }
        val urlString = "https://query1.finance.yahoo.com/v8/finance/chart/$symbol$suffix"

        try {
            val connection = URL(urlString).openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("User-Agent", "Mozilla/5.0")

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                connection.disconnect()
                return@withContext null
            }

            val body = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()

            val root = JSONObject(body)
            val result = root.getJSONObject("chart").getJSONArray("result")
            if (result.length() == 0) return@withContext null

            val meta = result.getJSONObject(0).getJSONObject("meta")
            if (!meta.has("regularMarketPrice")) return@withContext null

            meta.getDouble("regularMarketPrice")
        } catch (e: Exception) {
            // Network failure, malformed response, symbol not found, etc.
            // — all treated the same: no price this time, caller falls
            // back to the last cached value.
            null
        }
    }
}
