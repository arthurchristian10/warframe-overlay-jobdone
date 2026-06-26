package com.warframe.priceoverlay

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import java.io.InputStreamReader
import java.text.Normalizer

data class ItemEntry(
    @SerializedName("n") val name: String,
    @SerializedName("u") val slug: String
)

// Returned by searchItemDetailed — confidence drives the vote threshold downstream.
// 1.00 = exact string, 0.95 = exact bidirectional word match (unique),
// 0.75 = exact bidirectional ambiguous (shortest picked), 0.55-0.90 = fuzzy
data class MatchResult(
    val entry: ItemEntry,
    val confidence: Float
)

class ItemDatabase(private val context: Context) {

    private data class IndexedItem(
        val entry: ItemEntry,
        val nameLower: String,
        val nameWords: List<String>,
        val nameWordsNorm: List<String>
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
                val words = it.name.lowercase()
                    .split(Regex("\\s+"))
                    .filter { w -> w.isNotBlank() }
                IndexedItem(
                    entry = it,
                    nameLower = it.name.lowercase(),
                    nameWords = words,
                    nameWordsNorm = words.map { w -> normalizeOcr(w) }
                )
            }
            Log.d("ItemDatabase", "Loaded ${index.size} items")
        } catch (e: Exception) {
            Log.e("ItemDatabase", "Error loading items.json", e)
        }
    }

    // Back-compat wrapper — discards confidence. Prefer searchItemDetailed.
    fun searchItem(query: String): ItemEntry? = searchItemDetailed(query)?.entry

    fun searchItemDetailed(query: String): MatchResult? {
        if (query.isBlank() || index.isEmpty()) return null

        val normalizedQuery = query.trim().lowercase()
        val queryWords = normalizedQuery
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }

        if (queryWords.isEmpty()) return null

        // 1. Exact full string match — perfect confidence.
        index.find { it.nameLower == normalizedQuery }?.let {
            Log.d("ItemDatabase", "Exact: '$query' → '${it.entry.name}' (1.00)")
            return MatchResult(it.entry, 1.00f)
        }

        // 2. Single-word queries: exact only — too ambiguous to fuzz.
        if (queryWords.size == 1) return null

        // 3. Exact bidirectional word match.
        val exactCandidates = index.filter { item ->
            queryWords.all { qw -> item.nameWords.any { iw -> iw == qw } } &&
            item.nameWords.all { iw -> queryWords.any { qw -> qw == iw } }
        }
        if (exactCandidates.size == 1) {
            Log.d("ItemDatabase", "WordMatch: '$query' → '${exactCandidates[0].entry.name}' (0.95)")
            return MatchResult(exactCandidates[0].entry, 0.95f)
        }
        if (exactCandidates.size > 1) {
            val best = exactCandidates.minByOrNull { it.nameWords.size }!!
            Log.d("ItemDatabase", "WordMatch ambiguous (${exactCandidates.size}): '$query' → '${best.entry.name}' (0.75)")
            return MatchResult(best.entry, 0.75f)
        }

        // 4. Fuzzy match — OCR-normalized Damerau-Levenshtein, length-scaled.
        // - Words must match positionally (zip) to prevent cross-word bleed.
        // - Words ≤ 3 chars must match exactly after normalization.
        // - Longer words get a length/4 edit budget (8 chars → 2 edits, 12 chars → 3).
        // - Confidence = 0.90 - avgErrorRatio × 1.4, clamped to [0.40, 0.90].
        val queryWordsNorm = queryWords.map { normalizeOcr(it) }

        var bestMatch: IndexedItem? = null
        var bestScore = 0f

        for (item in index) {
            if (item.nameWordsNorm.size != queryWordsNorm.size) continue

            var totalErrRatio = 0f
            var rejected = false
            for ((qw, iw) in queryWordsNorm.zip(item.nameWordsNorm)) {
                if (qw == iw) continue
                val maxLen = maxOf(qw.length, iw.length)
                val budget = if (maxLen <= 3) 0 else (maxLen / 4).coerceAtLeast(1)
                val d = damerauLevenshtein(qw, iw)
                if (d > budget) { rejected = true; break }
                totalErrRatio += d.toFloat() / maxLen.toFloat()
            }
            if (rejected) continue

            val avgErr = totalErrRatio / queryWordsNorm.size
            val score = (0.90f - avgErr * 1.4f).coerceIn(0.40f, 0.90f)

            if (score > bestScore) {
                bestScore = score
                bestMatch = item
            }
        }

        if (bestMatch != null && bestScore >= 0.55f) {
            Log.d("ItemDatabase", "Fuzzy: '$query' → '${bestMatch.entry.name}' (${"%.2f".format(bestScore)})")
            return MatchResult(bestMatch.entry, bestScore)
        }

        Log.d("ItemDatabase", "No match: '$query'")
        return null
    }

    // Strip diacritics + safe OCR digit→letter swaps. Applied symmetrically
    // to both query and index, so legitimate item names are unaffected.
    private fun normalizeOcr(s: String): String {
        val noDiacritics = Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        return noDiacritics.lowercase()
            .replace('0', 'o')
            .replace('1', 'l')
            .replace('|', 'l')
    }

    // Damerau-Levenshtein: standard Levenshtein + adjacent transposition cost 1.
    // Catches OCR transpositions like "Saryrn"↔"Saryn" at distance 1 instead of 2.
    private fun damerauLevenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            val cost = if (a[i-1] == b[j-1]) 0 else 1
            dp[i][j] = minOf(
                dp[i-1][j] + 1,
                dp[i][j-1] + 1,
                dp[i-1][j-1] + cost
            )
            if (i > 1 && j > 1 && a[i-1] == b[j-2] && a[i-2] == b[j-1]) {
                dp[i][j] = minOf(dp[i][j], dp[i-2][j-2] + 1)
            }
        }
        return dp[a.length][b.length]
    }
}