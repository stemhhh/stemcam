package com.obsphonecam.creator

import com.obsphonecam.camera.CaptureMode
import com.obsphonecam.camera.ColorLook
import com.obsphonecam.protocol.OpcmProtocol
import com.obsphonecam.quality.QualityPresets
import com.obsphonecam.quality.QualitySettings

enum class ScenePreset {
    TALKING_HEAD,
    PRODUCT,
    LOW_LIGHT,
    LOW_LATENCY
}

data class SceneConfig(
    val quality: QualitySettings,
    val mode: CaptureMode,
    val look: ColorLook,
    val fpsX100: Int,
    val blur: Boolean,
    val tracking: Boolean
)

object ScenePresets {
    fun config(scene: ScenePreset): SceneConfig = when (scene) {
        ScenePreset.TALKING_HEAD -> SceneConfig(
            QualityPresets.High.copy(preferredWidth = 1920, preferredHeight = 1080, fpsX100 = 3000),
            CaptureMode.LIVE, ColorLook.NEUTRAL, 3000, blur = true, tracking = true
        )
        ScenePreset.PRODUCT -> SceneConfig(
            QualityPresets.NativeMax.copy(preferredWidth = 3840, preferredHeight = 2160, fpsX100 = 3000),
            CaptureMode.STUDIO, ColorLook.VIVID, 3000, blur = false, tracking = false
        )
        ScenePreset.LOW_LIGHT -> SceneConfig(
            QualityPresets.Balanced.copy(bitrateKbpsHevc = 12000, fpsX100 = 3000),
            CaptureMode.STUDIO, ColorLook.SOFT, 3000, blur = false, tracking = false
        )
        ScenePreset.LOW_LATENCY -> SceneConfig(
            QualityPresets.LowLatency,
            CaptureMode.LIVE, ColorLook.NATIVE, 6000, blur = false, tracking = false
        )
    }

    fun names() = listOf("Talking Head", "Product", "Low Light", "Low Latency")
}
