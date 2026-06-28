package com.warframe.priceoverlay

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import org.json.JSONObject
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer

data class ItemEntry(
    @SerializedName("n") val name: String,
    @SerializedName("u") val slug: String,
    @SerializedName("d") var ducats: Int? = null,
    @SerializedName("t") var lastUpdate: Long = 0
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
    private val DB_FILE_NAME = "items.json"

    fun load(onComplete: () -> Unit = {}) {
        Thread {
            try {
                val dbFile = File(context.filesDir, DB_FILE_NAME)
                if (!dbFile.exists()) {
                    context.assets.open(DB_FILE_NAME).use { input ->
                        dbFile.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                dbFile.inputStream().use { inputStream ->
                    val loaded: List<ItemEntry> = Gson().fromJson(InputStreamReader(inputStream), object : TypeToken<List<ItemEntry>>() {}.type)
                    buildIndex(loaded)
                }
            } catch (e: Exception) {
                Log.e("ItemDatabase", "Load error", e)
            } finally {
                onComplete()
            }
        }.start()
    }

    @Synchronized
    private fun buildIndex(items: List<ItemEntry>) {
        val tempIndex = mutableListOf<IndexedItem>()
        val tempWordMap = mutableMapOf<String, MutableSet<Int>>()

        items.forEachIndexed { idx, it ->
            val nameLower = it.name.lowercase()
            val words = nameLower.split(Regex("\\s+")).filter { it.isNotBlank() }
            val wordsNorm = words.map { normalizeOcr(it) }
            tempIndex.add(IndexedItem(it, nameLower, nameLower.replace(" ", ""), words, wordsNorm))
            (words + wordsNorm).distinct().forEach { word ->
                if (word.length >= 3) tempWordMap.getOrPut(word) { mutableSetOf() }.add(idx)
            }
        }
        index = tempIndex
        wordMap.clear()
        wordMap.putAll(tempWordMap)
    }

    fun updateFromApi(onStatus: (String) -> Unit = {}) {
        Thread {
            try {
                onStatus("Updating names...")
                val conn = (URL("https://api.warframe.market/v1/items").openConnection() as HttpURLConnection).apply {
                    setRequestProperty("Accept", "application/json")
                    connectTimeout = 10000
                }
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().readText()
                    val itemsArray = JSONObject(body).getJSONObject("payload").getJSONArray("items")
                    val existingData = index.associate { it.entry.slug to (it.entry.ducats to it.entry.lastUpdate) }
                    val newList = mutableListOf<ItemEntry>()
                    for (i in 0 until itemsArray.length()) {
                        val obj = itemsArray.getJSONObject(i)
                        val slug = obj.getString("url_name")
                        val old = existingData[slug]
                        newList.add(ItemEntry(obj.getString("item_name"), slug, old?.first, old?.second ?: 0))
                    }
                    if (newList.size > 2000) {
                        saveToDisk(newList)
                        buildIndex(newList)
                        onStatus("Sync complete")
                    }
                }
                conn.disconnect()
            } catch (e: Exception) {
                onStatus("Sync failed")
            }
        }.start()
    }

    fun saveLearnedDucats(slug: String, value: Int) {
        val item = index.find { it.entry.slug == slug }?.entry ?: return
        item.ducats = value
        item.lastUpdate = System.currentTimeMillis()
        Thread { synchronized(this) { saveToDisk(index.map { it.entry }) } }.start()
    }

    private fun saveToDisk(items: List<ItemEntry>) {
        try { File(context.filesDir, DB_FILE_NAME).writeText(Gson().toJson(items)) } catch (e: Exception) {}
    }

    fun searchItemDetailed(query: String): MatchResult? {
        if (query.isBlank() || index.isEmpty()) return null
        
        val fixedQuery = query.trim().lowercase()
        val queryWords = fixedQuery.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (queryWords.isEmpty()) return null

        // 1. EXACT & GLUED
        index.find { it.nameLower == fixedQuery }?.let { return MatchResult(it.entry, 1.0f, "EXACT") }
        index.find { it.nameGlued == fixedQuery.replace(" ", "") }?.let { return MatchResult(it.entry, 0.98f, "GLUED") }

        // 2. CANDIDATE PRUNING WITH TYPO TOLERANCE (Handles "Pime", "Wokong", etc.)
        val candidates = queryWords.flatMap { qw ->
            val direct = (wordMap[qw] ?: emptySet<Int>()) + (wordMap[normalizeOcr(qw)] ?: emptySet<Int>())
            if (direct.isNotEmpty()) direct else {
                // If not found, look for similar words in the index (Fuzzy candidates)
                wordMap.keys.filter { dbw -> damerauLevenshtein(qw, dbw) <= 1 }
                    .flatMap { wordMap[it] ?: emptySet<Int>() }
            }
        }.toSet()

        if (candidates.isEmpty()) return null

        // 3. WORD COVERAGE
        val coverage = candidates.map { index[it] }.filter { item ->
            queryWords.all { qw -> 
                item.nameWords.any { it.contains(qw) || qw.contains(it) || damerauLevenshtein(qw, it) <= 1 }
            }
        }

        if (coverage.size == 1) return MatchResult(coverage[0].entry, 0.95f, "COVERAGE")
        if (coverage.size > 1) {
            val baseNames = coverage.map { it.nameWords.first() }.toSet()
            if (baseNames.size == 1) {
                val best = coverage.minByOrNull { Math.abs(it.nameWords.size - queryWords.size) }
                if (best != null) return MatchResult(best.entry, 0.90f, "PARTIAL")
            }
            // Preference for Prime if multiple found (like Wukong vs Wukong Prime)
            coverage.find { it.nameLower.contains("prime") }?.let { return MatchResult(it.entry, 0.85f, "PRIME_PRIORITY") }
            return null
        }

        // 4. DEEP FUZZY (Final fallback)
        val queryNorm = queryWords.map { normalizeOcr(it) }
        var bestF: IndexedItem? = null; var bestS = 0f
        for (idx in candidates) {
            val item = index[idx]; var tS = 0f; var m = 0
            for (qw in queryNorm) {
                var bWS = 0f
                for (iw in item.nameWordsNorm) {
                    val maxLen = maxOf(qw.length, iw.length).toFloat()
                    if (Math.abs(qw.length - iw.length) > 2) continue
                    val d = damerauLevenshtein(qw, iw)
                    var s = 1.0f - (d.toFloat() / maxLen)
                    if (iw.startsWith(qw.take(2)) || qw.startsWith(iw.take(2))) s += 0.05f
                    if (s > bWS) bWS = s
                }
                if (bWS > 0.60f) { tS += bWS; m++ }
            }
            if (m >= (queryNorm.size * 0.6)) {
                val score = (tS / queryNorm.size) * (m.toFloat() / item.nameWordsNorm.size)
                if (score > bestS) { bestS = score; bestF = item }
            }
        }
        return if (bestF != null && bestS >= 0.50f) MatchResult(bestF.entry, bestS, "FUZZY") else null
    }

    private fun normalizeOcr(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD)
        .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "").lowercase()
        .replace('0','o').replace('1','l').replace('|','l').replace('5','s').replace('3','e')
        .replace('v','u').replace('q','g')

    private fun damerauLevenshtein(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i; for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            val cost = if (a[i-1] == b[j-1]) 0 else 1
            dp[i][j] = minOf(dp[i-1][j] + 1, dp[i][j-1] + 1, dp[i-1][j-1] + cost)
            if (i>1 && j>1 && a[i-1]==b[j-2] && a[i-2]==b[j-1]) dp[i][j] = minOf(dp[i][j], dp[i-2][j-2] + 1)
        }
        return dp[a.length][b.length]
    }
}
