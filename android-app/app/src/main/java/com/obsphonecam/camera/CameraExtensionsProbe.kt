package com.obsphonecam.camera

import android.content.Context
import android.hardware.camera2.CameraExtensionCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import com.obsphonecam.util.SessionLogger

/**
 * Probes OEM Camera2 Extensions (HDR / Night) when API 31+ is available.
 * Actual extension sessions are opt-in and device-dependent; this reports capability.
 */
object CameraExtensionsProbe {
    data class Result(
        val hdrAvailable: Boolean,
        val nightAvailable: Boolean,
        val supportedModes: List<Int>
    )

    fun probe(context: Context, cameraId: String? = null): Result {
        if (Build.VERSION.SDK_INT < 31) {
            return Result(false, false, emptyList())
        }
        return try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cameraId ?: cm.cameraIdList.firstOrNull() ?: return Result(false, false, emptyList())
            val ext = cm.getCameraExtensionCharacteristics(id)
            val modes = ext.supportedExtensions.toList()
            val hdr = modes.contains(CameraExtensionCharacteristics.EXTENSION_HDR)
            val night = modes.contains(CameraExtensionCharacteristics.EXTENSION_NIGHT)
            SessionLogger.log("CameraExtensions id=$id modes=$modes hdr=$hdr night=$night")
            Result(hdr, night, modes)
        } catch (e: Exception) {
            SessionLogger.log("CameraExtensions probe fail: ${e.message}")
            Result(false, false, emptyList())
        }
    }
}
