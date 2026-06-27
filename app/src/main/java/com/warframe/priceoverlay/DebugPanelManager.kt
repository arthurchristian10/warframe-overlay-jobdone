package com.warframe.priceoverlay

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class DebugPanelManager(private val service: Service) {
    private val windowManager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var debugPanelView: View? = null
    private var tvDebugOcr: TextView? = null
    
    var isVisible = false
        private set

    fun toggle() {
        if (isVisible) dismiss() else show()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun show() {
        if (debugPanelView != null) return
        val density = service.resources.displayMetrics.density

        val container = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#EE0A0A1A"))
            setPadding(12, 10, 12, 10)
        }

        val header = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val tvTitle = TextView(service).apply {
            text = "OCR DEBUG"
            textSize = 10f
            setTextColor(Color.parseColor("#FFAAAAFF"))
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val btnClose = TextView(service).apply {
            text = " ✕ "
            textSize = 11f
            setTextColor(Color.parseColor("#FFFF6666"))
            typeface = Typeface.MONOSPACE
            setOnClickListener { dismiss() }
        }

        header.addView(tvTitle)
        header.addView(btnClose)

        val divLine = View(service).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            ).also { it.topMargin = 6; it.bottomMargin = 6 }
            setBackgroundColor(Color.parseColor("#33FFFFFF"))
        }

        val scroll = ScrollView(service).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                (220 * density).toInt()
            )
        }

        tvDebugOcr = TextView(service).apply {
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
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 10
            y = 120
        }

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
        isVisible = true
    }

    fun dismiss() {
        debugPanelView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        debugPanelView = null
        tvDebugOcr = null
        isVisible = false
    }

    fun postText(text: String) {
        if (!isVisible) return
        CoroutineScope(Dispatchers.Main).launch {
            tvDebugOcr?.text = text
        }
    }
}
