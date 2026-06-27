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

data class MatchResult(
    val entry: ItemEntry,
    val confidence: Float,
    val matchType: String
)

class ItemDatabase(private val context: Context) {

    private data class IndexedItem(
        val entry: ItemEntry,
        val nameLower: String,
        val nameGlued: String,
        val nameWords: List<String>,
        val nameWordsNorm: List<String>
    )

    private var index: List<IndexedItem> = emptyList()
    private val wordMap = mutableMapOf<String, MutableSet<Int>>()

    fun load(onComplete: () -> Unit = {}) {
        Thread {
            try {
                val inputStream = context.assets.open("items.json")
                val reader = InputStreamReader(inputStream)
                val type = object : TypeToken<List<ItemEntry>>() {}.type
                val loaded: List<ItemEntry> = Gson().fromJson(reader, type)
                reader.close()
                
                val tempIndex = mutableListOf<IndexedItem>()
                val tempWordMap = mutableMapOf<String, MutableSet<Int>>()

                loaded.forEachIndexed { idx, it ->
                    val nameLower = it.name.lowercase()
                    val words = nameLower.split(Regex("\\s+")).filter { w -> w.isNotBlank() }
                    val wordsNorm = words.map { w -> normalizeOcr(w) }
                    
                    tempIndex.add(IndexedItem(
                        entry = it,
                        nameLower = nameLower,
                        nameGlued = nameLower.replace(" ", ""),
                        nameWords = words,
                        nameWordsNorm = wordsNorm
                    ))

                    (words + wordsNorm).distinct().forEach { word ->
                        if (word.length >= 3) {
                            tempWordMap.getOrPut(word) { mutableSetOf() }.add(idx)
                        }
                    }
                }
                
                index = tempIndex
                wordMap.putAll(tempWordMap)
                Log.d("ItemDatabase", "Loaded ${index.size} items")
            } catch (e: Exception) {
                Log.e("ItemDatabase", "Error loading items.json", e)
            } finally {
                onComplete()
            }
        }.start()
    }

    fun searchItemDetailed(query: String): MatchResult? {
        if (query.isBlank() || index.isEmpty()) return null

        val normalizedQuery = query.trim().lowercase()
        val queryWords = normalizedQuery.split(Regex("\\s+")).filter { it.isNotBlank() }
        val queryGlued = normalizedQuery.replace(" ", "")
        
        if (queryWords.isEmpty()) return null

        // 1. EXACT & GLUED (Highest Priority)
        index.find { it.nameLower == normalizedQuery }?.let { return MatchResult(it.entry, 1.00f, "EXACT") }
        index.find { it.nameGlued == queryGlued }?.let { return MatchResult(it.entry, 0.98f, "GLUED") }

        // 2. WORD COVERAGE & AMBIGUITY CHECK
        val candidateIndices = queryWords.flatMap { qw -> 
            val qwNorm = normalizeOcr(qw)
            (wordMap[qw] ?: emptySet<Int>()) + (wordMap[qwNorm] ?: emptySet<Int>())
        }.toSet()

        if (candidateIndices.isEmpty()) return null

        val coverageCandidates = candidateIndices.map { index[it] }.filter { item ->
            queryWords.all { qw ->
                item.nameWords.contains(qw) || item.nameGlued.contains(qw) || 
                item.nameWordsNorm.contains(normalizeOcr(qw))
            }
        }

        if (coverageCandidates.size == 1) {
            return MatchResult(coverageCandidates[0].entry, 0.95f, "COVERAGE")
        }

        // 3. AMBIGUITY CHECK (Strict)
        if (coverageCandidates.size > 1) {
            // Multiple different items match these words. Too risky to show one.
            return null
        }

        // 4. RIGOROUS FUZZY MATCH
        val queryWordsNorm = queryWords.map { normalizeOcr(it) }
        var bestFuzzyMatch: IndexedItem? = null
        var bestFuzzyScore = 0f

        for (idx in candidateIndices) {
            val item = index[idx]
            var totalScore = 0f
            var matches = 0
            
            for (qw in queryWordsNorm) {
                var bestWordScore = 0f
                for (iw in item.nameWordsNorm) {
                    val maxLen = maxOf(qw.length, iw.length)
                    if (Math.abs(qw.length - iw.length) > 1) continue // Stricter length check
                    
                    val d = damerauLevenshtein(qw, iw)
                    var s = 1.0f - (d.toFloat() / maxLen.toFloat())
                    
                    // Prefix bonus: Warframe names are usually at the start
                    if (iw.startsWith(qw.take(3)) || qw.startsWith(iw.take(3))) s += 0.1f
                    
                    if (s > bestWordScore) bestWordScore = s
                }
                
                if (bestWordScore > 0.85f) { // Increased threshold from 0.7 to 0.85
                    totalScore += bestWordScore
                    matches++
                }
            }

            if (matches >= queryWordsNorm.size && matches > 0) {
                val avgScore = totalScore / matches
                val coverage = matches.toFloat() / item.nameWordsNorm.size
                val finalScore = avgScore * coverage

                if (finalScore > bestFuzzyScore) {
                    bestFuzzyScore = finalScore
                    bestFuzzyMatch = item
                }
            }
        }

        if (bestFuzzyMatch != null && bestFuzzyScore >= 0.70f) { // Increased min score
            return MatchResult(bestFuzzyMatch.entry, bestFuzzyScore, "FUZZY")
        }

        return null
    }

    private fun normalizeOcr(s: String): String {
        val noDiacritics = Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        return noDiacritics.lowercase()
            .replace('0', 'o').replace('1', 'l').replace('|', 'l')
            .replace('5', 's').replace('3', 'e')
    }

    private fun damerauLevenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            val cost = if (a[i-1] == b[j-1]) 0 else 1
            dp[i][j] = minOf(dp[i-1][j] + 1, dp[i][j-1] + 1, dp[i-1][j-1] + cost)
            if (i > 1 && j > 1 && a[i-1] == b[j-2] && a[i-2] == b[j-1]) {
                dp[i][j] = minOf(dp[i][j], dp[i-2][j-2] + 1)
            }
        }
        return dp[a.length][b.length]
    }
}
