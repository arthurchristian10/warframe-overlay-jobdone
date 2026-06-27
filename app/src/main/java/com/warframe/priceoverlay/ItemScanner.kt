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
        val sf = scaleFactorFor(bitmap.width, bitmap.height)
        val scaled = bitmap.scale((bitmap.width * sf).toInt(), (bitmap.height * sf).toInt(), true)

        val varA = preprocessGrayContrast(scaled)
        val varB = preprocessBinarized(scaled)
        val varC = preprocessSharpened(scaled)
        scaled.recycle()
        
        try {
            val dA = async { runOcr(varA) }
            val dB = async { runOcr(varB) }
            val dC = async { runOcr(varC) }
            
            val ocrResults = listOf(dA, dB, dC).awaitAll().filterNotNull()
            
            val detectedItems = mutableMapOf<String, ScannedItem>()
            val debugLines = mutableListOf<String>()

            for (vt in ocrResults) {
                val blocks = buildMergedBlocks(vt)
                matchBlocks(blocks, sf, cropOffset, lookupMode, detectedItems, debugLines)
            }

            if (debugLines.isNotEmpty()) {
                val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
                val header = "── $time ────────────────\n"
                onDebugLog(header + debugLines.distinct().joinToString("\n\n"))
            }

            detectedItems.values.toList()
        } finally {
            varA.recycle()
            varB.recycle()
            varC.recycle()
        }
    }

    private suspend fun runOcr(bitmap: Bitmap): Text? = suspendCancellableCoroutine { cont ->
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
            .addOnFailureListener {
                Log.e("ItemScanner", "OCR Failed", it)
                if (cont.isActive) cont.resume(null) 
            }
    }

    private fun scaleFactorFor(w: Int, h: Int): Float {
        val shortSide = minOf(w, h)
        return when {
            shortSide < 720 -> 3.0f
            shortSide < 1080 -> 2.0f
            shortSide < 1440 -> 1.5f
            else -> 1.0f
        }
    }

    private fun preprocessGrayContrast(bmp: Bitmap): Bitmap {
        val gray = createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(gray)
        canvas.drawBitmap(bmp, 0f, 0f, Paint().apply {
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

    private data class MergedBlock(
        val text: String,
        val nativeRect: Rect,
        val sourceIndices: Set<Int>,
        val sourceElements: List<Text.Element>
    )

    private fun buildMergedBlocks(vt: Text): List<MergedBlock> {
        val originals = vt.textBlocks.mapNotNull { block ->
            val bb = block.boundingBox ?: return@mapNotNull null
            val elements = block.lines.flatMap { it.elements }
            val linesText = block.lines.map { it.text.trim() }.filter { it.isNotBlank() }
            if (linesText.isEmpty()) return@mapNotNull null
            MergedBlock(
                text = linesText.joinToString(" "),
                nativeRect = Rect(bb),
                sourceIndices = setOf(vt.textBlocks.indexOf(block)),
                sourceElements = elements
            )
        }.sortedBy { it.nativeRect.top }

        if (originals.size < 2) return originals
        val result = originals.toMutableList()

        fun canMerge(a: MergedBlock, b: MergedBlock): Boolean {
            if (b.nativeRect.top - a.nativeRect.bottom > blockMergeGapPx) return false
            val overlapW = minOf(a.nativeRect.right, b.nativeRect.right) - maxOf(a.nativeRect.left, b.nativeRect.left)
            val smallerW = minOf(a.nativeRect.width(), b.nativeRect.width())
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
                    nativeRect = Rect(
                        minOf(current.nativeRect.left, candidate.nativeRect.left),
                        minOf(current.nativeRect.top, candidate.nativeRect.top),
                        maxOf(current.nativeRect.right, candidate.nativeRect.right),
                        maxOf(current.nativeRect.bottom, candidate.nativeRect.bottom)
                    ),
                    sourceIndices = current.sourceIndices + candidate.sourceIndices,
                    sourceElements = current.sourceElements + candidate.sourceElements
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
        sf: Float,
        cropOffset: Point,
        lookupMode: Boolean,
        detectedItems: MutableMap<String, ScannedItem>,
        debugLines: MutableList<String>
    ) {
        for (block in blocks) {
            val elements = block.sourceElements
            if (elements.isEmpty()) continue

            var matchedInBlock = false
            outer@ for (ws in minOf(7, elements.size) downTo 2) {
                for (start in 0..elements.size - ws) {
                    val subElements = elements.subList(start, start + ws)
                    val query = subElements.joinToString(" ") { it.text }
                    val match = itemDatabase.searchItemDetailed(query) ?: continue
                    
                    if (lookupMode && match.entry.name.contains("relic", ignoreCase = true)) continue

                    // Calculate precise native bounding box of ONLY matched elements
                    val preciseNative = Rect(Int.MAX_VALUE, Int.MAX_VALUE, Int.MIN_VALUE, Int.MIN_VALUE)
                    subElements.forEach { el ->
                        el.boundingBox?.let { bb ->
                            preciseNative.left = minOf(preciseNative.left, bb.left)
                            preciseNative.top = minOf(preciseNative.top, bb.top)
                            preciseNative.right = maxOf(preciseNative.right, bb.right)
                            preciseNative.bottom = maxOf(preciseNative.bottom, bb.bottom)
                        }
                    }
                    
                    // Convert native scaled coordinates to final screen coordinates
                    val screenRect = Rect(
                        (preciseNative.left / sf).roundToInt() + cropOffset.x,
                        (preciseNative.top / sf).roundToInt() + cropOffset.y,
                        (preciseNative.right / sf).roundToInt() + cropOffset.x,
                        (preciseNative.bottom / sf).roundToInt() + cropOffset.y
                    )

                    detectedItems[match.entry.slug] = ScannedItem(match.entry, screenRect, match.confidence)
                    matchedInBlock = true
                    
                    debugLines.add("✓ [${match.matchType}] \"$query\"\n  → ${match.entry.name} (${"%.2f".format(match.confidence)})")
                    break@outer
                }
            }
            if (!matchedInBlock) {
                debugLines.add("✗ \"${block.text.take(50)}\"")
            }
        }
    }
}
