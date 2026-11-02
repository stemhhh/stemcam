package com.obsphonecam.storage

import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import com.obsphonecam.protocol.OpcmProtocol
import com.obsphonecam.util.SessionLogger
import java.io.File
import java.nio.ByteBuffer

/**
 * Records encoded access units to internal (+ optional external mirror of the finished file).
 * Uses MediaMuxer MP4 for the primary file; mirrors by copying on stop when external exists.
 */
class LocalRecorder(private val context: Context) {
    private val dual = DualStorageWriter(context)
    private var muxer: MediaMuxer? = null
    private var track = -1
    private var started = false
    private var primaryFile: File? = null
    private var targets: DualStorageWriter.Targets? = null
    private var mirrorName: String? = null

    val isRecording: Boolean get() = muxer != null

    fun start(width: Int, height: Int, codecId: Int, mimeHint: String? = null): String {
        stop()
        val t = dual.prepareSessionDirs()
        targets = t
        dual.lowSpaceWarning(t)?.let { SessionLogger.log(it) }
        val name = "master_${width}x${height}.mp4"
        mirrorName = name
        val file = File(t.internalDir, name)
        primaryFile = file
        val mux = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val mime = mimeHint ?: if (codecId == OpcmProtocol.CODEC_H264) MediaFormat.MIMETYPE_VIDEO_AVC
        else MediaFormat.MIMETYPE_VIDEO_HEVC
        val format = MediaFormat.createVideoFormat(mime, width, height)
        track = mux.addTrack(format)
        mux.start()
        muxer = mux
        started = true
        SessionLogger.log("LocalRecorder start ${file.absolutePath}")
        return file.absolutePath
    }

    fun writeEncoded(data: ByteArray, ptsUs: Long, keyframe: Boolean) {
        val mux = muxer ?: return
        if (!started || track < 0) return
        val buf = ByteBuffer.wrap(data)
        val info = MediaCodec.BufferInfo()
        info.offset = 0
        info.size = data.size
        info.presentationTimeUs = ptsUs
        info.flags = if (keyframe) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
        try {
            mux.writeSampleData(track, buf, info)
        } catch (e: Exception) {
            SessionLogger.log("LocalRecorder write fail: ${e.message}")
        }
    }

    fun stop(): Pair<String?, String?> {
        val primary = primaryFile?.absolutePath
        try {
            muxer?.stop()
        } catch (_: Exception) {
        }
        try {
            muxer?.release()
        } catch (_: Exception) {
        }
        muxer = null
        started = false
        track = -1
        var secondary: String? = null
        val src = primaryFile
        val extDir = targets?.externalDir
        val name = mirrorName
        if (src != null && extDir != null && name != null && src.exists()) {
            try {
                val dst = File(extDir, name)
                src.copyTo(dst, overwrite = true)
                secondary = dst.absolutePath
                SessionLogger.log("Mirrored recording to $secondary")
            } catch (e: Exception) {
                SessionLogger.log("External mirror failed: ${e.message}")
            }
        }
        primaryFile = null
        targets = null
        return primary to secondary
    }
}
