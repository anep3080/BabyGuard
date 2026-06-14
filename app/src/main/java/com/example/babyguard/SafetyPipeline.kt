package com.example.babyguard

import android.graphics.Bitmap
import android.util.Log

class SafetyPipeline(
    private val motionDetector: MotionDetector,
    private val yoloDetector: YoloDetector,
    private val emotionDetector: EmotionDetector,
    private val aiGovernor: AIGovernor,
    private var sensitivity: Int = 2   // 1-3 from AppPreferences, mutable for live updates
) {
    enum class State { DORMANT, ACTIVE }

    private var currentState = State.DORMANT
    fun getState(): State = currentState
    fun updateSensitivity(s: Int) { sensitivity = s.coerceIn(1, 3) }

    private var lastFullScanTime = 0L

    // ── Motion thresholds scaled by sensitivity ───────────────────────────────
    // sensitivity 1 (Low)    → noisy environment, need big movement to trigger
    // sensitivity 2 (Normal) → balanced default
    // sensitivity 3 (High)   → quiet nursery, picks up even micro-movements
    private val motionThreshold get() = when (sensitivity) { 1 -> 12_000; 3 -> 4_000; else -> 8_000 }

    // ── Posture confirmation counters ─────────────────────────────────────────
    // Require N consecutive positive frames before raising an alert.
    // Eliminates single-frame false positives from YOLO keypoint jitter.
    private var proneStreak    = 0
    private var standingStreak = 0
    private val POSTURE_CONFIRM = 3     // frames

    // ── Suffocation timer ─────────────────────────────────────────────────────
    private var suffocationTimerStart = 0L
    private val SUFFOCATION_THRESHOLD = 5000L

    // ── Emotion rolling vote ──────────────────────────────────────────────────
    // Return the most-voted mood over the last MOOD_WINDOW frames so the label
    // doesn't flicker every time the face crop shifts slightly.
    private val moodHistory = ArrayDeque<String>(7)
    private val MOOD_WINDOW = 5

    // ── Result consensus buffer ───────────────────────────────────────────────
    private val resultBuffer = mutableListOf<DetectionResult>()
    private val BUFFER_SIZE = 6   // smaller than before → responds faster to real changes

    // ─────────────────────────────────────────────────────────────────────────

    data class DetectionResult(
        val status: String,
        val mood: String,
        val posture: String,
        val isProne: Boolean,
        val isStanding: Boolean,
        val motionLevel: Int,
        val motionPixels: Int = 0,
        val keypoints: List<Keypoint> = emptyList(),
        val faceRect: android.graphics.RectF? = null,
        val bodyBox: android.graphics.RectF? = null,
        val tier: String = "LOW",
        val action: String = "Normal"
    )

    fun processFrame(bitmap: Bitmap): DetectionResult {
        val now = System.currentTimeMillis()
        val heartbeatMs = aiGovernor.getHeartbeatInterval()

        val motionPixels = motionDetector.getMotionPixelCount(bitmap, sensitivity)
        val filteredPixels = if (motionPixels < when (sensitivity) { 1 -> 2500; 3 -> 600; else -> 1500 }) 0 else motionPixels
        val hasMotion = filteredPixels > motionThreshold

        // Logarithmic motion level (0-100 display scale)
        val motionLevel = if (filteredPixels == 0) 0
                          else (Math.sqrt(filteredPixels.toDouble() / 1200.0) * 8).toInt().coerceAtMost(100)

        val isHeartbeat = now - lastFullScanTime > heartbeatMs

        // ── Fast path: truly dormant, no heartbeat due ────────────────────────
        if (!hasMotion && !isHeartbeat && currentState == State.DORMANT) {
            motionDetector.setActiveLearningRate(false)   // let background adapt quickly
            val dormant = DetectionResult("🟢 Sleeping Soundly", "Sleeping", "Safe",
                false, false, motionLevel, filteredPixels, tier = "LOW", action = "Sleeping")
            addToBuffer(dormant)
            return dormant
        }

        if (isHeartbeat) lastFullScanTime = now
        val newState = if (hasMotion || isHeartbeat) State.ACTIVE else State.DORMANT
        motionDetector.setActiveLearningRate(newState == State.ACTIVE)
        currentState = newState

        // ── Full AI scan ──────────────────────────────────────────────────────
        val yoloResult = yoloDetector.detect(bitmap)
        var status  = if (hasMotion) "🟢 Baby is Active" else "🟢 Monitoring"
        var posture = "Safe"
        var isProne = false; var isStanding = false
        var bodyBox: android.graphics.RectF? = null
        var faceRect: android.graphics.RectF? = null
        var keypoints: List<Keypoint> = emptyList()
        var tier   = "LOW"
        var action = "Normal"

        if (yoloResult != null) {
            keypoints = yoloResult.keypoints
            bodyBox   = yoloResult.box

            // ── Posture with confirmation streaks ────────────────────────────
            if (yoloResult.isProne)    proneStreak++    else proneStreak = 0
            if (yoloResult.isStanding) standingStreak++ else standingStreak = 0

            val confirmedProne    = proneStreak    >= POSTURE_CONFIRM
            val confirmedStanding = standingStreak >= POSTURE_CONFIRM

            isProne    = confirmedProne
            isStanding = confirmedStanding
            posture    = when {
                isStanding -> "Standing"
                isProne    -> "Face Down"
                else       -> "Safe"
            }
            when {
                isStanding -> { tier = "HIGH"; action = "Standing" }
                isProne    -> { tier = "HIGH"; action = "Face Down" }
                else       -> { tier = "LOW";  action = if (hasMotion) "Active" else "Normal" }
            }

            // ── Emotion with rolling vote ────────────────────────────────────
            val faceCrop = yoloDetector.getFaceCrop(bitmap, keypoints)
            val rawMood = if (faceCrop != null) {
                faceRect = calculateFaceRect(keypoints)
                emotionDetector.detectMood(faceCrop)
            } else {
                if (isProne) "Hidden" else {
                    // Face not visible when not prone → start suffocation clock
                    if (suffocationTimerStart == 0L) suffocationTimerStart = now
                    if (now - suffocationTimerStart > SUFFOCATION_THRESHOLD) {
                        status = "🚨 SUFFOCATION RISK"; tier = "HIGH"; action = "Suffocation"
                    }
                    "Analyzing..."
                }
            }

            // Only add real emotions to the voting history (not transient states)
            if (rawMood !in listOf("Hidden", "Analyzing...", "Unknown")) {
                moodHistory.addLast(rawMood)
                if (moodHistory.size > MOOD_WINDOW) moodHistory.removeFirst()
            }
            if (faceCrop != null) suffocationTimerStart = 0L

            val mood = if (moodHistory.isEmpty()) rawMood
                       else moodHistory.groupingBy { it }.eachCount().maxByOrNull { it.value }!!.key

            if (mood == "Fussy" && tier == "LOW") { tier = "MEDIUM"; action = "Fussy" }
            if (status.startsWith("🟢") && tier == "LOW") status = "🟢 Baby Awake"
            if (tier == "HIGH") status = when {
                isStanding -> "🚨 DANGER: Standing"
                isProne    -> "⚠️ Prone Risk"
                else       -> status
            }

            val result = DetectionResult(status, mood, posture, isProne, isStanding,
                motionLevel, filteredPixels, keypoints, faceRect, bodyBox, tier, action)
            addToBuffer(result)

        } else {
            // YOLO found nothing
            proneStreak = 0; standingStreak = 0; suffocationTimerStart = 0L
            val mood = if (hasMotion) "Analyzing..." else "Sleeping"
            val result = DetectionResult(status, mood, "None", false, false,
                motionLevel, filteredPixels, tier = "LOW", action = "Normal")
            addToBuffer(result)
        }

        return getConsensusResult()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun calculateFaceRect(keypoints: List<Keypoint>): android.graphics.RectF? {
        if (keypoints.size < 5) return null
        val nose = keypoints[0]; val lE = keypoints[1]; val rE = keypoints[2]
        if (nose.confidence < 0.2f) return null
        val headSize = Math.abs(rE.position.x - lE.position.x) * 4f
        return android.graphics.RectF(
            nose.position.x - headSize / 2,
            nose.position.y - headSize / 1.5f,
            nose.position.x + headSize / 2,
            nose.position.y + headSize / 3f
        )
    }

    private fun addToBuffer(result: DetectionResult) {
        resultBuffer.add(result)
        if (resultBuffer.size > BUFFER_SIZE) resultBuffer.removeAt(0)
    }

    /**
     * Consensus strategy:
     *  - Status: majority vote across the buffer (smooths out one-frame glitches).
     *  - Keypoints: always the most recent non-empty set (freshest tracking data).
     *  This means the skeleton stays smooth even when the status label is averaged.
     */
    private fun getConsensusResult(): DetectionResult {
        if (resultBuffer.isEmpty())
            return DetectionResult("---", "---", "---", false, false, 0)

        val consensusStatus = resultBuffer
            .groupingBy { it.status }.eachCount()
            .maxByOrNull { it.value }?.key ?: resultBuffer.last().status

        // Find the freshest frame with actual keypoint data
        val latestKeypoints = resultBuffer.findLast { it.keypoints.isNotEmpty() }

        val base = resultBuffer.findLast { it.status == consensusStatus } ?: resultBuffer.last()
        return if (latestKeypoints != null && latestKeypoints != base)
            base.copy(status = consensusStatus,
                      keypoints = latestKeypoints.keypoints,
                      bodyBox   = latestKeypoints.bodyBox,
                      faceRect  = latestKeypoints.faceRect)
        else
            base.copy(status = consensusStatus)
    }
}
