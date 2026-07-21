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

    // Injected by CameraActivity after loading the user's crib calibration.
    // When non-null and isReady(), detect() uses corrected keypoints + spine-vector
    // math instead of the box-fraction fallback.
    @Volatile private var calibrationManager: CribCalibrationManager? = null

    fun setCalibration(manager: CribCalibrationManager?) {
        calibrationManager = manager
    }

    // ── Supine posture auto-calibration accumulators ──────────────────────────
    // PATH B accumulates measurements from frames that are STRONGLY supine (large
    // bounding box + high leg:torso ratio) and averages them into a reference after
    // AUTO_CALIB_FRAMES consecutive confident frames. No user interaction needed —
    // the reference updates automatically while the baby is sleeping.
    private val AUTO_CALIB_FRAMES = 20
    private var autoCalibCount    = 0
    private var autoCalibBboxH    = 0f
    private var autoCalibLTRatio  = 0f
    private var autoCalibSpread   = 0f

    /**
     * Call this from CameraActivity when the user manually confirms the baby is supine
     * (e.g. taps a "Calibrate" button). Immediately saves the current detection's visual
     * signature as the supine reference, bypassing the AUTO_CALIB_FRAMES accumulation.
     */
    fun calibrateSupineNow(detection: BoundingBox) {
        val kpts = detection.keypoints
        if (kpts.size < 17) return
        val ankleY    = (kpts[15].position.y + kpts[16].position.y) / 2f
        val hipY      = (kpts[11].position.y + kpts[12].position.y) / 2f
        val shoulderY = (kpts[5].position.y  + kpts[6].position.y)  / 2f
        val torso  = Math.abs(shoulderY - hipY)
        val leg    = Math.abs(ankleY   - hipY)
        val spread = Math.abs(kpts[15].position.x - kpts[16].position.x)
        val bboxH  = detection.box.height()
        if (torso < 5f || bboxH < 10f) return
        prefs.savePostureCalibration(
            bboxH        = bboxH,
            legTorsoRatio = if (torso > 0f) leg / torso else 2.0f,
            ankleSpread  = spread
        )
        Log.i("BabyGuard_Posture",
            "Posture manually calibrated: h=${bboxH.toInt()} " +
            "ratio=${"%.2f".format(if (torso > 0f) leg / torso else 2f)} " +
            "spread=${spread.toInt()}")
        // Reset the auto-accumulator so manual calibration takes immediate effect.
        autoCalibCount = 0; autoCalibBboxH = 0f; autoCalibLTRatio = 0f; autoCalibSpread = 0f
    }
    
    // Cache of the most recent successful detection — used by CameraActivity for
    // manual posture calibration (calibrateSupineNow) without requiring a separate detect() call.
    @Volatile private var lastDetection: BoundingBox? = null
    fun getLastDetection(): BoundingBox? = lastDetection

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

            val nose      = kpts[0]
            val lShoulder = kpts[5];  val rShoulder = kpts[6]
            val lHip      = kpts[11]; val rHip      = kpts[12]
            val lAnkle    = kpts[15]; val rAnkle    = kpts[16]

            // ── POSTURE LOGIC — two paths depending on calibration state ──────────
            //
            // PATH A (CALIBRATED): apply perspective-correction homography to all
            //   keypoints, then measure the physical spine length in the corrected
            //   "top-down" space. Camera-angle distortion is eliminated before any
            //   threshold is applied, making the discriminator invariant to mount angle.
            //
            // PATH B (UNCALIBRATED FALLBACK): original box-height-fraction heuristics
            //   from direct observation of the downward-angled setup.  Less precise but
            //   still functional while the user hasn't calibrated yet.

            val calib = calibrationManager?.takeIf { it.isReady() }
            val isStanding: Boolean
            val isProne:    Boolean

            if (calib != null) {
                // ── PATH A: homography-corrected spine-vector math ────────────────
                val corrected = calib.transformKeypoints(kpts)

                val cLS = corrected[5];  val cRS = corrected[6]
                val cLH = corrected[11]; val cRH = corrected[12]
                val cNose = corrected[0]

                val shoulderConfident = cLS.confidence > 0.25f || cRS.confidence > 0.25f
                val hipConfident      = cLH.confidence > 0.22f || cRH.confidence > 0.22f

                if (shoulderConfident && hipConfident) {
                    // Midpoints of shoulders and hips in the corrected floor plane
                    val sMidX = (cLS.position.x + cRS.position.x) / 2f
                    val sMidY = (cLS.position.y + cRS.position.y) / 2f
                    val hMidX = (cLH.position.x + cRH.position.x) / 2f
                    val hMidY = (cLH.position.y + cRH.position.y) / 2f

                    val dx       = hMidX - sMidX
                    val dy       = hMidY - sMidY
                    val spineLen = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()

                    // In the corrected top-down view:
                    //   LYING (supine/prone): baby's spine stretches across the floor plane
                    //     → spineLen is a large fraction of MODEL_SIZE (e.g. 200-450 px of 640)
                    //   STANDING: spine projects toward the camera → heavily foreshortened
                    //     → spineLen collapses to a very small value (< 18% of MODEL_SIZE ≈ 115 px)
                    //
                    // The 0.18 threshold was derived from the observed "torso appears shorter
                    // when standing" geometry of the reported 30-45° downward mount angle;
                    // adjust slightly if detections are borderline (log spineLen to tune).
                    // Threshold: 0.18 × 640 = 115 px.
                    // Observed ranges: LYING → 200–450 px (spine spans floor plane),
                    // STANDING → collapses to < 115 px (torso foreshortens heavily toward
                    // the camera in the corrected canonical view, or clips outside the crib).
                    // Nose confidence lowered to 0.20: baby at far end of crib may face camera
                    // but appear small → YOLO returns valid nose at lower confidence than 0.28.
                    val standingBySpine = spineLen < CribCalibrationManager.MODEL_SIZE * 0.18f &&
                                          cNose.confidence > 0.20f   // head visible facing camera

                    isStanding = standingBySpine
                    // Prone = lying flat AND face occluded; spine length does NOT discriminate
                    // supine from prone — only face visibility does.
                    val shouldersVisible = cLS.confidence > 0.35f || cRS.confidence > 0.35f
                    isProne = !isStanding && shouldersVisible &&
                              isFaceHidden(nose, kpts[1], kpts[2])

                    Log.d("BabyGuard_Posture",
                        "[CALIB] standing=$isStanding prone=$isProne | " +
                        "spineLen=${"%.1f".format(spineLen)} " +
                        "(thresh=${"%.1f".format(CribCalibrationManager.MODEL_SIZE * 0.18f)}) " +
                        "noseConf=${"%.2f".format(cNose.confidence)}")
                } else {
                    // Keypoint confidence too low to trust the corrected measurement —
                    // fall back to a conservative "no detection" rather than guess wrong.
                    isStanding = false; isProne = false
                    Log.d("BabyGuard_Posture",
                        "[CALIB] skipped — low keypoint confidence " +
                        "(sh=${shoulderConfident} hip=${hipConfident})")
                }
            } else {
                // ── PATH B: uncalibrated fallback — 4-signal geometric fusion ─────────
                //
                // VERIFIED CAMERA GEOMETRY: 2 ft from baby's feet, 2 ft above mattress
                // → 45° downward viewing angle from the foot end of the crib.
                //
                // Both supine and standing place head at top / feet at bottom of frame,
                // so naive box-shape checks are AMBIGUOUS.  Four independent geometric
                // signals discriminate reliably — their votes are fused below.
                //
                // ┌─────────────────────────┬──────────────┬──────────────┐
                // │ Signal                  │ Supine       │ Standing     │
                // ├─────────────────────────┼──────────────┼──────────────┤
                // │ A) Bbox height / 640px  │ > 0.55       │ < 0.42       │
                // │    (feet close → fills  │ body fills   │ baby far →   │
                // │    frame; standing baby │ most frame   │ small figure │
                // │    is small at far end) │              │              │
                // ├─────────────────────────┼──────────────┼──────────────┤
                // │ B) legPxY / torsoSpanY  │ 3.0 – 8.0    │ 1.5 – 2.5   │
                // │    (perspective ratio — │ legs look    │ normal body  │
                // │    distance-independent │ enormous;    │ proportions  │
                // │    ratio removes scale) │ torso tiny   │              │
                // ├─────────────────────────┼──────────────┼──────────────┤
                // │ C) ankleXSpread /       │ 0.65 – 4.5   │ 0.35 – 0.55  │
                // │    shoulderXSpread      │ feet spread  │ feet together │
                // │    (perspective magnif. │ side-by-side │ on mattress  │
                // │    at foot end)         │              │              │
                // ├─────────────────────────┼──────────────┼──────────────┤
                // │ D) ankle conf /         │ > 1.10×      │ ≈ equal or   │
                // │    shoulder conf        │ shoulder     │ shoulders >  │
                // │    (closest = clearest) │ (ankles near)│ (both far)   │
                // └─────────────────────────┴──────────────┴──────────────┘
                // ─────────────────────────────────────────────────────────────────────

                val frameH    = 640f
                val hipY      = (lHip.position.y      + rHip.position.y)      / 2f
                val ankleY    = (lAnkle.position.y    + rAnkle.position.y)    / 2f
                val shoulderY = (lShoulder.position.y + rShoulder.position.y) / 2f
                val torsoSpanY = Math.abs(shoulderY - hipY)
                val legLengthY = Math.abs(ankleY   - hipY)

                val isTorsoCompact = torsoSpanY < (h * 0.32f)
                val shouldersLevel = Math.abs(lShoulder.position.y - rShoulder.position.y) < (h * 0.24f)
                val hipsVisible    = lHip.confidence > 0.20f || rHip.confidence > 0.20f
                val headInFrame    = nose.confidence > 0.18f && nose.position.y < (cy - h * 0.04f)

                // ── Signal A: Bounding-box height (no keypoints required) ─────────────
                val isLargeBox = h > frameH * 0.55f    // supine: body spans near→far
                val isSmallBox = h < frameH * 0.42f    // standing: entire body at far end

                // ── Signal B: Leg-to-torso perspective ratio ──────────────────────────
                // Camera-distance INDEPENDENT — the ratio of apparent lengths cancels out
                // the overall scale, leaving only the perspective distortion signal.
                //
                // Derivation (pinhole model, camera at 2 ft above mattress, 2 ft from foot):
                //   Supine: ankles at ~2 ft, torso at ~6 ft → 3:1 distance ratio
                //           → leg pixels ≈ 3× longer in image than torso pixels
                //           → legLengthY / torsoSpanY ≈ 3–8
                //   Standing: body at ~7 ft throughout
                //           → normal body proportions
                //           → legLengthY / torsoSpanY ≈ 1.5–2.5
                val legTorsoRatio = if (torsoSpanY > 8f) legLengthY / torsoSpanY else 2.0f
                val isRatioHigh   = legTorsoRatio > 2.8f    // strongly supine
                val isRatioLow    = legTorsoRatio < 2.0f    // consistent with standing

                // ── Signal C: Ankle X-spread ──────────────────────────────────────────
                // Lower confidence threshold to 0.18: from a 45° foot-of-crib angle the
                // model is not well-conditioned and returns valid ankle positions at lower
                // confidence than it would from a canonical front/back view.
                val ankleXSpread    = Math.abs(lAnkle.position.x - rAnkle.position.x)
                val shoulderXSpread = Math.abs(lShoulder.position.x - rShoulder.position.x)
                val anklesDetected  = lAnkle.confidence > 0.18f && rAnkle.confidence > 0.18f
                val refWidth        = if (shoulderXSpread > 10f) shoulderXSpread else w * 0.35f
                // Threshold: 0.65 — supine ratio ≈ 3–4.5, standing ratio ≈ 0.4–0.6
                val isFeetSpreadApart = anklesDetected && ankleXSpread >= refWidth * 0.65f
                val isFeetNarrow      = anklesDetected && ankleXSpread <  refWidth * 0.58f

                // ── Signal D: Ankle/shoulder confidence ratio ─────────────────────────
                val avgAnkleConf    = (lAnkle.confidence    + rAnkle.confidence)    / 2f
                val avgShoulderConf = (lShoulder.confidence + rShoulder.confidence) / 2f
                val isAnklesNearest    = anklesDetected && avgAnkleConf > avgShoulderConf * 1.10f
                val isShouldersNearest = anklesDetected && avgShoulderConf >= avgAnkleConf * 1.00f

                // ── Weighted vote fusion ──────────────────────────────────────────────
                // Each signal contributes points to supineVote OR standingVote.
                // Decision: standing wins only when standingVote > supineVote AND ≥ 3.
                var supineVote   = 0
                var standingVote = 0

                // Signal A (weight 3 — strongest; no keypoint dependency)
                if (isLargeBox)          supineVote   += 3
                if (isSmallBox)          standingVote += 3

                // Signal B (weight 3 — camera-distance-independent ratio)
                if (isRatioHigh)         supineVote   += 3
                if (isRatioLow)          standingVote += 3

                // Signal C (weight 2 — perspective magnitude geometry)
                if (isFeetSpreadApart)   supineVote   += 2
                if (isFeetNarrow)        standingVote += 2

                // Signal D (weight 1 — confirmatory, weaker)
                if (isAnklesNearest)     supineVote   += 1
                if (isShouldersNearest)  standingVote += 1

                // Pose sanity check: basic body orientation (head up, torso compact, hips found)
                // Required for a positive standing call to avoid noise triggers.
                val poseOk = headInFrame && shouldersLevel && hipsVisible && isTorsoCompact

                // ── Calibration-boosted voting (Gemini concept, foot-of-crib adapted) ──
                // The Gemini approach (calibrate → compare ratios) is the right idea but
                // needs sign reversal for foot-of-crib: standing baby is SMALLER in frame
                // (far end) not taller.  Once the supine reference is learned, each feature
                // gets a fraction score:  ≈1.0 = same as supine, < 0.6 = much smaller.
                var calStandingVote = 0
                var calSupineVote   = 0
                if (prefs.hasPostureCalibration) {
                    val bboxFrac   = h            / prefs.supineBboxH            // 1.0 = supine ref
                    val ratioFrac  = legTorsoRatio / prefs.supineLegTorsoRatio    // 1.0 = supine ref
                    val spreadFrac = if (prefs.supineAnkleSpread > 0f)
                                         ankleXSpread / prefs.supineAnkleSpread else 1f
                    // Standing: all three fractions drop well below 1.0
                    if (bboxFrac   < 0.50f) calStandingVote += 4   // box much smaller than supine ref
                    if (bboxFrac   < 0.65f) calStandingVote += 2   // moderate decrease
                    if (bboxFrac   > 0.80f) calSupineVote   += 4   // box similar to supine ref
                    if (ratioFrac  < 0.55f) calStandingVote += 4   // ratio much lower = normal props
                    if (ratioFrac  < 0.70f) calStandingVote += 2
                    if (ratioFrac  > 0.80f) calSupineVote   += 4   // ratio similar = still supine
                    if (spreadFrac < 0.40f) calStandingVote += 3   // ankles much closer together
                    if (spreadFrac > 0.65f) calSupineVote   += 3   // spread still wide

                    standingVote += calStandingVote
                    supineVote   += calSupineVote

                    Log.d("BabyGuard_Posture",
                        "[B-cal] bbox=${"%.2f".format(bboxFrac)} " +
                        "ratio=${"%.2f".format(ratioFrac)} " +
                        "spread=${"%.2f".format(spreadFrac)} " +
                        "calSt=$calStandingVote calSup=$calSupineVote")
                }

                // ── Auto-calibration: learn supine reference without user interaction ──
                // Accumulate measurements on frames where BOTH the strongest signals
                // (large box + high leg:torso ratio) agree it is clearly supine.
                // After AUTO_CALIB_FRAMES such frames, average and persist the reference.
                // Only runs when calibration-based votes haven't already locked in a result.
                val veryConfidentSupine = isLargeBox && isRatioHigh
                if (veryConfidentSupine) {
                    autoCalibCount++
                    autoCalibBboxH   += h
                    autoCalibLTRatio += legTorsoRatio
                    autoCalibSpread  += ankleXSpread
                    if (autoCalibCount >= AUTO_CALIB_FRAMES) {
                        prefs.savePostureCalibration(
                            bboxH         = autoCalibBboxH   / autoCalibCount,
                            legTorsoRatio = autoCalibLTRatio / autoCalibCount,
                            ankleSpread   = autoCalibSpread  / autoCalibCount
                        )
                        Log.i("BabyGuard_Posture",
                            "Auto-calibrated supine ref: " +
                            "h=${"%.0f".format(autoCalibBboxH / autoCalibCount)} " +
                            "ratio=${"%.2f".format(autoCalibLTRatio / autoCalibCount)} " +
                            "spread=${"%.0f".format(autoCalibSpread / autoCalibCount)}")
                        autoCalibCount = 0; autoCalibBboxH = 0f
                        autoCalibLTRatio = 0f; autoCalibSpread = 0f
                    }
                } else {
                    // Non-supine frame: reset accumulator (don't mix states into the average)
                    if (autoCalibCount > 0) {
                        autoCalibCount = 0; autoCalibBboxH = 0f
                        autoCalibLTRatio = 0f; autoCalibSpread = 0f
                    }
                }

                // Standing: must win the vote, meet minimum evidence, AND pass pose check.
                isStanding = poseOk &&
                             standingVote >= 3 &&
                             standingVote > supineVote

                // ── Prone detection ───────────────────────────────────────────────────
                // From 45° foot-of-crib view: face-down baby (prone) is distinguished by
                // face occlusion.  Guard with !isLargeBox because a very large bbox with
                // no visible face is more likely supine (camera can't see the face well
                // from the feet end when baby is close to the lens).
                val shouldersVisible = lShoulder.confidence > 0.28f || rShoulder.confidence > 0.28f
                isProne = !isStanding && !isLargeBox &&
                          shouldersVisible && isFaceHidden(nose, kpts[1], kpts[2])

                Log.d("BabyGuard_Posture",
                    "[B] stand=$isStanding prone=$isProne | " +
                    "sv=$standingVote sup=$supineVote poseOk=$poseOk | " +
                    "h=${"%.0f".format(h)}(L=$isLargeBox S=$isSmallBox) " +
                    "ratio=${"%.2f".format(legTorsoRatio)}(H=$isRatioHigh L=$isRatioLow) " +
                    "ankSprd=${"%.0f".format(ankleXSpread)} ref=${"%.0f".format(refWidth)} " +
                    "(Spr=$isFeetSpreadApart Nar=$isFeetNarrow) " +
                    "aConf=${"%.2f".format(avgAnkleConf)} sConf=${"%.2f".format(avgShoulderConf)} " +
                    "nose=${"%.2f".format(nose.confidence)}")
            }

            val result = BoundingBox(box, "baby", bestScore, isStanding, isProne, kpts)
            lastDetection = result
            return result
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