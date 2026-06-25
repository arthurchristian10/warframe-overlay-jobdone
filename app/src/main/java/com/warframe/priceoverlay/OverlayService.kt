package com.warframe.priceoverlay

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
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
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private lateinit var itemDatabase: ItemDatabase
    private lateinit var api: WarframeMarketApi

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var screenWidth: Int = 0
    private var screenHeight: Int = 0
    private var screenDensity: Int = 0

    // Toggle state
    private var scanningActive = false
    private var scanLoopJob: Job? = null

    // How many seconds between scans
    private val SCAN_INTERVAL_MS = 3000L

    // UI references
    private lateinit var btnToggle: ImageView
    private lateinit var tvToggleLabel: TextView
    private lateinit var tvLastScan: TextView
    private lateinit var scrollResults: ScrollView
    private lateinit var resultsContainer: LinearLayout

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        itemDatabase = ItemDatabase(this)
        itemDatabase.load()
        api = WarframeMarketApi()
        startForegroundService()
        setupOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            // IMPORTANT: Activity.RESULT_OK == -1 in Android.
            // Using -1 as default/sentinel would cause every real RESULT_OK to be rejected.
            // Use Int.MIN_VALUE as sentinel instead.
            val resultCode = intent.getIntExtra("EXTRA_RESULT_CODE", Int.MIN_VALUE)

            // getParcelableExtra(String) silently returns null on Android 13+ (API 33)
            val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra("EXTRA_RESULT_DATA", Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra("EXTRA_RESULT_DATA")
            }

            Log.d("OverlayService", "onStartCommand: resultCode=$resultCode resultData=$resultData")

            if (resultCode == Activity.RESULT_OK && resultData != null && mediaProjection == null) {
                val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjection = mpm.getMediaProjection(resultCode, resultData)
                setupVirtualDisplay()
                Log.d("OverlayService", "MediaProjection ready! imageReader=$imageReader")
            } else {
                Log.w("OverlayService", "MediaProjection NOT set up: resultCode=$resultCode resultData=$resultData mediaProjection=$mediaProjection")
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundService() {
        val channelId = "OverlayServiceChannel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Overlay Service", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Warframe Price Overlay")
            .setContentText("Tap the overlay to start scanning")
            .setSmallIcon(R.drawable.ic_launcher)
            .build()
        startForeground(1, notification)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupOverlay() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_layout, null)

        btnToggle = overlayView.findViewById(R.id.btn_toggle)
        tvToggleLabel = overlayView.findViewById(R.id.tv_toggle_label)
        tvLastScan = overlayView.findViewById(R.id.tv_last_scan)
        scrollResults = overlayView.findViewById(R.id.scroll_results)
        resultsContainer = overlayView.findViewById(R.id.results_container)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            // FLAG_NOT_FOCUSABLE: game keeps focus (game input still works)
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 20
        params.y = 120

        windowManager.addView(overlayView, params)

        // Drag + tap logic on the whole overlay
        var initialX = 0; var initialY = 0
        var initialTouchX = 0f; var initialTouchY = 0f

        overlayView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x; initialY = params.y
                    initialTouchX = event.rawX; initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(overlayView, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val dx = Math.abs(event.rawX - initialTouchX)
                    val dy = Math.abs(event.rawY - initialTouchY)
                    if (dx < 12 && dy < 12) {
                        // It's a tap — toggle scanning
                        toggleScanning()
                    }
                    true
                }
                else -> false
            }
        }
    }

    // -------------------------------------------------------------------------
    // Toggle
    // -------------------------------------------------------------------------

    private fun toggleScanning() {
        if (scanningActive) {
            stopScanning()
        } else {
            startScanning()
        }
    }

    private fun startScanning() {
        if (mediaProjection == null || imageReader == null) {
            Toast.makeText(this, "Screen capture not ready. Re-launch the overlay from the app.", Toast.LENGTH_LONG).show()
            return
        }

        scanningActive = true
        setToggleUI(on = true)
        Log.d("OverlayService", "Scan loop started.")

        scanLoopJob = CoroutineScope(Dispatchers.Main).launch {
            while (isActive) {
                runSingleScan()
                delay(SCAN_INTERVAL_MS)
            }
        }
    }

    private fun stopScanning() {
        scanningActive = false
        scanLoopJob?.cancel()
        scanLoopJob = null
        setToggleUI(on = false)
        scrollResults.visibility = View.GONE
        resultsContainer.removeAllViews()
        Log.d("OverlayService", "Scan loop stopped.")
    }

    private fun setToggleUI(on: Boolean) {
        if (on) {
            btnToggle.setColorFilter(Color.parseColor("#FF00E676")) // green
            tvToggleLabel.text = "● SCANNING"
            tvToggleLabel.setTextColor(Color.parseColor("#FF00E676"))
            tvLastScan.visibility = View.VISIBLE
        } else {
            btnToggle.clearColorFilter()
            tvToggleLabel.text = "○ OFF"
            tvToggleLabel.setTextColor(Color.parseColor("#FF888888"))
            tvLastScan.visibility = View.GONE
            tvLastScan.text = ""
        }
    }

    // -------------------------------------------------------------------------
    // Single scan pass
    // -------------------------------------------------------------------------

    private suspend fun runSingleScan() {
        val image: Image? = withContext(Dispatchers.IO) {
            try { imageReader?.acquireLatestImage() } catch (e: Exception) {
                Log.e("OverlayService", "acquireLatestImage failed", e); null
            }
        }

        if (image == null) {
            Log.w("OverlayService", "No image from ImageReader yet.")
            return
        }

        val bitmap: Bitmap? = withContext(Dispatchers.IO) {
            try {
                val planes = image.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * screenWidth
                val bmp = Bitmap.createBitmap(
                    screenWidth + rowPadding / pixelStride,
                    screenHeight,
                    Bitmap.Config.ARGB_8888
                )
                bmp.copyPixelsFromBuffer(buffer)
                Bitmap.createBitmap(bmp, 0, 0, screenWidth, screenHeight)
            } catch (e: Exception) {
                Log.e("OverlayService", "Bitmap error", e); null
            } finally {
                image.close()
            }
        }

        if (bitmap == null) return

        // Run OCR via a suspendable wrapper
        val visionText = try {
            runOcr(bitmap)
        } catch (e: Exception) {
            Log.e("OverlayService", "OCR failed", e)
            return
        }

        Log.d("OverlayService", "OCR: ${visionText.textBlocks.size} blocks")

        // Collect unique item matches
        val matchedItems = linkedMapOf<String, ItemEntry>()
        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                val match = itemDatabase.searchItem(line.text)
                if (match != null && !matchedItems.containsKey(match.slug)) {
                    matchedItems[match.slug] = match
                }
            }
        }

        // Fetch all prices in parallel
        val results: List<Pair<String, String?>> = withContext(Dispatchers.IO) {
            matchedItems.values.map { entry ->
                async {
                    val price = api.getCheapestOrder(entry.slug)
                    Pair(entry.name, price?.toString())
                }
            }.awaitAll()
        }

        // Update the UI
        updateResults(results)

        // Update timestamp
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        tvLastScan.text = "updated $time"
    }

    private suspend fun runOcr(bitmap: Bitmap) = suspendCancellableCoroutine { cont ->
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(inputImage)
            .addOnSuccessListener { cont.resume(it) }
            .addOnFailureListener { cont.resumeWithException(it) }
    }

    // -------------------------------------------------------------------------
    // Results UI
    // -------------------------------------------------------------------------

    private fun updateResults(results: List<Pair<String, String?>>) {
        resultsContainer.removeAllViews()

        if (results.isEmpty()) {
            resultsContainer.addView(makeRow("— No items detected —", "#FF666677"))
        } else {
            for ((name, price) in results) {
                val label = if (price != null) "💰 $name  ›  ${price}p" else "🚫 $name  ›  no sellers"
                val color = if (price != null) "#FF80FF80" else "#FFFF8080"
                resultsContainer.addView(makeRow(label, color))
            }
        }

        scrollResults.visibility = View.VISIBLE
    }

    private fun makeRow(text: String, colorHex: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 12f
            setTextColor(Color.parseColor(colorHex))
            setPadding(6, 5, 6, 5)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = 2 }
        }
    }

    // -------------------------------------------------------------------------

    private fun setupVirtualDisplay() {
        val metrics = resources.displayMetrics
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi

        // Android 14+ REQUIRES a callback to be registered before createVirtualDisplay()
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.d("OverlayService", "MediaProjection stopped by system")
                // Clean up on the main thread
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    stopScanning()
                    virtualDisplay?.release()
                    virtualDisplay = null
                    imageReader?.close()
                    imageReader = null
                    mediaProjection = null
                }
            }
        }, android.os.Handler(android.os.Looper.getMainLooper()))

        @SuppressLint("WrongConstant")
        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )
        Log.d("OverlayService", "VirtualDisplay ready: ${screenWidth}x${screenHeight}")
    }


    override fun onDestroy() {
        super.onDestroy()
        stopScanning()
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        if (::overlayView.isInitialized) windowManager.removeView(overlayView)
    }
}
