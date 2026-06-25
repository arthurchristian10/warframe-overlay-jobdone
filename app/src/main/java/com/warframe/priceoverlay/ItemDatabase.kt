package com.warframe.priceoverlay

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import java.io.InputStreamReader

data class ItemEntry(
    @SerializedName("n") val name: String,
    @SerializedName("u") val slug: String
)

class ItemDatabase(private val context: Context) {
    private var items: List<ItemEntry> = emptyList()

    fun load() {
        try {
            val inputStream = context.assets.open("items.json")
            val reader = InputStreamReader(inputStream)
            val type = object : TypeToken<List<ItemEntry>>() {}.type
            items = Gson().fromJson(reader, type)
            reader.close()
            Log.d("ItemDatabase", "Loaded \${items.size} items from JSON")
        } catch (e: Exception) {
            Log.e("ItemDatabase", "Error loading items.json", e)
        }
    }

    fun searchItem(query: String): ItemEntry? {
        if (query.isBlank() || items.isEmpty()) return null
        
        val normalizedQuery = query.trim().lowercase()
        
        // Exact match
        var match = items.find { it.name.lowercase() == normalizedQuery }
        if (match != null) return match
        
        // Contains match
        match = items.find { it.name.lowercase().contains(normalizedQuery) }
        return match
    }
}
