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
    
    // PRE-ALLOCATE: Note 9 fails on large object allocations every frame
    private val outputTensor = Array(1) { Array(56) { FloatArray(8400) } }
    private val pixelBuffer = IntArray(640 * 640)
    private val inputBuffer = ByteBuffer.allocateDirect(1 * 640 * 640 * 3 * 4).order(ByteOrder.nativeOrder())

    init {
        try {
            val model = loadModelFile(context, "yolov8n-pose.tflite")
            val options = Interpreter.Options()

            // FIX: Build.MODEL is a product code like "SM-N960F", never "note 9".
            // Build.DEVICE is the hardware codename — reliable for legacy detection.
            val deviceCode = Build.DEVICE.lowercase()
            val isLegacyDevice = deviceCode.contains("crown") || // Note 9 (Exynos: crownlte, Snap: crownqltesq)
                                 deviceCode.contains("star")  || // S9 (starlte / starqltesq)
                                 Build.VERSION.SDK_INT < Build.VERSION_CODES.Q // Android < 10 as safety net

            when {
                // Modern device: try GPU delegate, catch any runtime failure
                !isLegacyDevice && CompatibilityList().isDelegateSupportedOnThisDevice -> {
                    try {
                        options.addDelegate(GpuDelegate())
                        useGpu = true
                        Log.i("BabyGuard_AI", "🚀 GPU Delegate enabled (${Build.MODEL})")
                    } catch (gpuEx: Exception) {
                        Log.w("BabyGuard_AI", "GPU delegate failed, using XNNPACK CPU: ${gpuEx.message}")
                        options.setNumThreads(4)
                        options.setUseXNNPACK(true)
                    }
                }
                // Legacy device (Note 9 / Exynos 9810 / Snapdragon 845):
                // NNAPI on these chips is unreliable for float32 YOLOv8 — it silently
                // produces zeros or delegates nothing. Pure XNNPACK CPU on all 4
                // performance cores is safe, fast enough for 3 FPS, and actually works.
                isLegacyDevice -> {
                    options.setNumThreads(4)
                    options.setUseXNNPACK(true)
                    Log.i("BabyGuard_AI", "🛡️ XNNPACK/CPU mode — legacy device ${Build.DEVICE}")
                }
                else -> {
                    options.setNumThreads(4)
                    options.setUseXNNPACK(true)
                    Log.i("BabyGuard_AI", "🔧 CPU-only mode (${Build.MODEL})")
                }
            }

            interpreter = Interpreter(model, options)
            Log.i("BabyGuard_AI", "✅ AI Initialized (GPU: $useGpu, Device: ${Build.DEVICE})")
        } catch (e: Exception) {
            Log.e("BabyGuard_AI", "❌ Init Error: ${e.message}")
        }
    }

    private fun loadModelFile(context: Context, modelName: String): MappedByteBuffer {
        val fd = context.assets.openFd(modelName)
        return FileInputStream(fd.fileDescriptor).channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
    }

    fun detect(bitmap: Bitmap): BoundingBox? {
        if (interpreter == null) return null
        
        val size = 640
        val resized = Bitmap.createScaledBitmap(bitmap, size, size, true)
        
        inputBuffer.rewind()
        resized.getPixels(pixelBuffer, 0, size, 0, 0, size, size)
        for (p in pixelBuffer) {
            inputBuffer.putFloat(((p shr 16) and 0xFF) / 255.0f)
            inputBuffer.putFloat(((p shr 8) and 0xFF) / 255.0f)
            inputBuffer.putFloat((p and 0xFF) / 255.0f)
        }

        try {
            interpreter?.run(inputBuffer, outputTensor)
        } catch (e: Exception) {
            Log.e("BabyGuard_AI", "❌ Inference error: ${e.message}")
            return null
        }

        val data = outputTensor[0]

        // Sanity check: all-zeros means the model silently failed.
        val maxConfidence = (0 until 8400).maxOf { data[4][it] }
        if (maxConfidence == 0f) {
            Log.w("BabyGuard_AI", "⚠️ All-zero output — model silent-failed. Device: ${Build.DEVICE}")
        } else {
            // Log.d so this shows in logcat without enabling verbose
            Log.d("BabyGuard_AI", "✅ Inference OK — peak conf: ${"%.2f".format(maxConfidence)}, threshold: 0.22")
        }

        var bestScore = 0f; var bestIdx = -1

        // 0.18 threshold — lower than original 0.28, handles:
        //   • Note 9's older sensor producing softer images
        //   • 2D images on a monitor (slightly lower YOLO scores)
        //   • Baby partially out of frame
        for (i in 0 until 8400) {
            val score = data[4][i]
            if (score > 0.18f && score > bestScore) {
                bestScore = score
                bestIdx = i
            }
        }

        if (bestIdx != -1) {
            val cx = data[0][bestIdx] * 640f; val cy = data[1][bestIdx] * 640f
            val w = data[2][bestIdx] * 640f; val h = data[3][bestIdx] * 640f

            val kpts = mutableListOf<Keypoint>()
            for (k in 0 until 17) {
                val kx = data[5 + k * 3][bestIdx] * 640f
                val ky = data[5 + k * 3 + 1][bestIdx] * 640f
                val kc = data[5 + k * 3 + 2][bestIdx]
                kpts.add(Keypoint(k, PointF(kx, ky), kc))
            }

            val box = RectF(cx - w/2, cy - h/2, cx + w/2, cy + h/2)

            // --- REFINED POSTURE LOGIC ---
            val nose = kpts[0]; val lHip = kpts[11]; val rHip = kpts[12]
            val lShoulder = kpts[5]; val rShoulder = kpts[6]
            val lAnkle = kpts[15]; val rAnkle = kpts[16]

            val hipY     = (lHip.position.y      + rHip.position.y)      / 2
            val ankleY   = (lAnkle.position.y    + rAnkle.position.y)    / 2
            val shoulderY = (lShoulder.position.y + rShoulder.position.y) / 2

            // 1. Standing: tall box + nose above hips + legs extended.
            //    Changes vs original:
            //    - shouldersLevel gate relaxed 15% → 20% (YOLO is less precise on 2D images)
            //    - hipsVisible confidence gate 0.30 → 0.22 (hips often under confidence on monitors)
            //    - Fallback path: if hips low confidence but legs clearly extended + very tall box,
            //      still flag standing (handles baby partially in frame or 2D image artifacts)
            val legLengthY     = Math.abs(ankleY - hipY)
            val isLegsExtended = legLengthY > (h * 0.40f)
            val shouldersLevel = Math.abs(lShoulder.position.y - rShoulder.position.y) < (h * 0.20f)
            val hipsVisible    = lHip.confidence > 0.22f && rHip.confidence > 0.22f
            val anklesVisible  = lAnkle.confidence > 0.22f || rAnkle.confidence > 0.22f

            // Primary path: full keypoint evidence
            val standingPrimary = h > (w * 1.35f) &&
                                  nose.position.y < (hipY - 40) &&
                                  isLegsExtended &&
                                  shouldersLevel &&
                                  hipsVisible &&
                                  nose.confidence > 0.25f

            // Fallback: very tall box + nose high + ankles visible even if hips uncertain
            val standingFallback = h > (w * 1.6f) &&
                                   nose.confidence > 0.30f &&
                                   nose.position.y < (cy - h * 0.15f) &&
                                   anklesVisible &&
                                   shouldersLevel

            val isStanding = standingPrimary || standingFallback

            // 2. Prone: face-down (shoulders visible, nose hidden).
            //    Extra guard: body box must be landscape (w > h * 0.8) because a baby
            //    standing or sitting produces a portrait box — if nose is hidden then,
            //    it's just occlusion, not face-down.
            val bodyIsLandscape = w > h * 0.8f
            val shouldersVisible = lShoulder.confidence > 0.4f || rShoulder.confidence > 0.4f
            val isProne = shouldersVisible && nose.confidence < 0.2f && bodyIsLandscape

            return BoundingBox(box, "baby", bestScore, isStanding, isProne, kpts)
        }
        return null
    }

    fun getFaceCrop(bitmap: Bitmap, keypoints: List<Keypoint>): Bitmap? {
        if (keypoints.size < 5) return null
        val nose = keypoints[0]; val lE = keypoints[1]; val rE = keypoints[2]
        if (nose.confidence < 0.2f) return null
        val headSize = Math.abs(rE.position.x - lE.position.x) * 4f
        val left = (nose.position.x - headSize / 2).toInt().coerceIn(0, bitmap.width - 10)
        val top  = (nose.position.y - headSize / 1.5f).toInt().coerceIn(0, bitmap.height - 10)
        // FIX: clamp size to both axes so we never exceed bitmap bounds on either dimension
        val s = headSize.toInt().coerceIn(10, minOf(bitmap.width - left, bitmap.height - top))
        return try { Bitmap.createBitmap(bitmap, left, top, s, s) } catch (_: Exception) { null }
    }

    fun close() { interpreter?.close() }
}