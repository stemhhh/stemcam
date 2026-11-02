package com.obsphonecam.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * Lightweight scopes: histogram + zebra hints drawn from sampled luma.
 */
class ScopeOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {
    enum class Mode { OFF, HISTOGRAM, WAVEFORM, VECTORSCOPE }

    var mode: Mode = Mode.HISTOGRAM
        set(value) { field = value; invalidate() }
    var zebraThreshold: Int = 235
    var peakingEnabled: Boolean = false

    private val hist = IntArray(64)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var zebraHits = 0
    private var peakHits = 0

    fun updateFromNv21(nv21: ByteArray, width: Int, height: Int) {
        hist.fill(0)
        zebraHits = 0
        peakHits = 0
        val ySize = min(nv21.size, width * height)
        var i = 0
        while (i < ySize) {
            val y = nv21[i].toInt() and 0xFF
            hist[y / 4]++
            if (y >= zebraThreshold) zebraHits++
            if (peakingEnabled && i > width && i < ySize - width) {
                val dx = absDiff(y, nv21[i - 1].toInt() and 0xFF)
                val dy = absDiff(y, nv21[i - width].toInt() and 0xFF)
                if (dx + dy > 80) peakHits++
            }
            i += 16
        }
        postInvalidateOnAnimation()
    }

    private fun absDiff(a: Int, b: Int) = kotlin.math.abs(a - b)

    override fun onDraw(canvas: Canvas) {
        when (mode) {
            Mode.OFF -> return
            Mode.HISTOGRAM -> drawHistogram(canvas)
            Mode.WAVEFORM -> drawWaveform(canvas)
            Mode.VECTORSCOPE -> drawVectorscope(canvas)
        }
        if (zebraHits > 0) {
            paint.color = 0x88FF3B30.toInt()
            paint.textSize = 28f
            canvas.drawText("ZEBRA $zebraHits", 16f, height - 24f, paint)
        }
        if (peakingEnabled && peakHits > 0) {
            paint.color = 0x8800E5FF.toInt()
            paint.textSize = 28f
            canvas.drawText("PEAK $peakHits", 16f, height - 56f, paint)
        }
    }

    private fun drawHistogram(canvas: Canvas) {
        val maxV = max(1, hist.maxOrNull() ?: 1)
        val boxH = height / 4f
        val boxW = width / 3f
        val left = width - boxW - 12f
        val top = 12f
        paint.color = 0x66000000
        canvas.drawRect(left, top, left + boxW, top + boxH, paint)
        paint.color = 0xFFE8A317.toInt()
        val barW = boxW / hist.size
        for (i in hist.indices) {
            val h = hist[i].toFloat() / maxV * (boxH - 4f)
            canvas.drawRect(
                left + i * barW,
                top + boxH - h,
                left + (i + 1) * barW - 1f,
                top + boxH,
                paint
            )
        }
    }

    private fun drawWaveform(canvas: Canvas) {
        val boxH = height / 3f
        val boxW = width / 2.5f
        val left = width - boxW - 12f
        val top = 12f
        paint.color = 0x66000000
        canvas.drawRect(left, top, left + boxW, top + boxH, paint)
        paint.color = 0xFF66BB6A.toInt()
        paint.strokeWidth = 2f
        val mid = top + boxH / 2f
        canvas.drawLine(left, mid, left + boxW, mid, paint)
        // Approximate waveform using hist cumulative shape
        var x = left
        val step = boxW / hist.size
        for (i in hist.indices) {
            val y = top + boxH - (hist[i].toFloat() / (hist.maxOrNull()?.coerceAtLeast(1) ?: 1) * boxH)
            canvas.drawPoint(x, y, paint)
            x += step
        }
    }

    private fun drawVectorscope(canvas: Canvas) {
        val size = min(width, height) / 4f
        val cx = width - size - 24f
        val cy = size + 24f
        paint.color = 0x66000000
        canvas.drawCircle(cx, cy, size, paint)
        paint.color = 0xFF90CAF9.toInt()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        canvas.drawCircle(cx, cy, size, paint)
        paint.style = Paint.Style.FILL
        // Simple chroma cloud proxy from hist tails
        paint.color = 0xAAE8A317.toInt()
        for (i in 16 until 48) {
            val a = (i - 32) / 32f
            canvas.drawCircle(cx + a * size * 0.6f, cy - hist[i] / 400f, 3f, paint)
        }
    }
}
