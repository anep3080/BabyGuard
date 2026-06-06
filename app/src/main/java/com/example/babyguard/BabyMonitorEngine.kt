package com.example.babyguard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * Keypoint data structure for Pose Estimation (YOLOv8-Pose).
 */
data class PoseKeypoint(val name: String, val x: Float, val y: Float, val confidence: Float)

/**
 * Multimodal Emotion & State Detector for BabyGuard.
 */
class BabyMonitorEngine(
    private val context: Context,
    private val motionDetector: MotionDetector,
    private val yoloDetector: YoloDetector,
    private val faceDetector: MediaPipeDetector, // Note: We use this for fallback or advanced diagnosis
    private val audioListener: AudioListener,
    private val onAlertTriggered: (String, String) -> Unit
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // --- Observable States ---
    private val _isStreamingActive = MutableStateFlow(false)
    val isStreamingActive = _isStreamingActive.asStateFlow()

    private val _liveInsight = MutableStateFlow<String>("")
    val liveInsight = _liveInsight.asStateFlow()

    // --- State Machine Variables ---
    @Volatile private var safetyStatus = "SAFE"
    @Volatile private var posture = "SUPINE"
    @Volatile private var currentState = "AWAKE" // SLEEPING, AWAKE, CRYING
    @Volatile private var facialEmotion = "NEUTRAL" // HAPPY, NEUTRAL, N/A
    @Volatile private var isBabyCrying = false

    // --- Timing & Hysteresis ---
    private var lastMotionTimestamp = System.currentTimeMillis()
    private val SLEEP_THRESHOLD_MS = 120000L // 2 Minutes of no motion = SLEEPING
    private var lastAITimestamp = 0L
    private val AI_FPS_LIMIT_MS = 200L // ~5 FPS to save battery

    private var proneTimerJob: Job? = null
    private val SIDS_CRITICAL_DELAY = 10000L

    init {
        startInsightEmitter()
        startAudioPolling()
    }

    fun onStreamStarted() { _isStreamingActive.value = true }
    fun onStreamStopped() { _isStreamingActive.value = false }

    /**
     * Priority 1: Audio Watchdog (Source of Truth for Crying)
     */
    private fun startAudioPolling() {
        scope.launch {
            audioListener.startListening()
            while (isActive) {
                isBabyCrying = audioListener.isBabyCrying()
                if (isBabyCrying) {
                    currentState = "CRYING"
                    if (safetyStatus == "SAFE") safetyStatus = "WARNING"
                }
                delay(1000)
            }
        }
    }

    /**
     * Main Pipeline: Called by Camera frames
     */
    fun onFrameAvailable(bitmap: Bitmap) {
        val now = System.currentTimeMillis()
        if (now - lastAITimestamp < AI_FPS_LIMIT_MS) return
        lastAITimestamp = now

        scope.launch(Dispatchers.Default) {
            // Level 1: Motion Analysis
            val hasMotion = motionDetector.hasMotion(bitmap)
            if (hasMotion) {
                lastMotionTimestamp = now
            }

            // Level 2: YOLOv8-Pose for Posture & Face ROI
            val yoloResult = yoloDetector.detect(bitmap)
            val keypoints = if (yoloResult != null) mapToCocoKeypoints(yoloResult) else null

            // Level 3: Multimodal Fusion
            evaluateBabyState(keypoints, bitmap)
        }
    }

    /**
     * Sensor Fusion Logic: Fuses Audio, Motion, and Visual Emotion
     */
    private fun evaluateBabyState(keypoints: List<PoseKeypoint>?, fullBitmap: Bitmap) {
        if (keypoints == null) return

        // 1. Posture Calculation
        val nose = keypoints.find { it.name == "nose" }
        val lEye = keypoints.find { it.name == "left_eye" }
        val rEye = keypoints.find { it.name == "right_eye" }
        val lShoulder = keypoints.find { it.name == "left_shoulder" }
        val rShoulder = keypoints.find { it.name == "right_shoulder" }

        val nConf = nose?.confidence ?: 0f
        val leConf = lEye?.confidence ?: 0f
        val reConf = rEye?.confidence ?: 0f

        val newPosture = when {
            nConf > 0.6f && (leConf > 0.6f || reConf > 0.6f) -> "SUPINE"
            (leConf > 0.6f && reConf < 0.3f) || (reConf > 0.6f && leConf < 0.3f) -> "SIDEWAY"
            (lShoulder?.confidence ?: 0f > 0.5f || rShoulder?.confidence ?: 0f > 0.5f) && 
                    (nConf < 0.3f && leConf < 0.3f && reConf < 0.3f) -> "PRONE_RISK"
            else -> posture
        }

        if (newPosture != posture) {
            posture = newPosture
            if (posture == "PRONE_RISK") startProneTimer() else cancelProneTimer()
        }

        // 2. Multimodal State & Emotion
        val timeSinceMotion = System.currentTimeMillis() - lastMotionTimestamp

        when {
            isBabyCrying -> {
                currentState = "CRYING"
                facialEmotion = "N/A" // Usually distorted by crying
            }
            timeSinceMotion > SLEEP_THRESHOLD_MS && (posture == "SUPINE" || posture == "SIDEWAY") -> {
                currentState = "SLEEPING"
                facialEmotion = "NEUTRAL"
            }
            else -> {
                currentState = "AWAKE"
                // Extract face and classify emotion only if awake to save CPU
                val faceBitmap = cropFaceFromPose(keypoints, fullBitmap)
                facialEmotion = if (faceBitmap != null) {
                    val diagnosis = faceDetector.diagnoseFace(faceBitmap)
                    if (diagnosis.isVisible) diagnosis.emotion.uppercase() else "NEUTRAL"
                } else {
                    "N/A"
                }
            }
        }

        // 3. Safety Sync
        if (safetyStatus != "CRITICAL_SIDS_WARNING") {
            safetyStatus = when {
                posture == "PRONE_RISK" -> "DANGER"
                currentState == "CRYING" -> "WARNING"
                else -> "SAFE"
            }
        }
    }

    /**
     * Helper: Dynamically crops face from the Bitmap using YOLO pose keypoints.
     */
    private fun cropFaceFromPose(keypoints: List<PoseKeypoint>, bitmap: Bitmap): Bitmap? {
        val nose = keypoints.find { it.name == "nose" } ?: return null
        val lEye = keypoints.find { it.name == "left_eye" } ?: return null
        val rEye = keypoints.find { it.name == "right_eye" } ?: return null
        
        // Basic heuristic: Calculate head size based on eye distance
        val eyeDist = Math.sqrt(Math.pow((rEye.x - lEye.x).toDouble(), 2.0) + Math.pow((rEye.y - lEye.y).toDouble(), 2.0)).toFloat()
        if (eyeDist == 0f) return null

        val margin = eyeDist * 1.5f // Expand box to include forehead and chin
        
        val left = (nose.x - margin).coerceAtLeast(0f) * bitmap.width
        val top = (nose.y - margin).coerceAtLeast(0f) * bitmap.height
        val right = (nose.x + margin).coerceAtMost(1f) * bitmap.width
        val bottom = (nose.y + margin).coerceAtMost(1f) * bitmap.height

        val width = (right - left).toInt()
        val height = (bottom - top).toInt()

        return if (width > 0 && height > 0) {
            Bitmap.createBitmap(bitmap, left.toInt(), top.toInt(), width, height)
        } else null
    }

    private fun startProneTimer() {
        if (proneTimerJob?.isActive == true) return
        proneTimerJob = scope.launch {
            delay(SIDS_CRITICAL_DELAY)
            safetyStatus = "CRITICAL_SIDS_WARNING"
            onAlertTriggered("CRITICAL_SIDS_WARNING", "SIDS RISK: Baby face-down too long!")
        }
    }

    private fun cancelProneTimer() {
        proneTimerJob?.cancel()
        if (safetyStatus == "CRITICAL_SIDS_WARNING") safetyStatus = "SAFE"
    }

    /**
     * Emitters: Generates JSON Insight Payload exactly as per requirements.
     */
    private fun startInsightEmitter() {
        scope.launch {
            while (isActive) {
                val json = JSONObject().apply {
                    put("timestamp", System.currentTimeMillis())
                    put("safety_status", safetyStatus)
                    put("posture", posture)
                    put("current_state", currentState)
                    put("facial_emotion", if (posture == "PRONE_RISK") "N/A" else facialEmotion)
                    put("is_streaming_active", _isStreamingActive.value)
                }
                _liveInsight.value = json.toString()
                delay(1000)
            }
        }
    }

    private fun mapToCocoKeypoints(box: BoundingBox): List<PoseKeypoint> {
        val c = box.confidence
        // Simulated COCO mapping for logic - in production, this comes from YOLOv8-Pose output
        return listOf(
            PoseKeypoint("nose", 0.5f, 0.4f, c),
            PoseKeypoint("left_eye", 0.45f, 0.38f, c),
            PoseKeypoint("right_eye", 0.55f, 0.38f, c),
            PoseKeypoint("left_shoulder", 0.4f, 0.6f, c),
            PoseKeypoint("right_shoulder", 0.6f, 0.6f, c)
        )
    }

    fun stop() {
        scope.cancel()
        audioListener.stopListening()
    }
}
