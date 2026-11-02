package com.obsphonecam.compute

/**
 * Cheap unsharp-style detail boost on Y plane (LIVE-safe if used lightly).
 */
object DetailEnhance {
    fun sharpenY(nv21: ByteArray, width: Int, height: Int, amount: Float = 0.35f) {
        if (amount <= 0.01f || width < 3 || height < 3) return
        val ySize = width * height
        val src = nv21.copyOf(ySize)
        for (y in 1 until height - 1) {
            val row = y * width
            for (x in 1 until width - 1) {
                val i = row + x
                val c = src[i].toInt() and 0xFF
                val blur = (
                    (src[i - 1].toInt() and 0xFF) +
                        (src[i + 1].toInt() and 0xFF) +
                        (src[i - width].toInt() and 0xFF) +
                        (src[i + width].toInt() and 0xFF)
                    ) / 4
                val v = (c + amount * (c - blur)).toInt().coerceIn(0, 255)
                nv21[i] = v.toByte()
            }
        }
    }
}
