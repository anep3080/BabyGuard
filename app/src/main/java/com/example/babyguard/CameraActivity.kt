package com.example.babyguard

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
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

@SuppressLint("SetTextI18n")
class CameraActivity : AppCompatActivity() {
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
    private lateinit var blackScreenOverlay: FrameLayout
    private lateinit var cameraExecutor: ExecutorService

    var parentIpAddress: String? = null
    private var isPaired = false
    private var isCurrentlyStreaming = false
    private var isVideoPlaying = false
    private var alertClient: AlertClient? = null

    private var lastScanTime = 0L
    private var lastYoloScanTime = 0L
    private var lastVideoFrameTime = 0L
    private var videoSocket: Socket? = null
    private var videoOutputStream: DataOutputStream? = null

    private var lastAlertTime = 0L
    private var lastTelemetryTime = 0L

    private var currentMotionIntensity = 0
    private var currentSoundIntensity = 0
    private var currentDetectedMood = "Searching..."
    private var currentDetectedPosture = "None"
    private var currentStatus = "🟢 Monitoring"
    private var currentTier = "LOW"
    private var currentAction = "Normal"
    
    private var frameCount = 0; private var lastFpsCheckTime = 0L; private var currentFps = 0
    private var audioStreamingSocket: Socket? = null; private var audioStreamingOutputStream: DataOutputStream? = null
    private var audioRecord: android.media.AudioRecord? = null; private var isAudioStreaming = false

    private val batteryManager by lazy { getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager }
    private fun getBatteryPercentage(): Int = batteryManager.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_camera)

        viewFinder = findViewById(R.id.viewFinder); overlayView = findViewById(R.id.overlayView)
        tvStatus = findViewById(R.id.tvStatus); tvHudBattery = findViewById(R.id.tvHudBattery)
        tvHudFps = findViewById(R.id.tvHudFps); tvHudMotion = findViewById(R.id.tvHudMotion)
        tvHudStage = findViewById(R.id.tvHudStage); blackScreenOverlay = findViewById(R.id.blackScreenOverlay)
        val switchSleepMode = findViewById<SwitchCompat>(R.id.switchSleepMode)
        val ivBurnInShield = findViewById<android.widget.ImageView>(R.id.ivBurnInShield)

        switchSleepMode.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                blackScreenOverlay.isVisible = true; window.attributes = window.attributes.apply { screenBrightness = 0.0f }
                val pulse = android.view.animation.AlphaAnimation(0.2f, 0.6f).apply { duration = 3000; repeatMode = android.view.animation.Animation.REVERSE; repeatCount = android.view.animation.Animation.INFINITE }
                ivBurnInShield.startAnimation(pulse)
            } else {
                blackScreenOverlay.isGone = true; ivBurnInShield.clearAnimation(); window.attributes = window.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
            }
        }

        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean { blackScreenOverlay.isGone = true; switchSleepMode.isChecked = false; window.attributes = window.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }; return true }
        })
        blackScreenOverlay.setOnTouchListener { _, event -> gestureDetector.onTouchEvent(event); true }
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        org.opencv.android.OpenCVLoader.initDebug(); aiGovernor = AIGovernor(this)
        yoloDetector = YoloDetector(this); emotionDetector = EmotionDetector(this); mediaPipeDetector = MediaPipeDetector(this); audioListener = AudioListener(this)
        motionDetector = MotionDetector(); qrScanner = QrScanner(); cameraExecutor = Executors.newSingleThreadExecutor()
        safetyPipeline = SafetyPipeline(motionDetector, yoloDetector, emotionDetector, aiGovernor)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO), 1001)
        } else startCamera()
        audioListener.startListening()
    }

    private fun startCamera() {
        ProcessCameraProvider.getInstance(this).addListener({
            val cameraProvider = ProcessCameraProvider.getInstance(this).get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(viewFinder.surfaceProvider) }

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetResolution(android.util.Size(640, 480))
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build().also {
                it.setAnalyzer(cameraExecutor) { imageProxy ->
                    val currentTime = System.currentTimeMillis()
                    val rawBitmap = imageProxy.toBitmap()
                    val matrix = android.graphics.Matrix().apply { postRotate(imageProxy.imageInfo.rotationDegrees.toFloat()) }
                    val bitmap = Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)

                    if (!isPaired) {
                        if (currentTime - lastScanTime > 500) {
                            lastScanTime = currentTime
                            val targetIp = qrScanner.scanForLanIp(bitmap)
                            if (targetIp != null && targetIp.isNotEmpty()) {
                                isPaired = true 
                                parentIpAddress = targetIp
                                alertClient = AlertClient(targetIp, 8888).apply { start() }
                                runOnUiThread { tvStatus.text = "🔗 Connected: $targetIp"; tvStatus.setTextColor(Color.GREEN) }
                            }
                        }
                    } else {
                        val isStreaming = videoSocket != null && !videoSocket!!.isClosed
                        
                        if (currentTime - lastTelemetryTime > 600) {
                            lastTelemetryTime = currentTime
                            val batteryPct = getBatteryPercentage(); val isCrying = audioListener.isBabyCrying()
                            currentSoundIntensity = audioListener.getLatestAmplitude()
                            
                            if (isCrying) { currentTier = "HIGH"; currentAction = "Crying"; currentStatus = "🚨 CRYING DETECTED" }

                            val json = org.json.JSONObject().apply {
                                put("status", currentStatus); put("mood", currentDetectedMood); put("posture", currentDetectedPosture)
                                put("motion_level", currentMotionIntensity); put("sound_level", currentSoundIntensity)
                                put("is_crying", isCrying); put("temp", aiGovernor.getCurrentTemperature())
                                put("battery", batteryPct); put("fps", currentFps); put("tier", currentTier); put("event_action", currentAction)
                            }.toString()

                            runOnUiThread {
                                tvHudBattery.text = "🔋 $batteryPct%"; tvHudFps.text = "⚡ $currentFps FPS"; tvHudMotion.text = "🌀 MOG: $currentMotionIntensity"
                                tvHudStage.text = if (safetyPipeline.getState() == SafetyPipeline.State.ACTIVE) "🎯 ACTIVE" else "🎯 DORMANT"
                                tvHudStage.setTextColor(if (safetyPipeline.getState() == SafetyPipeline.State.ACTIVE) Color.RED else Color.GREEN)
                            }
                            alertClient?.send(json)
                        }

                        frameCount++; if (currentTime - lastFpsCheckTime > 1000) { currentFps = frameCount; frameCount = 0; lastFpsCheckTime = currentTime }

                        if (isStreaming != isCurrentlyStreaming) {
                            isCurrentlyStreaming = isStreaming
                            runOnUiThread {
                                if (isStreaming) { tvStatus.text = "📹 Streaming Active (AI Paused)"; tvStatus.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#00BCD4")) } 
                                else { audioListener.resumeListening(); tvStatus.text = "🟢 Monitoring"; tvStatus.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#E5B800")) }
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
                            if (isPaired && (audioStreamingSocket == null || audioStreamingSocket!!.isClosed)) {
                                cameraExecutor.execute {
                                    try {
                                        audioStreamingSocket = Socket(parentIpAddress, 8890); audioStreamingOutputStream = DataOutputStream(audioStreamingSocket!!.getOutputStream())
                                        startAudioStreaming()
                                    } catch (_: Exception) { audioStreamingSocket?.close(); audioStreamingSocket = null }
                                }
                            }
                        }

                        val aiDelay = aiGovernor.getRequiredDelay()
                        if (!isStreaming && currentTime - lastYoloScanTime > aiDelay) {
                            lastYoloScanTime = currentTime
                            val result = try { safetyPipeline.processFrame(bitmap) } catch (_: Exception) { null }
                            
                            if (result != null) {
                                currentMotionIntensity = result.motionLevel
                                currentDetectedMood = result.mood; currentDetectedPosture = result.posture
                                currentStatus = result.status; currentTier = result.tier; currentAction = result.action

                                runOnUiThread {
                                    overlayView.setResults(result); tvStatus.text = currentStatus
                                    if (currentTier == "HIGH" && currentTime - lastAlertTime > 20000) {
                                        lastAlertTime = currentTime
                                        val stream = ByteArrayOutputStream(); bitmap.compress(Bitmap.CompressFormat.JPEG, 40, stream); val base64 = android.util.Base64.encodeToString(stream.toByteArray(), android.util.Base64.NO_WRAP)
                                        val alertJson = org.json.JSONObject().apply { 
                                            put("status", currentStatus); put("mood", currentDetectedMood); put("posture", currentDetectedPosture)
                                            put("is_crying", false); put("image", base64); put("tier", "HIGH"); put("event_action", currentAction)
                                            put("motion_level", currentMotionIntensity); put("sound_level", currentSoundIntensity)
                                        }.toString()
                                        alertClient?.send(alertJson)
                                    }
                                }
                            }
                        }
                    }
                    imageProxy.close()
                }
            }
            cameraProvider.unbindAll(); cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalyzer)
        }, ContextCompat.getMainExecutor(this))
    }

    @SuppressLint("MissingPermission")
    private fun startAudioStreaming() {
        if (isAudioStreaming) return
        isAudioStreaming = true
        Thread {
            try {
                val bSize = android.media.AudioRecord.getMinBufferSize(16000, android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT)
                audioRecord = android.media.AudioRecord(android.media.MediaRecorder.AudioSource.MIC, 16000, android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT, bSize)
                audioRecord?.startRecording(); val buffer = ShortArray(bSize)
                while (isAudioStreaming && audioStreamingSocket != null && !audioStreamingSocket!!.isClosed) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0) { for (i in 0 until read) audioStreamingOutputStream?.writeShort(buffer[i].toInt()); audioStreamingOutputStream?.flush() }
                }
            } catch (e: Exception) { Log.e("BabyGuard", "Audio Stream Error", e) } finally { stopAudioStreaming() }
        }.start()
    }

    private fun stopAudioStreaming() { isAudioStreaming = false; try { audioRecord?.stop() } catch (_: Exception) {}; try { audioRecord?.release() } catch (_: Exception) {}; audioRecord = null; try { audioStreamingSocket?.close() } catch (_: Exception) {}; audioStreamingSocket = null }

    override fun onDestroy() {
        super.onDestroy()
        alertClient?.stopClient()
        stopAudioStreaming()
        stopVideoServer()
    }

    private fun stopVideoServer() { isVideoPlaying = false; try { videoSocket?.close() } catch (_: Exception) {}; videoSocket = null }
}