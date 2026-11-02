package com.obsphonecam.camera

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sin

/**
 * Lightweight electronic stabilization using gyroscope integration.
 * Applies a compensatory crop offset hint for preview/encode (pixel shift).
 */
class GyroEis(context: Context) : SensorEventListener {
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    var enabled: Boolean = false
        set(value) {
            field = value
            if (value) start() else stop()
        }

    var offsetX: Float = 0f
        private set
    var offsetY: Float = 0f
        private set

    private var lastNs = 0L
    private var yaw = 0f
    private var pitch = 0f

    fun start() {
        sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.also {
            sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() {
        sm.unregisterListener(this)
        offsetX = 0f
        offsetY = 0f
        yaw = 0f
        pitch = 0f
        lastNs = 0L
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!enabled) return
        val ns = event.timestamp
        if (lastNs == 0L) {
            lastNs = ns
            return
        }
        val dt = (ns - lastNs) * 1e-9f
        lastNs = ns
        // values: x,y,z rad/s
        pitch += event.values[0] * dt
        yaw += event.values[1] * dt
        // Convert small angles to pixel-ish offsets (scaled later by consumer)
        offsetX = (sin(yaw.toDouble()) * 80.0).toFloat()
        offsetY = (sin(pitch.toDouble()) * 80.0).toFloat()
        // Leak toward zero to avoid drift
        yaw *= 0.995f
        pitch *= 0.995f
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /** Shift NV21 by integer pixels (simple, edges clamp). */
    fun stabilizeNv21(src: ByteArray, width: Int, height: Int): ByteArray {
        if (!enabled) return src
        val dx = offsetX.toInt().coerceIn(-40, 40)
        val dy = offsetY.toInt().coerceIn(-40, 40)
        if (dx == 0 && dy == 0) return src
        val out = ByteArray(src.size)
        val ySize = width * height
        for (y in 0 until height) {
            val sy = (y + dy).coerceIn(0, height - 1)
            for (x in 0 until width) {
                val sx = (x + dx).coerceIn(0, width - 1)
                out[y * width + x] = src[sy * width + sx]
            }
        }
        // Chroma approximate copy (NV21)
        val cw = width
        val ch = height / 2
        for (y in 0 until ch) {
            val sy = (y + dy / 2).coerceIn(0, ch - 1)
            for (x in 0 until cw step 2) {
                val sx = (x + (dx / 2) * 2).coerceIn(0, cw - 2)
                val di = ySize + y * cw + x
                val si = ySize + sy * cw + sx
                out[di] = src[si]
                out[di + 1] = src[si + 1]
            }
        }
        return out
    }
}
