package com.example.babyguard // Make sure this matches!

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

// This tiny class holds our result so we can pass it to the UI
data class BoundingBox(
    val box: RectF,
    val label: String,
    val confidence: Float,
    val isStanding: Boolean // NEW: FYP specific logic for standing detection!
)

class YoloDetector(context: Context) {

    private var interpreter: Interpreter? = null
    private var labels: List<String> = emptyList()

    init {
        try {
            val model = loadModelFile(context, "yolo_baby.tflite")
            val options = Interpreter.Options()
            interpreter = Interpreter(model, options)
            labels = context.assets.open("labels.txt").bufferedReader().readLines()
            Log.i("BabyGuard_AI", "YOLOv8 Model Loaded Successfully!")
        } catch (e: Exception) {
            Log.e("BabyGuard_AI", "Error loading YOLOv8 model: ${e.message}")
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

    // --- MAGIC HAPPENS HERE ---

    fun detect(bitmap: Bitmap): BoundingBox? {
        // 1. Resize image to 640x640 (what the AI expects)
        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, 640, 640, true)
        val inputBuffer = convertBitmapToByteBuffer(resizedBitmap)

        // 2. Prepare the output array based on your logcat [1, 7, 8400]
        val output = Array(1) { Array(7) { FloatArray(8400) } }

        // 3. RUN THE AI!
        interpreter?.run(inputBuffer, output)

        // 4. Find the best guess out of the 8400
        var bestConfidence = 0f
        var bestClassIndex = -1
        var bestBoxIndex = -1

        for (i in 0 until 8400) {
            val class0 = output[0][4][i] // Prone
            val class1 = output[0][5][i] // Sideways
            val class2 = output[0][6][i] // Suspine

            val maxClassConf = maxOf(class0, class1, class2)

            // Lowered to 40% (0.4f) to make it more sensitive to the baby!
            if (maxClassConf > 0.4f && maxClassConf > bestConfidence) {
                bestConfidence = maxClassConf
                bestBoxIndex = i
                bestClassIndex = when (maxClassConf) {
                    class0 -> 0
                    class1 -> 1
                    else -> 2
                }
            }
        }

        // 5. If we found a baby, calculate the geometry!
        if (bestBoxIndex != -1) {
            val cx = output[0][0][bestBoxIndex] * 640f
            val cy = output[0][1][bestBoxIndex] * 640f
            val w = output[0][2][bestBoxIndex] * 640f
            val h = output[0][3][bestBoxIndex] * 640f

            // Convert center (cx, cy) and width/height into Left, Top, Right, Bottom
            val left = cx - (w / 2)
            val top = cy - (h / 2)
            val right = cx + (w / 2)
            val bottom = cy + (h / 2)

            val box = RectF(left, top, right, bottom)
            val label = labels.getOrElse(bestClassIndex) { "Unknown" }

            // FYP GEOMETRY LOGIC: If the box is 1.3x taller than it is wide, the baby is upright!
            val isStanding = h > (w * 1.3f)

            return BoundingBox(box, label, bestConfidence, isStanding)
        }

        return null // No baby found in this frame
    }

    private fun convertBitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        // 4 bytes per float * 640 width * 640 height * 3 colors
        val byteBuffer = ByteBuffer.allocateDirect(4 * 640 * 640 * 3)
        byteBuffer.order(ByteOrder.nativeOrder())
        val intValues = IntArray(640 * 640)
        bitmap.getPixels(intValues, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        var pixel = 0
        for (i in 0 until 640) {
            for (j in 0 until 640) {
                val valPixel = intValues[pixel++]
                // Extract colors and convert to 0.0 - 1.0 float format
                byteBuffer.putFloat(((valPixel shr 16) and 0xFF) / 255.0f) // Red
                byteBuffer.putFloat(((valPixel shr 8) and 0xFF) / 255.0f)  // Green
                byteBuffer.putFloat((valPixel and 0xFF) / 255.0f)          // Blue
            }
        }
        return byteBuffer
    }

    fun close() {
        interpreter?.close()
    }
}