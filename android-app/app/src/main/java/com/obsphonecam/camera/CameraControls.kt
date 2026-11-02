package com.obsphonecam.camera

data class CameraControls(
    var exposureNs: Long = 0L,          // 0 = auto
    var iso: Int = 0,                   // 0 = auto
    var wbMode: Int = 0,                // 0 auto, 1 manual
    var wbTempK: Int = 5000,
    var focusMode: Int = 0,             // 0 continuous, 1 once, 2 manual, 3 infinity
    var focusDistance: Float = 0f,
    var zoomRatioX100: Int = 100,
    var torch: Boolean = false,
    var flashIntensity: Float = 1f,     // 0..1 best-effort
    var stabilization: Boolean = false,
    var ois: Boolean = true,
    var aeLock: Boolean = false,
    var afLock: Boolean = false,
    var awbLock: Boolean = false,
    var facingFront: Boolean = false
)

data class CameraCapabilities(
    val exposureRangeNs: LongRange,
    val isoRange: IntRange,
    val focusCalibrationAvailable: Boolean,
    val minFocusDistance: Float,
    val maxDigitalZoom: Float,
    val torchAvailable: Boolean,
    val stabilizationAvailable: Boolean,
    val aeCompensationRange: IntRange,
    val sizes: List<SizeFps>
)

data class SizeFps(
    val width: Int,
    val height: Int,
    val maxFpsX100: Int
) {
    override fun toString(): String = "${width}x${height}"
}
