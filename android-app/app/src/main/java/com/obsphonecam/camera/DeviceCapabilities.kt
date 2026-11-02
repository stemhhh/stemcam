package com.obsphonecam.camera

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaCodecList
import android.os.Build
import org.json.JSONObject

data class DeviceCapabilities(
    val model: String,
    val manufacturer: String,
    val hevc: Boolean,
    val hevc10: Boolean,
    val av1: Boolean,
    val rawSensor: Boolean,
    val ultraWide: Boolean,
    val tele: Boolean,
    val ois: Boolean,
    val torch: Boolean,
    val nnapiLikely: Boolean,
    val logicalMultiCam: Boolean,
    val extensionHdr: Boolean = false,
    val extensionNight: Boolean = false
) {
    fun toJson(): String = JSONObject()
        .put("model", model)
        .put("manufacturer", manufacturer)
        .put("hevc", hevc)
        .put("hevc10", hevc10)
        .put("av1", av1)
        .put("rawSensor", rawSensor)
        .put("ultraWide", ultraWide)
        .put("tele", tele)
        .put("ois", ois)
        .put("torch", torch)
        .put("nnapiLikely", nnapiLikely)
        .put("logicalMultiCam", logicalMultiCam)
        .put("extensionHdr", extensionHdr)
        .put("extensionNight", extensionNight)
        .toString(2)

    fun summaryLines(): List<String> = listOf(
        "$manufacturer $model",
        "HEVC=${yes(hevc)} HEVC10=${yes(hevc10)} AV1=${yes(av1)}",
        "RAW=${yes(rawSensor)} OIS=${yes(ois)} Torch=${yes(torch)}",
        "Ultra=${yes(ultraWide)} Tele=${yes(tele)} MultiCam=${yes(logicalMultiCam)}",
        "ExtHDR=${yes(extensionHdr)} ExtNight=${yes(extensionNight)}",
        "NPU/NNAPI likely=${yes(nnapiLikely)}"
    )

    private fun yes(v: Boolean) = if (v) "yes" else "no"
}

object CapabilityProbe {
    fun probe(context: Context): DeviceCapabilities {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        var raw = false
        var ultra = false
        var tele = false
        var ois = false
        var torch = false
        var multi = false
        val multiCtrl = MultiCameraController(context).refresh(false)
        ultra = multiCtrl.any { it.kind == LensKind.ULTRA_WIDE }
        tele = multiCtrl.any { it.kind == LensKind.TELE }
        for (id in cm.cameraIdList) {
            val c = cm.getCameraCharacteristics(id)
            val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            if (map?.getOutputSizes(ImageFormat.RAW_SENSOR)?.isNotEmpty() == true) raw = true
            if (c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true) torch = true
            val stab = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
            if (stab?.contains(CameraCharacteristics.LENS_OPTICAL_STABILIZATION_MODE_ON) == true) ois = true
            if (c.physicalCameraIds.isNotEmpty()) multi = true
        }
        val codecs = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.filter { it.isEncoder }
        val hevc = codecs.any { it.supportedTypes.any { t -> t.equals("video/hevc", true) } }
        var hevc10 = false
        if (hevc) {
            for (info in codecs) {
                if (!info.supportedTypes.any { it.equals("video/hevc", true) }) continue
                try {
                    val caps = info.getCapabilitiesForType("video/hevc")
                    if (caps.profileLevels.any {
                            it.profile == android.media.MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
                        }) {
                        hevc10 = true
                        break
                    }
                } catch (_: Exception) {
                }
            }
        }
        val av1 = codecs.any { it.supportedTypes.any { t -> t.equals("video/av01", true) } }
        val ext = CameraExtensionsProbe.probe(context)
        return DeviceCapabilities(
            model = Build.MODEL,
            manufacturer = Build.MANUFACTURER,
            hevc = hevc,
            hevc10 = hevc10,
            av1 = av1,
            rawSensor = raw,
            ultraWide = ultra,
            tele = tele,
            ois = ois,
            torch = torch,
            nnapiLikely = Build.VERSION.SDK_INT >= 27,
            logicalMultiCam = multi,
            extensionHdr = ext.hdrAvailable,
            extensionNight = ext.nightAvailable
        )
    }
}
