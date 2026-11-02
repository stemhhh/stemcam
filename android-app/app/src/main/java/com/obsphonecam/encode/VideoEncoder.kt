package com.obsphonecam.encode

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import com.obsphonecam.protocol.OpcmProtocol
import java.util.concurrent.atomic.AtomicBoolean

class VideoEncoder(
    private val onEncoded: (
        data: ByteArray,
        ptsUs: Long,
        keyframe: Boolean,
        config: Boolean,
        codec: Int
    ) -> Unit,
    private val onError: (String) -> Unit
) {
    private var codec: MediaCodec? = null
    private var codecId: Int = OpcmProtocol.CODEC_HEVC
    private var width = 0
    private var height = 0
    private val running = AtomicBoolean(false)
    private var configBlob: ByteArray? = null
    private var frameIndex = 0
    private var keyframeInterval = 60

    fun currentCodec(): Int = codecId

    fun start(
        width: Int,
        height: Int,
        bitrateKbps: Int,
        fps: Int,
        keyframeInterval: Int,
        preferHevc: Boolean,
        preferMain10: Boolean = false
    ) {
        stop()
        this.width = width
        this.height = height
        this.keyframeInterval = keyframeInterval.coerceAtLeast(1)
        frameIndex = 0
        configBlob = null

        val mimeHevc = MediaFormat.MIMETYPE_VIDEO_HEVC
        val mimeAvc = MediaFormat.MIMETYPE_VIDEO_AVC
        val mime = if (preferHevc && isEncoderAvailable(mimeHevc)) {
            codecId = OpcmProtocol.CODEC_HEVC
            mimeHevc
        } else {
            codecId = OpcmProtocol.CODEC_H264
            mimeAvc
        }

        val format = MediaFormat.createVideoFormat(mime, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateKbps * 1000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps.coerceAtLeast(1))
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, maxOf(1, keyframeInterval / fps.coerceAtLeast(1)))
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            if (mime == mimeHevc) {
                val profile = if (preferMain10) {
                    try {
                        MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
                    } catch (_: Throwable) {
                        MediaCodecInfo.CodecProfileLevel.HEVCProfileMain
                    }
                } else {
                    MediaCodecInfo.CodecProfileLevel.HEVCProfileMain
                }
                setInteger(MediaFormat.KEY_PROFILE, profile)
            } else {
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
                setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel42)
            }
        }

        try {
            val enc = MediaCodec.createEncoderByType(mime)
            // Many encoders want NV12 (semi-planar). Our camera yields NV21; convert on queue.
            // Prefer flexible YUV if supported.
            val caps = enc.codecInfo.getCapabilitiesForType(mime)
            val color = when {
                caps.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible) ->
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
                caps.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) ->
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
                else -> MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
            }
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, color)

            enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            enc.start()
            codec = enc
            running.set(true)
            Thread({ drainLoop() }, "VideoEncoderDrain").start()
        } catch (e: Exception) {
            onError("Encoder start failed: ${e.message}")
            stop()
        }
    }

    fun updateBitrate(bitrateKbps: Int) {
        val c = codec ?: return
        val params = Bundle()
        params.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bitrateKbps * 1000)
        try {
            c.setParameters(params)
        } catch (_: Exception) {
        }
    }

    fun requestKeyframe() {
        val c = codec ?: return
        val params = Bundle()
        params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
        try {
            c.setParameters(params)
        } catch (_: Exception) {
        }
    }

    fun queueNv21(nv21: ByteArray, width: Int, height: Int, ptsUs: Long) {
        val c = codec ?: return
        if (!running.get()) return
        try {
            val ix = c.dequeueInputBuffer(5_000)
            if (ix < 0) return
            val input = c.getInputBuffer(ix) ?: return
            input.clear()
            val nv12 = nv21ToNv12(nv21, width, height)
            input.put(nv12)
            val flags = if (frameIndex % keyframeInterval == 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            c.queueInputBuffer(ix, 0, nv12.size, ptsUs, flags)
            frameIndex++
        } catch (e: Exception) {
            onError("Encode queue failed: ${e.message}")
        }
    }

    private fun drainLoop() {
        val info = MediaCodec.BufferInfo()
        while (running.get()) {
            val c = codec ?: break
            try {
                val outIx = c.dequeueOutputBuffer(info, 10_000)
                when {
                    outIx == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    outIx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val fmt = c.outputFormat
                        configBlob = extractCsd(fmt)
                        configBlob?.let {
                            onEncoded(it, 0L, true, true, codecId)
                        }
                    }
                    outIx >= 0 -> {
                        val buf = c.getOutputBuffer(outIx)
                        if (buf != null && info.size > 0) {
                            val data = ByteArray(info.size)
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            buf.get(data)
                            val isCfg = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                            val isKey = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0 || isCfg
                            if (isCfg) {
                                configBlob = data
                            }
                            // Prepend config on keyframes for Annex-B friendliness when encoder
                            // does not already embed parameter sets.
                            val out = if (isKey && !isCfg && configBlob != null && !startsWithStartCodeConfig(data)) {
                                configBlob!! + data
                            } else {
                                data
                            }
                            onEncoded(out, info.presentationTimeUs, isKey, isCfg, codecId)
                        }
                        c.releaseOutputBuffer(outIx, false)
                    }
                }
            } catch (_: IllegalStateException) {
                break
            } catch (e: Exception) {
                onError("Encode drain failed: ${e.message}")
                break
            }
        }
    }

    fun stop() {
        running.set(false)
        try {
            codec?.stop()
        } catch (_: Exception) {
        }
        try {
            codec?.release()
        } catch (_: Exception) {
        }
        codec = null
        configBlob = null
    }

    companion object {
        fun isEncoderAvailable(mime: String): Boolean {
            return try {
                val n = MediaCodecListWrapper.countEncoders(mime)
                n > 0
            } catch (_: Exception) {
                false
            }
        }

        fun extractCsd(format: MediaFormat): ByteArray? {
            val parts = mutableListOf<ByteArray>()
            for (i in 0..3) {
                val key = "csd-$i"
                if (!format.containsKey(key)) continue
                val bb = format.getByteBuffer(key) ?: continue
                val copy = ByteArray(bb.remaining())
                bb.mark()
                bb.get(copy)
                bb.reset()
                parts += ensureAnnexB(copy)
            }
            if (parts.isEmpty()) return null
            return parts.reduce { a, b -> a + b }
        }

        fun ensureAnnexB(data: ByteArray): ByteArray {
            if (data.size >= 4 && data[0] == 0.toByte() && data[1] == 0.toByte() &&
                data[2] == 0.toByte() && data[3] == 1.toByte()
            ) return data
            if (data.size >= 3 && data[0] == 0.toByte() && data[1] == 0.toByte() && data[2] == 1.toByte()) {
                return data
            }
            // AVCC length-prefixed — convert first NALs roughly
            return byteArrayOf(0, 0, 0, 1) + data
        }

        fun startsWithStartCodeConfig(data: ByteArray): Boolean {
            return data.size > 4 && data[0] == 0.toByte() && data[1] == 0.toByte()
        }

        fun nv21ToNv12(nv21: ByteArray, width: Int, height: Int): ByteArray {
            val frameSize = width * height
            val out = nv21.copyOf()
            var i = frameSize
            while (i < nv21.size - 1) {
                val v = nv21[i]
                val u = nv21[i + 1]
                out[i] = u
                out[i + 1] = v
                i += 2
            }
            return out
        }
    }
}

/** Small helper to avoid importing MediaCodecList at call sites with try/catch. */
private object MediaCodecListWrapper {
    fun countEncoders(mime: String): Int {
        val list = android.media.MediaCodecList(android.media.MediaCodecList.ALL_CODECS)
        return list.codecInfos.count { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(mime, true) }
        }
    }
}
