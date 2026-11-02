package com.obsphonecam.compute

import com.obsphonecam.camera.ColorLook

/**
 * Tiny procedural 3D-LUT style mapping for LIVE/STUDIO color.
 * Real .cube LUT loading can extend loadCube().
 */
object Lut3D {
    fun apply(nv21: ByteArray, width: Int, height: Int, look: ColorLook) {
        ColorPipeline.applyLookNv21(nv21, width, height, look)
        if (look == ColorLook.CINEMATIC || look == ColorLook.VIVID) {
            // Extra chroma saturation on UV
            val ySize = width * height
            var i = ySize
            while (i + 1 < nv21.size) {
                val v = nv21[i].toInt() and 0xFF
                val u = nv21[i + 1].toInt() and 0xFF
                val sat = if (look == ColorLook.VIVID) 1.15f else 0.92f
                nv21[i] = (((v - 128) * sat) + 128).toInt().coerceIn(0, 255).toByte()
                nv21[i + 1] = (((u - 128) * sat) + 128).toInt().coerceIn(0, 255).toByte()
                i += 2
            }
        }
    }
}
