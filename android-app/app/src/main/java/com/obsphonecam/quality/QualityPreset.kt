package com.obsphonecam.quality

import com.obsphonecam.protocol.OpcmProtocol

data class QualitySettings(
    val presetId: Int,
    val preferredWidth: Int,
    val preferredHeight: Int,
    val fpsX100: Int,
    val bitrateKbpsHevc: Int,
    val bitrateKbpsH264: Int,
    val keyframeInterval: Int,
    val preferHevc: Boolean = true
) {
    fun bitrateFor(codec: Int): Int =
        if (codec == OpcmProtocol.CODEC_H264) bitrateKbpsH264 else bitrateKbpsHevc
}

object QualityPresets {
    val NativeMax = QualitySettings(
        presetId = OpcmProtocol.PRESET_NATIVE_MAX,
        preferredWidth = 0, // 0 = pick max stable
        preferredHeight = 0,
        fpsX100 = 6000,
        bitrateKbpsHevc = 50000,
        bitrateKbpsH264 = 65000,
        keyframeInterval = 60
    )

    val High = QualitySettings(
        presetId = OpcmProtocol.PRESET_HIGH,
        preferredWidth = 3840,
        preferredHeight = 2160,
        fpsX100 = 3000,
        bitrateKbpsHevc = 28000,
        bitrateKbpsH264 = 35000,
        keyframeInterval = 60
    )

    val Balanced = QualitySettings(
        presetId = OpcmProtocol.PRESET_BALANCED,
        preferredWidth = 1920,
        preferredHeight = 1080,
        fpsX100 = 3000,
        bitrateKbpsHevc = 15000,
        bitrateKbpsH264 = 18000,
        keyframeInterval = 30
    )

    val LowLatency = QualitySettings(
        presetId = OpcmProtocol.PRESET_LOW_LATENCY,
        preferredWidth = 1280,
        preferredHeight = 720,
        fpsX100 = 6000,
        bitrateKbpsHevc = 8000,
        bitrateKbpsH264 = 10000,
        keyframeInterval = 15
    )

    fun fromId(id: Int): QualitySettings = when (id) {
        OpcmProtocol.PRESET_HIGH -> High
        OpcmProtocol.PRESET_BALANCED -> Balanced
        OpcmProtocol.PRESET_LOW_LATENCY -> LowLatency
        else -> NativeMax
    }

    fun names(): List<String> = listOf("Native Max", "High", "Balanced", "Low Latency")
}
