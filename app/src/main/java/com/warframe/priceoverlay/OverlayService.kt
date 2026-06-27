package com.warframe.priceoverlay

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.Point
import android.graphics.Rect
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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

    private var scanningActive = false
    private var scanLoopJob: Job? = null
    private var lookupModeActive = false
    private var lookupLoopJob: Job? = null

    private var cropRect: Rect? = null
    private var statusBarHeight = 0
    private var scanCycle = 0L

    private data class VoteEntry(val votes: Int, val lastSeenCycle: Long)
    private val voteBank = mutableMapOf<String, VoteEntry>()
    private val voteEntries = mutableMapOf<String, ItemEntry>()
    private val itemBounds = mutableMapOf<String, Rect>()
    private val fetchJobs = mutableMapOf<String, Job>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("wf_overlay_prefs", MODE_PRIVATE)
        loadCropRect()
        
        itemDatabase = ItemDatabase(this).apply { load() }
        api = WarframeMarketApi()
        itemScanner = ItemScanner(itemDatabase, recognizer)
        relicManager = RelicPopupManager(this, api, apiSemaphore)
        debugManager = DebugPanelManager(this)
        
        cropManager = CropRegionManager(this) { newRect ->
            saveCropRect(newRect)
        }
        
        uiManager = OverlayUIManager(
            service = this,
            onToggleScan = { /* toggleScanning() // Main scan deactivated */ },
            onToggleLookup = { toggleLookupMode() },
            onOpenCropSelector = { openCropSelector() },
            onToggleDebug = { debugManager.toggle() }
        )

        @Suppress("InternalInsetResource", "DiscouragedApi")
        val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
        statusBarHeight = if (resId > 0) resources.getDimensionPixelSize(resId) else 0
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra("EXTRA_RESULT_CODE", Int.MIN_VALUE) ?: Int.MIN_VALUE
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            intent?.getParcelableExtra("EXTRA_RESULT_DATA", Intent::class.java)
        else @Suppress("DEPRECATION") intent?.getParcelableExtra("EXTRA_RESULT_DATA")

        startForegroundNotification()

        if (resultCode == Activity.RESULT_OK && resultData != null && screenCapturer == null) {
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val mp = mpm.getMediaProjection(resultCode, resultData)
            setupCapturer(mp)
        }
        return START_NOT_STICKY
    }

    private fun setupCapturer(mp: MediaProjection) {
        screenCapturer = ScreenCapturer(this, mp) {
            uiManager.ensureInsideScreen()
        }
    }

    private fun startForegroundNotification() {
        val channelId = "OverlayServiceChannel"
        val ch = NotificationChannel(channelId, "Warframe Overlay", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.app_name))
            .setSmallIcon(R.drawable.ic_launcher)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(1, notification)
    }

    private fun startScanning() {
        if (screenCapturer == null) {
            Toast.makeText(this, "Grant screen capture first", Toast.LENGTH_SHORT).show()
            return
        }
        scanningActive = true
        uiManager.setScanningState(true)
        scanLoopJob = serviceScope.launch {
            while (isActive) {
                runSingleScan()
                delay(1000.milliseconds)
            }
        }
    }

    private fun stopScanning() {
        scanningActive = false
        scanLoopJob?.cancel()
        uiManager.setScanningState(active = false)
        fetchJobs.values.forEach { it.cancel() }
        fetchJobs.clear()
        if (!lookupModeActive) {
            voteBank.clear()
            voteEntries.clear()
        }
    }

    private fun toggleLookupMode() {
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
                if (relicManager.activePopupCount() >= 4) {
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
    }

    private suspend fun runSingleScan() {
        val capturer = screenCapturer ?: return
        val bitmap = capturer.captureFrame(cropRect) ?: return
        
        scanCycle++
        val offset = Point(cropRect?.left ?: 0, cropRect?.top ?: 0)
        
        val scannedItems = itemScanner.scanBitmap(
            bitmap = bitmap,
            cropOffset = offset,
            lookupMode = lookupModeActive,
            onDebugLog = { debugManager.postText(it) },
        )
        bitmap.recycle()

        val seenThisCycle = scannedItems.asSequence().map { it.entry.slug }.toSet()
        scannedItems.forEach { 
            voteEntries[it.entry.slug] = it.entry
            itemBounds[it.entry.slug] = it.bounds
        }

        updateVotes(seenThisCycle)

        withContext(Dispatchers.Main) {
            processResults()
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
                    if (newVotes <= 0) voteBank.remove(slug) 
                    else voteBank[slug] = VoteEntry(newVotes, lastSeen)
                }
            }
        }
    }

    private fun processResults() {
        val activeSlugs = voteBank.filter { it.value.votes >= 1 }.keys
        
        for (slug in activeSlugs) {
            val entry = voteEntries[slug] ?: continue
            if (lookupModeActive) {
                val bounds = itemBounds[slug]
                bounds?.let {
                    relicManager.createRelicPopup(slug, entry.name, it, screenCapturer?.screenWidth ?: 0, statusBarHeight)
                }
            } else {
                /* Main scanning results deactivated */
            }
        }

        val slugsToRemove = voteEntries.keys.filter { it !in activeSlugs }
        slugsToRemove.forEach { slug ->
            uiManager.removeRow(slug)
            relicManager.dismissRelicPopup(slug)
            fetchJobs.remove(slug)?.cancel()
        }
    }

    private fun launchFetch(slug: String, entry: ItemEntry) {
        if (fetchJobs.containsKey(slug)) return
        fetchJobs[slug] = serviceScope.launch {
            val result = withContext(Dispatchers.IO) {
                apiSemaphore.withPermit { api.fetchStats(slug) }
            }
            when (result) {
                is ApiResult.Success -> uiManager.setRow(slug, entry.name, "${result.price}p", "#FF80FF80")
                is ApiResult.NotFound -> uiManager.setRow(slug, entry.name, getString(R.string.no_data), "#FFFF8080")
                else -> uiManager.setRow(slug, entry.name, "error", "#FFFFAA44")
            }
        }
    }

    private fun openCropSelector() {
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
        stopScanning()
        screenCapturer?.release()
        uiManager.release()
        relicManager.dismissAll()
        debugManager.dismiss()
        recognizer.close()
    }
}
