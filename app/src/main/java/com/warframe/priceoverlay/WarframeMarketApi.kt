package com.warframe.priceoverlay

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

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
    
    private var lastRequestTime = 0L
    private val minRequestIntervalMs = 350L

    private suspend fun throttle() {
        val wait: Long
        synchronized(this) {
            val now = System.currentTimeMillis()
            wait = minRequestIntervalMs - (now - lastRequestTime)
            if (wait <= 0) {
                lastRequestTime = now
                return
            }
            lastRequestTime = now + wait
        }
        delay(wait)
    }

    suspend fun fetchStats(slug: String): ApiResult = withContext(Dispatchers.IO) {
        val cached = statsCache[slug]
        if (cached != null && System.currentTimeMillis() - cached.first < cacheTtlMs) {
            return@withContext if (cached.second != null) ApiResult.Success(cached.second!!) else ApiResult.NotFound
        }
        return@withContext try {
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
                val retryWait = conn.getHeaderField("Retry-After")?.toLongOrNull()?.times(1000L) ?: 2000L
                conn.disconnect()
                return@withContext ApiResult.RateLimited(retryWait)
            }
            if (code != 200) { conn.disconnect(); return@withContext ApiResult.Failure("HTTP $code") }
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
                return@withContext ApiResult.NotFound
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

    suspend fun fetchItemDetail(slug: String): ItemDetail? {
        val cached = detailCache[slug]
        if (cached != null && System.currentTimeMillis() - cached.first < cacheTtlMs) {
            return cached.second
        }

        var platPrice: Int? = null
        var ducats: Int? = null
        
        // Retry loop for Stats
        var statsAttempt = 0
        while (statsAttempt < 3) {
            when (val stats = fetchStats(slug)) {
                is ApiResult.Success -> { platPrice = stats.price; break }
                is ApiResult.NotFound -> break
                is ApiResult.RateLimited -> delay(stats.retryAfterMs)
                else -> delay(1000)
            }
            statsAttempt++
        }
        
        // Retry loop for Ducats
        var ducatAttempt = 0
        while (ducatAttempt < 3) {
            val dResult = fetchDucatsInternal(slug)
            if (dResult is InternalResult.Success) { ducats = dResult.value; break }
            if (dResult is InternalResult.NotFound) break
            if (dResult is InternalResult.RateLimited) delay(dResult.wait) else delay(1000)
            ducatAttempt++
        }

        if (platPrice == null && ducats == null) return null

        val detail = ItemDetail(platPrice48h = platPrice, ducats = ducats)
        detailCache[slug] = System.currentTimeMillis() to detail
        return detail
    }

    private sealed class InternalResult {
        data class Success(val value: Int?) : InternalResult()
        object NotFound : InternalResult()
        data class RateLimited(val wait: Long) : InternalResult()
        object Error : InternalResult()
    }

    private suspend fun fetchDucatsInternal(slug: String): InternalResult = withContext(Dispatchers.IO) {
        try {
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
            if (code == 429) return@withContext InternalResult.RateLimited(2000L)
            if (code != 200) { conn.disconnect(); return@withContext InternalResult.Error }
            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            val data = JSONObject(body).optJSONObject("data") ?: return@withContext InternalResult.NotFound
            val d = data.optInt("ducats", -1)
            return@withContext if (d >= 0) InternalResult.Success(d) else InternalResult.Success(null)
        } catch (e: Exception) {
            Log.e("WFOverlay", "fetchDucats($slug): ${e.message}")
            return@withContext InternalResult.Error
        }
    }
}
