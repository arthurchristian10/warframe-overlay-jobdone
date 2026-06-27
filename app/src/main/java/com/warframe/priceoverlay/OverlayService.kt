package com.warframe.priceoverlay

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
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
import android.widget.Button
import android.widget.FrameLayout
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
import kotlin.math.roundToInt

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private lateinit var itemDatabase: ItemDatabase
    private lateinit var api: WarframeMarketApi
    private lateinit var prefs: SharedPreferences
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var screenWidth   = 0
    private var screenHeight  = 0
    private var screenDensity = 0

    private var scanningActive    = false
    private var scanLoopJob: Job?  = null
    private var lookupModeActive  = false
    private var lookupLoopJob: Job? = null
    private var statusBarHeight   = 0

    // Optional crop region — null means full screen
    private var cropRect: Rect? = null
    private var cropSelectorView: View? = null

    // ── Debug OCR panel ──────────────────────────────────────────────────────────
    private var debugPanelVisible = false
    private var debugPanelView: View? = null
    private lateinit var tvDebugOcr: TextView
    private lateinit var btnDebugToggle: ImageView

    private val SCAN_INTERVAL_MS     = 1000L
    private val LOOKUP_SCAN_INTERVAL = 800L
    private val CONFIRM_VOTES        = 1
    private val DECAY_PER_CYCLE      = 1
    private val MAX_RETRY_ATTEMPTS   = 3
    private val RELIC_REWARD_COUNT   = 4
    private val apiSemaphore         = Semaphore(3)

    private val BLOCK_MERGE_GAP_PX    = 60
    private val BLOCK_MERGE_OVERLAP_F = 0.3f

    private val PREFS_NAME    = "wf_overlay_prefs"
    private val KEY_CROP_LEFT = "crop_left"
    private val KEY_CROP_TOP  = "crop_top"
    private val KEY_CROP_RIGHT  = "crop_right"
    private val KEY_CROP_BOTTOM = "crop_bottom"

    private data class VoteEntry(val votes: Int, val lastSeenCycle: Long)
    private val voteBank    = mutableMapOf<String, VoteEntry>()
    private val voteEntries = mutableMapOf<String, ItemEntry>()
    private val itemBounds  = mutableMapOf<String, Rect>()

    private enum class RowState { LOADING_API, PRICED, NO_SELLERS, ERROR }
    private val rowState      = mutableMapOf<String, RowState>()
    private val displayedRows = mutableMapOf<String, TextView>()
    private val fetchJobs     = mutableMapOf<String, Job>()

    private val relicPopups     = mutableMapOf<String, View>()
    private val relicPopupJobs  = mutableMapOf<String, Job>()
    private val relicPopupSlugs = mutableListOf<String>()

    private var scanCycle = 0L
    private val VOTE_GRACE_CYCLES = 3

    private lateinit var btnToggle: ImageView
    private lateinit var tvToggleLabel: TextView
    private lateinit var btnLookup: ImageView
    private lateinit var tvLookupLabel: TextView
    private lateinit var btnCropSettings: ImageView
    private lateinit var tvLastScan: TextView
    private lateinit var tvMedianLabel: TextView
    private lateinit var divider: View
    private lateinit var scrollResults: ScrollView
    private lateinit var resultsContainer: LinearLayout

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        itemDatabase = ItemDatabase(this); itemDatabase.load()
        api = WarframeMarketApi()
        loadCropRect()
        setupOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra("EXTRA_RESULT_CODE", Int.MIN_VALUE) ?: Int.MIN_VALUE
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            intent?.getParcelableExtra("EXTRA_RESULT_DATA", Intent::class.java)
        else @Suppress("DEPRECATION") intent?.getParcelableExtra("EXTRA_RESULT_DATA")

        val channelId = "OverlayServiceChannel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(channelId, "ArtFrame Overlay", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("ArtFrame").setContentText("Scanning for Warframe items…")
            .setSmallIcon(R.drawable.ic_launcher).build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(1, notification)

        if (resultCode == Activity.RESULT_OK && resultData != null && mediaProjection == null) {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mpm.getMediaProjection(resultCode, resultData)
            setupVirtualDisplay(); startScanning()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopScanning()
        lookupLoopJob?.cancel(); dismissAllRelicPopups()
        recognizer.close()
        dismissCropSelector()
        dismissDebugPanel()
        virtualDisplay?.release(); imageReader?.close(); mediaProjection?.stop()
        if (::overlayView.isInitialized) windowManager.removeView(overlayView)
    }

    private fun loadCropRect() {
        val l = prefs.getInt(KEY_CROP_LEFT, -1)
        if (l >= 0) {
            cropRect = Rect(
                l,
                prefs.getInt(KEY_CROP_TOP, 0),
                prefs.getInt(KEY_CROP_RIGHT, 0),
                prefs.getInt(KEY_CROP_BOTTOM, 0)
            )
            Log.d("WFOverlay", "Loaded cropRect=$cropRect")
        }
    }

    private fun saveCropRect(r: Rect) {
        cropRect = r
        prefs.edit()
            .putInt(KEY_CROP_LEFT, r.left)
            .putInt(KEY_CROP_TOP, r.top)
            .putInt(KEY_CROP_RIGHT, r.right)
            .putInt(KEY_CROP_BOTTOM, r.bottom)
            .apply()
        Log.d("WFOverlay", "Saved cropRect=$r")
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun openCropSelector() {
        if (cropSelectorView != null) { dismissCropSelector(); return }

        val currentW = screenWidth
        val currentH = screenHeight

        if (currentW == 0 || currentH == 0) {
            Toast.makeText(this, "Screen capture not ready yet. Start scanning first.", Toast.LENGTH_SHORT).show()
            return
        }

        val container = FrameLayout(this)
        val selector = CropSelectorView(this, cropRect, currentW, currentH)

        val btnConfirm = Button(this).apply {
            text = "Confirm crop region"
            setBackgroundColor(Color.parseColor("#CC4488FF"))
            setTextColor(Color.WHITE)
            typeface = Typeface.MONOSPACE
            setPadding(32, 16, 32, 16)
        }
        val btnParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ).also { it.bottomMargin = 80 }

        container.addView(selector, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
        container.addView(btnConfirm, btnParams)

        btnConfirm.setOnClickListener {
            saveCropRect(selector.rect)
            Toast.makeText(this, "Crop region saved (${selector.rect.width()}×${selector.rect.height()})", Toast.LENGTH_SHORT).show()
            dismissCropSelector()
        }

        val wlp = WindowManager.LayoutParams(
            currentW, currentH,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).also { it.gravity = Gravity.TOP or Gravity.START; it.x = 0; it.y = 0 }

        windowManager.addView(container, wlp)
        cropSelectorView = container
    }

    private fun dismissCropSelector() {
        cropSelectorView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        cropSelectorView = null
    }

    private fun toggleDebugPanel() {
        if (debugPanelVisible) dismissDebugPanel() else showDebugPanel()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showDebugPanel() {
        if (debugPanelView != null) return
        val density = resources.displayMetrics.density

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#EE0A0A1A"))
            setPadding(12, 10, 12, 10)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val tvTitle = TextView(this).apply {
            text = "OCR DEBUG"
            textSize = 10f
            setTextColor(Color.parseColor("#FFAAAAFF"))
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val btnClose = TextView(this).apply {
            text = " ✕ "
            textSize = 11f
            setTextColor(Color.parseColor("#FFFF6666"))
            typeface = Typeface.MONOSPACE
            setOnClickListener { dismissDebugPanel() }
        }

        header.addView(tvTitle)
        header.addView(btnClose)

        val divLine = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            ).also { it.topMargin = 6; it.bottomMargin = 6 }
            setBackgroundColor(Color.parseColor("#33FFFFFF"))
        }

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                (220 * density).toInt()
            )
        }

        tvDebugOcr = TextView(this).apply {
            text = "Waiting for scan…"
            textSize = 9f
            setTextColor(Color.parseColor("#FFCCCCDD"))
            typeface = Typeface.MONOSPACE
            setPadding(0, 0, 0, 0)
        }

        scroll.addView(tvDebugOcr)
        container.addView(header)
        container.addView(divLine)
        container.addView(scroll)

        val wlp = WindowManager.LayoutParams(
            (300 * density).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).also { it.gravity = Gravity.TOP or Gravity.END; it.x = 10; it.y = 120 }

        var iX = 0; var iY = 0; var iTX = 0f; var iTY = 0f
        container.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { iX = wlp.x; iY = wlp.y; iTX = e.rawX; iTY = e.rawY; true }
                MotionEvent.ACTION_MOVE -> {
                    wlp.x = iX + (e.rawX - iTX).toInt()
                    wlp.y = iY + (e.rawY - iTY).toInt()
                    windowManager.updateViewLayout(container, wlp); true
                }
                else -> false
            }
        }

        windowManager.addView(container, wlp)
        debugPanelView = container
        debugPanelVisible = true
        btnDebugToggle.setColorFilter(Color.parseColor("#FFAAAAFF"))
    }

    private fun dismissDebugPanel() {
        debugPanelView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        debugPanelView = null
        debugPanelVisible = false
        if (::btnDebugToggle.isInitialized) btnDebugToggle.clearColorFilter()
    }

    private fun postDebugText(text: String) {
        if (!debugPanelVisible || !::tvDebugOcr.isInitialized) return
        CoroutineScope(Dispatchers.Main).launch { tvDebugOcr.text = text }
    }

    private fun showRelicPopupsForCurrentItems() {
        voteBank.keys
            .filter { (voteBank[it]?.votes ?: 0) >= CONFIRM_VOTES && voteEntries.containsKey(it) }
            .forEach { slug ->
                val entry  = voteEntries[slug] ?: return@forEach
                val bounds = itemBounds[slug]  ?: return@forEach
                if (!relicPopups.containsKey(slug)) createRelicPopup(slug, entry.name, bounds)
            }
    }

    @SuppressLint("InflateParams")
    private fun createRelicPopup(slug: String, name: String, bounds: Rect) {
        if (relicPopups.containsKey(slug)) return
        val density  = resources.displayMetrics.density
        val gapPx    = (4 * density).roundToInt()
        val colWidth = screenWidth / RELIC_REWARD_COUNT

        val centreX      = (bounds.left + bounds.right) / 2
        val slot         = (centreX / colWidth).coerceIn(0, RELIC_REWARD_COUNT - 1)
        val popupWidthPx = colWidth - gapPx * 2
        val popupX       = slot * colWidth + gapPx
        val popupY       = (bounds.bottom - statusBarHeight + gapPx).coerceAtLeast(0)

        Log.d("WFOverlay", "Popup slot=$slot x=$popupX y=$popupY bounds=$bounds sb=$statusBarHeight")

        val popupView = LayoutInflater.from(this).inflate(R.layout.item_popup_layout, null)
        val tvName   = popupView.findViewById<TextView>(R.id.popup_item_name)
        val tvPlat   = popupView.findViewById<TextView>(R.id.popup_plat_price)
        val tvDucat  = popupView.findViewById<TextView>(R.id.popup_ducat_value)
        val tvRatio  = popupView.findViewById<TextView>(R.id.popup_ratio)
        val tvStatus = popupView.findViewById<TextView>(R.id.popup_status)

        tvName.text = name; tvPlat.text = "—"; tvDucat.text = "—"; tvRatio.text = "—"
        tvStatus.text = "loading…"; tvStatus.visibility = View.VISIBLE

        val wlp = WindowManager.LayoutParams(
            popupWidthPx, WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).also { it.gravity = Gravity.TOP or Gravity.START; it.x = popupX; it.y = popupY }

        windowManager.addView(popupView, wlp)
        relicPopups[slug] = popupView
        if (!relicPopupSlugs.contains(slug)) relicPopupSlugs.add(slug)
        popupView.setOnClickListener { dismissRelicPopup(slug) }

        relicPopupJobs[slug]?.cancel()
        relicPopupJobs[slug] = CoroutineScope(Dispatchers.Main).launch {
            val platResult = withContext(Dispatchers.IO) {
                try { api.fetchStats(slug) } catch (e: Exception) { null }
            }
            if (!relicPopups.containsKey(slug)) return@launch
            val plat: Int? = if (platResult is ApiResult.Success) platResult.price else null
            tvPlat.text = if (plat != null) "${plat}p" else "no data"
            if (plat == null) tvPlat.setTextColor(Color.parseColor("#FFFF8080"))
            tvStatus.text = "loading ducats…"

            val detail = withContext(Dispatchers.IO) {
                try { api.fetchItemDetail(slug) } catch (e: Exception) { null }
            }
            if (!relicPopups.containsKey(slug)) return@launch
            tvStatus.visibility = View.GONE
            val ducat     = detail?.ducats
            val finalPlat = detail?.platPrice48h ?: plat
            tvDucat.text = if (ducat != null) "${ducat}d" else "—"
            if (ducat == null) tvDucat.setTextColor(Color.parseColor("#FF888899"))
            if (finalPlat != null && finalPlat != plat) tvPlat.text = "${finalPlat}p"
            if (finalPlat != null && finalPlat > 0 && ducat != null && ducat > 0) {
                val ratio = ducat.toFloat() / finalPlat.toFloat()
                tvRatio.text = String.format("%.2f d/p", ratio)
                tvRatio.setTextColor(Color.parseColor(when {
                    ratio >= 1.0f -> "#FF80FF80"
                    ratio >= 0.5f -> "#FFFFCC44"
                    else          -> "#FFFF8080"
                }))
            } else { tvRatio.text = "—" }
        }
    }

    private fun dismissRelicPopup(slug: String) {
        relicPopupJobs.remove(slug)?.cancel()
        relicPopups.remove(slug)?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        relicPopupSlugs.remove(slug)
    }

    private fun dismissAllRelicPopups() {
        relicPopupSlugs.toList().forEach { dismissRelicPopup(it) }
        relicPopupSlugs.clear()
    }

    private fun setRow(slug: String, name: String, state: RowState, price: String? = null) {
        rowState[slug] = state
        val (line2, colorHex) = when (state) {
            RowState.LOADING_API -> "  loading..."   to "#FFAAAAAA"
            RowState.PRICED      -> "  ${price}p"    to "#FF80FF80"
            RowState.NO_SELLERS  -> "  no data"      to "#FFFF8080"
            RowState.ERROR       -> "  error"        to "#FFFFAA44"
        }
        val label = "$name\n$line2"
        val existing = displayedRows[slug]
        if (existing != null) {
            existing.text = label; existing.setTextColor(Color.parseColor(colorHex))
        } else {
            val tv = makeRow(label, colorHex); displayedRows[slug] = tv
            resultsContainer.addView(tv)
            divider.visibility = View.VISIBLE; scrollResults.visibility = View.VISIBLE
        }
    }

    private fun removeRow(slug: String) {
        fetchJobs.remove(slug)?.cancel()
        rowState.remove(slug); itemBounds.remove(slug)
        displayedRows.remove(slug)?.let { resultsContainer.removeView(it) }
        dismissRelicPopup(slug)
        if (displayedRows.isEmpty()) {
            divider.visibility = View.GONE; scrollResults.visibility = View.GONE
        }
    }

    private fun makeRow(text: String, colorHex: String) = TextView(this).apply {
        this.text = text; textSize = 10f
        setTextColor(Color.parseColor(colorHex)); setPadding(4, 3, 4, 3)
        typeface = Typeface.MONOSPACE
        maxLines = 2
        isSingleLine = false
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { it.bottomMargin = 2 }
    }

    private fun launchFetch(slug: String, entry: ItemEntry) {
        fetchJobs[slug]?.cancel()
        fetchJobs[slug] = CoroutineScope(Dispatchers.Main).launch {
            var attempt = 0
            while (attempt < MAX_RETRY_ATTEMPTS && isActive) {
                attempt++
                val result = apiSemaphore.withPermit { withContext(Dispatchers.IO) { api.fetchStats(slug) } }
                if (!rowState.containsKey(slug)) break
                when (result) {
                    is ApiResult.Success     -> { setRow(slug, entry.name, RowState.PRICED, result.price.toString()); break }
                    is ApiResult.NotFound    -> { setRow(slug, entry.name, RowState.NO_SELLERS); break }
                    is ApiResult.RateLimited -> { delay(result.retryAfterMs.coerceAtLeast(1000L)) }
                    is ApiResult.Failure     -> {
                        if (attempt >= MAX_RETRY_ATTEMPTS) setRow(slug, entry.name, RowState.ERROR)
                        else delay((500L * attempt).coerceAtMost(3000L))
                    }
                }
            }
        }
    }

    private fun toggleScanning() { if (scanningActive) stopScanning() else startScanning() }

    private fun startScanning() {
        if (mediaProjection == null || imageReader == null) {
            Toast.makeText(this, "Screen capture not ready. Re-launch.", Toast.LENGTH_LONG).show(); return
        }
        scanningActive = true; setToggleUI(on = true)
        scanLoopJob = CoroutineScope(Dispatchers.Default).launch {
            while (isActive) { runSingleScan(); delay(SCAN_INTERVAL_MS) }
        }
    }

    private fun stopScanning() {
        scanningActive = false
        scanLoopJob?.cancel(); scanLoopJob = null
        fetchJobs.values.forEach { it.cancel() }; fetchJobs.clear()
        if (!lookupModeActive) { voteBank.clear(); voteEntries.clear(); itemBounds.clear() }
        rowState.clear(); displayedRows.clear()
        setToggleUI(on = false)
        divider.visibility = View.GONE; scrollResults.visibility = View.GONE
        resultsContainer.removeAllViews()
    }

    private fun setToggleUI(on: Boolean) {
        if (on) {
            btnToggle.setColorFilter(Color.parseColor("#FF00E676"))
            tvToggleLabel.text = "● SCANNING"; tvToggleLabel.setTextColor(Color.parseColor("#FF00E676"))
            tvLastScan.visibility = View.VISIBLE; tvMedianLabel.visibility = View.VISIBLE
        } else {
            btnToggle.clearColorFilter()
            tvToggleLabel.text = "○ OFF"; tvToggleLabel.setTextColor(Color.parseColor("#FF888888"))
            tvLastScan.visibility = View.GONE; tvLastScan.text = ""; tvMedianLabel.visibility = View.GONE
        }
    }

    private fun toggleLookupMode() {
        lookupModeActive = !lookupModeActive
        if (lookupModeActive) {
            btnLookup.setColorFilter(Color.parseColor("#FF4488FF"))
            tvLookupLabel.text = "● CRACKING"; tvLookupLabel.setTextColor(Color.parseColor("#FF4488FF"))
            startLookupLoop()
        } else {
            btnLookup.clearColorFilter()
            tvLookupLabel.text = "○ CRACKING"; tvLookupLabel.setTextColor(Color.parseColor("#FF4488FF"))
            stopLookupLoop()
        }
    }

    private fun startLookupLoop() {
        if (mediaProjection == null || imageReader == null) {
            Toast.makeText(this, "Screen capture not ready. Re-launch.", Toast.LENGTH_LONG).show()
            lookupModeActive = false; btnLookup.clearColorFilter()
            tvLookupLabel.text = "○ CRACKING"; return
        }
        lookupLoopJob?.cancel()
        lookupLoopJob = CoroutineScope(Dispatchers.Default).launch {
            while (isActive) {
                if (relicPopups.size >= RELIC_REWARD_COUNT) {
                    withContext(Dispatchers.Main) { tvLookupLabel.text = "✓ CRACKING" }
                    break
                }
                runSingleScan()
                delay(LOOKUP_SCAN_INTERVAL)
            }
        }
    }

    private fun stopLookupLoop() {
        lookupLoopJob?.cancel(); lookupLoopJob = null
        dismissAllRelicPopups()
        voteBank.clear(); voteEntries.clear(); itemBounds.clear()
    }

    private fun setupVirtualDisplay() {
        val m = resources.displayMetrics
        val rawW = m.widthPixels; val rawH = m.heightPixels
        screenWidth   = maxOf(rawW, rawH)
        screenHeight  = minOf(rawW, rawH)
        screenDensity = m.densityDpi
        val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
        statusBarHeight = if (resId > 0) resources.getDimensionPixelSize(resId) else 0
        Log.d("WFOverlay", "screen=${screenWidth}x${screenHeight} (landscape) statusBar=$statusBarHeight")

        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    stopScanning(); lookupLoopJob?.cancel()
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
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupOverlay() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_layout, null)
        btnToggle        = overlayView.findViewById(R.id.btn_toggle)
        tvToggleLabel    = overlayView.findViewById(R.id.tv_toggle_label)
        btnLookup        = overlayView.findViewById(R.id.btn_lookup)
        tvLookupLabel    = overlayView.findViewById(R.id.tv_lookup_label)
        btnCropSettings  = overlayView.findViewById(R.id.btn_crop_settings)
        btnDebugToggle   = overlayView.findViewById(R.id.btn_debug_toggle)
        tvLastScan       = overlayView.findViewById(R.id.tv_last_scan)
        tvMedianLabel    = overlayView.findViewById(R.id.tv_median_label)
        divider          = overlayView.findViewById(R.id.divider)
        scrollResults    = overlayView.findViewById(R.id.scroll_results)
        resultsContainer = overlayView.findViewById(R.id.results_container)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).also { it.gravity = Gravity.TOP or Gravity.START; it.x = 20; it.y = 120 }
        windowManager.addView(overlayView, params)

        var iX = 0; var iY = 0; var iTX = 0f; var iTY = 0f
        overlayView.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { iX = params.x; iY = params.y; iTX = e.rawX; iTY = e.rawY; true }
                MotionEvent.ACTION_MOVE -> {
                    params.x = iX + (e.rawX - iTX).toInt(); params.y = iY + (e.rawY - iTY).toInt()
                    windowManager.updateViewLayout(overlayView, params); true
                }
                MotionEvent.ACTION_UP -> {
                    if (Math.abs(e.rawX - iTX) < 12 && Math.abs(e.rawY - iTY) < 12) toggleScanning()
                    true
                }
                else -> false
            }
        }
        btnLookup.setOnClickListener { toggleLookupMode() }
        btnCropSettings.setOnClickListener { openCropSelector() }
        btnDebugToggle.setOnClickListener { toggleDebugPanel() }
    }

    private fun scaleFactorFor(w: Int) = when {
        w < 1080 -> 2.0f
        w < 1440 -> 2.0f
        w < 2160 -> 1.5f
        else     -> 1.0f
    }

    private fun preprocessGrayContrast(bmp: Bitmap): Bitmap {
        val gray = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
        Canvas(gray).drawBitmap(bmp, 0f, 0f, Paint().apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix().also { it.setSaturation(0f) })
        })
        val out = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(gray, 0f, 0f, Paint().apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
                2.2f,0f,0f,0f,-60f, 0f,2.2f,0f,0f,-60f, 0f,0f,2.2f,0f,-60f, 0f,0f,0f,1f,0f
            )))
        })
        gray.recycle(); return out
    }

    private fun preprocessBinarized(bmp: Bitmap): Bitmap {
        val gray = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
        Canvas(gray).drawBitmap(bmp, 0f, 0f, Paint().apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix().also { it.setSaturation(0f) })
        })
        val out = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(gray, 0f, 0f, Paint().apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
                5f,0f,0f,0f,-400f, 0f,5f,0f,0f,-400f, 0f,0f,5f,0f,-400f, 0f,0f,0f,1f,0f
            )))
        })
        gray.recycle(); return out
    }

    private fun preprocessSharpened(bmp: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(bmp, 0f, 0f, Paint().apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
                1.5f,0f,0f,0f,-20f, 0f,1.5f,0f,0f,-20f, 0f,0f,1.5f,0f,-20f, 0f,0f,0f,1f,0f
            )))
        })
        return out
    }

    private fun captureFrame(): Bitmap? {
        return try {
            val image = imageReader?.acquireLatestImage() ?: return null
            try {
                val p = image.planes[0]
                val rowPadding = p.rowStride - p.pixelStride * screenWidth
                val bmp = Bitmap.createBitmap(
                    screenWidth + rowPadding / p.pixelStride, screenHeight, Bitmap.Config.ARGB_8888
                )
                bmp.copyPixelsFromBuffer(p.buffer)
                val full = Bitmap.createBitmap(bmp, 0, 0, screenWidth, screenHeight)
                bmp.recycle()
                val cr = cropRect
                if (cr != null && !cr.isEmpty) {
                    val cropped = Bitmap.createBitmap(
                        full,
                        cr.left.coerceIn(0, screenWidth - 1),
                        cr.top.coerceIn(0, screenHeight - 1),
                        cr.width().coerceAtLeast(1).coerceAtMost(screenWidth - cr.left),
                        cr.height().coerceAtLeast(1).coerceAtMost(screenHeight - cr.top)
                    )
                    full.recycle(); cropped
                } else full
            } finally { image.close() }
        } catch (e: Exception) { null }
    }

    private suspend fun runOcr(bitmap: Bitmap) = suspendCancellableCoroutine { cont ->
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { cont.resume(it) }
            .addOnFailureListener { cont.resumeWithException(it) }
    }

    private data class MergedBlock(
        val text: String,
        val rect: Rect,
        val sourceIndices: Set<Int>
    )

    private fun buildMergedBlocks(
        vt: com.google.mlkit.vision.text.Text,
        sf: Float
    ): List<MergedBlock> {
        val cropOffsetX = cropRect?.left ?: 0
        val cropOffsetY = cropRect?.top  ?: 0

        val originals = vt.textBlocks.mapNotNull { block ->
            val bb = block.boundingBox ?: return@mapNotNull null
            val lines = block.lines.map { it.text.trim() }.filter { it.isNotBlank() }
            if (lines.isEmpty()) return@mapNotNull null
            MergedBlock(
                text = lines.joinToString(" "),
                rect = Rect(
                    (bb.left   / sf).roundToInt() + cropOffsetX,
                    (bb.top    / sf).roundToInt() + cropOffsetY,
                    (bb.right  / sf).roundToInt() + cropOffsetX,
                    (bb.bottom / sf).roundToInt() + cropOffsetY
                ),
                sourceIndices = setOf(vt.textBlocks.indexOf(block))
            )
        }.sortedBy { it.rect.top }

        if (originals.size < 2) return originals

        val result = originals.toMutableList()

        fun canMerge(a: MergedBlock, b: MergedBlock): Boolean {
            val gap = b.rect.top - a.rect.bottom
            if (gap > BLOCK_MERGE_GAP_PX) return false

            val overlapLeft  = maxOf(a.rect.left,  b.rect.left)
            val overlapRight = minOf(a.rect.right, b.rect.right)
            val overlapW     = overlapRight - overlapLeft
            val smallerW     = minOf(a.rect.width(), b.rect.width())
            if (smallerW <= 0) return false
            return overlapW.toFloat() / smallerW >= BLOCK_MERGE_OVERLAP_F
        }

        val MAX_MERGE_DEPTH = 4

        fun dfs(current: MergedBlock, startIdx: Int, depth: Int) {
            if (depth >= MAX_MERGE_DEPTH) return
            for (j in startIdx until originals.size) {
                val candidate = originals[j]
                if (candidate.sourceIndices.any { it in current.sourceIndices }) continue
                if (!canMerge(current, candidate)) continue

                val mergedRect = Rect(
                    minOf(current.rect.left,   candidate.rect.left),
                    minOf(current.rect.top,    candidate.rect.top),
                    maxOf(current.rect.right,  candidate.rect.right),
                    maxOf(current.rect.bottom, candidate.rect.bottom)
                )
                val mergedBlock = MergedBlock(
                    text = "${current.text} ${candidate.text}",
                    rect = mergedRect,
                    sourceIndices = current.sourceIndices + candidate.sourceIndices
                )
                result.add(mergedBlock)

                Log.d("WFOverlay_Merge",
                    "Merged depth=${depth+1}: '${current.text.take(30)}' + '${candidate.text.take(30)}' " +
                    "→ '${mergedBlock.text.take(50)}'")

                dfs(mergedBlock, j + 1, depth + 1)
            }
        }

        for (i in originals.indices) {
            dfs(originals[i], i + 1, 1)
        }

        val seen = mutableSetOf<Set<Int>>()
        return result.filter { seen.add(it.sourceIndices) }
    }

    private fun matchBlocks(
        blocks: List<MergedBlock>,
        seen: MutableSet<String>,
        debugLines: MutableList<String>
    ) {
        for (block in blocks) {
            val words = block.text.split(Regex("\\s+")).filter { it.length >= 2 }
            if (words.isEmpty()) continue

            var blockMatched = false

            outer@ for (ws in minOf(7, words.size) downTo 2) {
                for (start in 0..words.size - ws) {
                    val query = words.subList(start, start + ws).joinToString(" ")
                    val match = itemDatabase.searchItemDetailed(query) ?: continue
                    if (seen.add(match.entry.slug)) {
                        if (lookupModeActive && match.entry.name.contains("relic", ignoreCase = true)) {
                            seen.remove(match.entry.slug); continue
                        }
                        voteEntries[match.entry.slug] = match.entry
                        itemBounds[match.entry.slug]  = block.rect
                        blockMatched = true

                        if (debugPanelVisible) {
                            debugLines.add(
                                "✓ [${String.format("%.2f", match.confidence)}] \"$query\"\n" +
                                "  → ${match.entry.name}"
                            )
                        }
                        break@outer
                    }
                }
            }

            if (!blockMatched && debugPanelVisible) {
                val preview = block.text.take(50).let { if (block.text.length > 50) "$it…" else it }
                debugLines.add("✗ \"$preview\"")
            }
        }
    }

    private suspend fun runSingleScan() {
        scanCycle++
        val raw = captureFrame() ?: return
        val sf  = scaleFactorFor(raw.width)
        val scaled = Bitmap.createScaledBitmap(raw, (raw.width * sf).toInt(), (raw.height * sf).toInt(), true)
        raw.recycle()

        val varA = preprocessGrayContrast(scaled)
        val varB = preprocessBinarized(scaled)
        val varC = preprocessSharpened(scaled)
        scaled.recycle()

        val scope = CoroutineScope(Dispatchers.Default)
        val dA = scope.async { try { Pair(runOcr(varA), sf) } catch (e: Exception) { null } finally { varA.recycle() } }
        val dB = scope.async { try { Pair(runOcr(varB), sf) } catch (e: Exception) { null } finally { varB.recycle() } }
        val dC = scope.async { try { Pair(runOcr(varC), sf) } catch (e: Exception) { null } finally { varC.recycle() } }
        val results = listOf(dA, dB, dC).awaitAll().filterNotNull()

        val seenThisCycle = mutableSetOf<String>()
        val debugLines    = mutableListOf<String>()

        for ((vt, s) in results) {
            val blocks = buildMergedBlocks(vt, s)
            matchBlocks(blocks, seenThisCycle, debugLines)
        }

        if (debugPanelVisible) {
            val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
            val header = "── $time ──────────────\n"
            postDebugText(header + debugLines.distinct().joinToString("\n\n"))
        }

        val allTracked = (voteBank.keys + seenThisCycle).toSet()
        for (slug in allTracked) {
            val prev = voteBank[slug]
            val wasSeen = slug in seenThisCycle

            if (wasSeen) {
                val newVotes = (prev?.votes ?: 0) + 1
                voteBank[slug] = VoteEntry(
                    votes = newVotes.coerceAtMost(6),
                    lastSeenCycle = scanCycle
                )
            } else {
                val lastSeen = prev?.lastSeenCycle ?: scanCycle
                val cyclesSinceSeen = scanCycle - lastSeen
                if (cyclesSinceSeen > VOTE_GRACE_CYCLES) {
                    val newVotes = (prev?.votes ?: 1) - DECAY_PER_CYCLE
                    if (newVotes <= 0) {
                        voteBank.remove(slug)
                    } else {
                        voteBank[slug] = VoteEntry(newVotes, lastSeen)
                    }
                }
            }
        }

        withContext(Dispatchers.Main) {
            rowState.keys.filter { it !in voteBank }.forEach { removeRow(it) }
            for ((slug, entry) in voteBank) {
                if (entry.votes < CONFIRM_VOTES) continue
                val itemEntry = voteEntries[slug] ?: continue
                if (!rowState.containsKey(slug)) {
                    setRow(slug, itemEntry.name, RowState.LOADING_API); launchFetch(slug, itemEntry)
                }
                if (lookupModeActive && itemBounds.containsKey(slug) && !relicPopups.containsKey(slug))
                    createRelicPopup(slug, itemEntry.name, itemBounds[slug]!!)
            }
            relicPopupSlugs.toList().filter { it !in voteBank }.forEach { dismissRelicPopup(it) }
            val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
            tvLastScan.text = "  last scan $time"
        }
    }
}