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

        // 1. Exact full string match
        index.find { it.nameLower == normalizedQuery }?.let {
            Log.d("ItemDatabase", "Exact: '$query' → '${it.entry.name}'")
            return it.entry
        }

        // 2. Single-word queries: exact only — too ambiguous for fuzzy
        if (queryWords.size == 1) return null

        // 3. Exact bidirectional word match
        val exactCandidates = index.filter { item ->
            queryWords.all { qw -> item.nameWords.any { iw -> iw == qw } } &&
            item.nameWords.all { iw -> queryWords.any { qw -> qw == iw } }
        }
        if (exactCandidates.size == 1) {
            Log.d("ItemDatabase", "Match: '$query' → '${exactCandidates[0].entry.name}'")
            return exactCandidates[0].entry
        }
        if (exactCandidates.size > 1) {
            val best = exactCandidates.minByOrNull { it.nameWords.size }
            Log.d("ItemDatabase", "Ambiguous exact (${exactCandidates.size}): '$query' → '${best?.entry?.name}'")
            return best?.entry
        }

        // 3.5. Fuzzy bidirectional match — Levenshtein ≤ 1 per word
        // Catches OCR corruption: "Neuropücs"→"Neuroptics", "Saryrn"→"Saryn"
        // Words must match positionally (zip) to prevent cross-word fuzzy bleed
        // Short words (≤ 3 chars) must match exactly — too ambiguous to fuzz
        val fuzzyCandidates = index.filter { item ->
            item.nameWords.size == queryWords.size &&
            queryWords.zip(item.nameWords).all { (qw, iw) ->
                iw == qw || (qw.length > 3 && iw.length > 3 && levenshtein(qw, iw) <= 1)
            }
        }
        if (fuzzyCandidates.size == 1) {
            Log.d("ItemDatabase", "Fuzzy: '$query' → '${fuzzyCandidates[0].entry.name}'")
            return fuzzyCandidates[0].entry
        }
        if (fuzzyCandidates.size > 1) {
            val best = fuzzyCandidates.minByOrNull { item ->
                queryWords.zip(item.nameWords).sumOf { (qw, iw) -> levenshtein(qw, iw) }
            }
            Log.d("ItemDatabase", "Fuzzy ambiguous (${fuzzyCandidates.size}): '$query' → '${best?.entry?.name}'")
            return best?.entry
        }

        Log.d("ItemDatabase", "No match: '$query'")
        return null
    }

    // Standard iterative Levenshtein — O(m×n), safe for short word-length strings
    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            dp[i][j] = if (a[i-1] == b[j-1]) dp[i-1][j-1]
                       else 1 + minOf(dp[i-1][j], dp[i][j-1], dp[i-1][j-1])
        }
        return dp[a.length][b.length]
    }
}