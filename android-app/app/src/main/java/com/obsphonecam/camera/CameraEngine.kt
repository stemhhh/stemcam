package com.obsphonecam.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.RggbChannelVector
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.util.Size
import android.view.Surface
import kotlin.math.max
import kotlin.math.min

class CameraEngine(
    private val context: Context,
    private val onYuvFrame: (data: ByteArray, width: Int, height: Int, ptsUs: Long) -> Unit,
    private val onError: (String) -> Unit
) {
    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var previewSurface: Surface? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var characteristics: CameraCharacteristics? = null
    private var cameraId: String? = null

    var controls = CameraControls()
        private set
    var capabilities: CameraCapabilities? = null
        private set
    var activeSize: Size = Size(1920, 1080)
        private set
    var activeFpsRange: Range<Int> = Range(30, 30)
        private set
    var sensorOrientation: Int = 90
        private set
    /** Degrees to rotate sensor buffer so it is upright for landscape OBS output. */
    var outputRotation: Int = 0
        private set

    private var repeatingBuilder: CaptureRequest.Builder? = null
    private var displayRotationDegrees: Int = 90

    fun startBackground() {
        if (thread != null) return
        thread = HandlerThread("CameraEngine").also { it.start() }
        handler = Handler(thread!!.looper)
    }

    fun stopBackground() {
        thread?.quitSafely()
        thread = null
        handler = null
    }

    fun listCameras(front: Boolean): List<String> {
        val facing = if (front) CameraCharacteristics.LENS_FACING_FRONT
        else CameraCharacteristics.LENS_FACING_BACK
        return manager.cameraIdList.filter { id ->
            manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == facing
        }
    }

    fun probeCapabilities(front: Boolean = controls.facingFront): CameraCapabilities {
        val id = listCameras(front).firstOrNull()
            ?: manager.cameraIdList.first()
        val chars = manager.getCameraCharacteristics(id)
        characteristics = chars
        cameraId = id
        sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        val yuvSizes = map.getOutputSizes(ImageFormat.YUV_420_888) ?: emptyArray()
        val sizes = yuvSizes.map { s ->
            val fps = map.getOutputMinFrameDuration(ImageFormat.YUV_420_888, s)
            val maxFps = if (fps > 0) ((1_000_000_000.0 / fps) * 100).toInt() else 3000
            SizeFps(s.width, s.height, maxFps)
        }.sortedByDescending { it.width.toLong() * it.height }

        val exp = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            ?: Range(1_000_000L, 33_000_000L)
        val iso = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) ?: Range(100, 1600)
        val maxZoom = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
        val flash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        val stabModes = chars.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
        val minFocus = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        val aeComp = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: Range(0, 0)

        val caps = CameraCapabilities(
            exposureRangeNs = exp.lower..exp.upper,
            isoRange = iso.lower..iso.upper,
            focusCalibrationAvailable = minFocus > 0f,
            minFocusDistance = minFocus,
            maxDigitalZoom = maxZoom,
            torchAvailable = flash,
            stabilizationAvailable = stabModes?.contains(
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
            ) == true,
            aeCompensationRange = aeComp.lower..aeComp.upper,
            sizes = sizes
        )
        capabilities = caps
        return caps
    }

    @SuppressLint("MissingPermission")
    fun open(
        previewTexture: SurfaceTexture?,
        width: Int,
        height: Int,
        fpsX100: Int,
        front: Boolean,
        preferredCameraId: String? = null,
        onOpened: () -> Unit
    ) {
        startBackground()
        closeSessionOnly()
        controls.facingFront = front
        val caps = probeCapabilities(front)
        if (preferredCameraId != null && manager.cameraIdList.contains(preferredCameraId)) {
            cameraId = preferredCameraId
            characteristics = manager.getCameraCharacteristics(preferredCameraId)
            sensorOrientation = characteristics?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        }
        val chosen = chooseSize(caps.sizes, width, height)
        // Re-probe sizes for the preferred physical camera when possible
        val map = characteristics?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val physicalSizes = map?.getOutputSizes(ImageFormat.YUV_420_888)?.map {
            SizeFps(it.width, it.height, chosen.maxFpsX100)
        }?.sortedByDescending { it.width.toLong() * it.height }
        val useSize = if (!physicalSizes.isNullOrEmpty()) {
            chooseSize(physicalSizes, width, height)
        } else chosen
        activeSize = Size(useSize.width, useSize.height)
        val targetFps = max(1, fpsX100 / 100)
        activeFpsRange = chooseFps(characteristics!!, targetFps)
        outputRotation = computeOutputRotation(front)

        previewTexture?.setDefaultBufferSize(activeSize.width, activeSize.height)
        previewSurface = previewTexture?.let { Surface(it) }

        imageReader?.close()
        imageReader = ImageReader.newInstance(
            activeSize.width, activeSize.height, ImageFormat.YUV_420_888, 4
        ).also { reader ->
            reader.setOnImageAvailableListener({ r ->
                val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    var bytes = yuv420ToNv21(image)
                    var w = image.width
                    var h = image.height
                    val rot = outputRotation
                    if (rot == 90 || rot == 270) {
                        bytes = rotateNv21(bytes, w, h, rot)
                        val tmp = w
                        w = h
                        h = tmp
                    } else if (rot == 180) {
                        bytes = rotateNv21(bytes, w, h, 180)
                    }
                    onYuvFrame(bytes, w, h, image.timestamp / 1000L)
                } finally {
                    image.close()
                }
            }, handler)
        }

        val id = cameraId!!
        manager.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                camera = device
                createSession(onOpened)
            }

            override fun onDisconnected(device: CameraDevice) {
                device.close()
                camera = null
                onError("Camera disconnected")
            }

            override fun onError(device: CameraDevice, error: Int) {
                device.close()
                camera = null
                onError("Camera error $error")
            }
        }, handler)
    }

    private fun createSession(onOpened: () -> Unit) {
        val device = camera ?: return
        val surfaces = mutableListOf<Surface>()
        previewSurface?.let { surfaces += it }
        surfaces += imageReader!!.surface

        device.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                this@CameraEngine.session = session
                startRepeating()
                onOpened()
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                onError("Capture session configure failed")
            }
        }, handler)
    }

    fun setDisplayRotation(degrees: Int) {
        displayRotationDegrees = ((degrees % 360) + 360) % 360
        outputRotation = computeOutputRotation(controls.facingFront)
    }

    private fun computeOutputRotation(front: Boolean): Int {
        // JPEG orientation formula so landscape-held phone produces upright frames.
        return if (front) {
            (sensorOrientation + displayRotationDegrees) % 360
        } else {
            (sensorOrientation - displayRotationDegrees + 360) % 360
        }
    }

    fun applyControls(update: CameraControls.() -> Unit) {
        controls.update()
        startRepeating()
    }

    /** Tap-to-focus at normalized preview coords 0..1. */
    fun tapToFocus(nx: Float, ny: Float) {
        val chars = characteristics ?: return
        val sensor = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
        val x = (nx.coerceIn(0f, 1f) * sensor.width()).toInt()
        val y = (ny.coerceIn(0f, 1f) * sensor.height()).toInt()
        val half = min(sensor.width(), sensor.height()) / 10
        val region = android.hardware.camera2.params.MeteringRectangle(
            (x - half).coerceAtLeast(sensor.left),
            (y - half).coerceAtLeast(sensor.top),
            half * 2,
            half * 2,
            android.hardware.camera2.params.MeteringRectangle.METERING_WEIGHT_MAX
        )
        controls.focusMode = 1
        controls.afLock = false
        val device = camera ?: return
        val sess = session ?: return
        try {
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            previewSurface?.let { builder.addTarget(it) }
            builder.addTarget(imageReader!!.surface)
            applyManualControls(builder)
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(region))
            builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(region))
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
            sess.capture(builder.build(), null, handler)
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
            sess.setRepeatingRequest(builder.build(), null, handler)
        } catch (_: Exception) {
        }
    }

    fun startRepeating() {
        val device = camera ?: return
        val sess = session ?: return
        val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
        previewSurface?.let { builder.addTarget(it) }
        builder.addTarget(imageReader!!.surface)
        builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, activeFpsRange)

        applyManualControls(builder)
        repeatingBuilder = builder
        sess.setRepeatingRequest(builder.build(), null, handler)
    }

    private fun applyManualControls(builder: CaptureRequest.Builder) {
        val c = controls
        val chars = characteristics

        if (c.exposureNs > 0 && c.iso > 0) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, c.exposureNs)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, c.iso)
        } else {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            builder.set(
                CaptureRequest.CONTROL_AE_LOCK,
                c.aeLock
            )
        }

        when {
            c.afLock || c.focusMode == 2 -> {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                if (c.focusMode == 2) {
                    builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, c.focusDistance)
                }
            }
            c.focusMode == 3 -> {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0f)
            }
            c.focusMode == 1 -> builder.set(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_AUTO
            )
            else -> builder.set(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
            )
        }

        if (c.wbMode == 1) {
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
            builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
            builder.set(CaptureRequest.COLOR_CORRECTION_GAINS, kelvinToRggb(c.wbTempK))
        } else {
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AWB_LOCK, c.awbLock)
        }

        val oisModes = chars?.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
        if (oisModes?.contains(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON) == true) {
            builder.set(
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                if (c.ois) CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
                else CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF
            )
        }

        val maxZoom = chars?.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
        val zoom = min(maxZoom, max(1f, c.zoomRatioX100 / 100f))
        val sensor = chars?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        if (sensor != null && zoom > 1f) {
            val cx = sensor.width() / 2
            val cy = sensor.height() / 2
            val hw = (sensor.width() / (2f * zoom)).toInt()
            val hh = (sensor.height() / (2f * zoom)).toInt()
            builder.set(
                CaptureRequest.SCALER_CROP_REGION,
                android.graphics.Rect(cx - hw, cy - hh, cx + hw, cy + hh)
            )
        }

        if (c.torch && capabilities?.torchAvailable == true) {
            builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                try {
                    val levels = chars?.get(
                        CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL
                    ) ?: 1
                    if (levels > 1) {
                        val strength = (1 + c.flashIntensity.coerceIn(0f, 1f) * (levels - 1)).toInt()
                        builder.set(CaptureRequest.FLASH_STRENGTH_LEVEL, strength)
                    }
                } catch (_: Exception) {
                }
            }
        } else {
            builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
        }

        if (c.stabilization && capabilities?.stabilizationAvailable == true) {
            builder.set(
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
            )
        } else {
            builder.set(
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
            )
        }
    }

    fun close() {
        closeSessionOnly()
        camera?.close()
        camera = null
        imageReader?.close()
        imageReader = null
        previewSurface?.release()
        previewSurface = null
        stopBackground()
    }

    private fun closeSessionOnly() {
        try {
            session?.stopRepeating()
        } catch (_: Exception) {
        }
        session?.close()
        session = null
    }

    companion object {
        fun chooseSize(sizes: List<SizeFps>, wantW: Int, wantH: Int): SizeFps {
            if (sizes.isEmpty()) return SizeFps(1280, 720, 3000)
            if (wantW <= 0 || wantH <= 0) return sizes.first()
            val exact = sizes.firstOrNull { it.width == wantW && it.height == wantH }
            if (exact != null) return exact
            // Prefer same aspect, then closest pixel count without going far above request for High/Balanced
            val targetPixels = wantW.toLong() * wantH
            return sizes.minByOrNull {
                val pixels = it.width.toLong() * it.height
                kotlin.math.abs(pixels - targetPixels)
            } ?: sizes.first()
        }

        fun chooseFps(chars: CameraCharacteristics, target: Int): Range<Int> {
            val ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                ?: return Range(target, target)
            val exact = ranges.firstOrNull { it.lower == target && it.upper == target }
            if (exact != null) return exact
            return ranges.minByOrNull {
                kotlin.math.abs(it.upper - target) + kotlin.math.abs(it.lower - target)
            } ?: Range(30, 30)
        }

        fun kelvinToRggb(kelvin: Int): RggbChannelVector {
            val temp = kelvin.coerceIn(2000, 10000) / 100f
            var r: Float
            var g: Float
            var b: Float
            if (temp <= 66f) {
                r = 255f
                g = (99.4708025861f * kotlin.math.ln(temp) - 161.1195681661f)
                b = if (temp <= 19f) 0f else (138.5177312231f * kotlin.math.ln(temp - 10f) - 305.0447927307f)
            } else {
                r = (329.698727446f * Math.pow((temp - 60f).toDouble(), -0.1332047592).toFloat())
                g = (288.1221695283f * Math.pow((temp - 60f).toDouble(), -0.0755148492).toFloat())
                b = 255f
            }
            r = r.coerceIn(0f, 255f) / 128f
            g = g.coerceIn(0f, 255f) / 128f
            b = b.coerceIn(0f, 255f) / 128f
            return RggbChannelVector(r, g, g, b)
        }

        fun yuv420ToNv21(image: android.media.Image): ByteArray {
            val width = image.width
            val height = image.height
            val ySize = width * height
            val out = ByteArray(ySize + ySize / 2)
            val yPlane = image.planes[0]
            val uPlane = image.planes[1]
            val vPlane = image.planes[2]
            val yBuf = yPlane.buffer
            val yRow = yPlane.rowStride
            var outPos = 0
            if (yRow == width) {
                yBuf.get(out, 0, ySize)
                outPos = ySize
            } else {
                var pos = 0
                for (row in 0 until height) {
                    yBuf.position(pos)
                    yBuf.get(out, outPos, width)
                    outPos += width
                    pos += yRow
                }
            }

            // NV21 = Y + interleaved VU
            val vBuf = vPlane.buffer
            val uBuf = uPlane.buffer
            val vRow = vPlane.rowStride
            val uRow = uPlane.rowStride
            val vPix = vPlane.pixelStride
            val uPix = uPlane.pixelStride
            val chromaHeight = height / 2
            val chromaWidth = width / 2
            for (row in 0 until chromaHeight) {
                for (col in 0 until chromaWidth) {
                    val vIndex = row * vRow + col * vPix
                    val uIndex = row * uRow + col * uPix
                    out[outPos++] = vBuf.get(vIndex)
                    out[outPos++] = uBuf.get(uIndex)
                }
            }
            return out
        }

        fun rotateNv21(yuv: ByteArray, width: Int, height: Int, rotation: Int): ByteArray {
            if (rotation == 0) return yuv
            val frameSize = width * height
            val out = ByteArray(yuv.size)
            when (rotation) {
                90 -> {
                    var i = 0
                    for (x in 0 until width) {
                        for (y in height - 1 downTo 0) {
                            out[i++] = yuv[y * width + x]
                        }
                    }
                    var ci = frameSize
                    for (x in 0 until width step 2) {
                        for (y in height / 2 - 1 downTo 0) {
                            val src = frameSize + y * width + x
                            out[ci++] = yuv[src]
                            out[ci++] = yuv[src + 1]
                        }
                    }
                    return out
                }
                180 -> {
                    for (i in 0 until frameSize) {
                        out[i] = yuv[frameSize - 1 - i]
                    }
                    val chroma = frameSize / 2
                    for (i in 0 until chroma step 2) {
                        out[frameSize + i] = yuv[frameSize + chroma - 2 - i]
                        out[frameSize + i + 1] = yuv[frameSize + chroma - 1 - i]
                    }
                    return out
                }
                270 -> {
                    var i = 0
                    for (x in width - 1 downTo 0) {
                        for (y in 0 until height) {
                            out[i++] = yuv[y * width + x]
                        }
                    }
                    var ci = frameSize
                    for (x in width - 2 downTo 0 step 2) {
                        for (y in 0 until height / 2) {
                            val src = frameSize + y * width + x
                            out[ci++] = yuv[src]
                            out[ci++] = yuv[src + 1]
                        }
                    }
                    return out
                }
                else -> return yuv
            }
        }
    }
}
