package com.example.babyguard

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.GestureDetector
import android.view.Menu
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.SwitchCompat
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalLensFacing
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@SuppressLint("SetTextI18n")
class CameraActivity : AppCompatActivity() {
    private lateinit var yoloDetector: YoloDetector
    private lateinit var faceDetector: MediaPipeDetector
    private lateinit var audioListener: AudioListener
    private lateinit var motionDetector: MotionDetector
    private lateinit var engine: BabyMonitorEngine
    private lateinit var qrScanner: QrScanner
    
    private lateinit var viewFinder: PreviewView
    private lateinit var overlayView: OverlayView
    private lateinit var tvStatus: TextView
    private lateinit var tvSafetyStatus: TextView
    private lateinit var blackScreenOverlay: FrameLayout
    
    private lateinit var cameraExecutor: ExecutorService
    private val videoExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var commandServer: CommandServer? = null
    private var wifiDirectManager: WifiDirectManager? = null

    private lateinit var layoutUnpaired: LinearLayout
    private lateinit var layoutPairedControls: LinearLayout

    private var lastScanTime = 0L
    private var lastVideoFrameTime = 0L
    private var videoSocket: Socket? = null
    private var videoOutputStream: DataOutputStream? = null
    
    var parentIpAddress: String? = null
    var isPaired = false
        set(value) {
            field = value
            runOnUiThread { updateUiForPairingState() }
        }
    
    private var isSendingFrame = false
    private var currentCameraId: String? = null
    private var manualRotation = Surface.ROTATION_0

    private val ACTION_USB_PERMISSION = "com.example.babyguard.USB_PERMISSION"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Color.parseColor("#4A4A4A")
        setContentView(R.layout.activity_camera)

        initViews()
        initEngine()
        setupListeners()
        setupObservers()
        
        checkPermissions()
        startCommandServer()
        setupWifiDirect()
        registerUsbReceivers()
        
        requestUsbPermission()
    }

    private fun initViews() {
        viewFinder = findViewById(R.id.viewFinder)
        overlayView = findViewById(R.id.overlayView)
        tvStatus = findViewById(R.id.tvStatus)
        // Note: In activity_camera.xml there might not be a tvSafetyStatus, using tvStatus for now or assume layout updated
        tvSafetyStatus = findViewById(R.id.tvStatus) 
        blackScreenOverlay = findViewById(R.id.blackScreenOverlay)
        layoutUnpaired = findViewById(R.id.layoutUnpaired)
        layoutPairedControls = findViewById(R.id.layoutPairedControls)
        
        viewFinder.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
    }

    private fun initEngine() {
        org.opencv.android.OpenCVLoader.initDebug()
        yoloDetector = YoloDetector(this)
        faceDetector = MediaPipeDetector(this)
        audioListener = AudioListener(this)
        motionDetector = MotionDetector()
        qrScanner = QrScanner()
        cameraExecutor = Executors.newSingleThreadExecutor()

        engine = BabyMonitorEngine(
            this,
            motionDetector,
            yoloDetector,
            faceDetector,
            audioListener
        ) { status, description ->
            // Alert Triggered Callback
            sendAlertToParent(status, description)
        }
    }

    private fun setupObservers() {
        lifecycleScope.launch {
            engine.liveInsight.collectLatest { jsonStr ->
                if (jsonStr.isEmpty()) return@collectLatest
                val json = org.json.JSONObject(jsonStr)
                val status = json.getString("safety_status")
                val posture = json.getString("posture")
                val emotion = json.getString("emotion")
                
                runOnUiThread {
                    tvStatus.text = "Status: $status | Posture: $posture"
                    updateStatusColor(status)
                }
            }
        }
    }

    private fun updateStatusColor(status: String) {
        val color = when (status) {
            "SAFE" -> "#4CAF50"
            "WARNING" -> "#FFC107"
            "DANGER", "CRITICAL_SIDS_WARNING" -> "#F44336"
            else -> "#757575"
        }
        tvStatus.setTextColor(Color.parseColor(color))
    }

    private fun sendAlertToParent(status: String, description: String) {
        if (!isPaired || parentIpAddress == null) return
        
        val json = org.json.JSONObject().apply {
            put("status", status)
            put("description", description)
            put("battery", getBatteryLevel())
            put("timestamp", System.currentTimeMillis())
        }.toString()
        
        AlertClient(parentIpAddress!!, 8888, json).start()
    }

    private fun processImage(imageProxy: ImageProxy) {
        val currentTime = System.currentTimeMillis()
        val isStreamingActive = videoSocket != null && !videoSocket!!.isClosed
        
        // Pass frame to the engine for AI analysis
        val rawBitmap = imageProxy.toBitmap()
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        val bitmap = if (rotationDegrees != 0) {
            val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
            Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)
        } else { rawBitmap }

        // QR Scanning if not paired
        if (!isPaired && (currentTime - lastScanTime > 500)) {
            lastScanTime = currentTime
            qrScanner.scanForLanIp(bitmap)?.let { targetIp -> 
                parentIpAddress = targetIp
                isPaired = true 
            }
        }

        // Delegate to Engine
        engine.onFrameAvailable(bitmap)

        // Video Streaming Logic
        val needsVideo = isPaired && (currentTime - lastVideoFrameTime > 60) && !isSendingFrame
        if (needsVideo) {
            lastVideoFrameTime = currentTime
            isSendingFrame = true
            videoExecutor.execute { 
                sendVideoFrame(bitmap)
                if (!isStreamingActive) engine.onStreamStarted()
            }
        } else if (!isPaired || (isStreamingActive && videoSocket?.isClosed == true)) {
             // Logic to detect stream stop if needed
        }

        imageProxy.close()
    }

    private fun sendVideoFrame(bitmap: Bitmap) {
        try {
            if (videoSocket == null || videoSocket!!.isClosed) {
                videoSocket = Socket(parentIpAddress, 8889)
                videoOutputStream = DataOutputStream(java.io.BufferedOutputStream(videoSocket!!.getOutputStream()))
            }
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 15, stream)
            val bytes = stream.toByteArray()
            videoOutputStream?.writeInt(bytes.size)
            videoOutputStream?.write(bytes)
            videoOutputStream?.flush()
        } catch (e: Exception) {
            try { videoSocket?.close() } catch (_: Exception) {}
            videoSocket = null
            engine.onStreamStopped()
        } finally { isSendingFrame = false }
    }

    private fun setupListeners() {
        findViewById<Button>(R.id.btnBroadcast).setOnClickListener {
            wifiDirectManager?.startDiscovery()
            Toast.makeText(this, "Pairing Mode Active", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnSwitchCamera).setOnClickListener { showCameraSelectionMenu(it) }
        findViewById<Button>(R.id.btnRotateCamera).setOnClickListener {
            manualRotation = (manualRotation + 1) % 4
            startCamera()
        }
        findViewById<SwitchCompat>(R.id.switchSleepMode).setOnCheckedChangeListener { _, isChecked ->
            blackScreenOverlay.isVisible = isChecked
            window.attributes = window.attributes.apply { screenBrightness = if (isChecked) 0.01f else -1f }
        }
    }

    // --- Standard CameraX Boilerplate ---

    @OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val infos = cameraProvider.availableCameraInfos
            if (infos.isEmpty()) return@addListener

            if (currentCameraId == null) {
                currentCameraId = try { Camera2CameraInfo.from(infos.first()).cameraId } catch (e: Exception) { null }
            }

            val selector = CameraSelector.Builder()
                .addCameraFilter { camInfos ->
                    camInfos.filter { try { Camera2CameraInfo.from(it).cameraId == currentCameraId } catch (e: Exception) { false } }
                }
                .build()
            
            val preview = Preview.Builder().setTargetRotation(manualRotation).build().also { it.setSurfaceProvider(viewFinder.surfaceProvider) }
            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setTargetRotation(manualRotation)
                .build().also {
                    it.setAnalyzer(cameraExecutor) { imageProxy -> processImage(imageProxy) }
                }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, selector, preview, imageAnalysis)
            } catch (e: Exception) { Log.e("BabyGuard", "Binding failed", e) }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun checkPermissions() {
        val permissions = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        val toRequest = permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (toRequest.isNotEmpty()) ActivityCompat.requestPermissions(this, toRequest.toTypedArray(), 1001) else startCamera()
    }

    private fun getBatteryLevel(): Int {
        val batteryStatus: Intent? = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return batteryStatus?.let { intent ->
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            (level * 100 / scale.toFloat()).toInt()
        } ?: -1
    }

    // --- USB and Lifecycle logic omitted for brevity, same as previous ---
    private fun registerUsbReceivers() {
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_USB_PERMISSION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
                requestUsbPermission()
            }
        }
    }

    private fun requestUsbPermission() {
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        usbManager.deviceList.values.forEach { device ->
            if (!usbManager.hasPermission(device)) {
                val intent = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE)
                usbManager.requestPermission(device, intent)
            }
        }
    }

    private fun setupWifiDirect() {
        wifiDirectManager = WifiDirectManager(this, { }, { ip -> parentIpAddress = ip; isPaired = true })
    }

    private fun startCommandServer() {
        commandServer = CommandServer { command ->
            runOnUiThread {
                when (command) {
                    "SWITCH_CAM" -> startCamera()
                    "ROTATE_CAM" -> { manualRotation = (manualRotation + 1) % 4; startCamera() }
                }
            }
        }
        commandServer?.start()
    }

    @OptIn(ExperimentalLensFacing::class, androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
    private fun showCameraSelectionMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add("Toggle Camera")
        popup.show()
    }

    private fun updateUiForPairingState() {
        layoutUnpaired.isVisible = !isPaired
        layoutPairedControls.isVisible = isPaired
        if (isPaired) tvStatus.text = "🟢 CONNECTED"
    }

    override fun onDestroy() {
        super.onDestroy()
        engine.stop()
        cameraExecutor.shutdown()
        videoExecutor.shutdown()
        commandServer?.close()
    }
}
