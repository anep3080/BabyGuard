package com.example.babyguard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import android.os.Build
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
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
    val isProne: Boolean,
    val keypoints: List<Keypoint> = emptyList()
)

class YoloDetector(context: Context) {

    private var interpreter: Interpreter? = null
    private var useGpu = false

    init {
        try {
            val model = loadModelFile(context, "yolov8n-pose.tflite")
            val options = Interpreter.Options()
            
            val modelName = Build.MODEL.lowercase()
            val isOld = modelName.contains("note 9") || modelName.contains("crown") || 
                        modelName.contains("s9") || modelName.contains("star")

            if (CompatibilityList().isDelegateSupportedOnThisDevice && !isOld) {
                options.addDelegate(GpuDelegate())
                useGpu = true
                Log.i("BabyGuard_AI", "🚀 GPU Active")
            } else {
                options.setNumThreads(2)
                options.setUseXNNPACK(true)
                Log.i("BabyGuard_AI", "🛡️ CPU Stability Mode")
            }
            
            interpreter = Interpreter(model, options)
            Log.i("BabyGuard_AI", "Model ready")
        } catch (e: Exception) { Log.e("BabyGuard_AI", "Init Error: ${e.message}") }
    }

    private fun loadModelFile(context: Context, modelName: String): MappedByteBuffer {
        val fd = context.assets.openFd(modelName)
        return FileInputStream(fd.fileDescriptor).channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
    }

    fun detect(bitmap: Bitmap): BoundingBox? {
        if (interpreter == null) return null
        val size = 640
        val resized = Bitmap.createScaledBitmap(bitmap, size, size, true)
        val input = ByteBuffer.allocateDirect(1 * size * size * 3 * 4).order(ByteOrder.nativeOrder())
        
        val pixels = IntArray(size * size); resized.getPixels(pixels, 0, size, 0, 0, size, size)
        for (p in pixels) {
            input.putFloat(((p shr 16) and 0xFF) / 255.0f)
            input.putFloat(((p shr 8) and 0xFF) / 255.0f)
            input.putFloat((p and 0xFF) / 255.0f)
        }

        // YOLOv8n-pose Output: 1 x 56 x 8400
        val output = Array(1) { Array(56) { FloatArray(8400) } }
        try { interpreter?.run(input, output) } catch (e: Exception) { 
            Log.e("BabyGuard_AI", "Inference error: ${e.message}")
            return null 
        }

        var bestScore = 0f; var bestIdx = -1
        for (i in 0 until 8400) {
            val score = output[0][4][i]
            if (score > 0.30f && score > bestScore) { bestScore = score; bestIdx = i }
        }

        if (bestIdx != -1) {
            var cx = output[0][0][bestIdx]; var cy = output[0][1][bestIdx]
            var w = output[0][2][bestIdx]; var h = output[0][3][bestIdx]
            if (cx < 2.0f) { cx *= 640f; cy *= 640f; w *= 640f; h *= 640f }

            val kpts = mutableListOf<Keypoint>()
            for (k in 0 until 17) {
                var kx = output[0][5 + k * 3][bestIdx]
                var ky = output[0][5 + k * 3 + 1][bestIdx]
                val kc = output[0][5 + k * 3 + 2][bestIdx]
                if (output[0][0][bestIdx] < 2.0f) { kx *= 640f; ky *= 640f }
                kpts.add(Keypoint(k, PointF(kx, ky), kc))
            }

            val nose = kpts[0]; val lHip = kpts[11]; val rHip = kpts[12]
            val hipY = (lHip.position.y + rHip.position.y) / 2
            val isStanding = h > (w * 1.35f) && nose.position.y < hipY && nose.confidence > 0.3f
            val isProne = (kpts[5].confidence > 0.4f || kpts[6].confidence > 0.4f) && nose.confidence < 0.2f

            return BoundingBox(RectF(cx-w/2, cy-h/2, cx+w/2, cy+h/2), "baby", bestScore, isStanding, isProne, kpts)
        }
        return null
    }

    fun getFaceCrop(bitmap: Bitmap, keypoints: List<Keypoint>): Bitmap? {
        if (keypoints.size < 5) return null
        val nose = keypoints[0]; val lEye = keypoints[1]; val rEye = keypoints[2]
        if (nose.confidence < 0.2f) return null
        val headSize = Math.abs(rEye.position.x - lEye.position.x) * 4f
        val l = (nose.position.x - headSize/2).toInt().coerceIn(0, 630)
        val t = (nose.position.y - headSize/1.5f).toInt().coerceIn(0, 630)
        val s = headSize.toInt().coerceIn(10, 640 - l)
        return try { Bitmap.createBitmap(bitmap, l, t, s, s) } catch (_: Exception) { null }
    }

    fun close() { interpreter?.close() }
}