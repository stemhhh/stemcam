package com.obsphonecam.util

import android.content.Context
import android.os.Build
import android.os.PowerManager

class ThermalBitrateController(context: Context) {
    private val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    var baseBitrateKbps: Int = 50000
    var drops: Int = 0

    fun suggestedBitrateKbps(): Int {
        var kbps = baseBitrateKbps
        val status = thermalStatus()
        when (status) {
            1 -> kbps = (kbps * 0.9f).toInt()
            2 -> kbps = (kbps * 0.75f).toInt()
            3, 4, 5, 6 -> kbps = (kbps * 0.55f).toInt()
        }
        if (drops > 30) kbps = (kbps * 0.7f).toInt()
        else if (drops > 10) kbps = (kbps * 0.85f).toInt()
        return kbps.coerceIn(2000, baseBitrateKbps)
    }

    fun thermalLabel(): String = when (thermalStatus()) {
        0 -> "cool"
        1 -> "warm"
        2 -> "hot"
        else -> "throttle"
    }

    fun thermalStatus(): Int {
        return if (Build.VERSION.SDK_INT >= 29) {
            try {
                pm.currentThermalStatus
            } catch (_: Exception) {
                0
            }
        } else 0
    }
}
