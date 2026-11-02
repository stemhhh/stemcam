package com.obsphonecam.usb

import android.content.Context
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.ParcelFileDescriptor
import com.obsphonecam.protocol.OpcmProtocol
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * AOA accessory link. The PC (OBS plugin) puts the phone into accessory mode;
 * this class opens the accessory and speaks OPCM framing on the bulk streams.
 */
class UsbAccessoryLink(
    private val context: Context,
    private val onPacket: (type: Int, flags: Int, ptsUs: Long, codec: Int, payload: ByteArray) -> Unit,
    private val onConnectionChanged: (connected: Boolean, detail: String) -> Unit
) {
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var pfd: ParcelFileDescriptor? = null
    private var input: FileInputStream? = null
    private var output: FileOutputStream? = null
    private val connected = AtomicBoolean(false)
    private val txSeq = AtomicInteger(0)
    private val writeLock = Any()
    private var readerThread: Thread? = null

    fun isConnected(): Boolean = connected.get()

    fun currentAccessory(): UsbAccessory? = usbManager.accessoryList?.firstOrNull()

    fun open(accessory: UsbAccessory? = currentAccessory()): Boolean {
        close()
        val acc = accessory ?: return false
        if (!usbManager.hasPermission(acc)) {
            onConnectionChanged(false, "USB permission required")
            return false
        }
        return try {
            val fd = usbManager.openAccessory(acc) ?: return false
            pfd = fd
            input = FileInputStream(fd.fileDescriptor)
            output = FileOutputStream(fd.fileDescriptor)
            connected.set(true)
            onConnectionChanged(true, "USB accessory open")
            startReader()
            true
        } catch (e: Exception) {
            close()
            onConnectionChanged(false, "USB open failed: ${e.message}")
            false
        }
    }

    fun close() {
        connected.set(false)
        readerThread = null
        try {
            input?.close()
        } catch (_: Exception) {
        }
        try {
            output?.close()
        } catch (_: Exception) {
        }
        try {
            pfd?.close()
        } catch (_: Exception) {
        }
        input = null
        output = null
        pfd = null
        onConnectionChanged(false, "USB closed")
    }

    fun send(
        type: Int,
        payload: ByteArray = ByteArray(0),
        flags: Int = 0,
        ptsUs: Long = 0L,
        codec: Int = OpcmProtocol.CODEC_NONE
    ): Boolean {
        if (!connected.get()) return false
        val header = OpcmProtocol.writeHeader(
            packetType = type,
            flags = flags,
            sequence = txSeq.getAndIncrement(),
            ptsUs = ptsUs,
            payloadSize = payload.size,
            codec = codec
        )
        return synchronized(writeLock) {
            try {
                val out = output ?: return false
                out.write(header)
                if (payload.isNotEmpty()) out.write(payload)
                out.flush()
                true
            } catch (_: IOException) {
                close()
                false
            }
        }
    }

    fun sendHello(
        maxWidth: Int,
        maxHeight: Int,
        maxFpsX100: Int,
        maxBitrateKbps: Int,
        capabilities: Int,
        sessionId: Int,
        deviceName: String
    ) {
        val buf = OpcmProtocol.buffer(48)
        buf.putShort(OpcmProtocol.ROLE_PHONE.toShort())
        buf.putShort(capabilities.toShort())
        buf.putInt(maxWidth)
        buf.putInt(maxHeight)
        buf.putInt(maxFpsX100)
        buf.putInt(maxBitrateKbps)
        buf.putInt(0x0200)
        val nameBytes = deviceName.toByteArray(Charsets.UTF_8)
        val nameField = ByteArray(24)
        System.arraycopy(nameBytes, 0, nameField, 0, minOf(24, nameBytes.size))
        buf.put(nameField)
        buf.putInt(sessionId)
        send(OpcmProtocol.PKT_HELLO, buf.array())
    }

    fun sendVideo(data: ByteArray, ptsUs: Long, keyframe: Boolean, config: Boolean, codec: Int): Boolean {
        var flags = 0
        if (keyframe) flags = flags or OpcmProtocol.FLAG_KEYFRAME
        if (config) flags = flags or OpcmProtocol.FLAG_CONFIG
        return send(OpcmProtocol.PKT_VIDEO, data, flags, ptsUs, codec)
    }

    fun sendAudioPcm(pcm: ByteArray, sampleRate: Int, channels: Int, ptsUs: Long = 0L) {
        val header = OpcmProtocol.buffer(8)
        header.putInt(sampleRate)
        header.putShort(channels.toShort())
        header.putShort(0)
        val payload = header.array() + pcm
        send(OpcmProtocol.PKT_AUDIO, payload, 0, ptsUs, OpcmProtocol.CODEC_NONE)
    }

    fun sendStats(
        width: Int,
        height: Int,
        fpsX100: Int,
        bitrateKbps: Int,
        framesSent: Int,
        dropped: Int,
        ptsUs: Long,
        encodeLatencyMs: Int,
        thermal: Int
    ) {
        val buf = OpcmProtocol.buffer(40)
        buf.putInt(width)
        buf.putInt(height)
        buf.putInt(fpsX100)
        buf.putInt(bitrateKbps)
        buf.putInt(framesSent)
        buf.putInt(dropped)
        buf.putLong(ptsUs)
        buf.putInt(encodeLatencyMs)
        buf.putInt(thermal)
        send(OpcmProtocol.PKT_STATS, buf.array())
    }

    fun sendControlAck(msgId: Int, status: Int, appliedFlags: Int) {
        val buf = OpcmProtocol.buffer(8)
        buf.putShort(msgId.toShort())
        buf.putShort(status.toShort())
        buf.putInt(appliedFlags)
        send(OpcmProtocol.PKT_CONTROL_ACK, buf.array())
    }

    private fun startReader() {
        readerThread = thread(name = "UsbAccessoryReader", isDaemon = true) {
            val headerBuf = ByteArray(OpcmProtocol.HEADER_SIZE)
            val inp = input ?: return@thread
            while (connected.get()) {
                try {
                    if (!readFully(inp, headerBuf)) break
                    val header = OpcmProtocol.readHeader(headerBuf) ?: continue
                    if (header.payloadSize < 0 || header.payloadSize > OpcmProtocol.MAX_PAYLOAD) break
                    val payload = if (header.payloadSize > 0) {
                        val p = ByteArray(header.payloadSize)
                        if (!readFully(inp, p)) break
                        p
                    } else ByteArray(0)

                    when (header.packetType) {
                        OpcmProtocol.PKT_PING -> send(OpcmProtocol.PKT_PONG, payload)
                        else -> onPacket(
                            header.packetType,
                            header.flags,
                            header.ptsUs,
                            header.codec,
                            payload
                        )
                    }
                } catch (_: Exception) {
                    break
                }
            }
            if (connected.get()) close()
        }
    }

    private fun readFully(inp: FileInputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = inp.read(buf, off, buf.size - off)
            if (n < 0) return false
            off += n
        }
        return true
    }
}
