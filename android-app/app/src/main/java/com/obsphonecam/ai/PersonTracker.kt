package com.obsphonecam.ai

/**
 * Lightweight person/subject tracker using motion centroid on luma.
 * Replaced later by ML Kit Pose/Object if dependency enabled.
 */
class PersonTracker {
    data class Box(val cx: Float, val cy: Float, val w: Float, val h: Float)

    var enabled: Boolean = false
    private var prev: ByteArray? = null
    private var box: Box? = null

    fun current(): Box? = box

    fun update(nv21: ByteArray, width: Int, height: Int): Box? {
        if (!enabled) {
            box = null
            return null
        }
        val ySize = width * height
        val p = prev
        if (p == null || p.size != ySize) {
            prev = nv21.copyOf(ySize)
            box = Box(0.5f, 0.5f, 0.35f, 0.45f)
            return box
        }
        var sx = 0.0
        var sy = 0.0
        var sw = 0.0
        var i = 0
        while (i < ySize) {
            val d = kotlin.math.abs((nv21[i].toInt() and 0xFF) - (p[i].toInt() and 0xFF))
            if (d > 28) {
                val x = i % width
                val y = i / width
                sx += x * d
                sy += y * d
                sw += d
            }
            i += 12
        }
        prev = nv21.copyOf(ySize)
        if (sw < 500) return box
        val cx = ((sx / sw) / width).toFloat().coerceIn(0.15f, 0.85f)
        val cy = ((sy / sw) / height).toFloat().coerceIn(0.15f, 0.85f)
        val prevBox = box ?: Box(cx, cy, 0.35f, 0.45f)
        box = Box(
            cx = prevBox.cx * 0.7f + cx * 0.3f,
            cy = prevBox.cy * 0.7f + cy * 0.3f,
            w = prevBox.w,
            h = prevBox.h
        )
        return box
    }

    /** Vertical 9:16 crop rect in normalized coords around tracked subject. */
    fun verticalCrop(): Box {
        val b = box ?: Box(0.5f, 0.5f, 0.56f, 1f)
        val w = 0.56f
        val left = (b.cx - w / 2f).coerceIn(0f, 1f - w)
        return Box(left + w / 2f, 0.5f, w, 1f)
    }
}
