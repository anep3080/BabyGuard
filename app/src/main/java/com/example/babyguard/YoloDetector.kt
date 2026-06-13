package com.example.babyguard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

data class BoundingBox(
    val box: RectF,
    val label: String,
    val confidence: Float,
    val isStanding: Boolean,
    val isProne: Boolean, // SIDS Logic
    val keypoints: List<Keypoint> = emptyList() // Skeletal Data
)

class YoloDetector(context: Context) {

    private var interpreter: Interpreter? = null
    private var labels: List<String> = emptyList()

    init {
        try {
            // Load yolov8n-pose.tflite instead of yolo_baby.tflite
            val model = loadModelFile(context, "yolov8n-pose.tflite")
            val options = Interpreter.Options()
            
            try {
                options.addDelegate(org.tensorflow.lite.gpu.GpuDelegate())
                Log.i("BabyGuard_AI", "🚀 GPU Delegate enabled for Pose!")
            } catch (e: Exception) {
                options.setUseNNAPI(true)
            }

            interpreter = Interpreter(model, options)
            labels = listOf("baby") // Pose models usually have 1 class
            Log.i("BabyGuard_AI", "YOLOv8-Pose Model Loaded Successfully!")
        } catch (e: Exception) {
            Log.e("BabyGuard_AI", "Error loading YOLOv8-Pose model: ${e.message}")
        }
    }

    private fun loadModelFile(context: Context, modelName: String): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(modelName)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    fun detect(bitmap: Bitmap): BoundingBox? {
        // ADAPTIVE SCALING: Run at 320x320 if throttling is active to save resources
        val modelInputSize = if (org.tensorflow.lite.gpu.CompatibilityList().isDelegateSupportedOnThisDevice) 640 else 320
        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, modelInputSize, modelInputSize, true)
        val inputBuffer = convertBitmapToByteBuffer(resizedBitmap, modelInputSize)

        // YOLOv8n-Pose output is [1, 56, 8400]
        val output = Array(1) { Array(56) { FloatArray(8400) } }

        interpreter?.run(inputBuffer, output)

        var bestConfidence = 0f
        var bestBoxIndex = -1

        for (i in 0 until 8400) {
            val confidence = output[0][4][i]
            if (confidence > 0.45f && confidence > bestConfidence) {
                bestConfidence = confidence
                bestBoxIndex = i
            }
        }

        if (bestBoxIndex != -1) {
            val scale = 640f / modelInputSize
            val cx = output[0][0][bestBoxIndex] * 640f
            val cy = output[0][1][bestBoxIndex] * 640f
            val w = output[0][2][bestBoxIndex] * 640f
            val h = output[0][3][bestBoxIndex] * 640f

            val box = RectF(cx - w/2, cy - h/2, cx + w/2, cy + h/2)
            
            // Extract Keypoints
            val kpts = mutableListOf<Keypoint>()
            for (k in 0 until 17) {
                val kx = output[0][5 + k * 3][bestBoxIndex] * 640f
                val ky = output[0][5 + k * 3 + 1][bestBoxIndex] * 640f
                val kc = output[0][5 + k * 3 + 2][bestBoxIndex]
                kpts.add(Keypoint(k, PointF(kx, ky), kc))
            }

            // 3.3 Zero-Training SIDS Logic
            val nose = kpts[PoseJoints.NOSE]
            val lShoulder = kpts[PoseJoints.LEFT_SHOULDER]
            val rShoulder = kpts[PoseJoints.RIGHT_SHOULDER]
            
            // Prone Risk: Shoulders visible but nose is hidden
            val isProne = (lShoulder.confidence > 0.5f || rShoulder.confidence > 0.5f) && nose.confidence < 0.25f
            val isStanding = h > (w * 1.35f)

            return BoundingBox(box, "baby", bestConfidence, isStanding, isProne, kpts)
        }

        return null
    }

    fun getFaceCrop(bitmap: Bitmap, keypoints: List<Keypoint>): Bitmap? {
        if (keypoints.size < 5) return null
        
        val nose = keypoints[PoseJoints.NOSE]
        val lEye = keypoints[PoseJoints.LEFT_EYE]
        val rEye = keypoints[PoseJoints.RIGHT_EYE]

        // Only crop if face points are relatively confident
        if (nose.confidence < 0.3f && lEye.confidence < 0.3f && rEye.confidence < 0.3f) return null

        // Calculate a box around the face based on nose and eyes
        val faceWidth = Math.abs(rEye.position.x - lEye.position.x) * 3.5f
        val faceHeight = faceWidth // Keep it square for the CNN

        val left = (nose.position.x - faceWidth / 2).coerceAtLeast(0f)
        val top = (nose.position.y - faceHeight / 1.5f).coerceAtLeast(0f)
        
        val right = (left + faceWidth).coerceAtMost(bitmap.width.toFloat())
        val bottom = (top + faceHeight).coerceAtMost(bitmap.height.toFloat())

        if (right <= left || bottom <= top) return null

        return try {
            Bitmap.createBitmap(bitmap, left.toInt(), top.toInt(), (right - left).toInt(), (bottom - top).toInt())
        } catch (e: Exception) {
            null
        }
    }

    private fun convertBitmapToByteBuffer(bitmap: Bitmap, size: Int): ByteBuffer {
        val byteBuffer = ByteBuffer.allocateDirect(4 * size * size * 3)
        byteBuffer.order(ByteOrder.nativeOrder())
        val intValues = IntArray(size * size)
        bitmap.getPixels(intValues, 0, size, 0, 0, size, size)
        for (pixelValue in intValues) {
            byteBuffer.putFloat(((pixelValue shr 16) and 0xFF) / 255f)
            byteBuffer.putFloat(((pixelValue shr 8) and 0xFF) / 255f)
            byteBuffer.putFloat((pixelValue and 0xFF) / 255f)
        }
        return byteBuffer
    }

    fun close() {
        interpreter?.close()
    }
}