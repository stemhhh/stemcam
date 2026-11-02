package com.obsphonecam.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

enum class MicSource { BUILTIN, USB, BLUETOOTH, CAMCORDER }

class AudioRouter(
    private val context: Context,
    private val sampleRate: Int = 48000,
    private val onPcm: (ByteArray, Int, Int, Float /*level 0..1*/) -> Unit
) {
    private val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val running = AtomicBoolean(false)
    private var threadRef: Thread? = null

    var source: MicSource = MicSource.CAMCORDER
    var gain: Float = 1f
    var muted: Boolean = false
    var gateThreshold: Float = 0.02f
    var gateEnabled: Boolean = true
    @Volatile var level: Float = 0f
        private set

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = Unit
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = Unit
    }

    fun availableSources(): List<MicSource> {
        val list = mutableListOf(MicSource.BUILTIN, MicSource.CAMCORDER)
        if (Build.VERSION.SDK_INT >= 23) {
            val inputs = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
            if (inputs.any { it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET }) {
                list += MicSource.USB
            }
            if (inputs.any {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
                }) {
                list += MicSource.BLUETOOTH
            }
        }
        return list.distinct()
    }

    fun start() {
        if (running.getAndSet(true)) return
        if (Build.VERSION.SDK_INT >= 23) {
            am.registerAudioDeviceCallback(deviceCallback, null)
        }
        if (source == MicSource.BLUETOOTH) {
            @Suppress("DEPRECATION")
            am.startBluetoothSco()
            am.isBluetoothScoOn = true
        }
        threadRef = thread(name = "AudioRouter", isDaemon = true) { loop() }
    }

    fun stop() {
        running.set(false)
        threadRef = null
        if (Build.VERSION.SDK_INT >= 23) {
            try { am.unregisterAudioDeviceCallback(deviceCallback) } catch (_: Exception) {}
        }
        @Suppress("DEPRECATION")
        try {
            am.stopBluetoothSco()
            am.isBluetoothScoOn = false
        } catch (_: Exception) {
        }
    }

    @SuppressLint("MissingPermission")
    private fun loop() {
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val format = AudioFormat.ENCODING_PCM_16BIT
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, format)
        val bufSize = max(minBuf, sampleRate / 5 * 2)
        val audioSource = when (source) {
            MicSource.BUILTIN -> MediaRecorder.AudioSource.MIC
            MicSource.USB -> MediaRecorder.AudioSource.MIC
            MicSource.BLUETOOTH -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
            MicSource.CAMCORDER -> MediaRecorder.AudioSource.CAMCORDER
        }
        val record = try {
            AudioRecord(audioSource, sampleRate, channelConfig, format, bufSize)
        } catch (_: Exception) {
            running.set(false)
            return
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            running.set(false)
            return
        }
        // Prefer preferred device when API available
        if (Build.VERSION.SDK_INT >= 23) {
            val inputs = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
            val pref = when (source) {
                MicSource.USB -> inputs.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
                }
                MicSource.BLUETOOTH -> inputs.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
                }
                else -> null
            }
            if (pref != null) record.preferredDevice = pref
        }
        val buf = ShortArray(bufSize / 2)
        try {
            record.startRecording()
            while (running.get()) {
                val n = record.read(buf, 0, buf.size)
                if (n <= 0) continue
                var sumSq = 0.0
                for (i in 0 until n) {
                    var s = buf[i] * gain
                    if (muted) s = 0f
                    buf[i] = s.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                    val f = buf[i] / 32768f
                    sumSq += (f * f).toDouble()
                }
                val rms = sqrt(sumSq / n).toFloat()
                level = rms
                if (gateEnabled && rms < gateThreshold) {
                    for (i in 0 until n) buf[i] = 0
                    level = 0f
                }
                val bytes = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until n) bytes.putShort(buf[i])
                onPcm(bytes.array(), sampleRate, 1, level)
            }
        } catch (_: Exception) {
        } finally {
            try { record.stop() } catch (_: Exception) {}
            record.release()
            running.set(false)
        }
    }
}
