package com.example.babyguard

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
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
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@SuppressLint("SetTextI18n") // Silences translation warnings
class CameraActivity : AppCompatActivity() {
    private lateinit var aiGovernor: AIGovernor
    private lateinit var yoloDetector: YoloDetector
    private lateinit var emotionDetector: EmotionDetector
    private lateinit var mediaPipeDetector: MediaPipeDetector
    private lateinit var audioListener: AudioListener
    private lateinit var motionDetector: MotionDetector
    private lateinit var qrScanner: QrScanner
    private lateinit var viewFinder: PreviewView
    private lateinit var overlayView: OverlayView
    private lateinit var tvStatus: TextView
    private lateinit var blackScreenOverlay: FrameLayout
    private lateinit var cameraExecutor: ExecutorService

    private var lastYoloScanTime = 0L; private var lastScanTime = 0L; private var unseenFaceCount = 0
    private var lastVideoFrameTime = 0L; private var videoSocket: Socket? = null; private var videoOutputStream: DataOutputStream? = null
    var parentIpAddress: String? = null; var isPaired = false
    private var isCurrentlyStreaming = false

    private var proneRiskStartTime = 0L
    private var isAlertEscalated = false
    private var lastTelemetryTime = 0L

    private var currentMotionIntensity = 0
    private var currentDetectedMood = "Sleeping"
    private var currentDetectedPosture = "Safe"
    private var currentStatus = "🟢 Monitoring"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_camera)

        viewFinder = findViewById(R.id.viewFinder)
        overlayView = findViewById(R.id.overlayView)
        tvStatus = findViewById(R.id.tvStatus)
        blackScreenOverlay = findViewById(R.id.blackScreenOverlay)
        val switchSleepMode = findViewById<SwitchCompat>(R.id.switchSleepMode)
        val ivBurnInShield = findViewById<android.widget.ImageView>(R.id.ivBurnInShield)

        switchSleepMode.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                blackScreenOverlay.isVisible = true
                window.attributes = window.attributes.apply { screenBrightness = 0.0f }
                // Start pulsing animation for burn-in protection
                val pulse = android.view.animation.AlphaAnimation(0.2f, 0.6f).apply {
                    duration = 3000
                    repeatMode = android.view.animation.Animation.REVERSE
                    repeatCount = android.view.animation.Animation.INFINITE
                }
                ivBurnInShield.startAnimation(pulse)
            } else {
                blackScreenOverlay.isGone = true
                ivBurnInShield.clearAnimation()
                window.attributes = window.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
            }
        }

        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                blackScreenOverlay.isGone = true
                switchSleepMode.isChecked = false
                window.attributes = window.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
                return true
            }
        })
        blackScreenOverlay.setOnTouchListener { _, event -> gestureDetector.onTouchEvent(event); true }

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        org.opencv.android.OpenCVLoader.initDebug()
        aiGovernor = AIGovernor(this)
        yoloDetector = YoloDetector(this); emotionDetector = EmotionDetector(this); mediaPipeDetector = MediaPipeDetector(this); audioListener = AudioListener(this)
        motionDetector = MotionDetector(); qrScanner = QrScanner(); cameraExecutor = Executors.newSingleThreadExecutor()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO), 1001)
        } else startCamera()
        audioListener.startListening()
    }

    private fun startCamera() {
        ProcessCameraProvider.getInstance(this).addListener({
            val cameraProvider = ProcessCameraProvider.getInstance(this).get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(viewFinder.surfaceProvider) }

            val imageAnalyzer = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888).build().also {
                it.setAnalyzer(cameraExecutor) { imageProxy ->
                    val currentTime = System.currentTimeMillis()
                    val rawBitmap = imageProxy.toBitmap()
                    val matrix = android.graphics.Matrix().apply { postRotate(imageProxy.imageInfo.rotationDegrees.toFloat()) }
                    val bitmap = Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)

                    if (!isPaired) {
                        if (currentTime - lastScanTime > 500) {
                            lastScanTime = currentTime
                            val targetIp = qrScanner.scanForLanIp(bitmap)
                            if (targetIp != null) {
                                parentIpAddress = targetIp; isPaired = true
                                runOnUiThread { tvStatus.text = "🔗 Locked: $targetIp"; tvStatus.setTextColor(Color.GREEN) }
                            }
                        }
                    } else {
                        val isStreaming = videoSocket != null && !videoSocket!!.isClosed
                        
                        // Handle Telemetry Heartbeat (6.1)
                        if (currentTime - lastTelemetryTime > 1000) {
                            lastTelemetryTime = currentTime
                            val telemetryJson = org.json.JSONObject().apply {
                                put("status", currentStatus)
                                put("mood", currentDetectedMood)
                                put("posture", currentDetectedPosture)
                                put("motion_level", currentMotionIntensity)
                                put("is_crying", audioListener.isBabyCrying())
                                put("temp", aiGovernor.getCurrentTemperature())
                            }.toString()
                            if (isPaired) {
                                AlertClient(parentIpAddress!!, 8888, telemetryJson).start()
                            }
                        }

                        if (isStreaming != isCurrentlyStreaming) {
                            isCurrentlyStreaming = isStreaming
                            if (isStreaming) {
                                // 5.3 STREAM-TIME PAUSE: Pause vision, keep audio
                                // (AudioListener continues to run)
                                runOnUiThread {
                                    tvStatus.text = "📹 Streaming Active (AI Paused)"
                                    tvStatus.setBackgroundResource(R.drawable.pill_background) // Keep the shape
                                    tvStatus.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#00BCD4"))
                                }
                            } else {
                                // Resume audio when streaming ends if needed
                                // (If we didn't pause it, we don't strictly need to resume here, 
                                // but for architecture consistency we ensure it's on)
                                audioListener.resumeListening()
                                runOnUiThread {
                                    tvStatus.text = "🟢 AI Engine Active"
                                    tvStatus.setBackgroundResource(R.drawable.pill_background)
                                    tvStatus.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#E5B800"))
                                }
                            }
                        }

                        if (currentTime - lastVideoFrameTime > 41) {
                            lastVideoFrameTime = currentTime
                            cameraExecutor.execute {
                                try {
                                    if (videoSocket == null || videoSocket!!.isClosed) { videoSocket = Socket(parentIpAddress, 8889); videoOutputStream = DataOutputStream(videoSocket!!.getOutputStream()) }
                                    val stream = ByteArrayOutputStream(); bitmap.compress(Bitmap.CompressFormat.JPEG, 20, stream); val bytes = stream.toByteArray()
                                    videoOutputStream?.writeInt(bytes.size); videoOutputStream?.write(bytes); videoOutputStream?.flush()
                                } catch (_: Exception) { videoSocket?.close(); videoSocket = null } 
                            }
                        }

                        // 5.1 & 5.3: AI Vision Throttling and Pause
                        val aiDelay = aiGovernor.getRequiredDelay()
                        if (!isStreaming && currentTime - lastYoloScanTime > aiDelay) {
                            lastYoloScanTime = currentTime
                            val hasMotion = motionDetector.hasMotion(bitmap)
                            currentMotionIntensity = if (hasMotion) (40..100).random() else (0..10).random()
                            
                            if (hasMotion) {
                                val yoloResult = yoloDetector.detect(bitmap)
                                if (yoloResult != null) {
                                    // Tier 3: Emotion Analysis
                                    val faceCrop = yoloDetector.getFaceCrop(bitmap, yoloResult.keypoints)
                                    currentDetectedMood = if (faceCrop != null) {
                                        emotionDetector.detectMood(faceCrop)
                                    } else if (yoloResult.isProne) {
                                        "Hidden"
                                    } else {
                                        "Sleeping"
                                    }

                                    runOnUiThread {
                                        // 3.4 Danger Timer Logic
                                        var status = if (yoloResult.isStanding) "🚨 DANGER (Baby Standing!)" else "🟢 Baby is Awake"
                                        currentDetectedPosture = if (yoloResult.isStanding) "Standing" else if (yoloResult.isProne) "Face Down" else "Safe"
                                        
                                        if (yoloResult.isProne) {
                                            if (proneRiskStartTime == 0L) proneRiskStartTime = currentTime
                                            val elapsed = (currentTime - proneRiskStartTime) / 1000
                                            status = "⚠️ PRONE RISK ($elapsed s)"
                                            
                                            if (elapsed >= 10 && !isAlertEscalated) {
                                                status = "🚨 CRITICAL: FACE DOWN!"
                                                isAlertEscalated = true
                                            }
                                        } else {
                                            proneRiskStartTime = 0L
                                            isAlertEscalated = false
                                        }

                                        currentStatus = status
                                        tvStatus.text = status

                                        if (status.contains("DANGER") || status.contains("CRITICAL")) {
                                            val stream = java.io.ByteArrayOutputStream()
                                            bitmap.compress(Bitmap.CompressFormat.JPEG, 40, stream)
                                            val base64Image = android.util.Base64.encodeToString(stream.toByteArray(), android.util.Base64.NO_WRAP)
                                            val alertJson = org.json.JSONObject().apply { 
                                                put("status", status)
                                                put("mood", currentDetectedMood)
                                                put("posture", currentDetectedPosture)
                                                put("is_crying", false)
                                                put("image", base64Image) 
                                            }.toString()
                                            AlertClient(parentIpAddress!!, 8888, alertJson).start()
                                        }
                                    }
                                } else {
                                    currentDetectedMood = "Searching..."
                                    currentDetectedPosture = "None"
                                }
                            } else {
                                currentDetectedMood = "Sleeping"
                                currentDetectedPosture = "Safe"
                            }
                        }
                    }
                    imageProxy.close()
                }
            }
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalyzer)
        }, ContextCompat.getMainExecutor(this))
    }
}