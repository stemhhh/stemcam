package com.obsphonecam.ai

/**
 * Approximate background blur: keep center ROI sharp, blur periphery on Y.
 * Placeholder until segmentation model is bundled.
 */
object BackgroundBlur {
    fun apply(nv21: ByteArray, width: Int, height: Int, subject: PersonTracker.Box?, amount: Float = 0.6f) {
        if (amount <= 0.05f) return
        val box = subject ?: PersonTracker.Box(0.5f, 0.5f, 0.4f, 0.55f)
        val x0 = ((box.cx - box.w / 2f) * width).toInt().coerceIn(0, width - 1)
        val x1 = ((box.cx + box.w / 2f) * width).toInt().coerceIn(0, width - 1)
        val y0 = ((box.cy - box.h / 2f) * height).toInt().coerceIn(0, height - 1)
        val y1 = ((box.cy + box.h / 2f) * height).toInt().coerceIn(0, height - 1)
        val src = nv21.copyOf(width * height)
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                if (x in x0..x1 && y in y0..y1) continue
                val i = y * width + x
                val blur = (
                    (src[i - 1].toInt() and 0xFF) +
                        (src[i + 1].toInt() and 0xFF) +
                        (src[i - width].toInt() and 0xFF) +
                        (src[i + width].toInt() and 0xFF)
                    ) / 4
                val c = src[i].toInt() and 0xFF
                nv21[i] = (c * (1f - amount) + blur * amount).toInt().coerceIn(0, 255).toByte()
            }
        }
    }
}
