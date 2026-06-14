package com.example.babyguard

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

class EmotionDetector(context: Context) {

    private var interpreter: Interpreter? = null
    
    // Mapping from provided model labels (0-6) to BabyGuard moods
    private val moodMap = mapOf(
        0 to "Happy",    // 0 Happy
        1 to "Fussy",    // 1 Sad -> Fussy
        2 to "Active",   // 2 Surprised -> Active
        3 to "Fussy",    // 3 Fearful -> Fussy
        4 to "Fussy",    // 4 Angry -> Fussy
        5 to "Fussy",    // 5 Disgusted -> Fussy
        6 to "Calm"      // 6 Neutral -> Calm
    )

    init {
        try {
            // Loading your provided model.tflite
            val model = loadModelFile(context, "model.tflite")
            val options = Interpreter.Options()
            options.setUseNNAPI(true) // Accelerate on recycled phones
            interpreter = Interpreter(model, options)
            Log.i("BabyGuard_Emotion", "Emotion model.tflite loaded successfully!")
        } catch (e: Exception) {
            Log.e("BabyGuard_Emotion", "Error loading emotion model: ${e.message}")
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

    fun detectMood(faceBitmap: Bitmap): String {
        if (interpreter == null) return "Unknown"

        try {
            // 1. Preprocess: 48x48 Grayscale (most emotion models use this format)
            val resized = Bitmap.createScaledBitmap(faceBitmap, 48, 48, true)
            val inputBuffer = convertBitmapToGrayscaleBuffer(resized)
            
            // 2. Run Inference: Output [1, 7]
            val output = Array(1) { FloatArray(7) }
            interpreter?.run(inputBuffer, output)

            // 3. Post-process: Find max confidence
            var maxIdx = 0
            var maxConf = 0f
            for (i in 0 until 7) {
                if (output[0][i] > maxConf) {
                    maxConf = output[0][i]
                    maxIdx = i
                }
            }

            // FIX: Don't report emotion when the model is unsure — below 35% it's noise.
            // Baby faces are out-of-distribution for adult FER models so confidence is often low.
            if (maxConf < 0.35f) return "Calm"

            return moodMap[maxIdx] ?: "Calm"

        } catch (e: Exception) {
            Log.e("BabyGuard_Emotion", "Mood detection failed: ${e.message}")
            return "Calm"
        }
    }

    private fun convertBitmapToGrayscaleBuffer(bitmap: Bitmap): ByteBuffer {
        val byteBuffer = ByteBuffer.allocateDirect(4 * 48 * 48 * 1) // 1 channel
        byteBuffer.order(ByteOrder.nativeOrder())
        
        val intValues = IntArray(48 * 48)
        bitmap.getPixels(intValues, 0, 48, 0, 0, 48, 48)
        
        for (pixelValue in intValues) {
            val r = (pixelValue shr 16) and 0xFF
            val g = (pixelValue shr 8) and 0xFF
            val b = pixelValue and 0xFF
            // FIX: ITU-R BT.601 luminance — matches how FER2013 and most emotion models
            // were trained. Simple average (r+g+b)/3 shifts the perceived brightness and
            // makes the model see a different face than it was trained on.
            val gray = (0.299f * r + 0.587f * g + 0.114f * b) / 255.0f
            byteBuffer.putFloat(gray)
        }
        return byteBuffer
    }

    fun close() {
        interpreter?.close()
    }
}