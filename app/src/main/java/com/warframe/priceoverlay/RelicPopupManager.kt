package com.warframe.priceoverlay

import android.app.Service
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.graphics.toColorInt
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.Locale
import kotlin.math.roundToInt

class RelicPopupManager(
    private val service: Service,
    private val api: WarframeMarketApi,
    private val apiSemaphore: Semaphore,
    private val itemDatabase: ItemDatabase
) {
    private val windowManager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val relicPopups = mutableMapOf<String, View>()
    private val relicPopupJobs = mutableMapOf<String, Job>()
    private val activeBounds = mutableMapOf<String, Rect>()
    
    private val DUCAT_EXPIRY_MS = 7 * 24 * 60 * 60 * 1000L

    fun createRelicPopup(slug: String, name: String, bounds: Rect, screenWidth: Int, statusBarHeight: Int) {
        activeBounds[slug] = bounds
        
        if (!relicPopups.containsKey(slug)) {
            val view = LayoutInflater.from(service).inflate(R.layout.item_popup_layout, FrameLayout(service), false)
            view.findViewById<TextView>(R.id.popup_item_name).text = name
            windowManager.addView(view, createInitialParams())
            relicPopups[slug] = view
            startDataFetch(slug, name, view)
        }
        updateAllPopupPositions(screenWidth)
    }

    private fun createInitialParams(): WindowManager.LayoutParams {
        val prefs = service.getSharedPreferences("wf_overlay_prefs", Context.MODE_PRIVATE)
        val allowCaptures = prefs.getBoolean("allow_captures", false)
        
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or 
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        
        if (!allowCaptures) {
            flags = flags or WindowManager.LayoutParams.FLAG_SECURE
        }

        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
    }

    private fun updateAllPopupPositions(screenWidth: Int) {
        val density = service.resources.displayMetrics.density
        val verticalGap = (10 * density).roundToInt()
        val horizontalPadding = (6 * density).roundToInt()
        val globalMaxBottom = activeBounds.values.maxOfOrNull { it.bottom } ?: 0
        val targetY = globalMaxBottom + verticalGap
        val sortedSlugs = activeBounds.keys.sortedBy { activeBounds[it]?.left ?: 0 }
        val colWidth = screenWidth / 4
        
        for (slug in sortedSlugs) {
            val view = relicPopups[slug] ?: continue
            val bounds = activeBounds[slug] ?: continue
            val params = view.layoutParams as WindowManager.LayoutParams
            val popupWidth = bounds.width().coerceIn((colWidth * 0.7f).toInt(), (colWidth * 0.95f).toInt())
            val centerX = (bounds.left + bounds.right) / 2
            var targetX = centerX - (popupWidth / 2)
            val prevIdx = sortedSlugs.indexOf(slug) - 1
            if (prevIdx >= 0) {
                val prevSlug = sortedSlugs[prevIdx]
                val prevView = relicPopups[prevSlug]
                if (prevView != null) {
                    val prevParams = prevView.layoutParams as WindowManager.LayoutParams
                    val minX = prevParams.x + prevParams.width + horizontalPadding
                    if (targetX < minX) targetX = minX
                }
            }
            params.x = targetX.coerceIn(horizontalPadding, screenWidth - popupWidth - horizontalPadding)
            params.y = targetY
            params.width = popupWidth
            try { windowManager.updateViewLayout(view, params) } catch (_: Exception) {}
        }
    }

    private fun startDataFetch(slug: String, name: String, view: View) {
        val tvPlat = view.findViewById<TextView>(R.id.popup_plat_price)
        val tvDucat = view.findViewById<TextView>(R.id.popup_ducat_value)
        val tvRatio = view.findViewById<TextView>(R.id.popup_ratio)
        val tvStatus = view.findViewById<TextView>(R.id.popup_status)

        relicPopupJobs[slug] = CoroutineScope(Dispatchers.Main).launch {
            val entry = itemDatabase.searchItemDetailed(name)?.entry
            var ducatValue = entry?.ducats
            val lastUpdate = entry?.lastUpdate ?: 0L
            val needsDucatVerify = (ducatValue == null || (System.currentTimeMillis() - lastUpdate > DUCAT_EXPIRY_MS))

            if (ducatValue != null) {
                tvStatus.visibility = View.GONE
                tvDucat.text = service.getString(R.string.ducats_format, ducatValue)
                tvPlat.text = "..." 
            } else {
                tvStatus.visibility = View.VISIBLE
                tvStatus.text = service.getString(R.string.loading)
            }

            val platResult = withContext(Dispatchers.IO) { apiSemaphore.withPermit { api.fetchPlatPrice(slug) } }

            if (!relicPopups.containsKey(slug)) return@launch

            if (needsDucatVerify) {
                val newDucats = withContext(Dispatchers.IO) { api.fetchDucatsOnline(slug) }
                if (newDucats != null) {
                    ducatValue = newDucats
                    itemDatabase.saveLearnedDucats(slug, newDucats)
                    tvStatus.visibility = View.GONE
                    tvDucat.text = service.getString(R.string.ducats_format, ducatValue)
                }
            }

            if (platResult is ApiResult.Success) {
                tvPlat.text = service.getString(R.string.plat_format, platResult.price)
                if (ducatValue != null) {
                    val ratio = ducatValue.toFloat() / platResult.price.toFloat()
                    tvRatio.text = String.format(Locale.getDefault(), service.getString(R.string.ratio_format), ratio)
                    tvRatio.setTextColor(when {
                        ratio >= 1.0f -> "#FF80FF80".toColorInt()
                        ratio >= 0.5f -> "#FFFFCC44".toColorInt()
                        else -> "#FFFF8080".toColorInt()
                    })
                }
            } else if (ducatValue == null) {
                tvStatus.text = service.getString(R.string.no_data)
            } else {
                tvPlat.text = "—"
            }
        }
    }

    fun dismissRelicPopup(slug: String) {
        relicPopupJobs.remove(slug)?.cancel()
        activeBounds.remove(slug)
        relicPopups.remove(slug)?.let { view ->
            try { windowManager.removeView(view) } catch (_: Exception) {}
        }
    }

    fun dismissAll() {
        relicPopups.keys.toList().forEach { dismissRelicPopup(it) }
        activeBounds.clear()
    }

    fun activePopupCount() = relicPopups.size
}
