package com.obsphonecam.creator

/**
 * Crops a landscape NV21 frame into simultaneous horizontal (full) and vertical (9:16 center) views.
 * Used by Creator mode H+V dual recording — one sensor, two aspect encodes.
 */
object DualAspectCrop {
    data class Pair(val horizontal: ByteArray, val vertical: ByteArray, val vw: Int, val vh: Int)

    fun verticalCropNv21(nv21: ByteArray, width: Int, height: Int): Pair {
        // 9:16 vertical from center of landscape
        val targetAspect = 9f / 16f
        var cropW = (height * targetAspect).toInt().coerceAtMost(width)
        cropW -= cropW % 2
        var cropH = height
        cropH -= cropH % 2
        if (cropW < 2 || cropH < 2) {
            return Pair(nv21, nv21.copyOf(), width, height)
        }
        val x0 = ((width - cropW) / 2).coerceAtLeast(0)
        val x0Even = x0 - (x0 % 2)
        val vertical = cropNv21(nv21, width, height, x0Even, 0, cropW, cropH)
        return Pair(nv21, vertical, cropW, cropH)
    }

    fun cropNv21(
        src: ByteArray,
        srcW: Int,
        srcH: Int,
        x: Int,
        y: Int,
        cropW: Int,
        cropH: Int
    ): ByteArray {
        val out = ByteArray(cropW * cropH * 3 / 2)
        val srcY = srcW * srcH
        // Y plane
        for (row in 0 until cropH) {
            val srcOff = (y + row) * srcW + x
            System.arraycopy(src, srcOff, out, row * cropW, cropW)
        }
        // NV21 VU interleaved, subsampled 2x
        val dstY = cropW * cropH
        val srcUvBase = srcY
        val uvY = y / 2
        val uvX = x / 2
        val srcUvW = srcW
        val cropUvW = cropW
        for (row in 0 until cropH / 2) {
            val srcOff = srcUvBase + (uvY + row) * srcUvW + uvX * 2
            val dstOff = dstY + row * cropUvW
            System.arraycopy(src, srcOff, out, dstOff, cropUvW)
        }
        return out
    }
}
