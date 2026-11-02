package com.obsphonecam.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class GridOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {
    var enabledGrid: Boolean = true
        set(value) { field = value; invalidate() }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66FFFFFF
        strokeWidth = 1.5f
        style = Paint.Style.STROKE
    }

    override fun onDraw(canvas: Canvas) {
        if (!enabledGrid) return
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawLine(w / 3f, 0f, w / 3f, h, paint)
        canvas.drawLine(2f * w / 3f, 0f, 2f * w / 3f, h, paint)
        canvas.drawLine(0f, h / 3f, w, h / 3f, paint)
        canvas.drawLine(0f, 2f * h / 3f, w, 2f * h / 3f, paint)
    }
}
