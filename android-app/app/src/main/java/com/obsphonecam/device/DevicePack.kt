package com.obsphonecam.device

import android.os.Build
import com.obsphonecam.camera.ColorLook

data class DevicePack(
    val family: String,
    val denoiseAlpha: Float,
    val detailAmount: Float,
    val defaultLook: ColorLook,
    val preferHevc10: Boolean,
    val notes: String
)

object DevicePacks {
    fun resolve(): DevicePack {
        val m = (Build.MANUFACTURER + " " + Build.MODEL).lowercase()
        return when {
            m.contains("google") || m.contains("pixel") -> DevicePack(
                "Pixel", 0.35f, 0.22f, ColorLook.NEUTRAL, true,
                "Natural color, moderate denoise"
            )
            m.contains("samsung") -> DevicePack(
                "Samsung", 0.4f, 0.3f, ColorLook.VIVID, true,
                "Punchier sharpen/saturation"
            )
            m.contains("xiaomi") || m.contains("redmi") || m.contains("poco") -> DevicePack(
                "Xiaomi", 0.45f, 0.28f, ColorLook.VIVID, false,
                "Stronger denoise for noisy sensors"
            )
            m.contains("oneplus") || m.contains("oppo") || m.contains("realme") -> DevicePack(
                "OnePlus/OPPO", 0.38f, 0.26f, ColorLook.NEUTRAL, true,
                "Balanced compute pack"
            )
            m.contains("huawei") || m.contains("honor") -> DevicePack(
                "Huawei/Honor", 0.42f, 0.27f, ColorLook.CINEMATIC, true,
                "Cinematic default look"
            )
            else -> DevicePack(
                "Generic", 0.3f, 0.25f, ColorLook.NATIVE, false,
                "Safe defaults"
            )
        }
    }
}
