package com.example.babyguard

import android.graphics.Bitmap
import android.util.Log

class SafetyPipeline(
    private val motionDetector: MotionDetector,
    private val yoloDetector: YoloDetector,
    private val emotionDetector: EmotionDetector,
    private val aiGovernor: AIGovernor,
    private var sensitivity: Int = 2,  // 1-3 from AppPreferences, mutable for live updates
    private val babyPostureDetector: BabyPostureDetector? = null
) {
    enum class State { DORMANT, ACTIVE }

    private var currentState = State.DORMANT
    fun getState(): State = currentState
    fun updateSensitivity(s: Int) { sensitivity = s.coerceIn(1, 3) }

    /**
     * Clears all internal state: posture streaks, consensus buffer, hysteresis, mood history,
     * and presence lock. Call after calibration or when the Parent dismisses a HIGH alert so
     * the pipeline starts fresh and doesn't stay stuck in a latched HIGH tier.
     */
    fun forceReset() {
        proneStreak = 0; standingStreak = 0
        agitationStreak = 0; extraEntityStreak = 0
        suffocationTimerStart = 0L
        heldTierRank = 0; fallingSince = 0L; heldStatus = ""; heldAction = ""
        moodHistory.clear()
        resultBuffer.clear()
        lastBabyDetectedTime = 0L
        currentState = State.DORMANT
        lastMotionLevel = 0
    }
    /** True if YOLO confirmed a person within the last PRESENCE_LOCK_MS. */
    fun isBabyPresentRecently(): Boolean =
        System.currentTimeMillis() - lastBabyDetectedTime < PRESENCE_LOCK_MS

    /**
     * True while a posture streak is still building toward POSTURE_CONFIRM, or the
     * suffocation timer is running. CameraActivity uses this to force fast-rate scanning
     * instead of the motion-driven dynamic delay: a baby lying still face-down, or calmly
     * covered, produces little/no MOG motion — so the normal "no motion → slow scan" tier
     * is exactly backwards for the two cases that matter most. This only engages once a
     * candidate signal has already shown up in at least one frame, so it doesn't burn
     * battery scanning fast 24/7 — only while something is actively being confirmed.
     */
    fun isInvestigating(): Boolean =
        suffocationTimerStart != 0L ||
        (proneStreak    in 1 until POSTURE_CONFIRM) ||
        (standingStreak in 1 until POSTURE_CONFIRM)

    private var lastFullScanTime = 0L

    // ── Motion thresholds scaled by sensitivity ───────────────────────────────
    // sensitivity 1 (Low)    → noisy environment, need big movement to trigger
    // sensitivity 2 (Normal) → balanced default
    // sensitivity 3 (High)   → quiet nursery, picks up even micro-movements
    private val motionThreshold get() = when (sensitivity) { 1 -> 12_000; 3 -> 4_000; else -> 8_000 }

    // ── Posture confirmation counters ─────────────────────────────────────────
    // Require N consecutive positive frames before raising an alert.
    // Eliminates single-frame false positives from YOLO keypoint jitter.
    // Set to 4: the new 4-signal fusion in YoloDetector PATH B has higher
    // per-frame accuracy, so we can afford a stricter confirmation gate.
    // At BALANCED idle rate (~1 scan/s) this means ~4 s of observed standing
    // before the alert fires — long enough to exclude transient keypoint glitches
    // but short enough to catch a baby actually pulling up to stand.
    // Streaks decay by 1 per missed frame (not hard-reset), so brief detection
    // gaps don't restart the counter from zero.
    private var proneStreak    = 0
    private var standingStreak = 0
    private val POSTURE_CONFIRM = 4     // frames (was 2)

    // ── Baby Presence Lock ────────────────────────────────────────────────────
    // Once YOLO finds a person, we keep scanning at full rate for PRESENCE_LOCK_MS
    // even if MOG shows no motion. Fixes the "static image on monitor" case where
    // background subtraction adapts and stops triggering YOLO via the motion path.
    private var lastBabyDetectedTime = 0L
    private val PRESENCE_LOCK_MS = 15_000L  // 15 seconds after last detection

    // ── Suffocation timer ─────────────────────────────────────────────────────
    // Scaled by sensitivity like motionThreshold above — was a flat 5000ms, which
    // read as "takes too long to alert" once a cover was actually detected.
    // Sensitivity 3 (quiet nursery / wants fast alerts) now escalates in ~2s.
    private var suffocationTimerStart = 0L
    private val SUFFOCATION_THRESHOLD get() = when (sensitivity) { 1 -> 6000L; 3 -> 2000L; else -> 3500L }

    // ── Struggle-while-covered fast-track ────────────────────────────────────
    // A calm, still face-cover can wait for the full suffocation timer, but if the
    // baby is also moving/struggling hard while covered, that's more urgent — escalate
    // to HIGH much sooner instead of blindly waiting the full threshold above.
    private val STRUGGLE_MOTION_THRESHOLD = 55
    private val STRUGGLE_CONFIRM_MS get() = when (sensitivity) { 1 -> 2000L; 3 -> 700L; else -> 1200L }

    // ── Restlessness / agitation streak ──────────────────────────────────────
    // Sustained high motion while lying down safely (not standing/prone) can signal
    // distress, tangled bedding, etc. Streak-confirmed to avoid single-frame jitter.
    private var agitationStreak = 0
    private val AGITATION_CONFIRM = 4
    private val AGITATION_MOTION_THRESHOLD = 70

    // ── Extra entity: a second body-like detection in frame ──────────────────
    private var extraEntityStreak = 0
    private val EXTRA_ENTITY_CONFIRM = 4
    private val EXTRA_ENTITY_MIN_CONF = 0.25f
    private var lastMotionLevel = 0

    // ── Risk-score fusion + asymmetric hysteresis ─────────────────────────────
    // Escalation (rank going up) is always immediate — never delay a real danger
    // signal. De-escalation (rank going down) requires the fused riskScore to stay
    // below a tier-specific floor for a sustained dwell period first. This kills
    // tier flicker right at a boundary (e.g. baby briefly settling mid-struggle no
    // longer instantly cancels a HIGH alert) without touching any of the existing
    // rule-based event detection above.
    private var heldTierRank = 0   // 0=LOW 1=MEDIUM 2=HIGH — currently reported tier
    private var fallingSince = 0L
    private var heldStatus = ""
    private var heldAction = ""
    private val HIGH_FALL_SCORE = 50f
    private val HIGH_FALL_DWELL_MS = 16_000L  // 6 s base + 10 s extra cooldown after HIGH alert
    private val MEDIUM_FALL_SCORE = 12f
    private val MEDIUM_FALL_DWELL_MS = 4_000L

    // ── Emotion rolling vote ──────────────────────────────────────────────────
    // Return the most-voted mood over the last MOOD_WINDOW frames so the label
    // doesn't flicker every time the face crop shifts slightly.
    private val moodHistory = ArrayDeque<String>(7)
    private val MOOD_WINDOW = 5

    // ── Result consensus buffer ───────────────────────────────────────────────
    private val resultBuffer = mutableListOf<DetectionResult>()
    private val BUFFER_SIZE = 6   // smaller than before → responds faster to real changes

    // ─────────────────────────────────────────────────────────────────────────

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
        val bodyBox: android.graphics.RectF? = null,
        val tier: String = "LOW",
        val action: String = "Normal",
        val faceJustCovered: Boolean = false,
        val riskScore: Float = 0f
    )

    fun processFrame(bitmap: Bitmap): DetectionResult {
        val now = System.currentTimeMillis()
        val heartbeatMs = aiGovernor.getHeartbeatInterval()

        val motionPixels = motionDetector.getMotionPixelCount(bitmap, sensitivity)
        val filteredPixels = if (motionPixels < when (sensitivity) { 1 -> 2500; 3 -> 600; else -> 1500 }) 0 else motionPixels
        val hasMotion = filteredPixels > motionThreshold

        // sqrt scale against 1920×1080 virtual area: 1%→10, 10%→32, 50%→71, 100%→100
        val motionLevel = if (filteredPixels == 0) 0
                          else (Math.sqrt(filteredPixels.toDouble() / 2_073_600.0) * 100).toInt().coerceAtMost(100)

        val isHeartbeat = now - lastFullScanTime > heartbeatMs
        val babyPresentRecently = now - lastBabyDetectedTime < PRESENCE_LOCK_MS

        // ── Fast path: truly dormant, no heartbeat, no recent baby detection ──
        if (!hasMotion && !isHeartbeat && !babyPresentRecently && currentState == State.DORMANT) {
            motionDetector.setActiveLearningRate(false)   // let background adapt quickly
            val dormant = DetectionResult("🟢 Sleeping Soundly", "Sleeping", "Safe",
                false, false, motionLevel, filteredPixels, tier = "LOW", action = "Sleeping")
            addToBuffer(dormant)
            return dormant
        }

        if (isHeartbeat) lastFullScanTime = now
        val newState = if (hasMotion || isHeartbeat || babyPresentRecently) State.ACTIVE else State.DORMANT
        motionDetector.setActiveLearningRate(newState == State.ACTIVE)
        currentState = newState

        // ── Full AI scan ──────────────────────────────────────────────────────
        val yoloResult = yoloDetector.detect(bitmap)
        var status  = if (hasMotion) "🟢 Baby is Active" else "🟢 Monitoring"
        var posture = "Safe"
        var isProne = false; var isStanding = false
        var bodyBox: android.graphics.RectF? = null
        var faceRect: android.graphics.RectF? = null
        var keypoints: List<Keypoint> = emptyList()
        var tier   = "LOW"
        var action = "Normal"

        if (yoloResult != null) {
            lastBabyDetectedTime = now   // refresh presence lock on every successful detection
            keypoints = yoloResult.keypoints
            bodyBox   = yoloResult.box

            // ── Extra entity: second body-like detection, distinct from the baby ──
            val secondary = yoloDetector.detectSecondaryPerson(yoloResult.box)
            if (secondary != null && secondary.confidence >= EXTRA_ENTITY_MIN_CONF)
                extraEntityStreak++ else extraEntityStreak = 0

            // ── Prone: skeletal keypoints (unchanged) ────────────────────────
            // Decay by 1 on a miss so a single dropped frame doesn't reset progress.
            if (yoloResult.isProne) proneStreak++ else proneStreak = (proneStreak - 1).coerceAtLeast(0)

            // ── Standing: bounding box size logic ────────────────────────────
            // Camera: foot-of-crib, 2 ft from feet, 2 ft above mattress (~45° angle).
            //
            // WHY SIZE WORKS:
            //   • Supine  — baby's feet are 2 ft from the lens → feet loom large, the whole
            //               body fills 60-80 % of the 640 px frame height.
            //   • Standing — baby is at the far end of the crib (4-6 ft away) → apparent
            //               size shrinks, body fills only 25-48 % of frame height.
            //   The 2-3× apparent-size difference is far more reliable than any keypoint
            //   math, because it holds even when the baby is under a blanket.
            //
            // SIGNALS (all in 640 px space):
            //   heightFrac  = box.height() / 640   — primary discriminator
            //   aspectRatio = height / width        — secondary: upright body is taller/narrower
            //   bboxCy      = box.centerY()         — standing → body centre is in upper half
            //
            // THRESHOLD TUNING:
            //   If you get false standing alerts while baby is supine → raise STAND_MAX.
            //   If standing is missed → lower STAND_MAX or lower SUPINE_MIN.
            //   Log tag [BBOX] prints the live values every frame so you can calibrate.
            val bboxH      = yoloResult.box.height()          // 0-640 px
            val bboxW      = yoloResult.box.width().coerceAtLeast(1f)
            val bboxCy     = yoloResult.box.centerY()         // 0=top … 640=bottom
            val frameH     = 640f
            val heightFrac = bboxH / frameH
            val aspectRatio = bboxH / bboxW

            // INVERTED from overhead-camera intuition — foot-of-crib 45° angle causes:
            //   SUPINE  : body lies ALONG the camera's line of sight → maximum foreshortening
            //             → small bounding box (h < ~50 % of frame)
            //   STANDING: body is UPRIGHT → full height projects vertically into the image
            //             → large bounding box (h > ~58 % of frame)
            //
            // Baby occupying more than 58 % of the frame height → upright / standing.
            val isDefinitelyStanding = heightFrac > 0.58f
            // Baby occupying less than 50 % of the frame → foreshortened body → lying.
            val isDefinitelyLying    = heightFrac < 0.50f
            // Ambiguous zone (50-58 %): taller-than-wide aspect ratio → more likely upright.
            val isAmbiguousUpright   = !isDefinitelyStanding && !isDefinitelyLying &&
                                       aspectRatio > 1.8f

            val isStandingByBbox = isDefinitelyStanding || isAmbiguousUpright

            Log.d("BabyGuard_Bbox",
                "[BBOX] h=${bboxH.toInt()} w=${bboxW.toInt()} " +
                "hFrac=${"%.2f".format(heightFrac)} cy=${bboxCy.toInt()} " +
                "ar=${"%.1f".format(aspectRatio)} → ${if (isStandingByBbox) "STANDING ⬆" else "lying ⬇"} " +
                "streak=$standingStreak")

            if (isStandingByBbox) standingStreak++
            else standingStreak = (standingStreak - 1).coerceAtLeast(0)

            isProne    = proneStreak    >= POSTURE_CONFIRM
            isStanding = standingStreak >= POSTURE_CONFIRM
            posture    = when {
                isStanding -> "Standing"
                isProne    -> "Face Down"
                else       -> "Safe"
            }
            when {
                isStanding -> { tier = "HIGH"; action = "Standing" }
                isProne    -> { tier = "HIGH"; action = "Face Down" }
                else       -> { tier = "LOW";  action = if (hasMotion) "Active" else "Normal" }
            }

            // ── Restlessness: sustained high motion while lying safely ───────
            // Only relevant when not already flagged Standing/Prone above.
            if (posture == "Safe") {
                if (motionLevel >= AGITATION_MOTION_THRESHOLD) agitationStreak++ else agitationStreak = 0
                if (agitationStreak >= AGITATION_CONFIRM && tier == "LOW") {
                    tier = "MEDIUM"; action = "Restless"
                }
            } else {
                agitationStreak = 0
            }

            // ── Extra entity tier: someone/something else is in frame ────────
            // Placed after restlessness but before the suffocation/face block below,
            // so a genuinely more severe suffocation signal can still override it.
            if (extraEntityStreak >= EXTRA_ENTITY_CONFIRM) {
                val suddenStop = lastMotionLevel >= AGITATION_MOTION_THRESHOLD && motionLevel < 15
                when {
                    suddenStop -> {
                        tier = "HIGH"; action = "Possible Interference"
                        status = "🚨 CHECK BABY — Unexpected presence, motion stopped"
                    }
                    tier == "LOW" -> { tier = "MEDIUM"; action = "Extra Presence" }
                }
            }

            // ── Emotion with rolling vote ────────────────────────────────────
            val faceCrop = yoloDetector.getFaceCrop(bitmap, keypoints)
            var faceJustCovered = false
            var occlusionRamp = 0f
            val rawMood = if (faceCrop != null) {
                faceRect = calculateFaceRect(keypoints)
                emotionDetector.detectMood(faceCrop)
            } else {
                if (isProne) "Hidden" else {
                    // Face not visible when not prone → start suffocation clock
                    if (suffocationTimerStart == 0L) {
                        suffocationTimerStart = now
                        faceJustCovered = true   // one-shot: true only the instant cover begins
                    }
                    val coveredFor = now - suffocationTimerStart
                    occlusionRamp = (coveredFor.toFloat() / SUFFOCATION_THRESHOLD * 100f).coerceIn(0f, 100f)
                    when {
                        // Struggling hard while covered is more urgent than calm cover —
                        // don't wait the full SUFFOCATION_THRESHOLD if baby is thrashing.
                        motionLevel >= STRUGGLE_MOTION_THRESHOLD && coveredFor > STRUGGLE_CONFIRM_MS -> {
                            status = "🚨 SUFFOCATION RISK"; tier = "HIGH"; action = "Suffocation"
                        }
                        coveredFor > SUFFOCATION_THRESHOLD -> {
                            status = "🚨 SUFFOCATION RISK"; tier = "HIGH"; action = "Suffocation"
                        }
                    }
                    "Analyzing..."
                }
            }

            // Only add real emotions to the voting history (not transient states)
            if (rawMood !in listOf("Hidden", "Analyzing...", "Unknown")) {
                moodHistory.addLast(rawMood)
                if (moodHistory.size > MOOD_WINDOW) moodHistory.removeFirst()
            }
            if (faceCrop != null) suffocationTimerStart = 0L

            val mood = if (moodHistory.isEmpty()) rawMood
                       else moodHistory.groupingBy { it }.eachCount().maxByOrNull { it.value }!!.key

            if (mood == "Fussy" && tier == "LOW") { tier = "MEDIUM"; action = "Fussy" }
            if (status.startsWith("🟢") && tier == "LOW") status = "🟢 Baby Awake"
            if (tier == "HIGH") status = when {
                isStanding -> "🚨 DANGER: Standing"
                isProne    -> "⚠️ Prone Risk"
                else       -> status
            }

            // ── Risk-score fusion ─────────────────────────────────────────────
            // Numeric companion to the rule-based tier above — used only to gate
            // de-escalation timing in applyHysteresis, never to override escalation.
            val postureScore = if (isStanding) 95f else if (isProne) 88f else 0f
            val agitationRamp = (agitationStreak.toFloat() / AGITATION_CONFIRM * 55f).coerceIn(0f, 55f)
            val moodRamp = if (mood == "Fussy") 22f else 0f
            val extraEntityRamp = (extraEntityStreak.toFloat() / EXTRA_ENTITY_CONFIRM * 40f).coerceIn(0f, 40f)
            val riskScore = maxOf(postureScore, occlusionRamp, agitationRamp + moodRamp, extraEntityRamp)
                .coerceIn(0f, 100f)

            val raw = DetectionResult(status, mood, posture, isProne, isStanding,
                motionLevel, filteredPixels, keypoints, faceRect, bodyBox, tier, action, faceJustCovered, riskScore)
            val result = applyHysteresis(raw, riskScore)
            addToBuffer(result)
            lastMotionLevel = motionLevel

        } else {
            // YOLO found nothing this frame — decay posture streaks the same way as a single
            // missed-classification frame above, instead of wiping them instantly. A momentary
            // dropped detection (motion blur, one slow/skipped scan) shouldn't cost an
            // otherwise-confirmed standing/prone streak its entire progress.
            proneStreak    = (proneStreak - 1).coerceAtLeast(0)
            standingStreak = (standingStreak - 1).coerceAtLeast(0)
            suffocationTimerStart = 0L
            agitationStreak = 0; extraEntityStreak = 0
            val mood = if (hasMotion) "Analyzing..." else "Sleeping"
            val raw = DetectionResult(status, mood, "None", false, false,
                motionLevel, filteredPixels, tier = "LOW", action = "Normal", riskScore = 0f)
            val result = applyHysteresis(raw, 0f)
            addToBuffer(result)
            lastMotionLevel = motionLevel
        }

        return getConsensusResult()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun calculateFaceRect(keypoints: List<Keypoint>): android.graphics.RectF? {
        if (keypoints.size < 5) return null
        val nose = keypoints[0]; val lE = keypoints[1]; val rE = keypoints[2]
        if (YoloDetector.isFaceHidden(nose, lE, rE)) return null
        val headSize = Math.abs(rE.position.x - lE.position.x) * 4f
        return android.graphics.RectF(
            nose.position.x - headSize / 2,
            nose.position.y - headSize / 1.5f,
            nose.position.x + headSize / 2,
            nose.position.y + headSize / 3f
        )
    }

    private fun tierRank(t: String): Int = when (t) { "HIGH" -> 2; "MEDIUM" -> 1; else -> 0 }
    private fun rankTier(r: Int): String = when (r) { 2 -> "HIGH"; 1 -> "MEDIUM"; else -> "LOW" }

    /**
     * Asymmetric hysteresis: escalation is immediate, de-escalation requires the
     * fused riskScore to stay below a tier-specific floor for a sustained dwell
     * time. While waiting, the frame's fresh posture/mood/keypoints/motionLevel
     * still pass through untouched — only tier/action/status are held.
     */
    private fun applyHysteresis(raw: DetectionResult, riskScore: Float): DetectionResult {
        val rawRank = tierRank(raw.tier)
        val now = System.currentTimeMillis()

        if (rawRank >= heldTierRank) {
            heldTierRank = rawRank
            heldStatus = raw.status
            heldAction = raw.action
            fallingSince = 0L
            return raw
        }

        // Candidate de-escalation — needs sustained calm before it's allowed through.
        if (fallingSince == 0L) fallingSince = now
        val dwell = if (heldTierRank == 2) HIGH_FALL_DWELL_MS else MEDIUM_FALL_DWELL_MS
        val scoreFloor = if (heldTierRank == 2) HIGH_FALL_SCORE else MEDIUM_FALL_SCORE
        val readyToDrop = (now - fallingSince >= dwell) && riskScore < scoreFloor

        return if (readyToDrop) {
            heldTierRank = rawRank
            fallingSince = 0L
            raw
        } else {
            raw.copy(tier = rankTier(heldTierRank), action = heldAction, status = heldStatus)
        }
    }

    private fun addToBuffer(result: DetectionResult) {
        resultBuffer.add(result)
        if (resultBuffer.size > BUFFER_SIZE) resultBuffer.removeAt(0)
    }

    /**
     * Consensus strategy:
     *  - Status: majority vote across the buffer (smooths out one-frame glitches).
     *  - Keypoints: always the most recent non-empty set (freshest tracking data).
     *  This means the skeleton stays smooth even when the status label is averaged.
     */
    private fun getConsensusResult(): DetectionResult {
        if (resultBuffer.isEmpty())
            return DetectionResult("---", "---", "---", false, false, 0)

        val consensusStatus = resultBuffer
            .groupingBy { it.status }.eachCount()
            .maxByOrNull { it.value }?.key ?: resultBuffer.last().status

        // Find the freshest frame with actual keypoint data
        val latestKeypoints = resultBuffer.findLast { it.keypoints.isNotEmpty() }
        // faceJustCovered is a one-shot transient signal — always read from the newest
        // frame rather than the vote-smoothed one, or it could get diluted/missed entirely.
        val latestFaceJustCovered = resultBuffer.last().faceJustCovered

        val base = resultBuffer.findLast { it.status == consensusStatus } ?: resultBuffer.last()
        return if (latestKeypoints != null && latestKeypoints != base)
            base.copy(status = consensusStatus,
                      keypoints = latestKeypoints.keypoints,
                      bodyBox   = latestKeypoints.bodyBox,
                      faceRect  = latestKeypoints.faceRect,
                      faceJustCovered = latestFaceJustCovered)
        else
            base.copy(status = consensusStatus, faceJustCovered = latestFaceJustCovered)
    }
}
