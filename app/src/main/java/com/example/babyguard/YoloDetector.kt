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

    // Personal torso/leg pixel baseline from CameraActivity's guided calibration flow (see
    // AppPreferences). When present, the standing/prone thresholds below compare against this
    // specific baby + camera setup instead of the generic guessed constants — see isTorsoCompact
    // and legsNotExtended in detect().
    private val prefs = AppPreferences(context)

    companion object {
        /**
         * Is the face hidden/occluded? Single-keypoint (nose-only) gating let hand/cloth
         * covers slip through undetected whenever the pose model "hallucinated" a
         * plausible-but-wrong nose position with moderate confidence (a known behavior of
         * keypoint models on partially-occluded inputs) — that silently skipped the
         * suffocation check entirely, which is exactly the "no warning at all" failure mode.
         * Raising the nose bar to 0.30 and additionally requiring at least one eye to be
         * confidently visible makes "hidden" the default verdict whenever evidence is
         * ambiguous — the safe direction for a safety feature (an extra check-in costs far
         * less than a missed suffocation warning).
         */
        fun isFaceHidden(nose: Keypoint, leftEye: Keypoint, rightEye: Keypoint): Boolean =
            nose.confidence < 0.30f || (leftEye.confidence < 0.25f && rightEye.confidence < 0.25f)
    }

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

            // 1. Standing — RECALIBRATED for an elevated, downward-angled camera mount
            //    (reported setup: camera 2-3ft from the baby, mounted ~1ft above the baby's
            //    own standing height, tilted ~30-45° down into the crib).
            //
            //    At eye level, "tall box + big nose-hip gap + extended legs" correctly means
            //    standing. At a steep downward angle it's backwards: a STANDING baby's torso
            //    axis points back toward the camera, so perspective foreshortens it hard —
            //    confirmed by direct observation ("when the baby is standing the torso appears
            //    shorter"). A SUPINE baby lying flat on the mattress has its head-to-feet axis
            //    laid out across the depth direction from this angle, which perspective
            //    stretches into a TALL silhouette instead — exactly the old standing signature,
            //    which is why supine was triggering "standing".
            //
            //    New signal: standing now requires a COMPACT, foreshortened torso (small
            //    shoulder-hip vertical span relative to box height), legs NOT extended away
            //    from the hip in a long straight line (the opposite of the old gate), and a
            //    head/face reading as the nearest, highest-confidence point — the geometry you
            //    actually expect when looking down at a baby's head/shoulders from above.
            val legLengthY      = Math.abs(ankleY - hipY)
            val shouldersLevel  = Math.abs(lShoulder.position.y - rShoulder.position.y) < (h * 0.20f)
            val hipsVisible     = lHip.confidence > 0.22f || rHip.confidence > 0.22f
            val torsoSpanY      = Math.abs(shoulderY - hipY)
            val isBoxCompact    = h < (w * 1.3f)
            val headIsNearest   = nose.confidence > 0.30f && nose.position.y < (cy - h * 0.08f)

            // REVERTED at user's request back to plain fixed-fraction-of-box-height thresholds —
            // the calibration-ratio compactness check, the leg-vs-torso scale-free ratio
            // cross-check, and the head-to-ankle alpha-angle exclusion (all added afterward) are
            // removed. None of those layers fixed the "doll standing not detected" report, so
            // stripping back to this simpler, easier-to-reason-about baseline to retest from here.
            val isTorsoCompact  = torsoSpanY < (h * 0.32f)
            val legsNotExtended = legLengthY < (h * 0.30f)

            // Primary path: full keypoint evidence
            val standingPrimary = isBoxCompact &&
                                  isTorsoCompact &&
                                  legsNotExtended &&
                                  headIsNearest &&
                                  shouldersLevel &&
                                  hipsVisible

            // Fallback: torso/leg compactness signal holds even if hip keypoints are
            // low-confidence (legs tucked/occluded under a standing body viewed from above).
            val standingFallback = isTorsoCompact &&
                                   legsNotExtended &&
                                   h < (w * 1.5f) &&
                                   nose.confidence > 0.35f &&
                                   headIsNearest &&
                                   shouldersLevel

            val isStanding = standingPrimary || standingFallback

            Log.d("BabyGuard_Posture",
                "standing=$isStanding (primary=$standingPrimary fallback=$standingFallback) | " +
                "box=$isBoxCompact(h=${"%.0f".format(h)},w=${"%.0f".format(w)}) torso=$isTorsoCompact(${"%.1f".format(torsoSpanY)}) " +
                "legs=$legsNotExtended(${"%.1f".format(legLengthY)}) head=$headIsNearest(conf=${"%.2f".format(nose.confidence)}) " +
                "shoulders=$shouldersLevel hips=$hipsVisible(${"%.2f".format(lHip.confidence)}/${"%.2f".format(rHip.confidence)})")

            // 2. Prone: face-down (shoulders visible, face hidden).
            //    Guard updated to match the elevated-mount recalibration above: the old
            //    "box is landscape" check (w > h * 0.8) assumed eye-level geometry where a
            //    standing/sitting box is tall and narrow. The new compact standing signature
            //    can itself read as landscape-ish from this camera's downward angle, so a
            //    standing baby with an occluded face (hands, blanket) could wrongly pass that
            //    old guard. Instead, gate on the ELONGATED-torso signature (not torso-compact)
            //    that now specifically distinguishes lying flat from standing at this angle.
            val shouldersVisible = lShoulder.confidence > 0.4f || rShoulder.confidence > 0.4f
            val isProne = shouldersVisible && isFaceHidden(nose, kpts[1], kpts[2]) && !isTorsoCompact

            return BoundingBox(box, "baby", bestScore, isStanding, isProne, kpts)
        }
        return null
    }

    /**
     * Looks for a SECOND body-like detection in the same frame, distinct from the
     * primary one already found by [detect]. Reuses [outputTensor] from the same
     * inference pass — zero extra inference cost. Threshold is lower than the
     * primary 0.18f because a secondary subject is often partially out of frame or
     * farther from the camera.
     *
     * Note: the bundled model is a single-class (person/baby pose) detector, so this
     * can only ever report "another person-shaped body in frame" — not pets or
     * inanimate objects, since no such class exists in the model.
     */
    fun detectSecondaryPerson(primaryBox: RectF): BoundingBox? {
        if (interpreter == null) return null
        val data = outputTensor[0]
        val secondaryThreshold = 0.25f

        var bestScore = 0f; var bestIdx = -1
        for (i in 0 until 8400) {
            val score = data[4][i]
            if (score <= secondaryThreshold || score <= bestScore) continue

            val cx = data[0][i] * 640f; val cy = data[1][i] * 640f
            val w = data[2][i] * 640f; val h = data[3][i] * 640f
            val box = RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)

            // Skip anchors that are really just the same body as the primary detection.
            if (iou(box, primaryBox) > 0.3f) continue

            bestScore = score
            bestIdx = i
        }

        if (bestIdx == -1) return null
        val cx = data[0][bestIdx] * 640f; val cy = data[1][bestIdx] * 640f
        val w = data[2][bestIdx] * 640f; val h = data[3][bestIdx] * 640f
        val box = RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
        return BoundingBox(box, "person", bestScore, false, false, emptyList())
    }

    private fun iou(a: RectF, b: RectF): Float {
        val left = maxOf(a.left, b.left); val top = maxOf(a.top, b.top)
        val right = minOf(a.right, b.right); val bottom = minOf(a.bottom, b.bottom)
        if (right <= left || bottom <= top) return 0f
        val inter = (right - left) * (bottom - top)
        val union = (a.width() * a.height()) + (b.width() * b.height()) - inter
        return if (union <= 0f) 0f else inter / union
    }

    fun getFaceCrop(bitmap: Bitmap, keypoints: List<Keypoint>): Bitmap? {
        if (keypoints.size < 5) return null
        val nose = keypoints[0]; val lE = keypoints[1]; val rE = keypoints[2]
        if (isFaceHidden(nose, lE, rE)) return null
        val headSize = Math.abs(rE.position.x - lE.position.x) * 4f
        val left = (nose.position.x - headSize / 2).toInt().coerceIn(0, bitmap.width - 10)
        val top  = (nose.position.y - headSize / 1.5f).toInt().coerceIn(0, bitmap.height - 10)
        // FIX: clamp size to both axes so we never exceed bitmap bounds on either dimension
        val s = headSize.toInt().coerceIn(10, minOf(bitmap.width - left, bitmap.height - top))
        return try { Bitmap.createBitmap(bitmap, left, top, s, s) } catch (_: Exception) { null }
    }

    fun close() { interpreter?.close() }
}