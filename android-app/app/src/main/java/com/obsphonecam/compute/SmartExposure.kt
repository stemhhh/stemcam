package com.obsphonecam.compute

/**
 * Dynamic exposure metering from NV21 luma.
 * Returns suggested exposure/ISO bias for LIVE path (fast) or STUDIO path.
 */
object SmartExposure {
    data class Suggestion(
        val targetLuma: Float,
        val measuredLuma: Float,
        val exposureScale: Float, // >1 brighter
        val isoScale: Float
    )

    fun measureNv21Luma(nv21: ByteArray, width: Int, height: Int): Float {
        val ySize = width * height
        if (nv21.size < ySize || ySize == 0) return 0f
        // Sample center ROI (rough face/product zone)
        val x0 = width / 4
        val x1 = width * 3 / 4
        val y0 = height / 4
        val y1 = height * 3 / 4
        var sum = 0L
        var count = 0
        var y = y0
        while (y < y1) {
            var x = x0
            val row = y * width
            while (x < x1) {
                sum += (nv21[row + x].toInt() and 0xFF)
                count++
                x += 4
            }
            y += 4
        }
        return if (count == 0) 0f else sum.toFloat() / count
    }

    fun suggest(
        measuredLuma: Float,
        targetLuma: Float = 110f,
        aggressive: Boolean = false
    ): Suggestion {
        if (measuredLuma <= 1f) {
            return Suggestion(targetLuma, measuredLuma, 1.4f, 1.3f)
        }
        val ratio = (targetLuma / measuredLuma).coerceIn(0.45f, if (aggressive) 2.2f else 1.7f)
        // Split correction between exposure and ISO
        val exp = kotlin.math.sqrt(ratio.toDouble()).toFloat()
        val iso = ratio / exp
        return Suggestion(targetLuma, measuredLuma, exp, iso)
    }
}
