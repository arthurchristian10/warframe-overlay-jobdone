package com.warframe.priceoverlay

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

class WarframeMarketApi {
    private val client = OkHttpClient()

    suspend fun getCheapestOrder(slug: String): Int? = withContext(Dispatchers.IO) {
        val url = "https://api.warframe.market/v1/items/$slug/orders?include=item"
        val request = Request.Builder()
            .url(url)
            .header("Platform", "pc")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e("WarframeMarketApi", "Failed to fetch orders: \${response.code}")
                    return@withContext null
                }

                val responseBody = response.body?.string() ?: return@withContext null
                val jsonObject = JSONObject(responseBody)
                val payload = jsonObject.optJSONObject("payload") ?: return@withContext null
                val orders = payload.optJSONArray("orders") ?: return@withContext null

                var minPrice: Int? = null

                for (i in 0 until orders.length()) {
                    val order = orders.optJSONObject(i) ?: continue
                    val orderType = order.optString("order_type")
                    val user = order.optJSONObject("user")
                    val status = user?.optString("status")
                    
                    if (orderType == "sell") {
                        val plat = order.optInt("platinum", -1)
                        if (plat > 0) {
                            if (minPrice == null || plat < minPrice) {
                                minPrice = plat
                            }
                        }
                    }
                }
                return@withContext minPrice
            }
        } catch (e: Exception) {
            Log.e("WarframeMarketApi", "Error fetching from market", e)
            return@withContext null
        }
    }
}
