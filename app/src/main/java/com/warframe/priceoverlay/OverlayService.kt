package com.warframe.priceoverlay

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.Point
import android.graphics.Rect
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlin.time.Duration.Companion.milliseconds

class OverlayService : Service() {

    private lateinit var prefs: SharedPreferences
    private lateinit var itemDatabase: ItemDatabase
    private lateinit var api: WarframeMarketApi
    
    private var screenCapturer: ScreenCapturer? = null
    private lateinit var itemScanner: ItemScanner
    private lateinit var uiManager: OverlayUIManager
    private lateinit var relicManager: RelicPopupManager
    private lateinit var debugManager: DebugPanelManager
    private lateinit var cropManager: CropRegionManager

    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val apiSemaphore = Semaphore(3)
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var lookupModeActive = false
    private var lookupLoopJob: Job? = null

    private var cropRect: Rect? = null
    private var statusBarHeight = 0
    private var scanCycle = 0L

    private data class VoteEntry(val votes: Int, val lastSeenCycle: Long)
    private val voteBank = mutableMapOf<String, VoteEntry>()
    private val voteEntries = mutableMapOf<String, ItemEntry>()
    private val itemBounds = mutableMapOf<String, Rect>()

    override fun onBind(intent: Intent?): IBinder? = null

    private var databaseLoaded = false

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("wf_overlay_prefs", MODE_PRIVATE)
        loadCropRect()
        
        createNotificationChannel()
        
        itemDatabase = ItemDatabase(this)
        itemDatabase.load {
            databaseLoaded = true
            Log.d("OverlayService", "Database ready to use")
        }

        api = WarframeMarketApi()
        itemScanner = ItemScanner(itemDatabase, recognizer)
        relicManager = RelicPopupManager(this, api, apiSemaphore, itemDatabase)
        debugManager = DebugPanelManager(this)
        
        cropManager = CropRegionManager(this) { newRect ->
            saveCropRect(newRect)
        }
        
        uiManager = OverlayUIManager(
            service = this,
            onToggleLookup = { toggleLookupMode() },
            onOpenCropSelector = { openCropSelector() },
            onToggleDebug = { debugManager.toggle() }
        )

        @Suppress("InternalInsetResource", "DiscouragedApi")
        val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
        statusBarHeight = if (resId > 0) resources.getDimensionPixelSize(resId) else 0
    }

    private var mediaProjection: MediaProjection? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundNotification()

        val resultCode = intent?.getIntExtra("EXTRA_RESULT_CODE", Int.MIN_VALUE) ?: Int.MIN_VALUE
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            intent?.getParcelableExtra("EXTRA_RESULT_DATA", Intent::class.java)
        else @Suppress("DEPRECATION") intent?.getParcelableExtra("EXTRA_RESULT_DATA")

        if (resultCode == Activity.RESULT_OK && resultData != null && screenCapturer == null) {
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            try {
                mediaProjection = mpm.getMediaProjection(resultCode, resultData)
                setupCapturer(mediaProjection!!)
            } catch (e: SecurityException) {
                Toast.makeText(this, "Screen capture permission failed", Toast.LENGTH_SHORT).show()
            }
        }
        return START_NOT_STICKY
    }

    private fun resetCapturer() {
        Log.w("OverlayService", "Hard reset of ScreenCapturer")
        screenCapturer?.refresh()
    }

    private fun setupCapturer(mp: MediaProjection) {
        screenCapturer = ScreenCapturer(this, mp) {
            uiManager.ensureInsideScreen()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelId = "OverlayServiceChannel"
            val ch = NotificationChannel(channelId, "Warframe Overlay", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun startForegroundNotification() {
        val channelId = "OverlayServiceChannel"
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.app_name))
            .setSmallIcon(R.drawable.ic_launcher)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            }
            startForeground(1, notification, type)
        } else {
            startForeground(1, notification)
        }
    }

    private fun toggleLookupMode() {
        if (!databaseLoaded) {
            Toast.makeText(this, "Loading database...", Toast.LENGTH_SHORT).show()
            return
        }
        lookupModeActive = !lookupModeActive
        if (lookupModeActive) {
            uiManager.setLookupState(active = true)
            startLookupLoop()
        } else {
            uiManager.setLookupState(active = false)
            stopLookupLoop()
        }
    }

    private fun startLookupLoop() {
        lookupLoopJob?.cancel()
        lookupLoopJob = serviceScope.launch {
            while (isActive) {
                if (relicManager.
                    activePopupCount() >= 4) {
                    uiManager.setLookupState(active = true, complete = true)
                    break
                }
                runSingleScan()
                delay(800.milliseconds)
            }
        }
    }

    private fun stopLookupLoop() {
        lookupLoopJob?.cancel()
        relicManager.dismissAll()
        voteBank.clear()
        voteEntries.clear()
        itemBounds.clear()
    }

    private var lastSuccessfulScanTime = 0L

    private suspend fun runSingleScan() {
        val capturer = screenCapturer ?: return
        
        withContext(Dispatchers.Main) {
            uiManager.updateLastScanTime("Scanning...")
        }

        val bitmap = capturer.captureFrame(cropRect)
        if (bitmap == null) {
            val now = System.currentTimeMillis()
            if (lastSuccessfulScanTime != 0L && (now - lastSuccessfulScanTime) > 5000) {
                withContext(Dispatchers.Main) { uiManager.updateLastScanTime("Resetting...") }
                resetCapturer()
                lastSuccessfulScanTime = now
            } else if (lastSuccessfulScanTime != 0L && (now - lastSuccessfulScanTime) > 10000) {
                withContext(Dispatchers.Main) { uiManager.updateLastScanTime("Sync Lost") }
            }
            return
        }
        
        lastSuccessfulScanTime = System.currentTimeMillis()
        scanCycle++
        val offset = Point(cropRect?.left ?: 0, cropRect?.top ?: 0)
        
        try {
            val scannedItems = withTimeoutOrNull(2500.milliseconds) {
                itemScanner.scanBitmap(
                    bitmap = bitmap,
                    cropOffset = offset,
                    lookupMode = lookupModeActive,
                    onDebugLog = { debugManager.postText(it) },
                )
            } ?: emptyList()
            
            bitmap.recycle()

            val seenThisCycle = scannedItems.asSequence().map { it.entry.slug }.toSet()
            scannedItems.forEach { 
                voteEntries[it.entry.slug] = it.entry
                itemBounds[it.entry.slug] = it.bounds
            }

            updateVotes(seenThisCycle)

            withContext(Dispatchers.Main) {
                processResults()
                val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
                uiManager.updateLastScanTime(time)
            }
        } catch (e: Exception) {
            bitmap.recycle()
            Log.e("OverlayService", "Scan loop error", e)
        }
    }

    private fun updateVotes(seenThisCycle: Set<String>) {
        val allTracked = (voteBank.keys + seenThisCycle).toSet()
        for (slug in allTracked) {
            val prev = voteBank[slug]
            if (slug in seenThisCycle) {
                voteBank[slug] = VoteEntry((prev?.votes ?: 0) + 1, scanCycle)
            } else {
                val lastSeen = prev?.lastSeenCycle ?: scanCycle
                if ((scanCycle - lastSeen) > 3) {
                    val newVotes = (prev?.votes ?: 1) - 1
                    if (newVotes <= 0) {
                        voteBank.remove(slug) 
                        voteEntries.remove(slug)
                        itemBounds.remove(slug)
                    } else {
                        voteBank[slug] = VoteEntry(newVotes, lastSeen)
                    }
                }
            }
        }
    }

    private fun processResults() {
        val activeSlugs = voteBank.filter { it.value.votes >= 1 }.keys
        
        for (slug in activeSlugs) {
            val entry = voteEntries[slug] ?: continue
            val bounds = itemBounds[slug]
            bounds?.let {
                relicManager.createRelicPopup(slug, entry.name, it, screenCapturer?.screenWidth ?: 0, statusBarHeight)
            }
        }

        val currentlyInPopups = voteEntries.keys.filter { it !in activeSlugs }
        currentlyInPopups.forEach { slug ->
            relicManager.dismissRelicPopup(slug)
        }
    }

    private fun openCropSelector() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Overlay permission not active", Toast.LENGTH_SHORT).show()
            return
        }
        cropManager.show(cropRect)
    }

    private fun saveCropRect(r: Rect) {
        cropRect = r
        prefs.edit().apply {
            putInt("crop_left", r.left)
            putInt("crop_top", r.top)
            putInt("crop_right", r.right)
            putInt("crop_bottom", r.bottom)
            apply()
        }
    }

    private fun loadCropRect() {
        val l = prefs.getInt("crop_left", -1)
        if (l >= 0) {
            cropRect = Rect(l, prefs.getInt("crop_top", 0), prefs.getInt("crop_right", 0), prefs.getInt("crop_bottom", 0))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        stopLookupLoop()
        screenCapturer?.release()
        uiManager.release()
        relicManager.dismissAll()
        debugManager.dismiss()
        mediaProjection?.stop()
        recognizer.close()
    }
}
