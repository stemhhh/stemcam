package com.obsphonecam.camera

/**
 * Live = low latency path for OBS.
 * Studio = computational photography path (multi-frame / heavier AI) — higher latency.
 */
enum class CaptureMode {
    LIVE,
    STUDIO
}

enum class ColorLook {
    NATIVE,
    NEUTRAL,
    VIVID,
    CINEMATIC,
    SOFT
}

enum class LensKind {
    ULTRA_WIDE,
    WIDE,
    TELE,
    OTHER
}

data class PhysicalLens(
    val cameraId: String,
    val kind: LensKind,
    val focalLengthMm: Float,
    val minZoom: Float,
    val maxZoom: Float,
    val facingFront: Boolean
)
