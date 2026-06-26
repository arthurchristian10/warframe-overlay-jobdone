package com.warframe.priceoverlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

// Draggable / resizable rectangle on a dimmed full-screen overlay.
// Reports the selected rect in screen pixels via `rect`.
@SuppressLint("ViewConstructor")
class CropSelectorView(
    context: Context,
    initialRect: Rect?,
    private val canvasW: Int,
    private val canvasH: Int
) : View(context) {

    val rect: Rect = (initialRect ?: Rect(
        canvasW / 4, canvasH / 4, canvasW * 3 / 4, canvasH * 3 / 4
    )).let { Rect(it) }

    private val maskPaint = Paint().apply { color = 0xCC000000.toInt() }
    private val clearPaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private val borderPaint = Paint().apply {
        color = Color.parseColor("#FF4488FF")
        style = Paint.Style.STROKE
        strokeWidth = 6f
        isAntiAlias = true
    }
    private val handlePaint = Paint().apply {
        color = Color.parseColor("#FF4488FF")
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val labelPaint = Paint().apply {
        color = Color.WHITE
        textSize = 32f
        isAntiAlias = true
        typeface = android.graphics.Typeface.MONOSPACE
        setShadowLayer(4f, 1f, 1f, Color.BLACK)
    }

    private val handleRadius = 28f
    private val edgeTolerance = 56
    private val minSize = 80

    private enum class Drag { NONE, MOVE, L, T, R, B, TL, TR, BL, BR }
    private var dragMode = Drag.NONE
    private var dragStartX = 0f
    private var dragStartY = 0f
    private val rectStart = Rect()

    init { setLayerType(LAYER_TYPE_SOFTWARE, null) }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Dim everything, then punch a clear hole where the crop rect is.
        canvas.drawRect(0f, 0f, canvasW.toFloat(), canvasH.toFloat(), maskPaint)
        canvas.drawRect(rect.left.toFloat(), rect.top.toFloat(),
                        rect.right.toFloat(), rect.bottom.toFloat(), clearPaint)
        canvas.drawRect(rect.left.toFloat(), rect.top.toFloat(),
                        rect.right.toFloat(), rect.bottom.toFloat(), borderPaint)
        // Corner handles
        canvas.drawCircle(rect.left.toFloat(),  rect.top.toFloat(),    handleRadius, handlePaint)
        canvas.drawCircle(rect.right.toFloat(), rect.top.toFloat(),    handleRadius, handlePaint)
        canvas.drawCircle(rect.left.toFloat(),  rect.bottom.toFloat(), handleRadius, handlePaint)
        canvas.drawCircle(rect.right.toFloat(), rect.bottom.toFloat(), handleRadius, handlePaint)
        // Info label
        val label = "${rect.width()}×${rect.height()} px"
        canvas.drawText(label, rect.left.toFloat(), (rect.top - 16).coerceAtLeast(40f), labelPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x; val y = event.y
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                dragStartX = x; dragStartY = y; rectStart.set(rect)
                dragMode = hitTest(x, y)
                return dragMode != Drag.NONE
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (x - dragStartX).toInt()
                val dy = (y - dragStartY).toInt()
                when (dragMode) {
                    Drag.MOVE -> {
                        val w = rectStart.width(); val h = rectStart.height()
                        val nl = (rectStart.left + dx).coerceIn(0, canvasW - w)
                        val nt = (rectStart.top  + dy).coerceIn(0, canvasH - h)
                        rect.set(nl, nt, nl + w, nt + h)
                    }
                    Drag.L  -> rect.left   = (rectStart.left   + dx).coerceIn(0, rect.right  - minSize)
                    Drag.R  -> rect.right  = (rectStart.right  + dx).coerceIn(rect.left + minSize, canvasW)
                    Drag.T  -> rect.top    = (rectStart.top    + dy).coerceIn(0, rect.bottom - minSize)
                    Drag.B  -> rect.bottom = (rectStart.bottom + dy).coerceIn(rect.top + minSize, canvasH)
                    Drag.TL -> {
                        rect.left = (rectStart.left + dx).coerceIn(0, rectStart.right  - minSize)
                        rect.top  = (rectStart.top  + dy).coerceIn(0, rectStart.bottom - minSize)
                    }
                    Drag.TR -> {
                        rect.right = (rectStart.right + dx).coerceIn(rectStart.left + minSize, canvasW)
                        rect.top   = (rectStart.top   + dy).coerceIn(0, rectStart.bottom - minSize)
                    }
                    Drag.BL -> {
                        rect.left   = (rectStart.left   + dx).coerceIn(0, rectStart.right - minSize)
                        rect.bottom = (rectStart.bottom + dy).coerceIn(rectStart.top + minSize, canvasH)
                    }
                    Drag.BR -> {
                        rect.right  = (rectStart.right  + dx).coerceIn(rectStart.left + minSize, canvasW)
                        rect.bottom = (rectStart.bottom + dy).coerceIn(rectStart.top + minSize, canvasH)
                    }
                    else -> {}
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragMode = Drag.NONE; return true
            }
        }
        return false
    }

    private fun hitTest(x: Float, y: Float): Drag {
        val nearL = abs(x - rect.left)   <= edgeTolerance
        val nearR = abs(x - rect.right)  <= edgeTolerance
        val nearT = abs(y - rect.top)    <= edgeTolerance
        val nearB = abs(y - rect.bottom) <= edgeTolerance
        val insideY = y >= rect.top  - edgeTolerance && y <= rect.bottom + edgeTolerance
        val insideX = x >= rect.left - edgeTolerance && x <= rect.right  + edgeTolerance
        if (nearL && nearT) return Drag.TL
        if (nearR && nearT) return Drag.TR
        if (nearL && nearB) return Drag.BL
        if (nearR && nearB) return Drag.BR
        if (nearL && insideY) return Drag.L
        if (nearR && insideY) return Drag.R
        if (nearT && insideX) return Drag.T
        if (nearB && insideX) return Drag.B
        if (rect.contains(x.toInt(), y.toInt())) return Drag.MOVE
        return Drag.NONE
    }
}