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

    private data class IndexedItem(
        val entry: ItemEntry,
        val nameLower: String,
        val nameWords: List<String>
    )

    private var index: List<IndexedItem> = emptyList()

    fun load() {
        try {
            val inputStream = context.assets.open("items.json")
            val reader = InputStreamReader(inputStream)
            val type = object : TypeToken<List<ItemEntry>>() {}.type
            val loaded: List<ItemEntry> = Gson().fromJson(reader, type)
            reader.close()
            index = loaded.map {
                IndexedItem(
                    entry = it,
                    nameLower = it.name.lowercase(),
                    nameWords = it.name.lowercase()
                        .split(Regex("\\s+"))
                        .filter { w -> w.isNotBlank() }
                )
            }
            Log.d("ItemDatabase", "Loaded ${index.size} items")
        } catch (e: Exception) {
            Log.e("ItemDatabase", "Error loading items.json", e)
        }
    }

    fun searchItem(query: String): ItemEntry? {
        if (query.isBlank() || index.isEmpty()) return null

        val normalizedQuery = query.trim().lowercase()
        val queryWords = normalizedQuery
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }

        if (queryWords.isEmpty()) return null

        // 1. Exact match — always wins, handles single-word mods too
        index.find { it.nameLower == normalizedQuery }?.let {
            Log.d("ItemDatabase", "Exact: '$query' → '${it.entry.name}'")
            return it.entry
        }

        // 2. Single-word queries: exact match only.
        //    No fuzzy matching — avoids false positives on short OCR fragments.
        if (queryWords.size == 1) return null

        // 3. Bidirectional whole-word match:
        //    ALL query words must exactly equal an item word,
        //    AND ALL item words must exactly equal a query word.
        //    Prevents "Saryn Set" matching "Saryn Blueprint" (set ≠ blueprint),
        //    and prevents "Mutalist V Coordinates" matching "Nav Coordinates"
        //    (mutalist, v not in query).
        val candidates = index.filter { item ->
            queryWords.all { qw -> item.nameWords.any { iw -> iw == qw } } &&
            item.nameWords.all { iw -> queryWords.any { qw -> qw == iw } }
        }

        if (candidates.isEmpty()) return null
        if (candidates.size == 1) {
            Log.d("ItemDatabase", "Match: '$query' → '${candidates[0].entry.name}'")
            return candidates[0].entry
        }

        // 4. Multiple matches — pick fewest words (most specific)
        val best = candidates.minByOrNull { it.nameWords.size }
        Log.d("ItemDatabase", "Ambiguous (${candidates.size}): '$query' → '${best?.entry?.name}'")
        return best?.entry
    }
}