package com.warframe.priceoverlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager

class ScreenCapturer(
    private val context: Context,
    private val mediaProjection: MediaProjection,
    private val onOrientationChanged: () -> Unit
) {
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    
    var screenWidth = 0
        private set
    var screenHeight = 0
        private set
    private var screenDensity = 0

    init {
        registerProjectionCallback()
        setupDisplay()
        registerOrientationListener()
    }

    private fun setupDisplay() {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)

        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi

        Log.d("ScreenCapturer", "Setup: ${screenWidth}x${screenHeight} @ $screenDensity")

        @SuppressLint("WrongConstant")
        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        
        if (virtualDisplay == null) {
            virtualDisplay = mediaProjection.createVirtualDisplay(
                "ScreenCapture", screenWidth, screenHeight, screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader?.surface, null, null
            )
        } else {
            // Use resize instead of recreating to avoid SecurityException on some devices
            virtualDisplay?.resize(screenWidth, screenHeight, screenDensity)
            virtualDisplay?.surface = imageReader?.surface
        }
    }

    private fun registerOrientationListener() {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        dm.registerDisplayListener(object : DisplayManager.DisplayListener {
            override fun onDisplayChanged(displayId: Int) {
                val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val metrics = DisplayMetrics()
                windowManager.defaultDisplay.getRealMetrics(metrics)
                
                if (metrics.widthPixels != screenWidth || metrics.heightPixels != screenHeight) {
                    Log.d("ScreenCapturer", "Orientation change: ${metrics.widthPixels}x${metrics.heightPixels}")
                    // Re-setup on the main thread to avoid race conditions
                    handler.post {
                        recreateDisplay()
                        onOrientationChanged()
                    }
                }
            }
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
        }, handler)
    }

    private fun registerProjectionCallback() {
        mediaProjection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.d("ScreenCapturer", "MediaProjection stopped")
                release()
            }
        }, android.os.Handler(android.os.Looper.getMainLooper()))
    }

    private fun recreateDisplay() {
        // Don't release virtualDisplay here, just close old reader
        imageReader?.close()
        imageReader = null
        setupDisplay()
    }

    fun captureFrame(cropRect: Rect?): Bitmap? {
        val reader = imageReader ?: return null
        return try {
            val image = reader.acquireLatestImage() ?: return null
            try {
                val p = image.planes[0]
                val rowPadding = p.rowStride - p.pixelStride * screenWidth
                val bmp = Bitmap.createBitmap(
                    screenWidth + rowPadding / p.pixelStride, screenHeight, Bitmap.Config.ARGB_8888
                )
                bmp.copyPixelsFromBuffer(p.buffer)
                
                val full = Bitmap.createBitmap(bmp, 0, 0, screenWidth, screenHeight)
                bmp.recycle()

                if (cropRect != null && !cropRect.isEmpty && 
                    cropRect.right <= screenWidth && cropRect.bottom <= screenHeight) {
                    val cropped = Bitmap.createBitmap(
                        full,
                        cropRect.left.coerceIn(0, screenWidth - 1),
                        cropRect.top.coerceIn(0, screenHeight - 1),
                        cropRect.width().coerceAtLeast(1).coerceAtMost(screenWidth - cropRect.left),
                        cropRect.height().coerceAtLeast(1).coerceAtMost(screenHeight - cropRect.top)
                    )
                    full.recycle()
                    cropped
                } else {
                    full
                }
            } finally {
                image.close()
            }
        } catch (e: Exception) {
            Log.e("ScreenCapturer", "Capture failed: ${e.message}")
            null
        }
    }

    fun release() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        try { mediaProjection.stop() } catch (_: Exception) {}
    }
}
