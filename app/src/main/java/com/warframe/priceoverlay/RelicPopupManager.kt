package com.warframe.priceoverlay

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.math.roundToInt

class RelicPopupManager(
    private val service: Service,
    private val api: WarframeMarketApi,
    private val apiSemaphore: Semaphore
) {
    private val windowManager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val relicPopups = mutableMapOf<String, View>()
    private val relicPopupJobs = mutableMapOf<String, Job>()
    
    private val RELIC_REWARD_COUNT = 4

    fun createRelicPopup(slug: String, name: String, bounds: Rect, screenWidth: Int, statusBarHeight: Int) {
        if (relicPopups.containsKey(slug)) return
        
        val density = service.resources.displayMetrics.density
        val gapPx = (4 * density).roundToInt()
        val colWidth = screenWidth / RELIC_REWARD_COUNT

        val centreX = (bounds.left + bounds.right) / 2
        val slot = (centreX / colWidth).coerceIn(0, RELIC_REWARD_COUNT - 1)
        val popupWidthPx = (colWidth * 0.72f).toInt().coerceAtLeast(120)
        val popupX = slot * colWidth + gapPx
        val popupY = (bounds.bottom - statusBarHeight + gapPx).coerceAtLeast(0)

        val popupView = LayoutInflater.from(service).inflate(R.layout.item_popup_layout, null)
        val tvName = popupView.findViewById<TextView>(R.id.popup_item_name)
        val tvPlat = popupView.findViewById<TextView>(R.id.popup_plat_price)
        val tvDucat = popupView.findViewById<TextView>(R.id.popup_ducat_value)
        val tvRatio = popupView.findViewById<TextView>(R.id.popup_ratio)
        val tvStatus = popupView.findViewById<TextView>(R.id.popup_status)

        tvName.text = name
        tvStatus.text = "loading…"
        tvStatus.visibility = View.VISIBLE

        val wlp = WindowManager.LayoutParams(
            popupWidthPx, WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = popupX
            y = popupY
        }

        windowManager.addView(popupView, wlp)
        relicPopups[slug] = popupView
        popupView.setOnClickListener { dismissRelicPopup(slug) }

        relicPopupJobs[slug] = CoroutineScope(Dispatchers.Main).launch {
            val detail = withContext(Dispatchers.IO) {
                try {
                    apiSemaphore.withPermit { api.fetchItemDetail(slug) }
                } catch (e: Exception) { null }
            }
            
            if (!relicPopups.containsKey(slug)) return@launch
            tvStatus.visibility = View.GONE
            
            val plat = detail?.platPrice48h
            val ducat = detail?.ducats

            tvPlat.text = if (plat != null) "${plat}p" else "no data"
            if (plat == null) tvPlat.setTextColor(Color.parseColor("#FFFF8080"))
            
            tvDucat.text = if (ducat != null) "${ducat}d" else "—"
            if (ducat == null) tvDucat.setTextColor(Color.parseColor("#FF888899"))

            if (plat != null && plat > 0 && ducat != null && ducat > 0) {
                val ratio = ducat.toFloat() / plat.toFloat()
                tvRatio.text = String.format("%.2f d/p", ratio)
                tvRatio.setTextColor(Color.parseColor(when {
                    ratio >= 1.0f -> "#FF80FF80"
                    ratio >= 0.5f -> "#FFFFCC44"
                    else -> "#FFFF8080"
                }))
            } else {
                tvRatio.text = "—"
            }
        }
    }

    fun dismissRelicPopup(slug: String) {
        relicPopupJobs.remove(slug)?.cancel()
        relicPopups.remove(slug)?.let { 
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
    }

    fun dismissAll() {
        relicPopups.keys.toList().forEach { dismissRelicPopup(it) }
    }
    
    fun isPopupVisible(slug: String) = relicPopups.containsKey(slug)
    
    fun activePopupCount() = relicPopups.size
}
