package com.obsphonecam

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import com.obsphonecam.audio.MicSource
import com.obsphonecam.camera.CaptureMode
import com.obsphonecam.camera.ColorLook
import com.obsphonecam.camera.DeviceCapabilities
import com.obsphonecam.camera.LensKind
import com.obsphonecam.camera.PhysicalLens
import com.obsphonecam.camera.SizeFps
import com.obsphonecam.creator.QrPairing
import com.obsphonecam.creator.ScenePreset
import com.obsphonecam.creator.ScenePresets
import com.obsphonecam.databinding.ActivityMainBinding
import com.obsphonecam.quality.QualityPresets
import com.obsphonecam.stream.StreamController
import com.obsphonecam.ui.ScopeOverlayView
import com.obsphonecam.usb.UsbStreamService
import com.obsphonecam.util.SessionLogger

class MainActivity : AppCompatActivity(), StreamController.Listener {
    private lateinit var binding: ActivityMainBinding
    private lateinit var stream: StreamController
    private var previewTexture: SurfaceTexture? = null
    private var sizes: List<SizeFps> = emptyList()
    private var lenses: List<PhysicalLens> = emptyList()
    private var micSources: List<MicSource> = emptyList()
    private var locksOn = false
    private var scaleDetector: ScaleGestureDetector? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result[Manifest.permission.CAMERA] == true) bootCamera()
        else binding.statusText.setText(R.string.permission_camera)
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                UsbManager.ACTION_USB_ACCESSORY_ATTACHED -> stream.attachUsb()
                UsbManager.ACTION_USB_ACCESSORY_DETACHED -> {
                    stream.detachUsb()
                    binding.statusText.setText(R.string.status_reconnecting)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        SessionLogger.clear()
        SessionLogger.log("App start Copilot Camera Pro")
        stream = StreamController(this, this)
        setupUi()
        maybeShowOnboarding()
        registerUsbReceiver()
        ensurePermissions()
        stream.attachUsb()
        updateHud()
    }

    private fun maybeShowOnboarding() {
        val prefs = getSharedPreferences("opcm", MODE_PRIVATE)
        if (prefs.getBoolean("onboarding_done", false)) return
        AlertDialog.Builder(this)
            .setTitle(R.string.onboarding_title)
            .setMessage(
                getString(R.string.onboarding_1) + "\n" +
                    getString(R.string.onboarding_2) + "\n" +
                    getString(R.string.onboarding_3) + "\n\n" +
                    "Checklist:\n• Data USB cable\n• OBS source Phone Cam (USB)\n• WinUSB installer if needed"
            )
            .setPositiveButton(R.string.onboarding_ok) { _, _ ->
                prefs.edit().putBoolean("onboarding_done", true).apply()
            }
            .show()
    }

    private fun setupUi() {
        binding.sceneSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, ScenePresets.names())
        binding.modeSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("Live OBS", "Studio Quality"))
        binding.lookSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("Native", "Neutral", "Vivid", "Cinematic", "Soft"))
        binding.presetSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, QualityPresets.names())
        binding.fpsSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("24 fps", "30 fps", "60 fps"))
        binding.fpsSpinner.setSelection(1)
        binding.scopeSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("Hist", "Wave", "Vector", "Off"))
        binding.lensSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("Auto lens"))

        micSources = stream.availableMics()
        binding.micSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, micSources.map { it.name })

        binding.previewView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                previewTexture = st
                applyDisplayRotation()
                if (hasCameraPermission()) bootCamera()
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) = Unit
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                previewTexture = null
                return true
            }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
        }

        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val cur = stream.cameraControls()?.zoomRatioX100 ?: 100
                val next = (cur * detector.scaleFactor).toInt().coerceIn(100, 1000)
                stream.applyCameraControls { zoomRatioX100 = next }
                binding.zoomSeek.progress = ((next - 100) / 9f).toInt().coerceIn(0, 100)
                binding.zoomValue.text = String.format("%.1fx", next / 100f)
                return true
            }
        })
        binding.previewView.setOnTouchListener { _, e ->
            scaleDetector?.onTouchEvent(e)
            if (e.action == MotionEvent.ACTION_UP && scaleDetector?.isInProgress != true) {
                val nx = e.x / binding.previewView.width.coerceAtLeast(1)
                val ny = e.y / binding.previewView.height.coerceAtLeast(1)
                stream.tapFocus(nx, ny)
            }
            true
        }

        binding.streamButton.setOnClickListener {
            if (stream.isStreaming()) {
                stream.stopStream()
                stopService(Intent(this, UsbStreamService::class.java))
                binding.streamButton.setText(R.string.start_stream)
            } else {
                ContextCompat.startForegroundService(this, Intent(this, UsbStreamService::class.java))
                stream.startStream()
                binding.streamButton.setText(R.string.stop_stream)
            }
            updateHud()
        }
        binding.recordButton.setOnClickListener {
            if (stream.isRecording()) {
                stream.stopLocalRecord()
                binding.recordButton.text = "Rec"
            } else {
                if (!stream.isStreaming()) stream.startStream()
                stream.startLocalRecord()
                binding.recordButton.text = "StopRec"
            }
        }
        binding.hvButton.alpha = 0.45f
        binding.hvButton.setOnClickListener {
            val on = binding.hvButton.alpha < 0.9f
            stream.dualHvEnabled = on
            binding.hvButton.alpha = if (on) 1f else 0.45f
            Toast.makeText(this, if (on) "H+V dual record ON" else "H+V OFF", Toast.LENGTH_SHORT).show()
        }
        binding.rawButton.setOnClickListener { stream.captureRawStill() }
        binding.torchButton.setOnClickListener {
            val on = !(stream.cameraControls()?.torch ?: false)
            stream.applyCameraControls { torch = on }
        }
        binding.flipButton.setOnClickListener { stream.flipCamera(previewTexture) }
        binding.lockButton.setOnClickListener {
            locksOn = !locksOn
            stream.applyCameraControls {
                aeLock = locksOn
                afLock = locksOn
                awbLock = locksOn
            }
            binding.lockButton.alpha = if (locksOn) 1f else 0.5f
        }
        binding.eisButton.setOnClickListener {
            val on = binding.eisButton.alpha < 0.9f
            stream.setEis(on)
            binding.eisButton.alpha = if (on) 1f else 0.45f
        }
        binding.trackButton.setOnClickListener {
            val on = binding.trackButton.alpha < 0.9f
            stream.setTracking(on)
            binding.trackButton.alpha = if (on) 1f else 0.45f
        }
        binding.blurButton.setOnClickListener {
            val on = binding.blurButton.alpha < 0.9f
            stream.setBlur(on)
            binding.blurButton.alpha = if (on) 1f else 0.45f
        }
        binding.gridButton.setOnClickListener {
            binding.gridOverlay.enabledGrid = !binding.gridOverlay.enabledGrid
        }
        binding.reconnectButton.setOnClickListener { stream.reconnectUsb() }
        binding.qrButton.setOnClickListener {
            val bmp = QrPairing.renderBitmap(QrPairing.payload())
            val iv = ImageView(this).apply { setImageBitmap(bmp); setPadding(24, 24, 24, 24) }
            AlertDialog.Builder(this).setTitle("Pair / OBS steps").setView(iv)
                .setMessage(QrPairing.payload())
                .setPositiveButton("OK", null).show()
        }
        binding.capsButton.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Device capabilities")
                .setMessage(stream.deviceCapabilities().summaryLines().joinToString("\n") +
                    "\n\nPack: ${stream.devicePack().family}\n${stream.devicePack().notes}")
                .setPositiveButton("Copy") { _, _ ->
                    copyDiag(stream.diagnosticText())
                }
                .setNegativeButton("Close", null).show()
        }
        binding.logButton.setOnClickListener {
            copyDiag(stream.diagnosticText())
            SessionLogger.exportAndShare(this)
        }

        binding.sceneSpinner.setOnItemSelectedListener(SimpleItemSelected { pos ->
            val scene = ScenePreset.values().getOrElse(pos) { ScenePreset.TALKING_HEAD }
            stream.applyScene(scene, previewTexture)
        })
        binding.modeSpinner.setOnItemSelectedListener(SimpleItemSelected { pos ->
            stream.setCaptureMode(if (pos == 1) CaptureMode.STUDIO else CaptureMode.LIVE)
        })
        binding.lookSpinner.setOnItemSelectedListener(SimpleItemSelected { pos ->
            stream.setColorLook(
                when (pos) {
                    1 -> ColorLook.NEUTRAL
                    2 -> ColorLook.VIVID
                    3 -> ColorLook.CINEMATIC
                    4 -> ColorLook.SOFT
                    else -> ColorLook.NATIVE
                }
            )
        })
        binding.presetSpinner.setOnItemSelectedListener(SimpleItemSelected { pos ->
            stream.applyPreset(pos)
            binding.bitrateSeek.progress = 80
        })
        binding.resolutionSpinner.setOnItemSelectedListener(SimpleItemSelected { pos ->
            val s = sizes.getOrNull(pos) ?: return@SimpleItemSelected
            stream.setResolution(s.width, s.height, previewTexture)
        })
        binding.fpsSpinner.setOnItemSelectedListener(SimpleItemSelected { pos ->
            stream.setFpsX100(when (pos) { 0 -> 2400; 2 -> 6000; else -> 3000 }, previewTexture)
        })
        binding.lensSpinner.setOnItemSelectedListener(SimpleItemSelected { pos ->
            if (pos <= 0) return@SimpleItemSelected
            lenses.getOrNull(pos - 1)?.let { stream.selectLens(it.kind, previewTexture) }
        })
        binding.micSpinner.setOnItemSelectedListener(SimpleItemSelected { pos ->
            micSources.getOrNull(pos)?.let { stream.setMicSource(it) }
        })
        binding.scopeSpinner.setOnItemSelectedListener(SimpleItemSelected { pos ->
            binding.scopeOverlay.mode = when (pos) {
                1 -> ScopeOverlayView.Mode.WAVEFORM
                2 -> ScopeOverlayView.Mode.VECTORSCOPE
                3 -> ScopeOverlayView.Mode.OFF
                else -> ScopeOverlayView.Mode.HISTOGRAM
            }
            binding.scopeOverlay.peakingEnabled = pos != 3
        })

        bindSeek(binding.isoSeek) { p ->
            val caps = stream.cameraCapabilities() ?: return@bindSeek
            if (p <= 2) {
                stream.applyCameraControls { iso = 0; exposureNs = 0 }
                binding.isoValue.text = "Auto"
            } else {
                val iso = mapRange(p, caps.isoRange.first, caps.isoRange.last)
                stream.applyCameraControls {
                    this.iso = iso
                    if (exposureNs == 0L) exposureNs = 10_000_000L
                }
                binding.isoValue.text = iso.toString()
            }
        }
        bindSeek(binding.exposureSeek) { p ->
            val caps = stream.cameraCapabilities() ?: return@bindSeek
            if (p <= 2) {
                stream.applyCameraControls { exposureNs = 0; iso = 0 }
                binding.expValue.text = "Auto"
            } else {
                val exp = mapRangeLong(p, caps.exposureRangeNs.first, caps.exposureRangeNs.last)
                stream.applyCameraControls {
                    exposureNs = exp
                    if (iso == 0) iso = caps.isoRange.first
                }
                binding.expValue.text = String.format("1/%.0f", 1_000_000_000.0 / exp.coerceAtLeast(1))
            }
        }
        bindSeek(binding.zoomSeek) { p ->
            val maxZ = stream.cameraCapabilities()?.maxDigitalZoom ?: 1f
            val zoomX100 = (100 + p / 100f * (maxZ * 100 - 100)).toInt().coerceAtLeast(100)
            stream.applyCameraControls { zoomRatioX100 = zoomX100 }
            binding.zoomValue.text = String.format("%.1fx", zoomX100 / 100f)
        }
        bindSeek(binding.focusSeek) { p ->
            val minFocus = stream.cameraCapabilities()?.minFocusDistance ?: 0f
            if (p <= 2 || minFocus <= 0f) stream.applyCameraControls { focusMode = 0; afLock = false }
            else stream.applyCameraControls { focusMode = 2; focusDistance = minFocus * (1f - p / 100f) }
        }
        bindSeek(binding.wbSeek) { p ->
            if (p in 45..55) {
                stream.applyCameraControls { wbMode = 0 }
                binding.wbValue.text = "Auto"
            } else {
                val kelvin = 2000 + (p * 80)
                stream.applyCameraControls { wbMode = 1; wbTempK = kelvin }
                binding.wbValue.text = "${kelvin}K"
            }
        }
        bindSeek(binding.flashSeek) { p ->
            stream.applyCameraControls { flashIntensity = p / 100f; torch = p > 5 }
        }
        bindSeek(binding.bitrateSeek) { p -> stream.setBitratePercent(p.coerceIn(10, 100)) }

        // Theme toggle via long-press HUD
        binding.hudText.setOnLongClickListener {
            val night = AppCompatDelegate.getDefaultNightMode() != AppCompatDelegate.MODE_NIGHT_YES
            AppCompatDelegate.setDefaultNightMode(
                if (night) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
            )
            true
        }
    }

    private fun copyDiag(text: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("opcm-diag", text))
        Toast.makeText(this, "Diagnostic copied", Toast.LENGTH_SHORT).show()
    }

    private fun updateHud() {
        val pack = if (::stream.isInitialized) stream.devicePack().family else "-"
        binding.hudText.text = "Copilot Camera Pro\npack=$pack\n" +
            (if (::stream.isInitialized && stream.isStreaming()) "LIVE" else "IDLE")
    }

    private fun bindSeek(seek: SeekBar, onChange: (Int) -> Unit) {
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) onChange(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
    }

    private fun ensurePermissions() {
        val needed = mutableListOf<String>()
        if (!hasCameraPermission()) needed += Manifest.permission.CAMERA
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            needed += Manifest.permission.RECORD_AUDIO
        }
        if (Build.VERSION.SDK_INT >= 31 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (needed.isEmpty()) {
            if (previewTexture != null) bootCamera()
        } else permissionLauncher.launch(needed.toTypedArray())
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun bootCamera() {
        applyDisplayRotation()
        stream.openCamera(previewTexture, false)
        updateHud()
    }

    private fun applyDisplayRotation() {
        val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.rotation ?: Surface.ROTATION_90
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.rotation
        }
        val degrees = when (rotation) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 90
        }
        stream.setDisplayRotation(degrees)
    }

    private fun registerUsbReceiver() {
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_ACCESSORY_ATTACHED)
            addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(usbReceiver, filter, RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(usbReceiver, filter)
    }

    override fun onDestroy() {
        unregisterReceiver(usbReceiver)
        stream.closeCamera()
        stream.detachUsb()
        stopService(Intent(this, UsbStreamService::class.java))
        super.onDestroy()
    }

    override fun onStatus(text: String) = runOnUiThread {
        binding.statusText.text = text
        updateHud()
    }
    override fun onMetrics(text: String) = runOnUiThread { binding.metricsText.text = text }
    override fun onUsbState(connected: Boolean) = runOnUiThread {
        binding.statusText.setText(if (connected) R.string.status_connected else R.string.status_idle)
    }
    override fun onSizes(sizes: List<SizeFps>) {
        this.sizes = sizes
        runOnUiThread {
            binding.resolutionSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, sizes.map { it.toString() })
        }
    }
    override fun onLenses(lenses: List<PhysicalLens>) {
        this.lenses = lenses
        runOnUiThread {
            val labels = mutableListOf("Auto lens")
            labels += lenses.map {
                when (it.kind) {
                    LensKind.ULTRA_WIDE -> "0.5x Ultra"
                    LensKind.WIDE -> "1x Main"
                    LensKind.TELE -> "Tele"
                    LensKind.OTHER -> "Lens"
                } + " · ${it.focalLengthMm.toInt()}mm"
            }
            binding.lensSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        }
    }
    override fun onAudioLevel(level: Float) = runOnUiThread {
        binding.audioMeter.progress = (level * 100f).toInt().coerceIn(0, 100)
    }
    override fun onScopeFrame(nv21: ByteArray, w: Int, h: Int) {
        binding.scopeOverlay.updateFromNv21(nv21, w, h)
    }
    override fun onCapabilities(caps: DeviceCapabilities) = runOnUiThread { updateHud() }

    private class SimpleItemSelected(val block: (Int) -> Unit) :
        android.widget.AdapterView.OnItemSelectedListener {
        private var first = true
        override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
            if (first) { first = false; return }
            block(position)
        }
        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
    }

    companion object {
        fun mapRange(percent: Int, min: Int, max: Int): Int {
            val t = percent.coerceIn(0, 100) / 100f
            return (min + (max - min) * t).toInt()
        }
        fun mapRangeLong(percent: Int, min: Long, max: Long): Long {
            val t = percent.coerceIn(0, 100) / 100f
            return (min + (max - min) * t).toLong()
        }
    }
}
