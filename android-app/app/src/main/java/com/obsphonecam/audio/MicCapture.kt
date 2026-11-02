package com.obsphonecam.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class MicCapture(
    private val sampleRate: Int = 48000,
    private val onPcm: (ByteArray, Int /*sampleRate*/, Int /*channels*/) -> Unit
) {
    private val running = AtomicBoolean(false)
    private var threadRef: Thread? = null

    @SuppressLint("MissingPermission")
    fun start() {
        if (running.getAndSet(true)) return
        threadRef = thread(name = "MicCapture", isDaemon = true) {
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val format = AudioFormat.ENCODING_PCM_16BIT
            val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, format)
            val bufSize = maxOf(minBuf, sampleRate / 5 * 2) // ~100ms
            val record = try {
                AudioRecord(
                    MediaRecorder.AudioSource.CAMCORDER,
                    sampleRate,
                    channelConfig,
                    format,
                    bufSize
                )
            } catch (_: Exception) {
                running.set(false)
                return@thread
            }
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                running.set(false)
                return@thread
            }
            val buf = ShortArray(bufSize / 2)
            try {
                record.startRecording()
                while (running.get()) {
                    val n = record.read(buf, 0, buf.size)
                    if (n > 0) {
                        val bytes = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
                        for (i in 0 until n) bytes.putShort(buf[i])
                        onPcm(bytes.array(), sampleRate, 1)
                    }
                }
            } catch (_: Exception) {
            } finally {
                try {
                    record.stop()
                } catch (_: Exception) {
                }
                record.release()
                running.set(false)
            }
        }
    }

    fun stop() {
        running.set(false)
        threadRef = null
    }
}
