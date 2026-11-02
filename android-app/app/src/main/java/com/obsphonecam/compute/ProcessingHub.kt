package com.obsphonecam.compute

import com.obsphonecam.ai.BackgroundBlur
import com.obsphonecam.ai.PersonTracker
import com.obsphonecam.camera.CaptureMode
import com.obsphonecam.camera.ColorLook
import com.obsphonecam.camera.GyroEis
import java.util.ArrayDeque

/**
 * Central computational photography entry for frames before encode.
 * LIVE keeps work tiny; STUDIO enables heavier passes.
 */
class ProcessingHub {
    var mode: CaptureMode = CaptureMode.LIVE
    var colorLook: ColorLook = ColorLook.NATIVE
    var detailAmount: Float = 0.25f
    var smartExposureEnabled: Boolean = true
    var denoiseEnabled: Boolean = false
    var blurEnabled: Boolean = false
    var trackingEnabled: Boolean = false
    var hdrFusionEnabled: Boolean = false

    val tracker = PersonTracker()
    var gyroEis: GyroEis? = null

    private var prevY: ByteArray? = null
    private var lastSuggestion: SmartExposure.Suggestion? = null
    private val hdrBuffer = ArrayDeque<ByteArray>(5)

    fun lastExposureSuggestion(): SmartExposure.Suggestion? = lastSuggestion

    fun process(nv21: ByteArray, width: Int, height: Int): ByteArray {
        var frame = nv21
        gyroEis?.let {
            if (it.enabled) frame = it.stabilizeNv21(frame, width, height)
        }

        if (smartExposureEnabled) {
            val luma = SmartExposure.measureNv21Luma(frame, width, height)
            lastSuggestion = SmartExposure.suggest(
                measuredLuma = luma,
                aggressive = mode == CaptureMode.STUDIO
            )
        }

        tracker.enabled = trackingEnabled || blurEnabled
        val subject = tracker.update(frame, width, height)

        if (mode == CaptureMode.STUDIO && hdrFusionEnabled) {
            hdrBuffer.addLast(frame.copyOf())
            while (hdrBuffer.size > 3) hdrBuffer.removeFirst()
            if (hdrBuffer.size >= 2) {
                frame = HdrFusion.fuse(hdrBuffer.toList(), width, height)
            }
        }

        if (mode == CaptureMode.STUDIO || denoiseEnabled) {
            temporalDenoiseY(frame, width, height, alpha = if (mode == CaptureMode.STUDIO) 0.45f else 0.25f)
        }

        if (detailAmount > 0.05f) {
            val amt = if (mode == CaptureMode.STUDIO) detailAmount * 1.35f else detailAmount * 0.7f
            DetailEnhance.sharpenY(frame, width, height, amt)
        }

        if (blurEnabled) {
            BackgroundBlur.apply(frame, width, height, subject, 0.55f)
        }

        Lut3D.apply(frame, width, height, colorLook)
        return frame
    }

    private fun temporalDenoiseY(nv21: ByteArray, width: Int, height: Int, alpha: Float) {
        val ySize = width * height
        val prev = prevY
        if (prev != null && prev.size == ySize) {
            for (i in 0 until ySize) {
                val a = prev[i].toInt() and 0xFF
                val b = nv21[i].toInt() and 0xFF
                nv21[i] = ((a * alpha + b * (1f - alpha)).toInt()).toByte()
            }
        }
        prevY = nv21.copyOf(ySize)
    }

    fun reset() {
        prevY = null
        lastSuggestion = null
        hdrBuffer.clear()
        tracker.enabled = trackingEnabled
    }

    fun applyDevicePack(denoise: Float, detail: Float, look: ColorLook) {
        detailAmount = detail
        denoiseEnabled = denoise > 0.2f
        colorLook = look
    }
}
