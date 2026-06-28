package com.warframe.priceoverlay

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

sealed class ApiResult {
    data class Success(val price: Int)             : ApiResult()
    object NotFound                                : ApiResult()
    data class RateLimited(val retryAfterMs: Long) : ApiResult()
    data class Failure(val message: String)        : ApiResult()
}

class WarframeMarketApi {

    private val statsCache  = ConcurrentHashMap<String, Pair<Long, Int?>>()
    private val cacheTtlMs = 5 * 60 * 1000L
    
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

    private suspend fun makeRequest(urlStr: String, headers: Map<String, String> = emptyMap()): String? = withContext(Dispatchers.IO) {
        throttle()
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
                connectTimeout = 5000
                readTimeout = 5000
            }
            if (conn.responseCode == 429) return@withContext "RATE_LIMIT"
            if (conn.responseCode != 200) return@withContext null
            conn.inputStream.bufferedReader().readText()
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    suspend fun fetchPlatPrice(slug: String): ApiResult {
        statsCache[slug]?.let { (time, price) ->
            if (System.currentTimeMillis() - time < cacheTtlMs) {
                return if (price != null) ApiResult.Success(price) else ApiResult.NotFound
            }
        }

        val body = makeRequest("https://api.warframe.market/v1/items/$slug/statistics", mapOf("Platform" to "pc"))
        if (body == "RATE_LIMIT") return ApiResult.RateLimited(2000L)
        if (body == null) return ApiResult.Failure("Network error")

        return try {
            val hours48 = JSONObject(body).getJSONObject("payload").getJSONObject("statistics_closed").getJSONArray("48hours")
            
            if (hours48.length() > 0) {
                // Focus on Rank 0 (unranked) items as they are the standard for 48h median/avg
                val unrankedEntries = mutableListOf<JSONObject>()
                for (i in 0 until hours48.length()) {
                    val entry = hours48.getJSONObject(i)
                    if (entry.optInt("mod_rank", 0) == 0) {
                        unrankedEntries.add(entry)
                    }
                }

                val target = if (unrankedEntries.isNotEmpty()) unrankedEntries.last() else hours48.getJSONObject(hours48.length() - 1)
                
                // Return VWAP (avg_price) if available, otherwise fallback to Median
                val vwap = target.optDouble("avg_price", -1.0)
                val median = target.optInt("median", 0)
                val finalPrice = if (vwap > 0) vwap.toInt() else median

                if (finalPrice > 0) {
                    statsCache[slug] = System.currentTimeMillis() to finalPrice
                    ApiResult.Success(finalPrice)
                } else {
                    statsCache[slug] = System.currentTimeMillis() to null
                    ApiResult.NotFound
                }
            } else {
                ApiResult.NotFound
            }
        } catch (e: Exception) {
            ApiResult.Failure("Parse error")
        }
    }

    suspend fun fetchDucatsOnline(slug: String): Int? {
        val body = makeRequest("https://api.warframe.market/v2/items/$slug", mapOf("Language" to "en"))
        if (body == null || body == "RATE_LIMIT") return null
        return try {
            val d = JSONObject(body).optJSONObject("data")?.optInt("ducats", -1) ?: -1
            if (d >= 0) d else null
        } catch (e: Exception) {
            null
        }
    }
}
