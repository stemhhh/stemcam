package com.obsphonecam.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

object OpcmProtocol {
    const val MAGIC = 0x4F50434D
    const val VERSION = 0x0001
    const val HEADER_SIZE = 32
    const val MAX_PAYLOAD = 4 * 1024 * 1024

    const val PKT_HELLO = 1
    const val PKT_HELLO_ACK = 2
    const val PKT_VIDEO = 3
    const val PKT_CONTROL = 4
    const val PKT_CONTROL_ACK = 5
    const val PKT_STATS = 6
    const val PKT_GOODBYE = 7
    const val PKT_PING = 8
    const val PKT_PONG = 9
    const val PKT_AUDIO = 10

    const val CODEC_NONE = 0
    const val CODEC_H264 = 1
    const val CODEC_HEVC = 2
    const val CODEC_MJPEG = 3

    const val FLAG_KEYFRAME = 1 shl 0
    const val FLAG_CONFIG = 1 shl 1
    const val FLAG_EOS = 1 shl 2

    const val ROLE_PHONE = 1
    const val ROLE_PC = 2

    const val CAP_HEVC = 1 shl 0
    const val CAP_H264 = 1 shl 1
    const val CAP_MJPEG = 1 shl 2
    const val CAP_MANUAL = 1 shl 3

    const val PRESET_NATIVE_MAX = 0
    const val PRESET_HIGH = 1
    const val PRESET_BALANCED = 2
    const val PRESET_LOW_LATENCY = 3

    const val CTRL_PRESET = 1
    const val CTRL_WIDTH = 2
    const val CTRL_HEIGHT = 3
    const val CTRL_FPS_X100 = 4
    const val CTRL_BITRATE_KBPS = 5
    const val CTRL_CODEC = 6
    const val CTRL_KEYFRAME_INTERVAL = 7
    const val CTRL_EXPOSURE_NS = 8
    const val CTRL_ISO = 9
    const val CTRL_WB_MODE = 10
    const val CTRL_WB_TEMP_K = 11
    const val CTRL_FOCUS_MODE = 12
    const val CTRL_FOCUS_DISTANCE = 13
    const val CTRL_ZOOM_RATIO_X100 = 14
    const val CTRL_TORCH = 15
    const val CTRL_STABILIZATION = 16
    const val CTRL_REQUEST_KEYFRAME = 17
    const val CTRL_CAMERA_FACING = 18
    const val CTRL_START_STREAM = 19
    const val CTRL_STOP_STREAM = 20

    fun buffer(capacity: Int): ByteBuffer =
        ByteBuffer.allocate(capacity).order(ByteOrder.LITTLE_ENDIAN)

    fun writeHeader(
        packetType: Int,
        flags: Int,
        sequence: Int,
        ptsUs: Long,
        payloadSize: Int,
        codec: Int = CODEC_NONE
    ): ByteArray {
        val buf = buffer(HEADER_SIZE)
        buf.putInt(MAGIC)
        buf.putShort(VERSION.toShort())
        buf.putShort(packetType.toShort())
        buf.putInt(flags)
        buf.putInt(sequence)
        buf.putLong(ptsUs)
        buf.putInt(payloadSize)
        buf.putShort(codec.toShort())
        buf.putShort(0)
        return buf.array()
    }

    fun readHeader(bytes: ByteArray, offset: Int = 0): Header? {
        if (bytes.size - offset < HEADER_SIZE) return null
        val buf = ByteBuffer.wrap(bytes, offset, HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        val magic = buf.int
        if (magic != MAGIC) return null
        return Header(
            version = buf.short.toInt() and 0xFFFF,
            packetType = buf.short.toInt() and 0xFFFF,
            flags = buf.int,
            sequence = buf.int,
            ptsUs = buf.long,
            payloadSize = buf.int,
            codec = buf.short.toInt() and 0xFFFF
        )
    }

    data class Header(
        val version: Int,
        val packetType: Int,
        val flags: Int,
        val sequence: Int,
        val ptsUs: Long,
        val payloadSize: Int,
        val codec: Int
    )

    data class Tlv(val key: Int, val value: ByteArray)

    fun buildControl(msgId: Int, tlvs: List<Tlv>): ByteArray {
        var size = 4
        for (t in tlvs) size += 4 + t.value.size
        val buf = buffer(size)
        buf.putShort(msgId.toShort())
        buf.putShort(tlvs.size.toShort())
        for (t in tlvs) {
            buf.putShort(t.key.toShort())
            buf.putShort(t.value.size.toShort())
            buf.put(t.value)
        }
        return buf.array()
    }

    fun parseControl(payload: ByteArray): Pair<Int, List<Tlv>> {
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val msgId = buf.short.toInt() and 0xFFFF
        val count = buf.short.toInt() and 0xFFFF
        val list = ArrayList<Tlv>(count)
        repeat(count) {
            val key = buf.short.toInt() and 0xFFFF
            val len = buf.short.toInt() and 0xFFFF
            val value = ByteArray(len)
            buf.get(value)
            list += Tlv(key, value)
        }
        return msgId to list
    }

    fun u8(v: Int) = byteArrayOf(v.toByte())
    fun u16(v: Int): ByteArray {
        val b = buffer(2); b.putShort(v.toShort()); return b.array()
    }
    fun u32(v: Int): ByteArray {
        val b = buffer(4); b.putInt(v); return b.array()
    }
    fun i32(v: Int): ByteArray = u32(v)
    fun i64(v: Long): ByteArray {
        val b = buffer(8); b.putLong(v); return b.array()
    }
    fun f32(v: Float): ByteArray {
        val b = buffer(4); b.putFloat(v); return b.array()
    }

    fun readU8(v: ByteArray) = v[0].toInt() and 0xFF
    fun readU16(v: ByteArray) = ByteBuffer.wrap(v).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
    fun readU32(v: ByteArray) = ByteBuffer.wrap(v).order(ByteOrder.LITTLE_ENDIAN).int
    fun readI32(v: ByteArray) = readU32(v)
    fun readI64(v: ByteArray) = ByteBuffer.wrap(v).order(ByteOrder.LITTLE_ENDIAN).long
    fun readF32(v: ByteArray) = ByteBuffer.wrap(v).order(ByteOrder.LITTLE_ENDIAN).float
}
