package com.warframe.priceoverlay

import android.graphics.*
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognizer
import kotlinx.coroutines.*
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import kotlin.coroutines.resume
import kotlin.math.roundToInt

data class ScannedItem(
    val entry: ItemEntry,
    val bounds: Rect,
    val confidence: Float
)

class ItemScanner(
    private val itemDatabase: ItemDatabase,
    private val recognizer: TextRecognizer
) {
    private val blockMergeGapPx = 60
    private val blockMergeOverlapF = 0.3f

    suspend fun scanBitmap(
        bitmap: Bitmap,
        cropOffset: Point,
        lookupMode: Boolean,
        onDebugLog: (String) -> Unit
    ): List<ScannedItem> = withContext(Dispatchers.Default) {
        val sf = scaleFactorFor(bitmap.width)
        val scaled = bitmap.scale((bitmap.width * sf).toInt(), (bitmap.height * sf).toInt(), true)

        val varA = preprocessGrayContrast(scaled)
        val varB = preprocessBinarized(scaled)
        val varC = preprocessSharpened(scaled)
        scaled.recycle()

        val dA = async { runOcr(varA) }
        val dB = async { runOcr(varB) }
        val dC = async { runOcr(varC) }
        
        val ocrResults = listOf(dA, dB, dC).awaitAll().filterNotNull()
        varA.recycle(); varB.recycle(); varC.recycle()

        val detectedItems = mutableMapOf<String, ScannedItem>()
        val debugLines = mutableListOf<String>()

        for (vt in ocrResults) {
            val blocks = buildMergedBlocks(vt, sf, cropOffset)
            matchBlocks(blocks, lookupMode, detectedItems, debugLines)
        }

        if (debugLines.isNotEmpty()) {
            onDebugLog(debugLines.distinct().joinToString("\n\n"))
        }

        detectedItems.values.toList()
    }

    private suspend fun runOcr(bitmap: Bitmap): Text? = suspendCancellableCoroutine { cont ->
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { cont.resume(it) }
            .addOnFailureListener {
                Log.e("ItemScanner", "OCR Failed", it)
                cont.resume(null) 
            }
    }

    private fun scaleFactorFor(w: Int) = when {
        w < 1080 -> 2.0f
        w < 1440 -> 2.0f
        w < 2160 -> 1.5f
        else -> 1.0f
    }

    private fun preprocessGrayContrast(bmp: Bitmap): Bitmap {
        val gray = createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
        Canvas(gray).drawBitmap(bmp, 0f, 0f, Paint().apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix().also { it.setSaturation(0f) })
        })
        val out = createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(gray, 0f, 0f, Paint().apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
                2.2f,0f,0f,0f,-60f, 0f,2.2f,0f,0f,-60f, 0f,0f,2.2f,0f,-60f, 0f,0f,0f,1f,0f
            )))
        })
        gray.recycle(); return out
    }

    private fun preprocessBinarized(bmp: Bitmap): Bitmap {
        val gray = createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
        Canvas(gray).drawBitmap(bmp, 0f, 0f, Paint().apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix().also { it.setSaturation(0f) })
        })
        val out = createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(gray, 0f, 0f, Paint().apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
                5f,0f,0f,0f,-400f, 0f,5f,0f,0f,-400f, 0f,0f,5f,0f,-400f, 0f,0f,0f,1f,0f
            )))
        })
        gray.recycle(); return out
    }

    private fun preprocessSharpened(bmp: Bitmap): Bitmap {
        val out = createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(bmp, 0f, 0f, Paint().apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
                1.5f,0f,0f,0f,-20f, 0f,1.5f,0f,0f,-20f, 0f,0f,1.5f,0f,-20f, 0f,0f,0f,1f,0f
            )))
        })
        return out
    }

    private data class MergedBlock(val text: String, val rect: Rect, val sourceIndices: Set<Int>)

    private fun buildMergedBlocks(vt: Text, sf: Float, offset: Point): List<MergedBlock> {
        val originals = vt.textBlocks.mapNotNull { block ->
            val bb = block.boundingBox ?: return@mapNotNull null
            val lines = block.lines.map { it.text.trim() }.filter { it.isNotBlank() }
            if (lines.isEmpty()) return@mapNotNull null
            MergedBlock(
                text = lines.joinToString(" "),
                rect = Rect(
                    (bb.left / sf).roundToInt() + offset.x,
                    (bb.top / sf).roundToInt() + offset.y,
                    (bb.right / sf).roundToInt() + offset.x,
                    (bb.bottom / sf).roundToInt() + offset.y
                ),
                sourceIndices = setOf(vt.textBlocks.indexOf(block))
            )
        }.sortedBy { it.rect.top }

        if (originals.size < 2) return originals
        val result = originals.toMutableList()

        fun canMerge(a: MergedBlock, b: MergedBlock): Boolean {
            if (b.rect.top - a.rect.bottom > blockMergeGapPx) return false
            val overlapW = minOf(a.rect.right, b.rect.right) - maxOf(a.rect.left, b.rect.left)
            val smallerW = minOf(a.rect.width(), b.rect.width())
            return smallerW > 0 && overlapW.toFloat() / smallerW >= blockMergeOverlapF
        }

        fun dfs(current: MergedBlock, startIdx: Int, depth: Int) {
            if (depth >= 4) return
            for (j in startIdx until originals.size) {
                val candidate = originals[j]
                if (candidate.sourceIndices.any { it in current.sourceIndices }) continue
                if (!canMerge(current, candidate)) continue

                val mergedBlock = MergedBlock(
                    text = "${current.text} ${candidate.text}",
                    rect = Rect(
                        minOf(current.rect.left, candidate.rect.left),
                        minOf(current.rect.top, candidate.rect.top),
                        maxOf(current.rect.right, candidate.rect.right),
                        maxOf(current.rect.bottom, candidate.rect.bottom)
                    ),
                    sourceIndices = current.sourceIndices + candidate.sourceIndices
                )
                result.add(mergedBlock)
                dfs(mergedBlock, j + 1, depth + 1)
            }
        }

        for (i in originals.indices) dfs(originals[i], i + 1, 1)
        val seen = mutableSetOf<Set<Int>>()
        return result.filter { seen.add(it.sourceIndices) }
    }

    private fun matchBlocks(
        blocks: List<MergedBlock>,
        lookupMode: Boolean,
        detectedItems: MutableMap<String, ScannedItem>,
        debugLines: MutableList<String>
    ) {
        for (block in blocks) {
            val words = block.text.split(Regex("\\s+")).filter { it.length >= 2 }
            if (words.isEmpty()) continue

            var matched = false
            outer@ for (ws in minOf(7, words.size) downTo 2) {
                for (start in 0..words.size - ws) {
                    val query = words.subList(start, start + ws).joinToString(" ")
                    val match = itemDatabase.searchItemDetailed(query) ?: continue
                    
                    if (lookupMode && match.entry.name.contains("relic", ignoreCase = true)) continue

                    detectedItems[match.entry.slug] = ScannedItem(match.entry, block.rect, match.confidence)
                    matched = true
                    debugLines.add("✓ [${"%.2f".format(match.confidence)}] \"$query\" -> ${match.entry.name}")
                    break@outer
                }
            }
            if (!matched) {
                debugLines.add("✗ \"${block.text.take(40)}\"")
            }
        }
    }
}
