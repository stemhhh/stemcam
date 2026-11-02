package com.obsphonecam.storage

import android.content.Context
import com.obsphonecam.encode.VideoEncoder
import com.obsphonecam.protocol.OpcmProtocol
import com.obsphonecam.util.SessionLogger

/**
 * Dual recording intent: master (full res / high bitrate) + optional proxy (lower res / bitrate).
 * OBS stream remains independent; this only affects local files.
 */
class ProxyMasterRecorder(private val context: Context) {
    private val master = LocalRecorder(context)
    private val proxy = LocalRecorder(context)
    private var proxyEncoder: VideoEncoder? = null
    private var proxyW = 0
    private var proxyH = 0
    private var dualHvVertical: LocalRecorder? = null
    private var verticalEncoder: VideoEncoder? = null

    var recordProxy: Boolean = true
    var recordVertical: Boolean = false

    val isRecording: Boolean get() = master.isRecording

    fun startMaster(width: Int, height: Int, codecId: Int, bitrateKbps: Int, fps: Int, preferHevc: Boolean) {
        stop()
        master.start(width, height, codecId)
        if (recordProxy) {
            proxyW = (width / 2).coerceAtLeast(640).let { it - it % 2 }
            proxyH = (height / 2).coerceAtLeast(360).let { it - it % 2 }
            proxy.start(proxyW, proxyH, codecId)
            proxyEncoder = VideoEncoder(
                onEncoded = { data, pts, key, cfg, _ ->
                    if (proxy.isRecording) proxy.writeEncoded(data, pts, key || cfg)
                },
                onError = { SessionLogger.log("Proxy enc: $it") }
            ).also {
                it.start(
                    width = proxyW,
                    height = proxyH,
                    bitrateKbps = (bitrateKbps * 0.25f).toInt().coerceAtLeast(1500),
                    fps = fps,
                    keyframeInterval = fps * 2,
                    preferHevc = preferHevc,
                    preferMain10 = false
                )
            }
        }
        if (recordVertical) {
            // Vertical encoder sized later on first frame
            dualHvVertical = LocalRecorder(context)
        }
        SessionLogger.log("ProxyMaster start ${width}x${height} proxy=$recordProxy hv=$recordVertical")
    }

    fun writeMasterEncoded(data: ByteArray, ptsUs: Long, keyframe: Boolean) {
        if (master.isRecording) master.writeEncoded(data, ptsUs, keyframe)
    }

    fun feedProxyNv21(nv21: ByteArray, width: Int, height: Int, ptsUs: Long) {
        val enc = proxyEncoder ?: return
        val scaled = scaleNv21Half(nv21, width, height, proxyW, proxyH)
        enc.queueNv21(scaled, proxyW, proxyH, ptsUs)
    }

    fun feedVerticalNv21(nv21: ByteArray, width: Int, height: Int, ptsUs: Long, preferHevc: Boolean, fps: Int) {
        if (!recordVertical) return
        val crop = com.obsphonecam.creator.DualAspectCrop.verticalCropNv21(nv21, width, height)
        if (verticalEncoder == null) {
            val rec = dualHvVertical ?: return
            rec.start(crop.vw, crop.vh, if (preferHevc) OpcmProtocol.CODEC_HEVC else OpcmProtocol.CODEC_H264)
            verticalEncoder = VideoEncoder(
                onEncoded = { data, pts, key, cfg, _ ->
                    if (rec.isRecording) rec.writeEncoded(data, pts, key || cfg)
                },
                onError = { SessionLogger.log("V-enc: $it") }
            ).also {
                it.start(
                    width = crop.vw,
                    height = crop.vh,
                    bitrateKbps = 8000,
                    fps = fps,
                    keyframeInterval = fps * 2,
                    preferHevc = preferHevc,
                    preferMain10 = false
                )
            }
        }
        verticalEncoder?.queueNv21(crop.vertical, crop.vw, crop.vh, ptsUs)
    }

    fun stop(): List<String> {
        val paths = mutableListOf<String>()
        proxyEncoder?.stop(); proxyEncoder = null
        verticalEncoder?.stop(); verticalEncoder = null
        val (m1, m2) = master.stop()
        m1?.let { paths += it }; m2?.let { paths += it }
        val (p1, p2) = proxy.stop()
        p1?.let { paths += "proxy:$it" }; p2?.let { paths += it }
        dualHvVertical?.let {
            val (v1, v2) = it.stop()
            v1?.let { p -> paths += "vertical:$p" }
            v2?.let { p -> paths += p }
        }
        dualHvVertical = null
        return paths
    }

    private fun scaleNv21Half(src: ByteArray, sw: Int, sh: Int, dw: Int, dh: Int): ByteArray {
        if (sw == dw && sh == dh) return src
        val out = ByteArray(dw * dh * 3 / 2)
        for (y in 0 until dh) {
            val sy = (y * sh / dh).coerceIn(0, sh - 1)
            for (x in 0 until dw) {
                val sx = (x * sw / dw).coerceIn(0, sw - 1)
                out[y * dw + x] = src[sy * sw + sx]
            }
        }
        val srcY = sw * sh
        val dstY = dw * dh
        for (y in 0 until dh / 2) {
            val sy = (y * (sh / 2) / (dh / 2)).coerceIn(0, sh / 2 - 1)
            for (x in 0 until dw / 2) {
                val sx = (x * (sw / 2) / (dw / 2)).coerceIn(0, sw / 2 - 1)
                val si = srcY + sy * sw + sx * 2
                val di = dstY + y * dw + x * 2
                if (si + 1 < src.size && di + 1 < out.size) {
                    out[di] = src[si]
                    out[di + 1] = src[si + 1]
                }
            }
        }
        return out
    }
}
