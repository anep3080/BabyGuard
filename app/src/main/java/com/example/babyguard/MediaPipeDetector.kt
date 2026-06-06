package com.example.babyguard

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.android.gms.tasks.Tasks

data class FaceDiagnosis(
    val isVisible: Boolean,
    val emotion: String = "Neutral",
    val isEyesClosed: Boolean = false,
    val confidence: Float = 0f
)

class MediaPipeDetector(context: Context) {

    private val options = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
        .setMinFaceSize(0.1f)
        .build()

    private val detector = FaceDetection.getClient(options)

    init {
        Log.i("BabyGuard_Face", "✅ Google ML Kit Face Detector Loaded Successfully!")
    }

    fun diagnoseFace(bitmap: Bitmap): FaceDiagnosis {
        return try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val faces = Tasks.await(detector.process(image))

            if (faces.isEmpty()) return FaceDiagnosis(false)

            val face = faces[0]
            val isSmiling = (face.smilingProbability ?: 0f) > 0.6f
            val eyesClosed = (face.leftEyeOpenProbability ?: 1f) < 0.4f && (face.rightEyeOpenProbability ?: 1f) < 0.4f

            val emotion = when {
                isSmiling -> "Happy"
                eyesClosed -> "Sleeping"
                else -> "Neutral/Observing"
            }

            FaceDiagnosis(
                isVisible = true,
                emotion = emotion,
                isEyesClosed = eyesClosed,
                confidence = face.headEulerAngleY
            )
        } catch (e: Exception) {
            Log.e("BabyGuard_Face", "❌ ML Kit Face scan error: ${e.message}")
            FaceDiagnosis(false)
        }
    }

    fun close() {
        detector.close()
    }
}