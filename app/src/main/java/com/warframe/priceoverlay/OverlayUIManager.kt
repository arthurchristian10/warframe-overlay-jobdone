package com.warframe.priceoverlay

import android.app.Service
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Build
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.toColorInt
import kotlin.math.abs

class OverlayUIManager(
    private val service: Service,
    private val onToggleLookup: () -> Unit,
    private val onOpenCropSelector: () -> Unit,
    private val onToggleDebug: () -> Unit
) {
    private val windowManager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private lateinit var overlayView: View
    
    private lateinit var btnLookup: ImageView
    private lateinit var tvLookupLabel: TextView
    private lateinit var btnCropSettings: ImageView
    private lateinit var btnDebugToggle: ImageView
    private lateinit var tvLastScan: TextView
    private lateinit var resultsContainer: LinearLayout
    
    private val displayedRows = mutableMapOf<String, TextView>()

    init {
        setupOverlay()
    }

    private fun setupOverlay() {
        overlayView = LayoutInflater.from(service).inflate(R.layout.overlay_layout, null)
        
        btnLookup = overlayView.findViewById(R.id.btn_lookup)
        tvLookupLabel = overlayView.findViewById(R.id.tv_lookup_label)
        btnCropSettings = overlayView.findViewById(R.id.btn_crop_settings)
        btnDebugToggle = overlayView.findViewById(R.id.btn_debug_toggle)
        tvLastScan = overlayView.findViewById(R.id.tv_last_scan)
        resultsContainer = overlayView.findViewById(R.id.results_container)

        overlayView.findViewById<View>(R.id.btn_toggle).visibility = View.GONE
        overlayView.findViewById<View>(R.id.tv_toggle_label).visibility = View.GONE

        val prefs = service.getSharedPreferences("wf_overlay_prefs", Context.MODE_PRIVATE)
        val allowCaptures = prefs.getBoolean("allow_captures", false)
        
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        if (!allowCaptures) {
            flags = flags or WindowManager.LayoutParams.FLAG_SECURE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 100
        }

        overlayView.setOnTouchListener(object : View.OnTouchListener {
            private var lastX = 0; private var lastY = 0
            private var startX = 0; private var startY = 0
            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        lastX = event.rawX.toInt(); lastY = event.rawY.toInt()
                        startX = lastX; startY = lastY
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x += event.rawX.toInt() - lastX
                        params.y += event.rawY.toInt() - lastY
                        lastX = event.rawX.toInt(); lastY = event.rawY.toInt()
                        windowManager.updateViewLayout(overlayView, params)
                    }
                    MotionEvent.ACTION_UP -> {
                        if (abs(event.rawX - startX) < 10 && abs(event.rawY - startY) < 10) v.performClick()
                    }
                }
                return true
            }
        })

        btnLookup.setOnClickListener { onToggleLookup() }
        btnCropSettings.setOnClickListener { onOpenCropSelector() }
        btnDebugToggle.setOnClickListener { onToggleDebug() }

        windowManager.addView(overlayView, params)
    }

    fun setLookupState(active: Boolean, complete: Boolean = false) {
        val color = "#FF4488FF".toColorInt()
        if (active) {
            btnLookup.setColorFilter(color)
            tvLookupLabel.text = if (complete) service.getString(R.string.cracking_done) else service.getString(R.string.cracking_on)
            tvLookupLabel.setTextColor(color)
            tvLastScan.visibility = View.VISIBLE
        } else {
            btnLookup.clearColorFilter()
            tvLookupLabel.text = service.getString(R.string.cracking_off)
            tvLookupLabel.setTextColor(color)
            tvLastScan.visibility = View.GONE
            tvLastScan.text = ""
        }
    }

    fun updateLastScanTime(time: String) { tvLastScan.text = time }

    fun setRow(slug: String, name: String, price: String, colorHex: String) {
        val row = displayedRows.getOrPut(slug) {
            val tv = TextView(service).apply {
                textSize = 12f
                setPadding(10, 5, 10, 5)
            }
            resultsContainer.addView(tv)
            tv
        }
        row.text = service.getString(R.string.row_format, name, price)
        row.setTextColor(colorHex.toColorInt())
    }

    fun removeRow(slug: String) {
        displayedRows.remove(slug)?.let { resultsContainer.removeView(it) }
    }

    fun ensureInsideScreen() {
        val size = Point()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = windowManager.currentWindowMetrics
            size.x = metrics.bounds.width(); size.y = metrics.bounds.height()
        } else {
            @Suppress("DEPRECATION") windowManager.defaultDisplay.getRealSize(size)
        }
        val params = overlayView.layoutParams as WindowManager.LayoutParams
        var changed = false
        if (params.x < 0) { params.x = 0; changed = true }
        if (params.y < 0) { params.y = 0; changed = true }
        if (params.x > size.x - overlayView.width) { params.x = size.x - overlayView.width; changed = true }
        if (params.y > size.y - overlayView.height) { params.y = size.y - overlayView.height; changed = true }
        if (changed) windowManager.updateViewLayout(overlayView, params)
    }

    fun release() {
        try { windowManager.removeView(overlayView) } catch (_: Exception) {}
    }
}
