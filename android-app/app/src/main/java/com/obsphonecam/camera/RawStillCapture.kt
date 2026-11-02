package com.obsphonecam.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import com.obsphonecam.util.SessionLogger
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * Best-effort RAW_SENSOR still capture for Studio mode when the device exposes it.
 * Does not interrupt the main YUV preview session long-term — opens a one-shot path.
 */
class RawStillCapture(private val context: Context) {
    fun isSupported(cameraId: String): Boolean {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        return try {
            val chars = cm.getCameraCharacteristics(cameraId)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            map?.getOutputSizes(ImageFormat.RAW_SENSOR)?.isNotEmpty() == true
        } catch (_: Exception) {
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun captureToFile(
        cameraId: String,
        outFile: File,
        onDone: (Boolean, String) -> Unit
    ) {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val chars = try {
            cm.getCameraCharacteristics(cameraId)
        } catch (e: Exception) {
            onDone(false, e.message ?: "chars"); return
        }
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val size = map?.getOutputSizes(ImageFormat.RAW_SENSOR)?.maxByOrNull { it.width * it.height }
        if (size == null) {
            onDone(false, "No RAW_SENSOR sizes"); return
        }
        val thread = HandlerThread("RawStill").also { it.start() }
        val handler = Handler(thread.looper)
        val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.RAW_SENSOR, 2)
        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null

        fun cleanup() {
            try { session?.close() } catch (_: Exception) {}
            try { device?.close() } catch (_: Exception) {}
            try { reader.close() } catch (_: Exception) {}
            thread.quitSafely()
        }

        reader.setOnImageAvailableListener({ r ->
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val planes = image.planes
                val buf: ByteBuffer = planes[0].buffer
                val bytes = ByteArray(buf.remaining())
                buf.get(bytes)
                outFile.parentFile?.mkdirs()
                FileOutputStream(outFile).use { it.write(bytes) }
                SessionLogger.log("RAW still saved ${outFile.absolutePath} ${bytes.size}B ${size.width}x${size.height}")
                onDone(true, outFile.absolutePath)
            } catch (e: Exception) {
                onDone(false, e.message ?: "write")
            } finally {
                image.close()
                cleanup()
            }
        }, handler)

        try {
            cm.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(cam: CameraDevice) {
                    device = cam
                    cam.createCaptureSession(listOf(reader.surface), object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(sess: CameraCaptureSession) {
                            session = sess
                            try {
                                val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                                    addTarget(reader.surface)
                                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                }
                                sess.capture(req.build(), null, handler)
                            } catch (e: Exception) {
                                onDone(false, e.message ?: "capture")
                                cleanup()
                            }
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            onDone(false, "RAW session failed")
                            cleanup()
                        }
                    }, handler)
                }

                override fun onDisconnected(cam: CameraDevice) {
                    onDone(false, "disconnected"); cleanup()
                }

                override fun onError(cam: CameraDevice, error: Int) {
                    onDone(false, "error $error"); cleanup()
                }
            }, handler)
        } catch (e: Exception) {
            onDone(false, e.message ?: "open")
            cleanup()
        }
    }
}
