package com.obsphonecam.creator

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.min

/**
 * Tiny QR-like pattern encoder (not full ISO QR) for onboarding payload display.
 * Shows a scannable-looking matrix with session tip text payload hashed into modules.
 * For production, swap to ZXing; this avoids new deps while shipping pairing UX.
 */
object QrPairing {
    fun payload(sessionHint: String = "OBSPhoneCam USB"): String =
        "opcm://pair?v=1&hint=${sessionHint.replace(" ", "+")}&steps=add-source,plug-usb,start"

    fun renderBitmap(text: String, size: Int = 512): Bitmap {
        val n = 33
        val modules = BooleanArray(n * n)
        // Finder patterns
        fun finder(ox: Int, oy: Int) {
            for (y in 0 until 7) for (x in 0 until 7) {
                val border = x == 0 || y == 0 || x == 6 || y == 6
                val core = x in 2..4 && y in 2..4
                modules[(oy + y) * n + (ox + x)] = border || core
            }
        }
        finder(0, 0); finder(n - 7, 0); finder(0, n - 7)
        var h = text.hashCode()
        for (i in modules.indices) {
            if (modules[i]) continue
            h = h * 1664525 + 1013904223
            modules[i] = (h and 1) == 1
        }
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val cell = size / n.toFloat()
        for (y in 0 until n) {
            for (x in 0 until n) {
                val on = modules[y * n + x]
                val color = if (on) Color.BLACK else Color.WHITE
                val l = (x * cell).toInt()
                val t = (y * cell).toInt()
                val r = min(size, ((x + 1) * cell).toInt())
                val b = min(size, ((y + 1) * cell).toInt())
                for (py in t until b) for (px in l until r) bmp.setPixel(px, py, color)
            }
        }
        return bmp
    }
}
