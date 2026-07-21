package com.example.babyguard

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import com.jiangdg.ausbc.MultiCameraClient
import com.jiangdg.ausbc.callback.ICameraStateCallBack
import com.jiangdg.ausbc.callback.IDeviceConnectCallBack
import com.jiangdg.ausbc.callback.IPreviewDataCallBack
import com.jiangdg.ausbc.camera.bean.CameraRequest
import com.serenegiant.usb.USBMonitor
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@SuppressLint("SetTextI18n")
class CameraActivity : AppCompatActivity() {

    private lateinit var prefs: AppPreferences
    private lateinit var aiGovernor: AIGovernor
    private lateinit var safetyPipeline: SafetyPipeline
    private lateinit var yoloDetector: YoloDetector
    private lateinit var emotionDetector: EmotionDetector
    private lateinit var mediaPipeDetector: MediaPipeDetector
    private lateinit var audioListener: AudioListener
    private lateinit var motionDetector: MotionDetector
    private lateinit var qrScanner: QrScanner
    private lateinit var viewFinder: PreviewView
    private lateinit var overlayView: OverlayView
    private lateinit var tvStatus: TextView
    private lateinit var tvHudBattery: TextView
    private lateinit var tvHudFps: TextView
    private lateinit var tvHudMotion: TextView
    private lateinit var tvHudStage: TextView
    private lateinit var tvHudSound: TextView
    private lateinit var tvHudMood: TextView
    private lateinit var tvHudTemp: TextView
    private lateinit var blackScreenOverlay: FrameLayout
    private lateinit var cameraExecutor: ExecutorService
    // YOLO inference gets its OWN single-thread executor, separate from cameraExecutor.
    // cameraExecutor is shared by the camera analyzer callback, video-frame JPEG encode +
    // socket write, and audio reconnects — all serialized on one thread. On hardware where
    // YOLO inference is slow (e.g. Note 9: no GPU/NNAPI delegate, CPU-only float32 model),
    // running inference inline on that same thread stalls EVERYTHING else for the full
    // inference duration, which is what produces the "1 FPS" reading even well below the
    // thermal-throttle temperature — the bottleneck is thread contention + raw compute time,
    // not the configured scan-rate delay. Decoupling means video push/audio/motion bookkeeping
    // keep running at full camera-driven rate while YOLO grinds in the background.
    private val yoloExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    @Volatile private var yoloInFlight = false

    var parentIpAddress: String? = null
    private var isPaired = false
    private var isCurrentlyStreaming = false
    private var alertClient: AlertClient? = null
    private lateinit var usbCameraManager: UsbCameraManager
    private var usingUsbCamera = false
    private var currentCameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

    // ── Real UVC (USB) camera decode ──────────────────────────────────────────
    // usbCameraManager (above) is kept only for attach/detach UI hints (showing/hiding the
    // camera-selector entry) — actual permission + decode goes through MultiCameraClient,
    // since only its own USBMonitor can hand back a USBMonitor.UsbControlBlock.
    private lateinit var multiCameraClient: MultiCameraClient
    private var uvcCamera: MultiCameraClient.Camera? = null
    // Plain TextureView, manually box-fit via applyUvcAspectFit() once the camera reports its
    // real preview size. AspectRatioTextureView (bundled in libausbc) was tried here previously,
    // but its setAspectRatio() applies an orientation-aware inversion meant for rotation-coupled
    // built-in sensors — wrong for a fixed-orientation external UVC camera, and a likely
    // contributor to the "too zoomed in" / off-center look. Computing our own centered
    // FrameLayout.LayoutParams instead gives full, predictable control over size and centering.
    private lateinit var usbTextureView: android.view.TextureView
    @Volatile private var uvcPreviewSize: com.jiangdg.ausbc.camera.bean.PreviewSize? = null
    // Promoted from startCamera()'s local val so switching to the USB camera can unbind CameraX.
    private var cameraProvider: ProcessCameraProvider? = null

    private var lastScanTime       = 0L
    private var lastYoloScanTime   = 0L
    private var lastMogScanTime    = 0L      // independent MOG cadence
    private var lastVideoFrameTime = 0L
    private var lastTelemetryTime  = 0L
    private var lastAlertTime      = 0L
    private var lastFaceCoverWarningTime = 0L
    private val FACE_COVER_WARNING_COOLDOWN = 20_000L   // was 60s — too long a blind spot before a re-cover gets a fresh warning
    private var lastCryWarningTime = 0L
    private val CRY_WARNING_COOLDOWN = 20_000L          // was 60s, same reasoning

    // ── Audio crying sustain tracker ──────────────────────────────────────────
    // Require baby to cry continuously for CRYING_SUSTAIN_MS before escalating to HIGH.
    // This prevents brief coughs or squeaks from triggering a critical alert.
    private val CRYING_SUSTAIN_MS = 3_000L   // 3 seconds sustained cry
    private var cryingStartTime   = 0L       // 0 = not currently crying
    private var sustainedCryFired = false    // true once the sustained-cry alert has fired this episode
    private var videoSocket: Socket? = null
    private var videoOutputStream: DataOutputStream? = null
    private var audioStreamingSocket: Socket? = null
    private var audioStreamingOutputStream: DataOutputStream? = null
    // NOTE: NO separate AudioRecord here — AudioListener owns the single MIC capture.
    // Streaming is done via AudioListener.onAudioChunk callback.

    private var currentMotionIntensity = 0
    private var currentSoundIntensity  = 0
    private var currentDetectedMood    = "Searching..."
    private var currentDetectedPosture = "None"
    private var currentStatus          = "🟢 Monitoring"
    private var currentTier            = "LOW"
    private var currentAction          = "Normal"
    private var currentRiskScore       = 0f

    private var frameCount = 0; private var lastFpsCheckTime = 0L; private var currentFps = 0

    // ── Crib calibration ──────────────────────────────────────────────────────
    private lateinit var calibManager: CribCalibrationManager

    /** Launches CribCalibrationActivity; on RESULT_OK recomputes + pushes the new homography. */
    private val calibLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            prefs.loadCribCorners()?.let { corners ->
                calibManager.computeHomography(corners)
                yoloDetector.setCalibration(calibManager)
            }
            // CribCalibrationActivity briefly owned the camera and left the MOG2 background
            // stale — reset both the background model and the pipeline state so the first
            // frames after returning don't saturate the foreground mask or carry stale streaks.
            safetyPipeline.forceReset()
            motionDetector.resetBackground()
            findViewById<View>(R.id.tvCalibBanner)?.isGone = true
            findViewById<View>(R.id.btnClearCalib)?.isVisible = true
            Toast.makeText(this, "✅ Crib calibration saved — angle correction active", Toast.LENGTH_LONG).show()
        }
        // Always restart the camera after returning from calibration.
        // CribCalibrationActivity calls provider.unbindAll() before binding to itself, which
        // also kills CameraActivity's CameraX preview binding — without this the viewFinder
        // stays black until the user manually flips the camera selector.
        if (!usingUsbCamera) startCamera()
    }

    private val batteryManager by lazy {
        getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
    }
    private fun getBatteryPercentage(): Int =
        batteryManager.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)

    private val MAX_AUTO_ATTEMPTS = 3

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_camera)

        prefs = AppPreferences(this)

        // ── USB camera manager ─────────────────────────────────────────────────
        usbCameraManager = UsbCameraManager(
            context            = this,
            onCameraAttached   = { device ->
                runOnUiThread {
                    val btn = findViewById<android.widget.Button>(R.id.btnCameraSelector)
                    btn?.text = "🔌 ${usbCameraManager.getCameraLabel(device).take(12)}…"
                    btn?.backgroundTintList = android.content.res.ColorStateList.valueOf(
                        Color.parseColor("#4000D4AA"))
                    Toast.makeText(this,
                        "USB camera detected: ${device.productName}",
                        Toast.LENGTH_SHORT).show()
                }
            },
            onCameraDetached   = { _ ->
                runOnUiThread {
                    val btn = findViewById<android.widget.Button>(R.id.btnCameraSelector)
                    btn?.text = "📷 Internal Camera"
                    btn?.backgroundTintList = android.content.res.ColorStateList.valueOf(
                        Color.parseColor("#22FFFFFF"))
                    if (usingUsbCamera) {
                        usingUsbCamera = false
                        startCamera()  // fall back to built-in
                        Toast.makeText(this, "USB camera disconnected — using built-in", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            // NOTE: usbCameraManager's own permission flow is no longer used to open the camera —
            // it only ever held a raw UsbDeviceConnection and could never feed a real decoder.
            // Opening now always goes through multiCameraClient.requestPermission() below, whose
            // onConnectDev callback is the actual "ready to decode" signal (see startUvcDecode).
            onPermissionResult = { _, granted ->
                if (!granted) {
                    runOnUiThread {
                        Toast.makeText(this, "USB camera permission denied", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
        usbCameraManager.init()

        // ── Real UVC decode engine ─────────────────────────────────────────────
        // Separate from usbCameraManager above: MultiCameraClient owns its own USBMonitor and
        // is the only way to obtain a USBMonitor.UsbControlBlock, which real video decode
        // requires. onConnectDev fires once permission is granted AND the device is actually
        // ready — that's the moment we hand off to startUvcDecode().
        multiCameraClient = MultiCameraClient(usbMonitorSafeContext(), object : IDeviceConnectCallBack {
            override fun onAttachDev(device: android.hardware.usb.UsbDevice?) {}
            override fun onDetachDec(device: android.hardware.usb.UsbDevice?) {}
            override fun onConnectDev(
                device: android.hardware.usb.UsbDevice?,
                ctrlBlock: USBMonitor.UsbControlBlock?
            ) {
                if (device == null || ctrlBlock == null) return
                runOnUiThread { startUvcDecode(device, ctrlBlock) }
            }
            override fun onDisConnectDec(
                device: android.hardware.usb.UsbDevice?,
                ctrlBlock: USBMonitor.UsbControlBlock?
            ) {
                runOnUiThread { if (usingUsbCamera) switchToInternalCamera() }
            }
            override fun onCancelDev(device: android.hardware.usb.UsbDevice?) {
                runOnUiThread {
                    Toast.makeText(this@CameraActivity, "USB camera permission denied", Toast.LENGTH_SHORT).show()
                }
            }
        })
        multiCameraClient.register()

        // Manifest-declared USB_DEVICE_ATTACHED intent-filter means Android can launch (or
        // re-deliver to) this Activity directly when a UVC device is plugged in — handle that
        // here so tapping the system "Open BabyGuard?" chooser dialog actually connects the
        // camera instead of just opening the app to whatever screen it was already on.
        handleUsbAttachIntent(intent)

        viewFinder         = findViewById(R.id.viewFinder)
        usbTextureView     = findViewById(R.id.usbTextureView)
        overlayView        = findViewById(R.id.overlayView)
        tvStatus           = findViewById(R.id.tvStatus)
        tvHudBattery       = findViewById(R.id.tvHudBattery)
        tvHudFps           = findViewById(R.id.tvHudFps)
        tvHudMotion        = findViewById(R.id.tvHudMotion)
        tvHudStage         = findViewById(R.id.tvHudStage)
        tvHudSound         = findViewById(R.id.tvHudSound)
        tvHudMood          = findViewById(R.id.tvHudMood)
        tvHudTemp          = findViewById(R.id.tvHudTemp)
        blackScreenOverlay = findViewById(R.id.blackScreenOverlay)

        applyRoundedCorners(viewFinder, 28f)
        applyRoundedCorners(overlayView, 28f)

        val switchPauseAi   = findViewById<SwitchCompat>(R.id.switchPauseAiStream)
        val switchSleepMode = findViewById<SwitchCompat>(R.id.switchSleepMode)

        switchPauseAi.isChecked = prefs.pauseAiDuringStream
        switchPauseAi.setOnCheckedChangeListener { _, v -> prefs.pauseAiDuringStream = v }
        val ivBurnInShield  = findViewById<android.widget.ImageView>(R.id.ivBurnInShield)

        switchSleepMode.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                blackScreenOverlay.isVisible = true
                window.attributes = window.attributes.apply { screenBrightness = 0.0f }
                ivBurnInShield.startAnimation(
                    android.view.animation.AlphaAnimation(0.2f, 0.6f).apply {
                        duration = 3000; repeatMode = android.view.animation.Animation.REVERSE
                        repeatCount = android.view.animation.Animation.INFINITE
                    })
            } else {
                blackScreenOverlay.isGone = true
                ivBurnInShield.clearAnimation()
                window.attributes = window.attributes.apply {
                    screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                }
            }
        }

        val gestureDetector = GestureDetector(this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    blackScreenOverlay.isGone = true
                    switchSleepMode.isChecked = false
                    window.attributes = window.attributes.apply {
                        screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                    }
                    return true
                }
            })
        blackScreenOverlay.setOnTouchListener { _, ev -> gestureDetector.onTouchEvent(ev); true }
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        // ── Camera flip button (front ↔ back) — mimics a native camera app's
        // flip transition with a quick 180° spin on the button itself ─────────
        findViewById<android.widget.ImageButton>(R.id.btnFlipCamera).setOnClickListener {
            it.animate()
                .rotationBy(180f)
                .setDuration(350L)
                .setInterpolator(android.view.animation.AccelerateDecelerateInterpolator())
                .start()
            flipLens()
        }

        // ── AI Mode buttons ────────────────────────────────────────────────────
        val btnEco  = findViewById<android.widget.Button>(R.id.btnModeEco)
        val btnBal  = findViewById<android.widget.Button>(R.id.btnModeBalanced)
        val btnPro  = findViewById<android.widget.Button>(R.id.btnModePerformance)

        fun refreshModeButtons(mode: AppPreferences.AIMode) {
            val on  = "#4000D4AA"; val off = "#22FFFFFF"
            btnEco.backgroundTintList = android.content.res.ColorStateList.valueOf(
                Color.parseColor(if (mode == AppPreferences.AIMode.ECO)         on else off))
            btnBal.backgroundTintList = android.content.res.ColorStateList.valueOf(
                Color.parseColor(if (mode == AppPreferences.AIMode.BALANCED)    on else off))
            btnPro.backgroundTintList = android.content.res.ColorStateList.valueOf(
                Color.parseColor(if (mode == AppPreferences.AIMode.PERFORMANCE) on else off))
        }

        btnEco.setOnClickListener  { aiGovernor.setMode(AppPreferences.AIMode.ECO);         refreshModeButtons(AppPreferences.AIMode.ECO) }
        btnBal.setOnClickListener  { aiGovernor.setMode(AppPreferences.AIMode.BALANCED);    refreshModeButtons(AppPreferences.AIMode.BALANCED) }
        btnPro.setOnClickListener  { aiGovernor.setMode(AppPreferences.AIMode.PERFORMANCE); refreshModeButtons(AppPreferences.AIMode.PERFORMANCE) }
        refreshModeButtons(prefs.aiMode)

        // ── Sensitivity buttons ────────────────────────────────────────────────
        val btnSensLow    = findViewById<android.widget.Button>(R.id.btnSensLow)
        val btnSensNormal = findViewById<android.widget.Button>(R.id.btnSensNormal)
        val btnSensHigh   = findViewById<android.widget.Button>(R.id.btnSensHigh)

        fun refreshSensButtons(sens: Int) {
            val on = "#4000D4AA"; val off = "#22FFFFFF"
            btnSensLow.backgroundTintList    = android.content.res.ColorStateList.valueOf(Color.parseColor(if (sens == 1) on else off))
            btnSensNormal.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor(if (sens == 2) on else off))
            btnSensHigh.backgroundTintList   = android.content.res.ColorStateList.valueOf(Color.parseColor(if (sens == 3) on else off))
        }

        btnSensLow.setOnClickListener    { prefs.motionSensitivity = 1; safetyPipeline.updateSensitivity(1); refreshSensButtons(1) }
        btnSensNormal.setOnClickListener { prefs.motionSensitivity = 2; safetyPipeline.updateSensitivity(2); refreshSensButtons(2) }
        btnSensHigh.setOnClickListener   { prefs.motionSensitivity = 3; safetyPipeline.updateSensitivity(3); refreshSensButtons(3) }
        refreshSensButtons(prefs.motionSensitivity)

        // ── Camera selector button ─────────────────────────────────────────────
        val btnCam = findViewById<android.widget.Button>(R.id.btnCameraSelector)
        btnCam.setOnClickListener {
            if (usingUsbCamera) {
                // Switch back to built-in
                switchToInternalCamera()
            } else {
                // Show USB camera picker
                val cameras = usbCameraManager.listUvcCameras()
                if (cameras.isEmpty()) {
                    Toast.makeText(this,
                        "No USB camera detected. Connect a UVC-compatible camera.",
                        Toast.LENGTH_LONG).show()
                } else {
                    val labels = cameras.map { usbCameraManager.getCameraLabel(it) }.toTypedArray()
                    android.app.AlertDialog.Builder(this)
                        .setTitle("🔌 Select Camera Source")
                        .setItems(arrayOf("📷 Built-in Camera") + labels) { _, idx ->
                            if (idx == 0) {
                                switchToInternalCamera()
                            } else {
                                val device = cameras[idx - 1]
                                // Always go through MultiCameraClient's own USBMonitor — its
                                // onConnectDev callback (wired in onCreate) is the only source
                                // of a usable USBMonitor.UsbControlBlock, and fires whether or
                                // not permission was already granted in a previous session.
                                multiCameraClient.requestPermission(device)
                            }
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
        }
        // Pre-scan for already-attached USB cameras
        val attached = usbCameraManager.listUvcCameras()
        if (attached.isNotEmpty()) {
            btnCam.text = "🔌 USB Camera"
            btnCam.backgroundTintList = android.content.res.ColorStateList.valueOf(
                Color.parseColor("#4000D4AA"))
        }

        // ── Init AI stack ──────────────────────────────────────────────────────
        org.opencv.android.OpenCVLoader.initDebug()
        aiGovernor        = AIGovernor(this)
        motionDetector    = MotionDetector()
        yoloDetector      = YoloDetector(this)
        emotionDetector   = EmotionDetector(this)
        mediaPipeDetector = MediaPipeDetector(this)
        audioListener     = AudioListener(this)
        qrScanner         = QrScanner()
        cameraExecutor    = Executors.newSingleThreadExecutor()
        safetyPipeline    = SafetyPipeline(
            motionDetector, yoloDetector, emotionDetector, aiGovernor,
            sensitivity           = prefs.motionSensitivity,
            babyPostureDetector   = BabyPostureDetector(this)
        )

        // ── Crib calibration: load saved corners and push homography to YOLO ──
        calibManager = CribCalibrationManager()
        prefs.loadCribCorners()?.let { corners ->
            calibManager.computeHomography(corners)
            yoloDetector.setCalibration(calibManager)
            Log.i("BabyGuard", "Crib calibration loaded — homography active")
        }

        // Calibrate button (small icon button in the controls area)
        findViewById<View>(R.id.btnCalibrateRoi)?.setOnClickListener {
            calibLauncher.launch(Intent(this, CribCalibrationActivity::class.java))
        }

        // Clear calibration button — removes saved corners and disables homography correction
        findViewById<View>(R.id.btnClearCalib)?.setOnClickListener {
            prefs.clearCribCalibration()
            yoloDetector.setCalibration(null)  // disable perspective correction
            calibManager.release()             // release the OpenCV Mat
            safetyPipeline.forceReset()
            motionDetector.resetBackground()
            findViewById<View>(R.id.tvCalibBanner)?.isVisible = true
            Toast.makeText(this,
                "Calibration cleared — using default posture detection",
                Toast.LENGTH_LONG).show()
        }

        // Long-press the clear-calibration button to manually calibrate the POSTURE
        // reference (teach the app what the baby looks like when supine).
        // Point the camera at the sleeping baby first, then long-press.
        findViewById<View>(R.id.btnClearCalib)?.setOnLongClickListener {
            val lastDetection = yoloDetector.getLastDetection()
            if (lastDetection != null) {
                yoloDetector.calibrateSupineNow(lastDetection)
                Toast.makeText(this,
                    "✅ Posture calibrated — supine reference saved",
                    Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this,
                    "⚠️ No baby detected yet — point camera at sleeping baby first",
                    Toast.LENGTH_LONG).show()
            }
            true
        }

        // Hide the clear button when not calibrated (nothing to clear)
        findViewById<View>(R.id.btnClearCalib)?.isVisible = prefs.isCribCalibrated

        // Show uncalibrated banner if no calibration saved yet
        val tvCalibBanner = findViewById<TextView>(R.id.tvCalibBanner)
        if (tvCalibBanner != null) {
            tvCalibBanner.isVisible = !prefs.isCribCalibrated
            tvCalibBanner.setOnClickListener {
                calibLauncher.launch(Intent(this, CribCalibrationActivity::class.java))
            }
        }

        val neededPermissions = listOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        val missing = neededPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1001)
        } else {
            tryAutoReconnect()
            startCamera()
        }
        audioListener.startListening()
        // 60 FPS overlay render loop — makes skeleton smooth independent of YOLO rate
        overlayView.startRenderLoop()
    }

    // ── USB attach intent (Note 9 / cold-launch via system chooser) ────────────

    /**
     * Fires when the Activity is already running and Android redelivers a fresh
     * USB_DEVICE_ATTACHED intent to it (e.g. plugging the webcam back in while this screen is
     * already open). onCreate only sees the intent that *launched* the Activity, so without
     * this override a same-instance re-attach would silently do nothing.
     */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleUsbAttachIntent(intent)
    }

    /**
     * The "Open BabyGuard to handle [device name]?" system dialog (seen e.g. on the Note 9,
     * where some cheap UVC webcams lack a USB iProduct string descriptor, so Android substitutes
     * "null"/the vendor ID for the device name in that dialog — a generic Android quirk with
     * bare-bones webcams, not a Note-9-specific bug) launches this Activity via the
     * USB_DEVICE_ATTACHED intent-filter declared in the manifest. Previously nothing here ever
     * read that intent, so tapping "Open" just opened the app to whatever it would normally
     * show — the camera never actually got requested. This reads the attached UsbDevice out of
     * the intent and pushes it straight into the same MultiCameraClient permission flow the
     * camera-selector button uses, so tapping the system dialog actually connects the camera.
     */
    private fun handleUsbAttachIntent(intent: android.content.Intent?) {
        if (intent?.action != android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED) return
        val device = intent.getParcelableExtra<android.hardware.usb.UsbDevice>(
            android.hardware.usb.UsbManager.EXTRA_DEVICE) ?: return
        Log.i("BabyGuard", "USB_DEVICE_ATTACHED intent received for ${device.deviceName}; requesting permission")
        multiCameraClient.requestPermission(device)
    }

    // ── Permission result ──────────────────────────────────────────────────────

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001) {
            val cameraGranted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            if (cameraGranted) {
                tryAutoReconnect()
                startCamera()
            } else {
                Toast.makeText(this, "Camera permission required", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ── Auto-reconnect ─────────────────────────────────────────────────────────

    private fun tryAutoReconnect() {
        val savedIp = prefs.lastPairedIp
        if (savedIp.isBlank()) return
        Thread {
            repeat(MAX_AUTO_ATTEMPTS) { attempt ->
                if (isPaired) return@Thread
                try {
                    Socket().apply { connect(java.net.InetSocketAddress(savedIp, 8888), 1500) }.close()
                    isPaired = true; parentIpAddress = savedIp
                    alertClient = AlertClient(
                        savedIp, 8888,
                        onCommand = ::handleParentCommand,
                        onConnected = {
                            runOnUiThread { tvStatus.text = "🔗 Auto-reconnected: $savedIp"; tvStatus.setTextColor(Color.GREEN) }
                        },
                        onDisconnected = {
                            runOnUiThread {
                                tvStatus.text = "⚠️ Can't reach Parent — retrying…"
                                tvStatus.setTextColor(Color.RED)
                            }
                        }
                    ).apply { start() }
                    Log.i("BabyGuard", "Auto-reconnected to $savedIp")
                    return@Thread
                } catch (_: Exception) {
                    Log.d("BabyGuard", "Auto-reconnect ${"${attempt + 1}"} failed")
                    Thread.sleep(500)
                }
            }
        }.start()
    }

    // ── Placement instructions (shown once per fresh QR pairing, never on auto-reconnect) ──

    private fun showPlacementInstructionDialog() {
        if (isFinishing || isDestroyed) return
        val imageView = android.widget.ImageView(this).apply {
            setImageResource(R.drawable.baby_unit_placement_instruction)
            adjustViewBounds = true
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setPadding(24, 24, 24, 24)
        }
        AlertDialog.Builder(this)
            .setTitle("Where to place the Baby Unit")
            .setView(imageView)
            .setCancelable(false)
            .setPositiveButton("Got it") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun applyRoundedCorners(view: View, radiusDp: Float) {
        val density = resources.displayMetrics.density
        val radiusPx = radiusDp * density
        view.clipToOutline = true
        view.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(v: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, v.width, v.height, radiusPx)
            }
        }
    }

    // ── Camera ─────────────────────────────────────────────────────────────────

    /** Front ↔ back lens toggle — shared by the local flip button and the Parent's remote command.
     *  Returns false (and reports why) when the flip can't happen right now, so callers relaying
     *  this over the network can tell the Parent it actually failed instead of staying silent. */
    private fun flipLens(): Boolean {
        if (usingUsbCamera) {
            runOnUiThread { Toast.makeText(this, "Flip not available for USB camera", Toast.LENGTH_SHORT).show() }
            return false
        }
        currentCameraSelector = if (currentCameraSelector == CameraSelector.DEFAULT_BACK_CAMERA)
            CameraSelector.DEFAULT_FRONT_CAMERA
        else
            CameraSelector.DEFAULT_BACK_CAMERA
        runOnUiThread { startCamera() }
        return true
    }

    /**
     * Handles a one-line JSON command relayed from the Parent over the alert socket. Previously
     * this only ever surfaced failures via a LOCAL Toast on the Baby Unit — the Parent had no
     * way to know a tap silently no-opped (e.g. no USB camera attached, or flip blocked while on
     * USB), so its button/label appeared to update even though nothing actually changed. Now
     * sends a "cmd_ack" back down the same socket so ParentActivity can show an accurate result.
     */
    private fun handleParentCommand(raw: String) {
        try {
            val json = org.json.JSONObject(raw)
            val cmd = json.optString("cmd")
            val success = when (cmd) {
                "switch_lens"    -> flipLens()
                "camera_source"  -> remoteSwitchCameraSource(json.optString("source") == "usb")
                // Parent dismissed a HIGH alert — clear stale hysteresis / streaks so the
                // Baby Unit stops reporting HIGH immediately rather than waiting out the dwell.
                "reset_pipeline" -> { safetyPipeline.forceReset(); motionDetector.resetBackground(); true }
                else             -> return
            }
            alertClient?.send(org.json.JSONObject().apply {
                put("type",    "cmd_ack")
                put("cmd",     cmd)
                put("success", success)
            }.toString())
        } catch (e: Exception) {
            Log.e("BabyGuard", "Bad command from Parent: $raw", e)
        }
    }

    /**
     * Remote equivalent of the local btnCameraSelector toggle (see setupListeners' "Camera
     * selector button" block) — invoked when the Parent's Internal/USB selector is tapped.
     * Picks the first attached UVC camera when switching to USB (the Parent has no way to show
     * the local device-picker dialog), and keeps btnCameraSelector's own label/tint in sync so
     * the Baby Unit's UI doesn't disagree with what's actually streaming. Returns false when the
     * switch couldn't happen (e.g. no UVC camera attached) so the caller can report it.
     */
    private fun remoteSwitchCameraSource(toUsb: Boolean): Boolean {
        if (toUsb) {
            if (usingUsbCamera) return true
            val cameras = usbCameraManager.listUvcCameras()
            if (cameras.isEmpty()) {
                runOnUiThread {
                    Toast.makeText(this, "No USB camera detected. Connect a UVC-compatible camera.", Toast.LENGTH_LONG).show()
                }
                return false
            }
            val device = cameras[0]
            runOnUiThread { multiCameraClient.requestPermission(device) }
            return true
        } else {
            if (!usingUsbCamera) return true
            runOnUiThread { switchToInternalCamera() }
            return true
        }
    }

    /**
     * Runs the full per-frame pipeline — QR pairing scan, motion meter, telemetry push, YOLO
     * safety analysis, and video/audio streaming — on whatever [bitmap] the active camera
     * source produced. Originally inline inside startCamera()'s CameraX analyzer; extracted
     * so the real UVC decode path (see startUvcDecode/nv21ToBitmap below) drives the exact
     * same pipeline instead of silently pausing AI monitoring and Parent streaming whenever
     * a USB camera is active.
     */
    private fun processIncomingFrame(bitmap: Bitmap) {
        val currentTime = System.currentTimeMillis()

        if (!isPaired) {
            if (currentTime - lastScanTime > 500) {
                lastScanTime = currentTime
                val result = qrScanner.scan(bitmap)
                if (result != null) {
                    // Peer-to-peer LAN pairing (sole pairing method)
                    val ip = result.ip
                    isPaired = true; parentIpAddress = ip
                    prefs.lastPairedIp = ip
                    // Show "Connecting" immediately, but don't claim "Connected" until
                    // AlertClient's onConnected fires — i.e. the TCP handshake to the Parent
                    // actually succeeded. Previously this set "Connected" the instant the QR
                    // decoded, so a failed handshake (wrong IP, AP isolation, Parent
                    // unreachable) left the Baby Unit permanently claiming a connection that
                    // never existed, while the Parent sat on the QR screen forever waiting
                    // for a connection that was never coming.
                    runOnUiThread {
                        tvStatus.text = "🔄 Connecting: $ip"
                        tvStatus.setTextColor(Color.parseColor("#FFC107"))
                        showPlacementInstructionDialog()
                    }
                    alertClient = AlertClient(
                        ip, 8888,
                        onCommand = ::handleParentCommand,
                        onConnected = {
                            runOnUiThread {
                                tvStatus.text = "🔗 Connected: $ip"
                                tvStatus.setTextColor(Color.GREEN)
                            }
                        },
                        onDisconnected = {
                            runOnUiThread {
                                tvStatus.text = "⚠️ Can't reach Parent — retrying…"
                                tvStatus.setTextColor(Color.RED)
                            }
                        }
                    ).apply { start() }
                }
            }
        } else {
            val isStreaming = videoSocket != null && !videoSocket!!.isClosed

            // ── MOG always: live motion meter independent of streaming ──
            if (currentTime - lastMogScanTime > 200) {
                lastMogScanTime = currentTime
                val rawMotionPixels = motionDetector.getMotionPixelCount(
                    bitmap, prefs.motionSensitivity)
                currentMotionIntensity = if (rawMotionPixels < 800) 0
                else (Math.sqrt(rawMotionPixels.toDouble() / 2_073_600.0) * 100)
                    .toInt().coerceAtMost(100)
            }

            // ── Telemetry every 200 ms ──────────────────────────
            if (currentTime - lastTelemetryTime > 200) {
                lastTelemetryTime     = currentTime

                val batteryPct        = getBatteryPercentage()
                val isCryingNow       = audioListener.isBabyCrying()
                currentSoundIntensity = audioListener.getLatestAmplitude()

                // ── Sustained-cry gating ───────────────────────
                // Only escalate to HIGH after baby has cried for CRYING_SUSTAIN_MS.
                // Reset the timer when crying stops.
                val sustainedCrying: Boolean
                if (isCryingNow) {
                    if (cryingStartTime == 0L) {
                        cryingStartTime = currentTime
                        sustainedCryFired = false
                        // Early one-shot MEDIUM warning the moment crying starts —
                        // a heads-up before the 3s-sustained HIGH escalation below.
                        if (currentTime - lastCryWarningTime > CRY_WARNING_COOLDOWN) {
                            lastCryWarningTime = currentTime
                            alertClient?.send(org.json.JSONObject().apply {
                                put("status",       "⚠️ Crying Detected")
                                put("mood",         currentDetectedMood)
                                put("posture",      currentDetectedPosture)
                                put("is_crying",    false)
                                put("tier",         "MEDIUM")
                                put("event_action", "Crying Started")
                                put("motion_level", currentMotionIntensity)
                                put("sound_level",  currentSoundIntensity)
                            }.toString())
                        }
                    }
                    sustainedCrying = (currentTime - cryingStartTime) >= CRYING_SUSTAIN_MS
                    if (sustainedCrying && !sustainedCryFired) {
                        sustainedCryFired = true
                        currentTier   = "HIGH"
                        currentAction = "Crying"
                        currentStatus = "🚨 CRYING DETECTED"
                    }
                } else {
                    // Crying stopped — reset tracker
                    cryingStartTime   = 0L
                    sustainedCryFired = false
                    sustainedCrying   = false
                }

                val json = org.json.JSONObject().apply {
                    put("status",       currentStatus)
                    put("mood",         currentDetectedMood)
                    put("posture",      currentDetectedPosture)
                    put("motion_level", currentMotionIntensity)
                    put("sound_level",  currentSoundIntensity)
                    put("is_crying",    sustainedCrying)
                    put("temp",         aiGovernor.getCurrentTemperature())
                    put("battery",      batteryPct)
                    put("fps",          currentFps)
                    put("tier",         currentTier)
                    put("event_action", currentAction)
                    put("risk_score",   currentRiskScore)
                }.toString()

                runOnUiThread {
                    tvHudBattery.text = "🔋 $batteryPct%"
                    tvHudFps.text     = "⚡ $currentFps FPS"
                    tvHudMotion.text  = "🌀 MOG: $currentMotionIntensity"
                    val active = safetyPipeline.getState() == SafetyPipeline.State.ACTIVE
                    tvHudStage.text  = if (active) "🎯 ACTIVE" else "🎯 DORMANT"
                    tvHudStage.setTextColor(if (active) Color.RED else Color.GREEN)
                    tvHudSound.text = "🔊 $currentSoundIntensity"
                    tvHudMood.text  = "🙂 $currentDetectedMood"
                    tvHudTemp.text  = "🌡️ %.1f°".format(aiGovernor.getCurrentTemperature())
                }
                alertClient?.send(json)
            }

            // ── FPS counter ────────────────────────────────────
            frameCount++
            if (currentTime - lastFpsCheckTime > 1000) {
                currentFps = frameCount; frameCount = 0; lastFpsCheckTime = currentTime
            }

            // ── Streaming state UI update ──────────────────────
            if (isStreaming != isCurrentlyStreaming) {
                isCurrentlyStreaming = isStreaming
                runOnUiThread {
                    if (isStreaming) {
                        val aiPauseLabel = if (prefs.pauseAiDuringStream) " · AI paused" else ""
                        tvStatus.text = "📹 Streaming$aiPauseLabel"
                        tvStatus.backgroundTintList =
                            android.content.res.ColorStateList.valueOf(Color.parseColor("#00BCD4"))
                    } else {
                        tvStatus.text = "🟢 Monitoring"
                        tvStatus.backgroundTintList =
                            android.content.res.ColorStateList.valueOf(Color.parseColor("#E5B800"))
                    }
                }
            }

            // ── Video push (~24 FPS) ────────────────────────────
            if (currentTime - lastVideoFrameTime > 41) {
                lastVideoFrameTime = currentTime
                cameraExecutor.execute {
                    try {
                        if (videoSocket == null || videoSocket!!.isClosed) {
                            videoSocket = Socket(parentIpAddress, 8889)
                            videoOutputStream = DataOutputStream(videoSocket!!.getOutputStream())
                        }
                        val stream = ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 20, stream)
                        val bytes = stream.toByteArray()
                        videoOutputStream?.writeInt(bytes.size)
                        videoOutputStream?.write(bytes)
                        videoOutputStream?.flush()
                    } catch (_: Exception) { videoSocket?.close(); videoSocket = null }
                }
                if (audioStreamingSocket == null || audioStreamingSocket!!.isClosed) {
                    cameraExecutor.execute { connectAudioStream() }
                }
            }

            // ── YOLO scan: rate driven by MOG score + baby presence ──
            val isStreamingNow = videoSocket != null && !videoSocket!!.isClosed
            val aiPaused = prefs.pauseAiDuringStream && isStreamingNow
            val aiDelay = if (aiPaused) Long.MAX_VALUE / 2
                // A posture streak is building or the suffocation timer is running — these
                // are exactly the still/low-motion cases the motion-driven delay below
                // scans slowest for, so force fast-rate scanning until it resolves instead
                // of waiting multiple seconds to confirm a standing/prone/covered baby.
                else if (safetyPipeline.isInvestigating()) aiGovernor.getInvestigatingDelay()
                else aiGovernor.getDynamicYoloDelay(
                    currentMotionIntensity, safetyPipeline.isBabyPresentRecently())
            // Dispatched onto yoloExecutor (NOT cameraExecutor) and guarded by yoloInFlight so
            // a slow inference on weaker hardware can never pile up a backlog of queued scans —
            // it just keeps working on the single freshest request. This is the actual fix for
            // "1 FPS at 37.7°C": that reading was the whole camera/video/audio pipeline stalling
            // on cameraExecutor for the full YOLO inference duration every time a scan fired —
            // not the configured scan-rate delay, and not thermal throttling (37.7°C is below
            // every mode's throttleTemp). Decoupling doesn't make a single inference compute any
            // faster on that CPU, but it stops one slow scan from also freezing video push,
            // motion/MOG updates, and the FPS counter while it runs.
            if (currentTime - lastYoloScanTime > aiDelay && !yoloInFlight) {
                lastYoloScanTime = currentTime
                yoloInFlight = true
                yoloExecutor.execute {
                    val result = try { safetyPipeline.processFrame(bitmap) } catch (_: Exception) { null }
                    yoloInFlight = false
                    if (result != null) {
                        if (result.motionLevel > currentMotionIntensity)
                            currentMotionIntensity = result.motionLevel
                        currentDetectedMood    = result.mood
                        currentDetectedPosture = result.posture
                        currentStatus          = result.status
                        currentTier            = result.tier
                        currentAction          = result.action
                        currentRiskScore        = result.riskScore

                        // Early one-shot MEDIUM warning the instant the face becomes
                        // covered — fires before the sustained-suffocation HIGH escalation,
                        // gated by its own cooldown so it can't spam while covered.
                        if (result.faceJustCovered &&
                            currentTime - lastFaceCoverWarningTime > FACE_COVER_WARNING_COOLDOWN) {
                            lastFaceCoverWarningTime = currentTime
                            alertClient?.send(org.json.JSONObject().apply {
                                put("status",       "⚠️ Face Covered — Checking")
                                put("mood",         currentDetectedMood)
                                put("posture",      currentDetectedPosture)
                                put("is_crying",    false)
                                put("tier",         "MEDIUM")
                                put("event_action", "Face Covered")
                                put("motion_level", currentMotionIntensity)
                                put("sound_level",  currentSoundIntensity)
                            }.toString())
                        }

                        runOnUiThread {
                            overlayView.setResults(result)
                            tvStatus.text = currentStatus

                            // Was 20s — the photo attached to an ongoing HIGH episode could be
                            // stale for a while; 10s keeps it closer to what's happening now.
                            if (currentTier == "HIGH" && currentTime - lastAlertTime > 10_000) {
                                lastAlertTime = currentTime
                                val stream = ByteArrayOutputStream()
                                bitmap.compress(Bitmap.CompressFormat.JPEG, 40, stream)
                                val b64 = android.util.Base64.encodeToString(
                                    stream.toByteArray(), android.util.Base64.NO_WRAP)
                                alertClient?.send(org.json.JSONObject().apply {
                                    put("status",       currentStatus)
                                    put("mood",         currentDetectedMood)
                                    put("posture",      currentDetectedPosture)
                                    put("is_crying",    false)
                                    put("image",        b64)
                                    put("tier",         "HIGH")
                                    put("event_action", currentAction)
                                    put("motion_level", currentMotionIntensity)
                                    put("sound_level",  currentSoundIntensity)
                                }.toString())
                            }
                        }
                    }
                }
            }
        }
    }

    private fun startCamera() {
        ProcessCameraProvider.getInstance(this).addListener({
            val cameraProvider = ProcessCameraProvider.getInstance(this).get()
            this.cameraProvider = cameraProvider
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(viewFinder.surfaceProvider)
            }

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetResolution(android.util.Size(640, 480))
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build().also { analysis ->
                    analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                        val rawBitmap   = imageProxy.toBitmap()
                        val matrix      = android.graphics.Matrix().apply {
                            postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
                        }
                        val bitmap = Bitmap.createBitmap(
                            rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)
                        // Release the camera frame immediately so CameraX can deliver the
                        // next frame while we do the heavy work (YOLO can take ~300 ms).
                        imageProxy.close()
                        processIncomingFrame(bitmap)
                    }
                }

            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                this, currentCameraSelector, preview, imageAnalyzer)
        }, ContextCompat.getMainExecutor(this))
    }

    // ── Audio streaming via AudioListener callback ──────────────────────────────

    private fun connectAudioStream() {
        try {
            audioStreamingSocket = Socket(parentIpAddress, 8890)
            val dos = DataOutputStream(audioStreamingSocket!!.getOutputStream())
            audioStreamingOutputStream = dos
            audioListener.onAudioChunk = { chunk, read ->
                try {
                    for (i in 0 until read) dos.writeShort(chunk[i].toInt())
                    dos.flush()
                } catch (_: Exception) {
                    audioListener.onAudioChunk = null
                    audioStreamingSocket?.close(); audioStreamingSocket = null
                }
            }
        } catch (_: Exception) {
            audioStreamingSocket?.close(); audioStreamingSocket = null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        overlayView.stopRenderLoop()
        alertClient?.stopClient()
        audioListener.onAudioChunk = null
        audioListener.stopListening()
        try { videoSocket?.close() } catch (_: Exception) {}
        try { audioStreamingSocket?.close() } catch (_: Exception) {}
        videoSocket = null; audioStreamingSocket = null
        usbCameraManager.tearDown()
        try { uvcCamera?.closeCamera() } catch (_: Exception) {}
        uvcCamera = null
        try { multiCameraClient.unRegister() } catch (_: Exception) {}
        try { multiCameraClient.destroy() } catch (_: Exception) {}
        yoloExecutor.shutdown()
    }

    // ── USB camera (real UVC decode) ────────────────────────────────────────────

    /**
     * AUSBC's internal USBMonitor (com.serenegiant.usb.USBMonitor, bundled inside the libuvc
     * artifact) calls the deprecated 2-arg `Context.registerReceiver(receiver, filter)` to
     * listen for USB attach/detach + permission-result broadcasts. On API 33+ with a targetSdk
     * of 33+, that overload throws `SecurityException: One of RECEIVER_EXPORTED or
     * RECEIVER_NOT_EXPORTED should be specified` — the library predates that requirement and
     * can't be patched directly since it's a remote JitPack dependency. Wrapping the Context
     * we hand to MultiCameraClient lets us intercept just that one deprecated overload and
     * supply RECEIVER_NOT_EXPORTED ourselves; every other Context call passes through
     * unchanged. NOT_EXPORTED is safe here — the broadcasts involved (USB attach/detach from
     * the system, and our own permission-request PendingIntent result) only ever come from the
     * system or from this app itself, never from another app.
     */
    private fun usbMonitorSafeContext(): Context {
        return object : android.content.ContextWrapper(this) {
            override fun registerReceiver(
                receiver: android.content.BroadcastReceiver?,
                filter: android.content.IntentFilter?
            ): android.content.Intent? {
                return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    super.registerReceiver(receiver, filter)
                }
            }
        }
    }

    /**
     * Manually sizes & centers [usbTextureView] to match the camera's real preview aspect ratio
     * inside whatever space is available above the bottom control panel (260dp) — replaces
     * AspectRatioTextureView's built-in sizing logic, which applies an orientation-aware
     * inversion meant for rotation-coupled internal sensors and produced incorrect/zoomed,
     * off-center framing for this fixed-orientation external UVC camera. Maximizes on-screen
     * size within the available space without cropping or stretching, addressing both the
     * "too zoomed in" and "stream too small" complaints together.
     */
    private fun applyUvcAspectFit(camWidth: Int, camHeight: Int) {
        if (camWidth <= 0 || camHeight <= 0) return
        val root = usbTextureView.parent as? android.view.ViewGroup ?: return
        root.post {
            val marginBottomPx = (260 * resources.displayMetrics.density).toInt()
            val availW = root.width
            val availH = (root.height - marginBottomPx).coerceAtLeast(1)
            if (availW <= 0) return@post
            val camAspect = camWidth.toFloat() / camHeight
            var w = availW
            var h = (w / camAspect).toInt()
            if (h > availH) {
                h = availH
                w = (h * camAspect).toInt()
            }
            // Absolute top|start positioning with manually computed margins — NOT
            // gravity=CENTER + bottomMargin. FrameLayout's gravity+margin formula for a
            // centered child is `top = (parentH - childH)/2 + topMargin - bottomMargin`, which
            // double-applies the bottom margin (once via the availH reduction above, again via
            // that formula) and shifts the box upward by the margin amount — that was the cause
            // of the "positioned too high" complaint.
            val leftPx = (availW - w) / 2
            val topPx  = (availH - h) / 2
            val lp = android.widget.FrameLayout.LayoutParams(w, h)
            lp.gravity = android.view.Gravity.TOP or android.view.Gravity.START
            lp.leftMargin = leftPx
            lp.topMargin  = topPx
            usbTextureView.layoutParams = lp

            // Keep the AI skeleton/bbox overlay aligned to this exact letterboxed rect. Without
            // this, OverlayView draws scaled to its own full bounds (which still span the entire
            // root minus the bottom panel) — that matched the old full-bleed UVC view, but no
            // longer matches now that usbTextureView is a centered, often-smaller box. overlayView
            // shares the same top-left origin as root (no topMargin of its own), so these margins
            // are valid directly as overlayView-local coordinates too.
            overlayView.setVideoRect(android.graphics.RectF(
                leftPx.toFloat(), topPx.toFloat(),
                (leftPx + w).toFloat(), (topPx + h).toFloat()))
        }
    }

    /**
     * Called once MultiCameraClient's USBMonitor confirms permission and delivers a usable
     * USBMonitor.UsbControlBlock for [device] — the real "ready to decode" signal. Replaces
     * the old onPermissionResult-driven openUsbCamera() stub, which only ever held a raw
     * UsbDeviceConnection and could never actually decode video.
     */
    private fun startUvcDecode(device: android.hardware.usb.UsbDevice, ctrlBlock: USBMonitor.UsbControlBlock) {
        // Stop CameraX so it isn't fighting the UVC camera for a preview surface, and isn't
        // burning frame-analyzer cycles nobody is looking at.
        try { cameraProvider?.unbindAll() } catch (_: Exception) {}

        try { uvcCamera?.closeCamera() } catch (_: Exception) {}

        val camera = MultiCameraClient.Camera(this, device)
        camera.setUsbControlBlock(ctrlBlock)
        camera.setCameraStateCallBack(object : ICameraStateCallBack {
            override fun onCameraState(
                self: MultiCameraClient.Camera,
                code: ICameraStateCallBack.State,
                msg: String?
            ) {
                runOnUiThread {
                    when (code) {
                        ICameraStateCallBack.State.OPENED -> {
                            uvcPreviewSize = self.getPreviewSize()
                            // Size/center the TextureView to the camera's real preview aspect
                            // ratio ourselves — fixes the "too zoomed in"/off-center look that
                            // came from forcing a 4:3 (or other) source into a full-bleed,
                            // mismatched-aspect view.
                            uvcPreviewSize?.let { applyUvcAspectFit(it.width, it.height) }
                            // The library already enables autoFocus=true internally on open, but
                            // some UVC sensors power on with focus parked at whatever distance
                            // and only actually rack focus on a state transition — toggle it off
                            // then on to force a real refocus pulse rather than trusting whatever
                            // distance it woke up at.
                            try { self.setAutoFocus(false); self.setAutoFocus(true) } catch (_: Exception) {}
                            tvStatus.text = "🔌 USB Camera: ${device.productName ?: "connected"}"
                            tvStatus.setTextColor(Color.parseColor("#00D4AA"))
                        }
                        ICameraStateCallBack.State.ERROR -> {
                            Toast.makeText(this@CameraActivity, "USB camera error: $msg", Toast.LENGTH_LONG).show()
                            switchToInternalCamera()
                        }
                        ICameraStateCallBack.State.CLOSED -> {}
                    }
                }
            }
        })
        camera.addPreviewDataCallBack(object : IPreviewDataCallBack {
            override fun onPreviewData(data: ByteArray?, format: IPreviewDataCallBack.DataFormat) {
                // Runs on the library's own native-frame-callback thread, NOT the main thread —
                // route the heavy work onto cameraExecutor (same thread the CameraX path used)
                // so we never block frame delivery and never run concurrently with it (CameraX
                // is unbound above, so there's no risk of both paths calling
                // processIncomingFrame() at once).
                val nv21 = data ?: return
                val size = uvcPreviewSize ?: return
                cameraExecutor.execute {
                    val bitmap = nv21ToBitmap(nv21, size.width, size.height) ?: return@execute
                    processIncomingFrame(bitmap)
                }
            }
        })

        usingUsbCamera = true
        uvcCamera = camera
        viewFinder.visibility = View.GONE
        usbTextureView.visibility = View.VISIBLE
        val btn = findViewById<android.widget.Button>(R.id.btnCameraSelector)
        btn?.text = "🔌 USB Camera Active"
        btn?.backgroundTintList = android.content.res.ColorStateList.valueOf(
            Color.parseColor("#4000D4AA"))

        // Requesting 1280x720 instead of 640x480: AUSBC 3.2.7's internal getSuitableSize() has a
        // bug where it matches the FIRST device-supported size with width==640 OR height==480
        // (hardcoded fallback constants) before properly checking for an exact match — on many
        // UVC cameras this silently overrides whatever resolution we ask for back down to
        // ~640x480. Requesting a size that doesn't intersect that trap gives devices whose size
        // list doesn't hit it a chance to actually deliver a larger (less "zoomed/small") stream;
        // applyUvcAspectFit() above sizes/centers correctly either way once OPENED reports
        // whatever size was actually negotiated.
        camera.openCamera(
            usbTextureView,
            CameraRequest.Builder()
                .setPreviewWidth(1280)
                .setPreviewHeight(720)
                .create()
        )
    }

    /** Tears down the UVC decode session (if any) and falls back to CameraX. Safe to call
     *  even when no USB camera is currently active. */
    private fun switchToInternalCamera() {
        try { uvcCamera?.closeCamera() } catch (_: Exception) {}
        uvcCamera = null
        uvcPreviewSize = null
        usingUsbCamera = false
        usbTextureView.visibility = View.GONE
        viewFinder.visibility = View.VISIBLE
        // Internal camera (CameraX PreviewView) is full-bleed inside its own bounds, so the
        // overlay should go back to using its own full view bounds — not the UVC letterbox rect.
        overlayView.setVideoRect(null)
        val btn = findViewById<android.widget.Button>(R.id.btnCameraSelector)
        btn?.text = "📷 Internal Camera"
        btn?.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#4000D4AA"))
        startCamera()
    }

    /**
     * Converts a UVC frame's NV21 byte array into the same ARGB Bitmap format CameraX delivers
     * to processIncomingFrame(), so the shared AI/streaming pipeline doesn't need to know which
     * camera source produced the frame.
     */
    private fun nv21ToBitmap(nv21: ByteArray, width: Int, height: Int): Bitmap? {
        return try {
            val yuvImage = android.graphics.YuvImage(
                nv21, android.graphics.ImageFormat.NV21, width, height, null)
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(android.graphics.Rect(0, 0, width, height), 90, out)
            val jpegBytes = out.toByteArray()
            android.graphics.BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
        } catch (_: Exception) { null }
    }
}
