package com.warframe.priceoverlay

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private lateinit var itemDatabase: ItemDatabase
    private lateinit var api: WarframeMarketApi

    // ── Singleton TextRecognizer — created once, reused for every OCR call ──────
    // Previously re-created inside runOcr() on every call (4× per scan cycle).
    // This was re-initializing the ML model each time, adding significant overhead.
    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var screenWidth: Int = 0
    private var screenHeight: Int = 0
    private var screenDensity: Int = 0

    private var scanningActive = false
    private var scanLoopJob: Job? = null

    // ── Timing ──────────────────────────────────────────────────────────────────
    private val SCAN_INTERVAL_MS   = 1500L
    private val FRAME_SPACING_MS   = 150L
    private val FRAME_COUNT        = 2
    private val CONFIRM_VOTES      = 1
    private val DECAY_PER_CYCLE    = 1
    private val MAX_RETRY_ATTEMPTS = 4
    private val apiSemaphore       = Semaphore(3)

    // Vote bank
    private val voteBank    = mutableMapOf<String, Int>()
    private val voteEntries = mutableMapOf<String, ItemEntry>()

    // Display state
    private enum class RowState { LOADING_API, PRICED, NO_SELLERS, ERROR }
    private val rowState      = mutableMapOf<String, RowState>()
    private val displayedRows = mutableMapOf<String, TextView>()
    private val fetchJobs     = mutableMapOf<String, Job>()

    private lateinit var btnToggle: ImageView
    private lateinit var tvToggleLabel: TextView
    private lateinit var tvLastScan: TextView
    private lateinit var tvMedianLabel: TextView
    private lateinit var divider: View
    private lateinit var scrollResults: ScrollView
    private lateinit var resultsContainer: LinearLayout

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        itemDatabase = ItemDatabase(this)
        itemDatabase.load()
        api = WarframeMarketApi()
        setupOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra("EXTRA_RESULT_CODE", Int.MIN_VALUE) ?: Int.MIN_VALUE
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            intent?.getParcelableExtra("EXTRA_RESULT_DATA", Intent::class.java)
        else
            @Suppress("DEPRECATION") intent?.getParcelableExtra("EXTRA_RESULT_DATA")

        val channelId = "OverlayServiceChannel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(channelId, "Overlay Service", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Warframe Price Overlay")
            .setContentText("Tap to toggle scanning")
            .setSmallIcon(R.drawable.ic_launcher)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else
            startForeground(1, notification)

        if (resultCode == Activity.RESULT_OK && resultData != null && mediaProjection == null) {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mpm.getMediaProjection(resultCode, resultData)
            setupVirtualDisplay()
            startScanning()
        } else {
            Log.w("WFOverlay", "MediaProjection not ready: resultCode=$resultCode")
        }

        return START_NOT_STICKY
    }

    // ── Row management ──────────────────────────────────────────────────────────

    private fun setRow(slug: String, name: String, state: RowState, price: String? = null) {
        rowState[slug] = state
        // "(48h median)" removed from per-row label — shown once in header subtitle instead
        val (label, colorHex) = when (state) {
            RowState.LOADING_API -> "🔄 $name  ›  loading..." to "#FFAAAAAA"
            RowState.PRICED      -> "📊 $name  ›  ${price}p"  to "#FF80FF80"
            RowState.NO_SELLERS  -> "🚫 $name  ›  no data"    to "#FFFF8080"
            RowState.ERROR       -> "⚠️ $name  ›  error"      to "#FFFFAA44"
        }
        val existing = displayedRows[slug]
        if (existing != null) {
            existing.text = label
            existing.setTextColor(Color.parseColor(colorHex))
        } else {
            val tv = makeRow(label, colorHex)
            displayedRows[slug] = tv
            resultsContainer.addView(tv)
            divider.visibility = View.VISIBLE
            scrollResults.visibility = View.VISIBLE
        }
    }

    private fun removeRow(slug: String) {
        fetchJobs.remove(slug)?.cancel()
        rowState.remove(slug)
        displayedRows.remove(slug)?.let { resultsContainer.removeView(it) }
        if (displayedRows.isEmpty()) {
            divider.visibility = View.GONE
            scrollResults.visibility = View.GONE
        }
    }

    // ── API fetch with retry ────────────────────────────────────────────────────

    private fun launchFetch(slug: String, entry: ItemEntry) {
        fetchJobs[slug]?.cancel()
        fetchJobs[slug] = CoroutineScope(Dispatchers.Main).launch {
            var attempt = 0
            while (attempt < MAX_RETRY_ATTEMPTS && isActive) {
                attempt++
                val result = apiSemaphore.withPermit {
                    withContext(Dispatchers.IO) { api.fetchStats(slug) }
                }
                if (!rowState.containsKey(slug)) break

                when (result) {
                    is ApiResult.Success -> {
                        setRow(slug, entry.name, RowState.PRICED, result.price.toString())
                        Log.d("WFOverlay", "✅ ${entry.name} → ${result.price}p")
                        break
                    }
                    is ApiResult.NotFound -> {
                        setRow(slug, entry.name, RowState.NO_SELLERS)
                        break
                    }
                    is ApiResult.RateLimited -> {
                        val wait = result.retryAfterMs.coerceAtLeast(1000L)
                        Log.w("WFOverlay", "⏳ 429 ${entry.name} — retrying in ${wait}ms")
                        delay(wait)
                    }
                    is ApiResult.Failure -> {
                        if (attempt >= MAX_RETRY_ATTEMPTS) {
                            setRow(slug, entry.name, RowState.ERROR)
                        } else {
                            delay((500L * attempt).coerceAtMost(3000L))
                        }
                    }
                }
            }
        }
    }

    // ── Overlay setup ───────────────────────────────────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    private fun setupOverlay() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_layout, null)

        btnToggle        = overlayView.findViewById(R.id.btn_toggle)
        tvToggleLabel    = overlayView.findViewById(R.id.tv_toggle_label)
        tvLastScan       = overlayView.findViewById(R.id.tv_last_scan)
        tvMedianLabel    = overlayView.findViewById(R.id.tv_median_label)
        divider          = overlayView.findViewById(R.id.divider)
        scrollResults    = overlayView.findViewById(R.id.scroll_results)
        resultsContainer = overlayView.findViewById(R.id.results_container)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 20; params.y = 120
        windowManager.addView(overlayView, params)

        var iX = 0; var iY = 0; var iTX = 0f; var iTY = 0f
        overlayView.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN  -> { iX = params.x; iY = params.y; iTX = e.rawX; iTY = e.rawY; true }
                MotionEvent.ACTION_MOVE  -> { params.x = iX + (e.rawX - iTX).toInt(); params.y = iY + (e.rawY - iTY).toInt(); windowManager.updateViewLayout(overlayView, params); true }
                MotionEvent.ACTION_UP    -> { if (Math.abs(e.rawX - iTX) < 12 && Math.abs(e.rawY - iTY) < 12) toggleScanning(); true }
                else -> false
            }
        }
    }

    private fun toggleScanning() { if (scanningActive) stopScanning() else startScanning() }

    private fun startScanning() {
        if (mediaProjection == null || imageReader == null) {
            Toast.makeText(this, "Screen capture not ready. Re-launch.", Toast.LENGTH_LONG).show(); return
        }
        scanningActive = true
        setToggleUI(on = true)
        scanLoopJob = CoroutineScope(Dispatchers.Main).launch {
            while (isActive) { runSingleScan(); delay(SCAN_INTERVAL_MS) }
        }
    }

    private fun stopScanning() {
        scanningActive = false
        scanLoopJob?.cancel(); scanLoopJob = null
        fetchJobs.values.forEach { it.cancel() }; fetchJobs.clear()
        voteBank.clear(); voteEntries.clear()
        rowState.clear(); displayedRows.clear()
        setToggleUI(on = false)
        divider.visibility = View.GONE
        scrollResults.visibility = View.GONE
        resultsContainer.removeAllViews()
    }

    private fun setToggleUI(on: Boolean) {
        if (on) {
            btnToggle.setColorFilter(Color.parseColor("#FF00E676"))
            tvToggleLabel.text = "● SCANNING"
            tvToggleLabel.setTextColor(Color.parseColor("#FF00E676"))
            tvLastScan.visibility = View.VISIBLE
            tvMedianLabel.visibility = View.VISIBLE
        } else {
            btnToggle.clearColorFilter()
            tvToggleLabel.text = "○ OFF"
            tvToggleLabel.setTextColor(Color.parseColor("#FF888888"))
            tvLastScan.visibility = View.GONE
            tvLastScan.text = ""
            tvMedianLabel.visibility = View.GONE
        }
    }

    // ── Preprocessing — both variants now accept an already-scaled bitmap ───────

    private fun scaleUp(bmp: Bitmap): Bitmap {
        val f = when { bmp.width < 1080 -> 2f; bmp.width < 1440 -> 1.5f; else -> 1f }
        return if (f > 1f) Bitmap.createScaledBitmap(bmp,
            (bmp.width * f).toInt(), (bmp.height * f).toInt(), true) else bmp
    }

    private fun preprocessHighContrast(scaled: Bitmap): Bitmap {
        val cm = ColorMatrix(floatArrayOf(
            1.8f, 0f, 0f, 0f, -30f,
            0f, 1.8f, 0f, 0f, -30f,
            0f, 0f, 1.8f, 0f, -30f,
            0f, 0f, 0f,  1f,   0f
        ))
        val out = Bitmap.createBitmap(scaled.width, scaled.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(scaled, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(cm) })
        return out
    }

    /**
     * GPU binarization — replaces the old pixel-by-pixel Kotlin loop.
     * Uses two ColorMatrix passes (both GPU-accelerated via Canvas hardware rendering):
     *   1. Desaturate → grayscale
     *   2. Extreme contrast (factor=10, brightness=-1100) → threshold at luma≈110
     * Result is visually equivalent to the pixel loop but runs on the GPU.
     */
    private fun preprocessBinarized(scaled: Bitmap): Bitmap {
        // Pass 1: grayscale (GPU)
        val gray = Bitmap.createBitmap(scaled.width, scaled.height, Bitmap.Config.ARGB_8888)
        Canvas(gray).drawBitmap(scaled, 0f, 0f,
            Paint().apply {
                colorFilter = ColorMatrixColorFilter(ColorMatrix().also { it.setSaturation(0f) })
            })

        // Pass 2: extreme contrast → effective binary threshold at luma ~110 (GPU)
        // Math: output = input * 10 - 1100
        //   luma=110 → 0  (crossover)
        //   luma=120 → 100 → bright (text)
        //   luma= 90 → -10 → clamped to 0 (background)
        val binary = Bitmap.createBitmap(scaled.width, scaled.height, Bitmap.Config.ARGB_8888)
        val cm = ColorMatrix(floatArrayOf(
            10f, 0f, 0f, 0f, -1100f,
            0f, 10f, 0f, 0f, -1100f,
            0f, 0f, 10f, 0f, -1100f,
            0f, 0f, 0f,  1f,     0f
        ))
        Canvas(binary).drawBitmap(gray, 0f, 0f,
            Paint().apply { colorFilter = ColorMatrixColorFilter(cm) })
        gray.recycle()
        return binary
    }

    // ── Frame capture ───────────────────────────────────────────────────────────

    private suspend fun captureFrame(): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val image = imageReader?.acquireLatestImage() ?: return@withContext null
            try {
                val p = image.planes[0]
                val rowPadding = p.rowStride - p.pixelStride * screenWidth
                val bmp = Bitmap.createBitmap(screenWidth + rowPadding / p.pixelStride,
                    screenHeight, Bitmap.Config.ARGB_8888)
                bmp.copyPixelsFromBuffer(p.buffer)
                Bitmap.createBitmap(bmp, 0, 0, screenWidth, screenHeight)
            } finally { image.close() }
        } catch (e: Exception) { Log.e("WFOverlay", "captureFrame: ${e.message}"); null }
    }

    // ── OCR helpers ─────────────────────────────────────────────────────────────

    private fun extractCandidates(vt: com.google.mlkit.vision.text.Text): List<String> {
        val out = mutableListOf<String>()
        for (block in vt.textBlocks) {
            val lines = block.lines.map { it.text.trim() }.filter { it.length >= 2 }
            if (lines.isEmpty()) continue
            lines.forEach { out.add(it) }
            for (i in 0 until lines.size - 1) out.add("${lines[i]} ${lines[i + 1]}")
            if (lines.size >= 3) out.add(lines.joinToString(" "))
        }
        return out
    }

    private fun matchCandidates(candidates: List<String>, seenThisPass: MutableSet<String>) {
        for (text in candidates) {
            if (text.length < 3 || text.count { it.isLetter() } < 3) continue
            val words = text.split(Regex("\\s+")).filter { it.length >= 2 }
            for (windowSize in 1..7) {
                for (start in 0..words.size - windowSize) {
                    val candidate = words.subList(start, start + windowSize).joinToString(" ")
                    val match = itemDatabase.searchItem(candidate)
                    if (match != null && seenThisPass.add(match.slug)) {
                        Log.d("WFOverlay", "  ✅ \"$candidate\" → \"${match.name}\"")
                        voteEntries[match.slug] = match
                    }
                }
            }
        }
    }

    // ── Main scan ───────────────────────────────────────────────────────────────

    private suspend fun runSingleScan() {
        Log.d("WFOverlay", "─── SCAN START ───────────────────────────────")

        // Capture frames
        val bitmaps = mutableListOf<Bitmap>()
        repeat(FRAME_COUNT) { i ->
            if (i > 0) delay(FRAME_SPACING_MS)
            captureFrame()?.also { bitmaps.add(it) }
        }
        if (bitmaps.isEmpty()) { Log.w("WFOverlay", "No frames"); return }

        // Scale once per frame, share between both preprocessing paths
        val scaledBitmaps = bitmaps.map { scaleUp(it) }

        // 4 OCR passes in parallel — singleton recognizer, no per-call init overhead
        val ocrTasks = scaledBitmaps.mapIndexed { fi, scaled ->
            listOf(
                CoroutineScope(Dispatchers.Default).async {
                    val p = preprocessHighContrast(scaled)
                    val r = try { runOcr(p).also { Log.d("WFOverlay", "F$fi hi-contrast: ${it.textBlocks.size}b") } }
                             catch (e: Exception) { null }
                    p.recycle(); r
                },
                CoroutineScope(Dispatchers.Default).async {
                    val p = preprocessBinarized(scaled)
                    val r = try { runOcr(p).also { Log.d("WFOverlay", "F$fi binarized: ${it.textBlocks.size}b") } }
                             catch (e: Exception) { null }
                    p.recycle(); r
                }
            )
        }.flatten()

        val ocrResults = ocrTasks.awaitAll().filterNotNull()
        scaledBitmaps.forEach { if (it !in bitmaps) it.recycle() }
        bitmaps.forEach { it.recycle() }
        Log.d("WFOverlay", "OCR: ${ocrResults.size}/4 passes")

        // Match
        val seenThisCycle = mutableSetOf<String>()
        for (vt in ocrResults) {
            val pass = mutableSetOf<String>()
            matchCandidates(extractCandidates(vt), pass)
            seenThisCycle.addAll(pass)
        }

        // Update votes
        val allTracked = (voteBank.keys + seenThisCycle).toSet()
        for (slug in allTracked) {
            val prev = voteBank.getOrDefault(slug, 0)
            val next = (prev + if (slug in seenThisCycle) 1 else -DECAY_PER_CYCLE).coerceIn(0, 6)
            if (next == 0) voteBank.remove(slug) else voteBank[slug] = next
        }

        rowState.keys.filter { it !in voteBank }.forEach { removeRow(it) }

        for ((slug, votes) in voteBank) {
            if (votes < CONFIRM_VOTES) continue
            val entry = voteEntries[slug] ?: continue
            if (!rowState.containsKey(slug)) {
                setRow(slug, entry.name, RowState.LOADING_API)
                launchFetch(slug, entry)
            }
        }

        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        tvLastScan.text = "updated $time"
        Log.d("WFOverlay", "─── SCAN END ─────────────────────────────────")
    }

    // Reuses singleton recognizer — no model re-init per call
    private suspend fun runOcr(bitmap: Bitmap) = suspendCancellableCoroutine { cont ->
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { cont.resume(it) }
            .addOnFailureListener { cont.resumeWithException(it) }
    }

    private fun makeRow(text: String, colorHex: String) = TextView(this).apply {
        this.text = text; textSize = 12f
        setTextColor(Color.parseColor(colorHex)); setPadding(6, 4, 6, 4)
        typeface = android.graphics.Typeface.MONOSPACE
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { it.bottomMargin = 2 }
    }

    private fun setupVirtualDisplay() {
        val m = resources.displayMetrics
        screenWidth = m.widthPixels; screenHeight = m.heightPixels; screenDensity = m.densityDpi

        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    stopScanning()
                    virtualDisplay?.release(); virtualDisplay = null
                    imageReader?.close(); imageReader = null; mediaProjection = null
                }
            }
        }, android.os.Handler(android.os.Looper.getMainLooper()))

        @SuppressLint("WrongConstant")
        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture", screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader?.surface, null, null
        )
        Log.d("WFOverlay", "VirtualDisplay: ${screenWidth}x${screenHeight} dpi=$screenDensity")
    }

    override fun onDestroy() {
        super.onDestroy()
        stopScanning()
        recognizer.close()
        virtualDisplay?.release(); imageReader?.close(); mediaProjection?.stop()
        if (::overlayView.isInitialized) windowManager.removeView(overlayView)
    }
}