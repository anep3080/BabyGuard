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

    private val resultBuffer = mutableListOf<DetectionResult>()
    private val BUFFER_SIZE = 5

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
        val bodyBox: android.graphics.RectF? = null
    )

    fun processFrame(bitmap: Bitmap): DetectionResult {
        // Stage 1: Motion Detection (Gatekeeper)
        val motionPixels = motionDetector.getMotionPixelCount(bitmap)
        val hasMotion = motionPixels > 2500 // Threshold from MotionDetector
        val motionLevel = if (hasMotion) (60..100).random() else (0..10).random()

        if (!hasMotion && currentState == State.DORMANT) {
            return DetectionResult("🟢 Sleeping Soundly", "Sleeping", "Safe", false, false, motionLevel, motionPixels)
        }

        // Stage 2: Wake up AI if motion detected or already active
        currentState = if (hasMotion) State.ACTIVE else State.DORMANT
        
        val yoloResult = yoloDetector.detect(bitmap)
        var status = if (hasMotion) "🟢 Baby is Active" else "🟢 Monitoring"
        var mood = "Calm"
        var posture = "Safe"
        var isProne = false
        var isStanding = false
        var bodyBox: android.graphics.RectF? = null
        var faceRect: android.graphics.RectF? = null
        var keypoints: List<Keypoint> = emptyList()

        if (yoloResult != null) {
            isProne = yoloResult.isProne
            isStanding = yoloResult.isStanding
            posture = if (isStanding) "Standing" else if (isProne) "Face Down" else "Safe"
            keypoints = yoloResult.keypoints
            bodyBox = yoloResult.box
            
            // Stage 3: Emotion Analysis
            val faceCrop = yoloDetector.getFaceCrop(bitmap, keypoints)
            if (faceCrop != null) {
                mood = emotionDetector.detectMood(faceCrop)
                // Approximate face rect for diagnostics
                faceRect = calculateFaceRect(keypoints)
            } else if (isProne) {
                mood = "Hidden"
            } else {
                mood = "Calm"
            }
            
            status = if (isStanding) "🚨 DANGER: Standing" else if (isProne) "⚠️ Prone Risk" else "🟢 Baby Awake"
        }

        val latestResult = DetectionResult(status, mood, posture, isProne, isStanding, motionLevel, motionPixels, keypoints, faceRect, bodyBox)
        
        // Stage 4: Temporal Voting (Industry Stability)
        addToBuffer(latestResult)
        return getConsensusResult()
    }

    private fun calculateFaceRect(keypoints: List<Keypoint>): android.graphics.RectF? {
        if (keypoints.size < 5) return null
        val nose = keypoints[PoseJoints.NOSE]
        val lEye = keypoints[PoseJoints.LEFT_EYE]
        val rEye = keypoints[PoseJoints.RIGHT_EYE]
        
        if (nose.confidence < 0.3f) return null
        
        val faceWidth = Math.abs(rEye.position.x - lEye.position.x) * 3.5f
        val faceHeight = faceWidth
        return android.graphics.RectF(
            nose.position.x - faceWidth/2,
            nose.position.y - faceHeight/1.5f,
            nose.position.x + faceWidth/2,
            nose.position.y + faceHeight/3f
        )
    }

    private fun addToBuffer(result: DetectionResult) {
        resultBuffer.add(result)
        if (resultBuffer.size > BUFFER_SIZE) {
            resultBuffer.removeAt(0)
        }
    }

    private fun getConsensusResult(): DetectionResult {
        if (resultBuffer.isEmpty()) return DetectionResult("---", "---", "---", false, false, 0)
        
        // Find most frequent status and mood to prevent jitter
        val statusFreq = resultBuffer.groupingBy { it.status }.eachCount()
        val moodFreq = resultBuffer.groupingBy { it.mood }.eachCount()
        val postureFreq = resultBuffer.groupingBy { it.posture }.eachCount()
        
        val consensusStatus = statusFreq.maxByOrNull { it.value }?.key ?: resultBuffer.last().status
        val consensusMood = moodFreq.maxByOrNull { it.value }?.key ?: resultBuffer.last().mood
        val consensusPosture = postureFreq.maxByOrNull { it.value }?.key ?: resultBuffer.last().posture

        return resultBuffer.last().copy(
            status = consensusStatus,
            mood = consensusMood,
            posture = consensusPosture
        )
    }
}