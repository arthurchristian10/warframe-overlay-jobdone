package com.warframe.priceoverlay

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
    private val apiSemaphore: Semaphore
) {
    private val windowManager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val relicPopups = mutableMapOf<String, View>()
    private val relicPopupJobs = mutableMapOf<String, Job>()
    
    private val relicRewardCount = 4

    fun createRelicPopup(slug: String, name: String, bounds: Rect, screenWidth: Int, statusBarHeight: Int) {
        if (relicPopups.containsKey(slug)) return
        
        val density = service.resources.displayMetrics.density
        val gapPx = (4 * density).roundToInt()
        val colWidth = screenWidth / relicRewardCount

        val centreX = (bounds.left + bounds.right) / 2
        val slot = (centreX / colWidth).coerceIn(0, relicRewardCount - 1)
        val popupWidthPx = (colWidth * 0.72f).toInt().coerceAtLeast(120)
        val popupX = slot * colWidth + gapPx
        val popupY = (bounds.bottom - statusBarHeight + gapPx).coerceAtLeast(0)

        val popupView = LayoutInflater.from(service).inflate(R.layout.item_popup_layout, FrameLayout(service), false)
        val tvName = popupView.findViewById<TextView>(R.id.popup_item_name)
        val tvPlat = popupView.findViewById<TextView>(R.id.popup_plat_price)
        val tvDucat = popupView.findViewById<TextView>(R.id.popup_ducat_value)
        val tvRatio = popupView.findViewById<TextView>(R.id.popup_ratio)
        val tvStatus = popupView.findViewById<TextView>(R.id.popup_status)

        tvName.text = name
        tvStatus.text = service.getString(R.string.loading)
        tvStatus.visibility = View.VISIBLE

        val wlp = WindowManager.LayoutParams(
            popupWidthPx, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
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
                } catch (_: Exception) { null }
            }
            
            if (!relicPopups.containsKey(slug)) return@launch
            tvStatus.visibility = View.GONE
            
            val plat = detail?.platPrice48h
            val ducat = detail?.ducats

            tvPlat.text = if (plat != null) service.getString(R.string.plat_format, plat) else service.getString(R.string.no_data)
            if (plat == null) tvPlat.setTextColor("#FFFF8080".toColorInt())
            
            tvDucat.text = if (ducat != null) service.getString(R.string.ducats_format, ducat) else "—"
            if (ducat == null) tvDucat.setTextColor("#FF888899".toColorInt())

            if (plat != null && plat > 0 && ducat != null && ducat > 0) {
                val ratio = ducat.toFloat() / plat.toFloat()
                tvRatio.text = String.format(Locale.getDefault(), service.getString(R.string.ratio_format), ratio)
                tvRatio.setTextColor("#FF80FF80".toColorInt().takeIf { ratio >= 1.0f } 
                    ?: "#FFFFCC44".toColorInt().takeIf { ratio >= 0.5f } 
                    ?: "#FFFF8080".toColorInt())
            } else {
                tvRatio.text = "—"
            }
        }
    }

    fun dismissRelicPopup(slug: String) {
        relicPopupJobs.remove(slug)?.cancel()
        val view = relicPopups.remove(slug) ?: return
        try { windowManager.removeView(view) } catch (_: Exception) {}
    }

    fun dismissAll() {
        relicPopups.keys.toList().forEach { dismissRelicPopup(it) }
    }
    
    fun activePopupCount() = relicPopups.size
}
