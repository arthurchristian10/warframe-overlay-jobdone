package com.warframe.priceoverlay

import android.app.Service
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.Toast
import androidx.core.graphics.toColorInt

class CropRegionManager(
    private val service: Service,
    private val onCropSaved: (Rect) -> Unit
) {
    private val windowManager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var cropSelectorView: View? = null

    fun show(currentCrop: Rect?) {
        if (cropSelectorView != null) { dismiss(); return }

        val bounds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds
        } else {
            val metrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
        }
        
        val currentW = bounds.width()
        val currentH = bounds.height()

        if (currentW == 0 || currentH == 0) {
            Toast.makeText(service, "Screen dimensions not available", Toast.LENGTH_SHORT).show()
            return
        }

        val effectiveCrop = currentCrop?.takeIf { it.right <= currentW && it.bottom <= currentH }
        val container = FrameLayout(service)
        val selector = CropSelectorView(service, effectiveCrop, currentW, currentH)

        val btnConfirm = Button(service).apply {
            text = service.getString(R.string.confirm_crop_region)
            setBackgroundColor("#CC4488FF".toColorInt())
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
            Toast.makeText(service, service.getString(R.string.crop_saved, selector.rect.width(), selector.rect.height()), Toast.LENGTH_SHORT).show()
            dismiss()
        }

        val wlp = WindowManager.LayoutParams(
            currentW, currentH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
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
        val view = cropSelectorView ?: return
        try { windowManager.removeView(view) } catch (_: Exception) {}
        cropSelectorView = null
    }
}
