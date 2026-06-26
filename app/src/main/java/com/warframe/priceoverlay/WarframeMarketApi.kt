package com.warframe.priceoverlay

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

sealed class ApiResult {
    data class Success(val price: Int)             : ApiResult()
    object NotFound                                : ApiResult()
    data class RateLimited(val retryAfterMs: Long) : ApiResult()
    data class Failure(val message: String)        : ApiResult()
}

class WarframeMarketApi {

    private val cache = mutableMapOf<String, Pair<Long, Int?>>()
    private val CACHE_TTL_MS = 3 * 60 * 1000L  // 3 minutes

    /**
     * Fetches the median sale price over the past 48 hours from WFM statistics.
     * Endpoint: GET /v1/items/{slug}/statistics
     * Uses statistics_closed["48hours"] — hourly buckets, median of medians.
     */
    fun fetchStats(slug: String): ApiResult {
        val cached = cache[slug]
        if (cached != null && System.currentTimeMillis() - cached.first < CACHE_TTL_MS) {
            Log.d("WFOverlay", "Cache hit: $slug → ${cached.second}")
            return if (cached.second != null) ApiResult.Success(cached.second!!) else ApiResult.NotFound
        }

        return try {
            val url = URL("https://api.warframe.market/v1/items/$slug/statistics")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Platform", "pc")
                connectTimeout = 5000
                readTimeout    = 5000
            }

            val code = conn.responseCode

            if (code == 429) {
                val retryAfterMs = conn.getHeaderField("Retry-After")
                    ?.toLongOrNull()?.times(1000L) ?: 2000L
                Log.w("WFOverlay", "429 for $slug — retry after ${retryAfterMs}ms")
                conn.disconnect()
                return ApiResult.RateLimited(retryAfterMs)
            }

            if (code != 200) {
                Log.w("WFOverlay", "HTTP $code for $slug")
                conn.disconnect()
                return ApiResult.Failure("HTTP $code")
            }

            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()

            val statsClosed = JSONObject(body)
                .getJSONObject("payload")
                .getJSONObject("statistics_closed")

            // 48hours = hourly buckets for the past 48h — use all of them
            val hours48 = statsClosed.getJSONArray("48hours")

            val medians = mutableListOf<Double>()
            for (i in 0 until hours48.length()) {
                val entry = hours48.getJSONObject(i)
                val m = entry.optDouble("median", -1.0)
                if (m > 0) medians.add(m)
            }

            Log.d("WFOverlay", "48h stats $slug: ${medians.size} buckets, values=$medians")

            if (medians.isEmpty()) {
                cache[slug] = System.currentTimeMillis() to null
                return ApiResult.NotFound
            }

            // Median of hourly medians
            medians.sort()
            val median48h = medians[medians.size / 2].toInt()
            cache[slug] = System.currentTimeMillis() to median48h

            Log.d("WFOverlay", "48h median $slug → ${median48h}p")
            ApiResult.Success(median48h)

        } catch (e: Exception) {
            Log.e("WFOverlay", "fetchStats($slug) exception: ${e.message}")
            ApiResult.Failure(e.message ?: "unknown")
        }
    }
}