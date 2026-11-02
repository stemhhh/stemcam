package com.obsphonecam.compute

import kotlin.math.max
import kotlin.math.min

/**
 * Simple multi-frame exposure fusion on Y plane (Studio path).
 * Expects 2-3 NV21 frames of identical size, roughly aligned.
 */
object HdrFusion {
    fun fuse(frames: List<ByteArray>, width: Int, height: Int): ByteArray {
        if (frames.isEmpty()) return ByteArray(0)
        if (frames.size == 1) return frames[0].clone()
        val ySize = width * height
        val out = frames[0].clone()
        for (i in 0 until ySize) {
            var wSum = 0.0
            var ySum = 0.0
            for (f in frames) {
                if (f.size < ySize) continue
                val y = (f[i].toInt() and 0xFF).toDouble()
                // Prefer mid-tones; downweight crushed/blown
                val w = weight(y)
                wSum += w
                ySum += y * w
            }
            val v = if (wSum < 1e-3) (frames[0][i].toInt() and 0xFF) else (ySum / wSum).toInt()
            out[i] = v.coerceIn(0, 255).toByte()
        }
        // Mild shadow/highlight recovery curve
        for (i in 0 until ySize) {
            val y = out[i].toInt() and 0xFF
            out[i] = toneMap(y).toByte()
        }
        return out
    }

    private fun weight(y: Double): Double {
        val t = y / 255.0
        return 1.0 - kotlin.math.abs(t - 0.5) * 1.6
    }

    private fun toneMap(y: Int): Int {
        val t = y / 255f
        // Lift shadows, soft-roll highlights
        val lifted = if (t < 0.5f) t * 1.08f else t
        val rolled = if (lifted > 0.85f) 0.85f + (lifted - 0.85f) * 0.55f else lifted
        return (rolled * 255f).toInt().coerceIn(0, 255)
    }

    fun exposureScore(nv21: ByteArray, width: Int, height: Int): Float {
        return SmartExposure.measureNv21Luma(nv21, width, height)
    }
}
