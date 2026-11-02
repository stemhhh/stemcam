package com.obsphonecam.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.SizeF
import kotlin.math.abs

/**
 * Discovers physical cameras (ultra / wide / tele) and picks the best lens
 * for a requested zoom ratio — similar spirit to native camera switching.
 */
class MultiCameraController(context: Context) {
    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var lenses: List<PhysicalLens> = emptyList()
    private var activeId: String? = null
    private var lastSwitchMs = 0L

    /** Hysteresis to avoid rapid lens flapping. */
    var switchCooldownMs: Long = 700L

    fun refresh(front: Boolean = false): List<PhysicalLens> {
        val facing = if (front) CameraCharacteristics.LENS_FACING_FRONT
        else CameraCharacteristics.LENS_FACING_BACK
        val result = mutableListOf<PhysicalLens>()

        for (id in manager.cameraIdList) {
            val chars = manager.getCameraCharacteristics(id)
            if (chars.get(CameraCharacteristics.LENS_FACING) != facing) continue

            // Prefer physical cameras when listed under a logical multi-camera.
            val physicalIds = chars.physicalCameraIds
            if (physicalIds.isNotEmpty()) {
                for (pid in physicalIds) {
                    parseLens(pid, manager.getCameraCharacteristics(pid), front)?.let { result += it }
                }
            } else {
                parseLens(id, chars, front)?.let { result += it }
            }
        }

        // Deduplicate by id
        lenses = result.distinctBy { it.cameraId }.sortedBy { it.focalLengthMm }
        if (activeId == null) {
            activeId = lenses.firstOrNull { it.kind == LensKind.WIDE }?.cameraId
                ?: lenses.firstOrNull()?.cameraId
        }
        return lenses
    }

    fun all(): List<PhysicalLens> = lenses

    fun active(): PhysicalLens? = lenses.firstOrNull { it.cameraId == activeId }

    fun selectKind(kind: LensKind): PhysicalLens? {
        val lens = lenses.firstOrNull { it.kind == kind } ?: return null
        activeId = lens.cameraId
        lastSwitchMs = System.currentTimeMillis()
        return lens
    }

    /**
     * Choose lens for a desired zoom ratio (1.0 = wide native).
     * Returns null if no switch needed / cooldown active.
     */
    fun suggestForZoom(zoomRatio: Float, force: Boolean = false): PhysicalLens? {
        if (lenses.isEmpty()) return null
        val now = System.currentTimeMillis()
        if (!force && now - lastSwitchMs < switchCooldownMs) return active()

        val target = when {
            zoomRatio < 0.85f -> lenses.firstOrNull { it.kind == LensKind.ULTRA_WIDE } ?: closestByZoom(zoomRatio)
            zoomRatio >= 2.4f -> lenses.firstOrNull { it.kind == LensKind.TELE }
                ?: lenses.maxByOrNull { it.focalLengthMm }
                ?: closestByZoom(zoomRatio)
            else -> lenses.firstOrNull { it.kind == LensKind.WIDE } ?: closestByZoom(zoomRatio)
        } ?: return null

        if (target.cameraId != activeId) {
            activeId = target.cameraId
            lastSwitchMs = now
        }
        return target
    }

    private fun closestByZoom(zoomRatio: Float): PhysicalLens? {
        // Approximate: lower focal ≈ wider
        val wide = lenses.firstOrNull { it.kind == LensKind.WIDE } ?: lenses.firstOrNull() ?: return null
        val base = wide.focalLengthMm.coerceAtLeast(1f)
        val wantFocal = base * zoomRatio
        return lenses.minByOrNull { abs(it.focalLengthMm - wantFocal) }
    }

    private fun parseLens(
        id: String,
        chars: CameraCharacteristics,
        front: Boolean
    ): PhysicalLens? {
        val focals = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS) ?: floatArrayOf(4f)
        val focal = focals.firstOrNull() ?: 4f
        val sensor = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: SizeF(6f, 4.5f)
        // 35mm equivalent rough estimate
        val diag = kotlin.math.sqrt((sensor.width * sensor.width + sensor.height * sensor.height).toDouble()).toFloat()
        val eq = if (diag > 0.1f) focal * (43.27f / diag) else focal * 7f

        val kind = when {
            eq < 20f -> LensKind.ULTRA_WIDE
            eq > 50f -> LensKind.TELE
            else -> LensKind.WIDE
        }
        val maxZoom = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
        return PhysicalLens(
            cameraId = id,
            kind = kind,
            focalLengthMm = eq,
            minZoom = if (kind == LensKind.ULTRA_WIDE) 0.5f else 1f,
            maxZoom = maxZoom,
            facingFront = front
        )
    }
}
