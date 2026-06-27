package com.warframe.priceoverlay

import android.app.Service
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Build
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.Toast

class CropRegionManager(
    private val service: Service,
    private val onCropSaved: (Rect) -> Unit
) {
    private val windowManager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var cropSelectorView: View? = null

    fun show(currentCrop: Rect?) {
        if (cropSelectorView != null) { dismiss(); return }

        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        val currentW = metrics.widthPixels
        val currentH = metrics.heightPixels

        if (currentW == 0 || currentH == 0) {
            Toast.makeText(service, "Screen dimensions not available", Toast.LENGTH_SHORT).show()
            return
        }

        val effectiveCrop = currentCrop?.takeIf { it.right <= currentW && it.bottom <= currentH }
        val container = FrameLayout(service)
        val selector = CropSelectorView(service, effectiveCrop, currentW, currentH)

        val btnConfirm = Button(service).apply {
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
            onCropSaved(selector.rect)
            dismiss()
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
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        windowManager.addView(container, wlp)
        cropSelectorView = container
    }

    fun dismiss() {
        cropSelectorView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        cropSelectorView = null
    }
}
