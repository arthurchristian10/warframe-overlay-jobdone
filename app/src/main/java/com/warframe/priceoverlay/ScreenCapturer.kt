package com.warframe.priceoverlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.util.Log

class ScreenCapturer(
    private val context: Context,
    private val mediaProjection: MediaProjection,
    private val onOrientationChange: () -> Unit
) {
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    
    var screenWidth = 0
    var screenHeight = 0
    private var screenDensity = 0
    
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.w("ScreenCapturer", "MediaProjection stopped by system")
            release()
        }
    }

    init {
        updateMetrics()
        setupReader()
    }

    private fun updateMetrics() {
        val metrics = context.resources.displayMetrics
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi
    }

    private fun setupReader() {
        try {
            // Android 14: ALWAYS unregister and re-register before creating a new virtual display
            try { mediaProjection.unregisterCallback(projectionCallback) } catch (_: Exception) {}
            mediaProjection.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))

            imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 1)
            virtualDisplay = mediaProjection.createVirtualDisplay(
                "ScreenCapture", screenWidth, screenHeight, screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface, null, null
            )
            Log.d("ScreenCapturer", "Reader setup complete: ${screenWidth}x${screenHeight}")
        } catch (e: Exception) {
            Log.e("ScreenCapturer", "Failed to setup reader", e)
        }
    }

    fun captureFrame(cropRect: Rect?): Bitmap? {
        val metrics = context.resources.displayMetrics
        if (metrics.widthPixels != screenWidth || metrics.heightPixels != screenHeight) {
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
            refresh()
            onOrientationChange()
            return null
        }

        val reader = imageReader ?: return null
        return try {
            val image = reader.acquireLatestImage() ?: reader.acquireNextImage() ?: return null
            image.use { img ->
                val plane = img.planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * img.width
                
                val bmp = Bitmap.createBitmap(
                    img.width + rowPadding / pixelStride, img.height, Bitmap.Config.ARGB_8888
                )
                bmp.copyPixelsFromBuffer(buffer)
                
                val fullBmp = Bitmap.createBitmap(bmp, 0, 0, img.width, img.height)
                bmp.recycle()

                if (cropRect != null && !cropRect.isEmpty && 
                    cropRect.right <= img.width && cropRect.bottom <= img.height) {
                    val cropped = Bitmap.createBitmap(
                        fullBmp,
                        cropRect.left.coerceIn(0, img.width - 1),
                        cropRect.top.coerceIn(0, img.height - 1),
                        cropRect.width().coerceAtLeast(1).coerceAtMost(img.width - cropRect.left),
                        cropRect.height().coerceAtLeast(1).coerceAtMost(img.height - cropRect.top)
                    )
                    fullBmp.recycle()
                    cropped
                } else {
                    fullBmp
                }
            }
        } catch (e: Exception) {
            Log.e("ScreenCapturer", "Capture error: ${e.message}")
            if (e.message?.contains("abandoned") == true || e.message?.contains("buffer") == true) {
                refresh()
            }
            null
        }
    }

    fun refresh() {
        try {
            // Instead of full destroy, try RESIZE first (smoother for Android 14)
            if (virtualDisplay != null) {
                Log.d("ScreenCapturer", "Resizing existing VirtualDisplay")
                virtualDisplay?.resize(screenWidth, screenHeight, screenDensity)
                imageReader?.close()
                imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 1)
                virtualDisplay?.surface = imageReader?.surface
            } else {
                setupReader()
            }
        } catch (e: Exception) {
            Log.e("ScreenCapturer", "Refresh failed, full recreate", e)
            virtualDisplay?.release()
            imageReader?.close()
            virtualDisplay = null
            imageReader = null
            setupReader()
        }
    }

    fun release() {
        try {
            mediaProjection.unregisterCallback(projectionCallback)
            virtualDisplay?.release()
            imageReader?.close()
        } catch (_: Exception) {}
    }
}
