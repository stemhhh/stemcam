package com.obsphonecam.stream

import android.content.Context
import android.graphics.SurfaceTexture
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.obsphonecam.audio.AudioRouter
import com.obsphonecam.audio.MicSource
import com.obsphonecam.camera.CameraEngine
import com.obsphonecam.camera.CaptureMode
import com.obsphonecam.camera.ColorLook
import com.obsphonecam.camera.DeviceCapabilities
import com.obsphonecam.camera.CapabilityProbe
import com.obsphonecam.camera.GyroEis
import com.obsphonecam.camera.LensKind
import com.obsphonecam.camera.MultiCameraController
import com.obsphonecam.camera.PhysicalLens
import com.obsphonecam.camera.SizeFps
import com.obsphonecam.compute.ProcessingHub
import com.obsphonecam.creator.SceneConfig
import com.obsphonecam.creator.ScenePreset
import com.obsphonecam.creator.ScenePresets
import com.obsphonecam.device.DevicePacks
import com.obsphonecam.encode.VideoEncoder
import com.obsphonecam.protocol.OpcmProtocol
import com.obsphonecam.quality.QualityPresets
import com.obsphonecam.quality.QualitySettings
import com.obsphonecam.camera.RawStillCapture
import com.obsphonecam.storage.ProxyMasterRecorder
import com.obsphonecam.usb.UsbAccessoryLink
import com.obsphonecam.util.SessionLogger
import com.obsphonecam.util.ThermalBitrateController
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

class StreamController(
    private val context: Context,
    private val listener: Listener
) {
    interface Listener {
        fun onStatus(text: String)
        fun onMetrics(text: String)
        fun onUsbState(connected: Boolean)
        fun onSizes(sizes: List<SizeFps>)
        fun onLenses(lenses: List<PhysicalLens>) {}
        fun onAudioLevel(level: Float) {}
        fun onScopeFrame(nv21: ByteArray, w: Int, h: Int) {}
        fun onCapabilities(caps: DeviceCapabilities) {}
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var camera: CameraEngine? = null
    private var encoder: VideoEncoder? = null
    private var usb: UsbAccessoryLink? = null
    private var audio: AudioRouter? = null
    private val multiCam = MultiCameraController(context)
    private val processing = ProcessingHub()
    private val thermal = ThermalBitrateController(context)
    private val recorder = ProxyMasterRecorder(context)
    private val rawStill = RawStillCapture(context)
    private val gyroEis = GyroEis(context)
    private val devicePack = DevicePacks.resolve()
    private val capabilities: DeviceCapabilities = CapabilityProbe.probe(context)

    var audioEnabled: Boolean = true
    var autoLensSwitch: Boolean = true
    var autoStartOnUsb: Boolean = true
    var recordLocal: Boolean = false
    var dualHvEnabled: Boolean = false
        set(value) {
            field = value
            recorder.recordVertical = value
        }
    var recordProxyEnabled: Boolean = true
        set(value) {
            field = value
            recorder.recordProxy = value
        }
    private var preferredLensId: String? = null
    private var previewTexture: SurfaceTexture? = null
    private var switchingLens: Boolean = false

    private val streaming = AtomicBoolean(false)
    private val framesSent = AtomicInteger(0)
    private val dropped = AtomicInteger(0)
    private var sessionId = Random.nextInt()
    private var settings: QualitySettings = QualityPresets.NativeMax
    private var bitrateOverrideKbps: Int = settings.bitrateKbpsHevc
    private var lastStatsAt = 0L
    private var fpsCounter = 0
    private var fpsWindowStart = 0L
    private var measuredFpsX100 = 0
    private var resumeStreamAfterReconnect = false
    private var lastEncodeW = 0
    private var lastEncodeH = 0

    init {
        processing.gyroEis = gyroEis
        processing.applyDevicePack(devicePack.denoiseAlpha, devicePack.detailAmount, devicePack.defaultLook)
        SessionLogger.log("DevicePack=${devicePack.family} ${devicePack.notes}")
        listener.onCapabilities(capabilities)
    }

    fun processingHub() = processing
    fun deviceCapabilities() = capabilities
    fun devicePack() = devicePack
    fun audioLevel() = audio?.level ?: 0f
    fun isRecording() = recorder.isRecording

    fun attachUsb() {
        if (usb == null) {
            usb = UsbAccessoryLink(
                context = context,
                onPacket = { type, _, _, _, payload -> handlePacket(type, payload) },
                onConnectionChanged = { connected, detail ->
                    SessionLogger.log("USB: $detail connected=$connected")
                    listener.onUsbState(connected)
                    listener.onStatus(detail)
                    if (connected) {
                        sendHello()
                        if (resumeStreamAfterReconnect || autoStartOnUsb) {
                            resumeStreamAfterReconnect = false
                            if (!streaming.get()) startStream()
                            listener.onStatus("Stream active after USB connect")
                        }
                    }
                }
            )
        }
        val ok = usb!!.open()
        if (!ok) listener.onStatus("Waiting for USB accessory from OBS…")
    }

    fun detachUsb() {
        if (streaming.get()) resumeStreamAfterReconnect = true
        stopStream(sendStop = false)
        usb?.close()
    }

    fun setCaptureMode(mode: CaptureMode) {
        processing.mode = mode
        processing.denoiseEnabled = mode == CaptureMode.STUDIO
        processing.hdrFusionEnabled = mode == CaptureMode.STUDIO
        processing.detailAmount =
            if (mode == CaptureMode.STUDIO) devicePack.detailAmount * 1.3f else devicePack.detailAmount
        SessionLogger.log("CaptureMode=$mode")
    }

    fun setColorLook(look: ColorLook) {
        processing.colorLook = look
    }

    fun setEis(enabled: Boolean) {
        gyroEis.enabled = enabled
    }

    fun setTracking(enabled: Boolean) {
        processing.trackingEnabled = enabled
    }

    fun setBlur(enabled: Boolean) {
        processing.blurEnabled = enabled
    }

    private var preferredMic: MicSource = MicSource.CAMCORDER

    fun setMicSource(source: MicSource) {
        preferredMic = source
        val was = streaming.get() && audioEnabled
        audio?.stop()
        audio = null
        if (was) startAudio()
    }

    fun availableMics() = AudioRouter(context) { _, _, _, _ -> }.availableSources()

    fun applyScene(scene: ScenePreset, preview: SurfaceTexture?) {
        val cfg: SceneConfig = ScenePresets.config(scene)
        settings = cfg.quality
        setCaptureMode(cfg.mode)
        setColorLook(cfg.look)
        setBlur(cfg.blur)
        setTracking(cfg.tracking)
        settings = settings.copy(fpsX100 = cfg.fpsX100)
        openCamera(preview, camera?.controls?.facingFront == true)
        if (streaming.get()) startStream()
    }

    fun selectLens(kind: LensKind, preview: SurfaceTexture?) {
        val lens = multiCam.selectKind(kind) ?: return
        preferredLensId = lens.cameraId
        reopenWithLens(preview)
        listener.onStatus("Lens ${lens.kind}")
    }

    fun tapFocus(nx: Float, ny: Float) {
        camera?.tapToFocus(nx, ny)
    }

    fun openCamera(preview: SurfaceTexture?, front: Boolean = false) {
        previewTexture = preview
        encoder?.stop()
        encoder = null
        camera?.close()
        camera = null
        processing.reset()
        val lenses = multiCam.refresh(front)
        listener.onLenses(lenses)
        if (preferredLensId == null) preferredLensId = multiCam.active()?.cameraId
        val cam = CameraEngine(
            context = context,
            onYuvFrame = { data, w, h, pts -> onCameraFrame(data, w, h, pts) },
            onError = { listener.onStatus(it) }
        )
        camera = cam
        val caps = cam.probeCapabilities(front)
        listener.onSizes(caps.sizes)
        val size = CameraEngine.chooseSize(caps.sizes, settings.preferredWidth, settings.preferredHeight)
        cam.open(
            previewTexture = preview,
            width = size.width,
            height = size.height,
            fpsX100 = settings.fpsX100,
            front = front,
            preferredCameraId = preferredLensId
        ) {
            listener.onStatus("Camera ready ${size.width}x${size.height} · ${multiCam.active()?.kind}")
        }
    }

    private fun reopenWithLens(preview: SurfaceTexture?) {
        if (switchingLens) return
        switchingLens = true
        val front = camera?.controls?.facingFront == true
        val was = streaming.get()
        openCamera(preview ?: previewTexture, front)
        if (was) startStream()
        switchingLens = false
    }

    fun closeCamera() {
        stopStream(sendStop = false)
        stopLocalRecord()
        gyroEis.enabled = false
        audio?.stop()
        audio = null
        encoder?.stop()
        encoder = null
        camera?.close()
        camera = null
    }

    fun applyPreset(id: Int) {
        settings = QualityPresets.fromId(id)
        bitrateOverrideKbps = settings.bitrateFor(encoder?.currentCodec() ?: OpcmProtocol.CODEC_HEVC)
        thermal.baseBitrateKbps = bitrateOverrideKbps
        if (streaming.get()) startStream()
    }

    fun setBitratePercent(percent: Int) {
        val base = settings.bitrateFor(encoder?.currentCodec() ?: OpcmProtocol.CODEC_HEVC)
        bitrateOverrideKbps = (base * (percent.coerceIn(10, 100) / 100f)).toInt().coerceAtLeast(1000)
        thermal.baseBitrateKbps = bitrateOverrideKbps
        encoder?.updateBitrate(thermal.suggestedBitrateKbps())
    }

    fun setResolution(width: Int, height: Int, preview: SurfaceTexture?) {
        settings = settings.copy(preferredWidth = width, preferredHeight = height)
        val was = streaming.get()
        openCamera(preview, camera?.controls?.facingFront == true)
        if (was) startStream()
    }

    fun setFpsX100(fpsX100: Int, preview: SurfaceTexture?) {
        settings = settings.copy(fpsX100 = fpsX100)
        val was = streaming.get()
        openCamera(preview, camera?.controls?.facingFront == true)
        if (was) startStream()
    }

    fun cameraControls() = camera?.controls
    fun cameraCapabilities() = camera?.capabilities
    fun applyCameraControls(block: com.obsphonecam.camera.CameraControls.() -> Unit) {
        camera?.applyControls(block)
    }

    fun flipCamera(preview: SurfaceTexture?) {
        val front = !(camera?.controls?.facingFront ?: false)
        preferredLensId = null
        val was = streaming.get()
        openCamera(preview, front)
        if (was) startStream()
    }

    fun setDisplayRotation(degrees: Int) {
        camera?.setDisplayRotation(degrees)
    }

    fun startLocalRecord() {
        if (lastEncodeW <= 0) return
        val fps = camera?.activeFpsRange?.upper ?: 30
        recorder.recordProxy = recordProxyEnabled
        recorder.recordVertical = dualHvEnabled
        recorder.startMaster(
            width = lastEncodeW,
            height = lastEncodeH,
            codecId = encoder?.currentCodec() ?: OpcmProtocol.CODEC_HEVC,
            bitrateKbps = thermal.suggestedBitrateKbps(),
            fps = fps,
            preferHevc = settings.preferHevc || devicePack.preferHevc10
        )
        recordLocal = true
        listener.onStatus("Recording master" + (if (recordProxyEnabled) "+proxy" else "") + (if (dualHvEnabled) "+V" else ""))
    }

    fun stopLocalRecord() {
        if (!recorder.isRecording) return
        val paths = recorder.stop()
        recordLocal = false
        listener.onStatus("Saved ${paths.joinToString(" · ").ifEmpty { "-" }}")
    }

    fun captureRawStill() {
        val id = preferredLensId ?: multiCam.active()?.cameraId
        if (id == null || !capabilities.rawSensor) {
            listener.onStatus("RAW not available on this device")
            return
        }
        val dir = File(context.getExternalFilesDir(null), "raw")
        dir.mkdirs()
        val out = File(dir, "raw_${System.currentTimeMillis()}.dng.bin")
        listener.onStatus("Capturing RAW…")
        rawStill.captureToFile(id, out) { ok, msg ->
            mainHandler.post {
                listener.onStatus(if (ok) "RAW saved $msg" else "RAW failed: $msg")
            }
        }
    }

    fun startStream() {
        val cam = camera ?: run {
            listener.onStatus("Open camera first")
            return
        }
        val rot = cam.outputRotation
        val w = if (rot == 90 || rot == 270) cam.activeSize.height else cam.activeSize.width
        val h = if (rot == 90 || rot == 270) cam.activeSize.width else cam.activeSize.height
        lastEncodeW = w
        lastEncodeH = h
        val fps = cam.activeFpsRange.upper
        encoder?.stop()
        thermal.baseBitrateKbps = bitrateOverrideKbps.takeIf { it > 0 }
            ?: settings.bitrateFor(OpcmProtocol.CODEC_HEVC)
        encoder = VideoEncoder(
            onEncoded = { data, pts, key, cfg, codec ->
                if (!streaming.get()) return@VideoEncoder
                val ok = usb?.sendVideo(data, pts, key, cfg, codec) == true
                if (ok) {
                    framesSent.incrementAndGet()
                    maybeStats(w, h, pts, codec)
                } else {
                    dropped.incrementAndGet()
                    thermal.drops = dropped.get()
                }
                if (recorder.isRecording) recorder.writeMasterEncoded(data, pts, key || cfg)
            },
            onError = {
                SessionLogger.log("Encoder error: $it")
                listener.onStatus(it)
            }
        )
        encoder!!.start(
            width = w,
            height = h,
            bitrateKbps = thermal.suggestedBitrateKbps(),
            fps = fps,
            keyframeInterval = settings.keyframeInterval,
            preferHevc = settings.preferHevc || devicePack.preferHevc10,
            preferMain10 = capabilities.hevc10 && devicePack.preferHevc10
        )
        streaming.set(true)
        framesSent.set(0)
        dropped.set(0)
        thermal.drops = 0
        fpsCounter = 0
        fpsWindowStart = System.nanoTime()
        startAudio()
        SessionLogger.log("Streaming ${w}x${h}@$fps")
        listener.onStatus("Streaming ${w}x${h}@${fps}")
        usb?.send(
            OpcmProtocol.PKT_CONTROL,
            OpcmProtocol.buildControl(
                1,
                listOf(OpcmProtocol.Tlv(OpcmProtocol.CTRL_START_STREAM, ByteArray(0)))
            )
        )
    }

    fun stopStream(sendStop: Boolean = true) {
        streaming.set(false)
        audio?.stop()
        audio = null
        encoder?.stop()
        encoder = null
        if (sendStop) {
            usb?.send(
                OpcmProtocol.PKT_CONTROL,
                OpcmProtocol.buildControl(
                    2,
                    listOf(OpcmProtocol.Tlv(OpcmProtocol.CTRL_STOP_STREAM, ByteArray(0)))
                )
            )
        }
        listener.onStatus(if (usb?.isConnected() == true) "USB connected" else "Waiting for USB…")
    }

    private fun startAudio() {
        audio?.stop()
        audio = null
        if (!audioEnabled) return
        audio = AudioRouter(context) { pcm, rate, ch, level ->
            listener.onAudioLevel(level)
            if (streaming.get() && usb?.isConnected() == true) {
                usb?.sendAudioPcm(pcm, rate, ch)
            }
        }.also { it.source = preferredMic }
        audio?.start()
    }

    fun isStreaming(): Boolean = streaming.get()

    fun reconnectUsb() {
        resumeStreamAfterReconnect = streaming.get() || resumeStreamAfterReconnect
        detachUsb()
        listener.onStatus("Reconnecting…")
        attachUsb()
    }

    fun diagnosticText(): String = buildString {
        appendLine(capabilities.toJson())
        appendLine("pack=${devicePack.family}")
        appendLine("thermal=${thermal.thermalLabel()} bitrate=${thermal.suggestedBitrateKbps()}")
        appendLine("streaming=${streaming.get()} drops=${dropped.get()} fps=${measuredFpsX100 / 100f}")
        appendLine("lens=${multiCam.active()?.kind} id=$preferredLensId")
    }

    private fun onCameraFrame(data: ByteArray, w: Int, h: Int, ptsUs: Long) {
        fpsCounter++
        val now = System.nanoTime()
        if (fpsWindowStart == 0L) fpsWindowStart = now
        val elapsed = now - fpsWindowStart
        if (elapsed >= 1_000_000_000L) {
            measuredFpsX100 = ((fpsCounter * 100_000_000_000.0) / elapsed).toInt()
            fpsCounter = 0
            fpsWindowStart = now
            val adapted = thermal.suggestedBitrateKbps()
            encoder?.updateBitrate(adapted)
        }

        if (autoLensSwitch && !switchingLens) {
            val zoom = (camera?.controls?.zoomRatioX100 ?: 100) / 100f
            val suggested = multiCam.suggestForZoom(zoom)
            if (suggested != null && suggested.cameraId != preferredLensId) {
                preferredLensId = suggested.cameraId
                SessionLogger.log("Auto lens -> ${suggested.kind}")
                mainHandler.post { reopenWithLens(previewTexture) }
            }
        }

        val processed = processing.process(data, w, h)
        listener.onScopeFrame(processed, w, h)
        if (recorder.isRecording) {
            val fps = camera?.activeFpsRange?.upper ?: 30
            recorder.feedProxyNv21(processed, w, h, ptsUs)
            if (dualHvEnabled) {
                recorder.feedVerticalNv21(
                    processed, w, h, ptsUs,
                    preferHevc = settings.preferHevc || devicePack.preferHevc10,
                    fps = fps
                )
            }
        }
        if (!streaming.get()) return
        encoder?.queueNv21(processed, w, h, ptsUs)
    }

    private fun maybeStats(w: Int, h: Int, ptsUs: Long, codec: Int) {
        val now = System.currentTimeMillis()
        if (now - lastStatsAt < 1000) return
        lastStatsAt = now
        val bitrate = thermal.suggestedBitrateKbps()
        usb?.sendStats(
            width = w, height = h, fpsX100 = measuredFpsX100, bitrateKbps = bitrate,
            framesSent = framesSent.get(), dropped = dropped.get(), ptsUs = ptsUs,
            encodeLatencyMs = 0, thermal = thermal.thermalStatus()
        )
        val codecName = if (codec == OpcmProtocol.CODEC_H264) "H264" else "HEVC"
        val lens = multiCam.active()?.kind?.name ?: "?"
        listener.onMetrics(
            "${w}x${h} ${measuredFpsX100 / 100f}fps ${bitrate}kbps $codecName · $lens · ${thermal.thermalLabel()} · drop=${dropped.get()}"
        )
    }

    private fun sendHello() {
        val caps = camera?.capabilities
        val maxSize = caps?.sizes?.firstOrNull()
        var capabilitiesFlags = OpcmProtocol.CAP_H264 or OpcmProtocol.CAP_MANUAL
        if (VideoEncoder.isEncoderAvailable(android.media.MediaFormat.MIMETYPE_VIDEO_HEVC)) {
            capabilitiesFlags = capabilitiesFlags or OpcmProtocol.CAP_HEVC
        }
        sessionId = Random.nextInt()
        usb?.sendHello(
            maxWidth = maxSize?.width ?: 1920,
            maxHeight = maxSize?.height ?: 1080,
            maxFpsX100 = maxSize?.maxFpsX100 ?: 6000,
            maxBitrateKbps = 80000,
            capabilities = capabilitiesFlags,
            sessionId = sessionId,
            deviceName = Build.MODEL.take(24)
        )
    }

    private fun handlePacket(type: Int, payload: ByteArray) {
        when (type) {
            OpcmProtocol.PKT_HELLO -> {
                val ack = OpcmProtocol.buffer(16)
                ack.putInt(1)
                ack.putInt(sessionId)
                ack.putInt(
                    if (VideoEncoder.isEncoderAvailable(android.media.MediaFormat.MIMETYPE_VIDEO_HEVC))
                        OpcmProtocol.CODEC_HEVC else OpcmProtocol.CODEC_H264
                )
                ack.putInt(0)
                usb?.send(OpcmProtocol.PKT_HELLO_ACK, ack.array())
                sendHello()
            }
            OpcmProtocol.PKT_HELLO_ACK -> listener.onStatus("Handshake OK")
            OpcmProtocol.PKT_CONTROL -> applyRemoteControl(payload)
            OpcmProtocol.PKT_GOODBYE -> {
                stopStream(sendStop = false)
                listener.onStatus("Peer goodbye")
            }
        }
    }

    private fun applyRemoteControl(payload: ByteArray) {
        val (msgId, tlvs) = OpcmProtocol.parseControl(payload)
        var applied = 0
        var start = false
        var stop = false
        for (tlv in tlvs) {
            when (tlv.key) {
                OpcmProtocol.CTRL_PRESET -> {
                    applyPreset(OpcmProtocol.readU8(tlv.value)); applied = applied or (1 shl tlv.key)
                }
                OpcmProtocol.CTRL_BITRATE_KBPS -> {
                    bitrateOverrideKbps = OpcmProtocol.readU32(tlv.value)
                    thermal.baseBitrateKbps = bitrateOverrideKbps
                    encoder?.updateBitrate(thermal.suggestedBitrateKbps())
                    applied = applied or (1 shl tlv.key)
                }
                OpcmProtocol.CTRL_TORCH -> {
                    camera?.applyControls { torch = OpcmProtocol.readU8(tlv.value) != 0 }
                    applied = applied or (1 shl tlv.key)
                }
                OpcmProtocol.CTRL_REQUEST_KEYFRAME -> {
                    encoder?.requestKeyframe(); applied = applied or (1 shl tlv.key)
                }
                OpcmProtocol.CTRL_START_STREAM -> start = true
                OpcmProtocol.CTRL_STOP_STREAM -> stop = true
                OpcmProtocol.CTRL_ZOOM_RATIO_X100 -> {
                    camera?.applyControls { zoomRatioX100 = OpcmProtocol.readU32(tlv.value) }
                    applied = applied or (1 shl tlv.key)
                }
            }
        }
        usb?.sendControlAck(msgId, 0, applied)
        when {
            start -> startStream()
            stop -> stopStream(sendStop = false)
        }
    }
}
