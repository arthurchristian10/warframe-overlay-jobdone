package com.warframe.priceoverlay

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.view.*
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat

class OverlayUIManager(
    private val service: Service,
    private val onToggleScan: () -> Unit,
    private val onToggleLookup: () -> Unit,
    private val onOpenCropSelector: () -> Unit,
    private val onToggleDebug: () -> Unit
) {
    private val windowManager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val overlayView: View = LayoutInflater.from(service).inflate(R.layout.overlay_layout, null)
    
    private val btnToggle: ImageView? = overlayView.findViewById(R.id.btn_toggle)
    private val tvToggleLabel: TextView? = overlayView.findViewById(R.id.tv_toggle_label)
    private val btnLookup: ImageView = overlayView.findViewById(R.id.btn_lookup)
    private val tvLookupLabel: TextView = overlayView.findViewById(R.id.tv_lookup_label)
    private val btnCropSettings: ImageView = overlayView.findViewById(R.id.btn_crop_settings)
    private val btnDebugToggle: ImageView = overlayView.findViewById(R.id.btn_debug_toggle)
    private val tvLastScan: TextView = overlayView.findViewById(R.id.tv_last_scan)
    private val tvMedianLabel: TextView = overlayView.findViewById(R.id.tv_median_label)
    private val divider: View = overlayView.findViewById(R.id.divider)
    private val scrollResults: ScrollView = overlayView.findViewById(R.id.scroll_results)
    private val resultsContainer: LinearLayout = overlayView.findViewById(R.id.results_container)

    private val displayedRows = mutableMapOf<String, TextView>()

    init {
        setupOverlay()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupOverlay() {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 20
            y = 120
        }

        windowManager.addView(overlayView, params)

        var iX = 0; var iY = 0; var iTX = 0f; var iTY = 0f
        overlayView.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    iX = params.x; iY = params.y; iTX = e.rawX; iTY = e.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = iX + (e.rawX - iTX).toInt()
                    params.y = iY + (e.rawY - iTY).toInt()
                    windowManager.updateViewLayout(overlayView, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    /* Main scanning toggle deactivated for now - future reference:
                    if (Math.abs(e.rawX - iTX) < 12 && Math.abs(e.rawY - iTY) < 12) {
                        onToggleScan()
                    }
                    */
                    true
                }
                else -> false
            }
        }

        btnLookup.setOnClickListener { onToggleLookup() }
        btnCropSettings.setOnClickListener { onOpenCropSelector() }
        btnDebugToggle.setOnClickListener { onToggleDebug() }
    }

    fun setScanningState(active: Boolean) {
        if (active) {
            btnToggle?.setColorFilter(Color.parseColor("#FF00E676"))
            tvToggleLabel?.text = "● SCANNING"
            tvToggleLabel?.setTextColor(Color.parseColor("#FF00E676"))
            tvLastScan.visibility = View.VISIBLE
            tvMedianLabel.visibility = View.VISIBLE
        } else {
            btnToggle?.clearColorFilter()
            tvToggleLabel?.text = "○ OFF"
            tvToggleLabel?.setTextColor(Color.parseColor("#FF888888"))
            tvLastScan.visibility = View.GONE
            tvMedianLabel.visibility = View.GONE
            clearResults()
        }
    }

    fun setLookupState(active: Boolean, complete: Boolean = false) {
        val color = Color.parseColor("#FF4488FF")
        if (active) {
            btnLookup.setColorFilter(color)
            tvLookupLabel.text = if (complete) "✓ CRACKING" else "● CRACKING"
            tvLookupLabel.setTextColor(color)
        } else {
            btnLookup.clearColorFilter()
            tvLookupLabel.text = "○ CRACKING"
            tvLookupLabel.setTextColor(color)
        }
    }

    fun updateLastScanTime(time: String) {
        tvLastScan.text = "  last scan $time"
    }

    fun setRow(slug: String, name: String, statusText: String, colorHex: String) {
        val label = "$name\n  $statusText"
        val existing = displayedRows[slug]
        if (existing != null) {
            existing.text = label
            existing.setTextColor(Color.parseColor(colorHex))
        } else {
            val tv = TextView(service).apply {
                text = label
                textSize = 8f
                setTextColor(Color.parseColor(colorHex))
                setPadding(2, 1, 2, 1)
                typeface = Typeface.MONOSPACE
                maxLines = 2
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = 1 }
            }
            displayedRows[slug] = tv
            resultsContainer.addView(tv)
            divider.visibility = View.VISIBLE
            scrollResults.visibility = View.VISIBLE
        }
    }

    fun removeRow(slug: String) {
        displayedRows.remove(slug)?.let { resultsContainer.removeView(it) }
        if (displayedRows.isEmpty()) {
            divider.visibility = View.GONE
            scrollResults.visibility = View.GONE
        }
    }

    fun clearResults() {
        displayedRows.clear()
        resultsContainer.removeAllViews()
        divider.visibility = View.GONE
        scrollResults.visibility = View.GONE
    }

    fun ensureInsideScreen() {
        val metrics = service.resources.displayMetrics
        val params = overlayView.layoutParams as WindowManager.LayoutParams
        
        val maxX = metrics.widthPixels - overlayView.width
        val maxY = metrics.heightPixels - overlayView.height
        
        var changed = false
        if (params.x > maxX) { params.x = maxX.coerceAtLeast(0); changed = true }
        if (params.y > maxY) { params.y = maxY.coerceAtLeast(0); changed = true }
        
        if (changed) {
            try { windowManager.updateViewLayout(overlayView, params) } catch (_: Exception) {}
        }
    }

    fun release() {
        try { windowManager.removeView(overlayView) } catch (_: Exception) {}
    }
}
