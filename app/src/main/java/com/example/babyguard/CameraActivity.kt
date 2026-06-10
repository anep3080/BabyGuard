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
    private lateinit var yoloDetector: YoloDetector
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_camera)

        viewFinder = findViewById(R.id.viewFinder)
        overlayView = findViewById(R.id.overlayView)
        tvStatus = findViewById(R.id.tvStatus)
        blackScreenOverlay = findViewById(R.id.blackScreenOverlay)
        val switchSleepMode = findViewById<SwitchCompat>(R.id.switchSleepMode)

        switchSleepMode.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                blackScreenOverlay.isVisible = true
                window.attributes = window.attributes.apply { screenBrightness = 0.0f }
            } else {
                blackScreenOverlay.isGone = true
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
        yoloDetector = YoloDetector(this); mediaPipeDetector = MediaPipeDetector(this); audioListener = AudioListener(this)
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
                        if (isStreaming != isCurrentlyStreaming) {
                            isCurrentlyStreaming = isStreaming
                            if (isStreaming) {
                                audioListener.pauseListening()
                                runOnUiThread {
                                    tvStatus.text = "📹 Streaming Active (AI Paused)"
                                    tvStatus.setBackgroundResource(R.drawable.pill_background) // Keep the shape
                                    tvStatus.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#00BCD4"))
                                }
                            } else {
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
                                } catch (_: Exception) { videoSocket?.close(); videoSocket = null } // THE FIX: "_" replaces unused "e"
                            }
                        }
                        if (!isStreaming && currentTime - lastYoloScanTime > 333) {
                            lastYoloScanTime = currentTime
                            if (motionDetector.hasMotion(bitmap)) {
                                val yoloResult = yoloDetector.detect(bitmap)
                                if (yoloResult != null) {
                                    runOnUiThread {
                                        val status = if (yoloResult.isStanding) "🚨 DANGER (Baby Standing!)" else "🟢 Baby is Awake"
                                        tvStatus.text = status

                                        var base64Image = ""
                                        if (status.contains("DANGER")) {
                                            val stream = java.io.ByteArrayOutputStream()
                                            bitmap.compress(Bitmap.CompressFormat.JPEG, 40, stream)
                                            base64Image = android.util.Base64.encodeToString(stream.toByteArray(), android.util.Base64.NO_WRAP)
                                        }
                                        val json = org.json.JSONObject().apply { put("status", status); put("is_crying", false); put("image", base64Image) }.toString()
                                        AlertClient(parentIpAddress!!, 8888, json).start()
                                    }
                                }
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