package com.obsphonecam.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.atan2

class LevelOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs), SensorEventListener {
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private var rollDeg = 0f
    var active: Boolean = true
        set(value) {
            field = value
            if (value) start() else stop()
            invalidate()
        }

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE8A317.toInt()
        strokeWidth = 3f
    }
    private val ref = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x88FFFFFF.toInt()
        strokeWidth = 2f
    }

    fun start() {
        sm.getDefaultSensor(Sensor.TYPE_GRAVITY)?.also {
            sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    fun stop() {
        sm.unregisterListener(this)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (active) start()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    override fun onSensorChanged(event: SensorEvent) {
        val x = event.values[0]
        val y = event.values[1]
        rollDeg = Math.toDegrees(atan2(x.toDouble(), y.toDouble())).toFloat()
        invalidate()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onDraw(canvas: Canvas) {
        if (!active) return
        val cx = width / 2f
        val cy = height / 2f
        canvas.drawLine(cx - 80, cy, cx + 80, cy, ref)
        val leveled = abs(rollDeg) < 1.2f
        line.color = if (leveled) 0xFF3DDC84.toInt() else 0xFFE8A317.toInt()
        canvas.save()
        canvas.rotate(-rollDeg, cx, cy)
        canvas.drawLine(cx - 120, cy, cx + 120, cy, line)
        canvas.restore()
    }
}
