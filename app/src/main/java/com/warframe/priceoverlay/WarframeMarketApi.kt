package com.warframe.priceoverlay

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future

sealed class ApiResult {
    data class Success(val price: Int)             : ApiResult()
    object NotFound                                : ApiResult()
    data class RateLimited(val retryAfterMs: Long) : ApiResult()
    data class Failure(val message: String)        : ApiResult()
}

data class ItemDetail(
    val platPrice48h: Int?,
    val ducats: Int?
)

class WarframeMarketApi {

    private val statsCache  = ConcurrentHashMap<String, Pair<Long, Int?>>()
    private val detailCache = ConcurrentHashMap<String, Pair<Long, ItemDetail>>()
    private val cacheTtlMs = 3 * 60 * 1000L
 // ── Rate-limit guard: max 3 requests per second ──────────────────────────
    private var lastRequestTime = 0L
    private val minRequestIntervalMs = 350L  // ~3 req/sec with margin

    @Synchronized
    private fun throttle() {
        val now = System.currentTimeMillis()
        val wait = minRequestIntervalMs - (now - lastRequestTime)
        if (wait > 0) Thread.sleep(wait)
        lastRequestTime = System.currentTimeMillis()
    }

    fun fetchStats(slug: String): ApiResult {
        val cached = statsCache[slug]
        if (cached != null && System.currentTimeMillis() - cached.first < cacheTtlMs) {
            return if (cached.second != null) ApiResult.Success(cached.second!!) else ApiResult.NotFound
        }
        return try {
            throttle()
            val conn = (URL("https://api.warframe.market/v1/items/$slug/statistics")
                .openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Platform", "pc")
                connectTimeout = 5000
                readTimeout = 5000
            }
            val code = conn.responseCode
            if (code == 429) {
                val wait = conn.getHeaderField("Retry-After")?.toLongOrNull()?.times(1000L) ?: 2000L
                conn.disconnect()
                return ApiResult.RateLimited(wait)
            }
            if (code != 200) { conn.disconnect(); return ApiResult.Failure("HTTP $code") }
            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()

            val hours48 = JSONObject(body)
                .getJSONObject("payload")
                .getJSONObject("statistics_closed")
                .getJSONArray("48hours")

            val medians = mutableListOf<Double>()
            for (i in 0 until hours48.length()) {
                val m = hours48.getJSONObject(i).optDouble("median", -1.0)
                if (m > 0) medians.add(m)
            }
            if (medians.isEmpty()) {
                statsCache[slug] = System.currentTimeMillis() to null
                return ApiResult.NotFound
            }
            medians.sort()
            val median = medians[medians.size / 2].toInt()
            statsCache[slug] = System.currentTimeMillis() to median
            Log.d("WFOverlay", "48h median $slug → ${median}p")
            ApiResult.Success(median)
        } catch (e: Exception) {
            Log.e("WFOverlay", "fetchStats($slug): ${e.message}")
            ApiResult.Failure(e.message ?: "unknown")
        }
    }

    private val executor = Executors.newFixedThreadPool(4)

    fun fetchItemDetail(slug: String): ItemDetail {
        val cached = detailCache[slug]
        if (cached != null && System.currentTimeMillis() - cached.first < cacheTtlMs) {
            Log.d("WFOverlay", "Cache hit detail: $slug")
            return cached.second
        }
        val futureStats:  Future<ApiResult> = executor.submit<ApiResult> { fetchStats(slug) }
        val futureDucats: Future<Int?>      = executor.submit<Int?>      { fetchDucats(slug) }
        
        val platPrice = try {
            when (val r = futureStats.get()) {
                is ApiResult.Success -> r.price
                else                 -> null
            }
        } catch (_: Exception) { null }

        val ducats = try { futureDucats.get() } catch (_: Exception) { null }

        val detail = ItemDetail(platPrice48h = platPrice, ducats = ducats)
        detailCache[slug] = System.currentTimeMillis() to detail
        Log.d("WFOverlay", "Detail $slug → plat=${platPrice}p ducats=${ducats}d")
        return detail
    }

    private fun fetchDucats(slug: String): Int? {
        return try {
            throttle()
            val conn = (URL("https://api.warframe.market/v2/items/$slug")
                .openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Language", "en")
                connectTimeout = 3000
                readTimeout    = 3000
            }
            val code = conn.responseCode
            if (code != 200) { conn.disconnect(); return null }
            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            val d = JSONObject(body).getJSONObject("data").optInt("ducats", 0)
            if (d > 0) d else null
        } catch (e: Exception) {
            Log.e("WFOverlay", "fetchDucats($slug): ${e.message}")
            null
        }
    }
}
