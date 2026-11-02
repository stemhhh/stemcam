package com.obsphonecam.compute

import com.obsphonecam.camera.ColorLook
import kotlin.math.min

/**
 * Lightweight CPU color look for LIVE path.
 * Studio path will later use 3D LUTs / GPU.
 */
object ColorPipeline {
    fun applyLookNv21(nv21: ByteArray, width: Int, height: Int, look: ColorLook) {
        if (look == ColorLook.NATIVE) return
        val ySize = width * height
        when (look) {
            ColorLook.VIVID -> {
                // Boost contrast on Y
                for (i in 0 until ySize) {
                    val y = nv21[i].toInt() and 0xFF
                    val v = ((y - 128) * 1.18f + 128f).toInt().coerceIn(0, 255)
                    nv21[i] = v.toByte()
                }
            }
            ColorLook.SOFT -> {
                for (i in 0 until ySize) {
                    val y = nv21[i].toInt() and 0xFF
                    val v = ((y - 128) * 0.88f + 128f).toInt().coerceIn(0, 255)
                    nv21[i] = v.toByte()
                }
            }
            ColorLook.CINEMATIC -> {
                // Slightly crush blacks + cool chroma nudge
                for (i in 0 until ySize) {
                    val y = nv21[i].toInt() and 0xFF
                    val crushed = if (y < 40) (y * 0.75f).toInt() else y
                    val v = ((crushed - 128) * 1.05f + 128f).toInt().coerceIn(0, 255)
                    nv21[i] = v.toByte()
                }
                var i = ySize
                while (i + 1 < nv21.size) {
                    // NV21 VU — nudge U toward cooler
                    val u = nv21[i + 1].toInt() and 0xFF
                    nv21[i + 1] = min(255, u + 4).toByte()
                    i += 2
                }
            }
            ColorLook.NEUTRAL -> {
                // Mild flatten
                for (i in 0 until ySize) {
                    val y = nv21[i].toInt() and 0xFF
                    nv21[i] = ((y - 128) * 0.96f + 128f).toInt().coerceIn(0, 255).toByte()
                }
            }
            else -> Unit
        }
    }
}
