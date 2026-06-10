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
    val isMouthWideOpen: Boolean = false,
    val isSleeping: Boolean = false
)

class MediaPipeDetector(context: Context) {

    private val options = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST) // Cool battery!
        .setMinFaceSize(0.04f) // See tiny faces from across the room!
        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
        .build()

    private val detector = FaceDetection.getClient(options)

    init {
        Log.i("BabyGuard_Face", "✅ Google ML Kit Face Detector Loaded Successfully!")
    }

    fun diagnoseFace(bitmap: Bitmap): FaceDiagnosis {
        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val faces = Tasks.await(detector.process(image))

            val faceCount = faces.size
            return FaceDiagnosis(isVisible = faceCount > 0)

        } catch (e: Exception) {
            Log.e("BabyGuard_Face", "❌ ML Kit Face scan error: ${e.message}")
            return FaceDiagnosis(false)
        }
    }

    fun close() {
        detector.close()
    }
}