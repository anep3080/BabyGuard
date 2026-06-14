package com.example.babyguard

import android.graphics.Bitmap
import android.util.Log

class SafetyPipeline(
    private val motionDetector: MotionDetector,
    private val yoloDetector: YoloDetector,
    private val emotionDetector: EmotionDetector,
    private val aiGovernor: AIGovernor
) {
    enum class State { DORMANT, ACTIVE }
    
    private var currentState = State.DORMANT
    fun getState(): State = currentState

    private var lastFullScanTime = 0L
    private val HEARTBEAT_INTERVAL = 3000L
    
    private var suffocationTimerStart = 0L
    private val SUFFOCATION_THRESHOLD = 5000L

    private val resultBuffer = mutableListOf<DetectionResult>()
    private val BUFFER_SIZE = 8

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
        val currentTime = System.currentTimeMillis()
        
        val motionPixels = motionDetector.getMotionPixelCount(bitmap)
        val hasMotion = motionPixels > 8000 
        
        // Logarithmic-style scaling to stop binary meter feel
        // Range 0-100. Lower sensitivity at bottom, high at top.
        val motionLevel = if (motionPixels < 2000) 0 
                         else (Math.sqrt(motionPixels.toDouble() / 1000.0) * 10).toInt().coerceAtMost(100)
        
        val isHeartbeat = currentTime - lastFullScanTime > HEARTBEAT_INTERVAL

        if (!hasMotion && !isHeartbeat && currentState == State.DORMANT) {
            val dormant = DetectionResult("🟢 Sleeping Soundly", "Sleeping", "Safe", false, false, motionLevel, motionPixels, tier = "LOW", action = "Sleeping")
            addToBuffer(dormant); return dormant
        }

        if (isHeartbeat) lastFullScanTime = currentTime
        currentState = if (hasMotion || isHeartbeat) State.ACTIVE else State.DORMANT
        
        val yoloResult = yoloDetector.detect(bitmap)
        var status = if (hasMotion) "🟢 Baby is Active" else "🟢 Monitoring"
        var mood = "Searching..."
        var posture = "Safe"
        var isProne = false; var isStanding = false
        var bodyBox: android.graphics.RectF? = null; var faceRect: android.graphics.RectF? = null
        var keypoints: List<Keypoint> = emptyList(); var tier = "LOW"; var action = "Normal"

        if (yoloResult != null) {
            isProne = yoloResult.isProne; isStanding = yoloResult.isStanding
            posture = if (isStanding) "Standing" else if (isProne) "Face Down" else "Safe"
            keypoints = yoloResult.keypoints; bodyBox = yoloResult.box
            
            if (isStanding) { tier = "HIGH"; action = "Standing" }
            else if (isProne) { tier = "HIGH"; action = "Face Down" }
            else if (hasMotion) { tier = "MEDIUM"; action = "Active" }

            val faceCrop = yoloDetector.getFaceCrop(bitmap, keypoints)
            if (faceCrop != null) {
                mood = emotionDetector.detectMood(faceCrop); faceRect = calculateFaceRect(keypoints)
                if (mood == "Fussy") { tier = "MEDIUM"; action = "Fussy" }
            } else if (isProne) { mood = "Hidden" } else {
                if (suffocationTimerStart == 0L) suffocationTimerStart = currentTime
                if (currentTime - suffocationTimerStart > SUFFOCATION_THRESHOLD) { status = "🚨 SUFFOCATION RISK"; tier = "HIGH"; action = "Suffocation" }
                mood = "Analyzing..."
            }
            if (status.startsWith("🟢") && tier == "LOW") status = "🟢 Baby Awake"
            if (tier == "HIGH") status = if (isStanding) "🚨 DANGER: Standing" else if (isProne) "⚠️ Prone Risk" else status
        } else {
            suffocationTimerStart = 0L; mood = if (hasMotion) "Analyzing..." else "Sleeping"
        }

        val latestResult = DetectionResult(status, mood, posture, isProne, isStanding, motionLevel, motionPixels, keypoints, faceRect, bodyBox, tier, action)
        addToBuffer(latestResult)
        return getConsensusResult()
    }

    private fun calculateFaceRect(keypoints: List<Keypoint>): android.graphics.RectF? {
        if (keypoints.size < 5) return null
        val nose = keypoints[0]; val lE = keypoints[1]; val rE = keypoints[2]
        if (nose.confidence < 0.2f) return null
        val headSize = Math.abs(rE.position.x - lE.position.x) * 4f
        return android.graphics.RectF(nose.position.x - headSize/2, nose.position.y - headSize/1.5f, nose.position.x + headSize/2, nose.position.y + headSize/3f)
    }

    private fun addToBuffer(result: DetectionResult) { resultBuffer.add(result); if (resultBuffer.size > BUFFER_SIZE) resultBuffer.removeAt(0) }

    private fun getConsensusResult(): DetectionResult {
        if (resultBuffer.isEmpty()) return DetectionResult("---", "---", "---", false, false, 0)
        val statusFreq = resultBuffer.groupingBy { it.status }.eachCount()
        val consensusStatus = statusFreq.maxByOrNull { it.value }?.key ?: resultBuffer.last().status
        val bestSample = resultBuffer.findLast { it.status == consensusStatus } ?: resultBuffer.last()
        return bestSample.copy(status = consensusStatus)
    }
}