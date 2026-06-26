package com.warframe.priceoverlay

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
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

    private val statsCache  = mutableMapOf<String, Pair<Long, Int?>>()
    private val detailCache = mutableMapOf<String, Pair<Long, ItemDetail>>()
    private val CACHE_TTL_MS = 3 * 60 * 1000L

    // ── 48h median sell price ────────────────────────────────────────────────────
    // Endpoint: GET /v1/items/{slug}/statistics  (still works as of v0.25.0)
    // Reads statistics_closed["48hours"] → median field per hourly bucket

    fun fetchStats(slug: String): ApiResult {
        val cached = statsCache[slug]
        if (cached != null && System.currentTimeMillis() - cached.first < CACHE_TTL_MS) {
            return if (cached.second != null) ApiResult.Success(cached.second!!) else ApiResult.NotFound
        }
        return try {
            val conn = (URL("https://api.warframe.market/v1/items/$slug/statistics")
                .openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Platform", "pc")
                connectTimeout = 3000
                readTimeout    = 3000
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

    // ── Plat + ducats together, fetched in parallel ──────────────────────────────

    fun fetchItemDetail(slug: String): ItemDetail {
        val cached = detailCache[slug]
        if (cached != null && System.currentTimeMillis() - cached.first < CACHE_TTL_MS) {
            Log.d("WFOverlay", "Cache hit detail: $slug")
            return cached.second
        }
        val pool          = Executors.newFixedThreadPool(2)
        val futureStats:  Future<ApiResult> = pool.submit<ApiResult> { fetchStats(slug) }
        val futureDucats: Future<Int?>      = pool.submit<Int?>      { fetchDucats(slug) }
        pool.shutdown()
        val platPrice = when (val r = futureStats.get()) {
            is ApiResult.Success -> r.price
            else                 -> null
        }
        val ducats = futureDucats.get()
        val detail = ItemDetail(platPrice48h = platPrice, ducats = ducats)
        detailCache[slug] = System.currentTimeMillis() to detail
        Log.d("WFOverlay", "Detail $slug → plat=${platPrice}p ducats=${ducats}d")
        return detail
    }

    // ── Ducat value ──────────────────────────────────────────────────────────────
    // v1/items/{slug} now returns 403 — migrated to v2.
    // v2 response shape: { "data": { "ducats": 100, "slug": "...", ... }, "error": null }

    private fun fetchDucats(slug: String): Int? {
        return try {
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