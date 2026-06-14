package com.example.babyguard

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

class OverlayView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private var currentResult: SafetyPipeline.DetectionResult? = null

    // ── Paints ────────────────────────────────────────────────────────────────
    private val safePaint   = Paint().apply { color = Color.GREEN; style = Paint.Style.STROKE; strokeWidth = 5f; isAntiAlias = true }
    private val dangerPaint = Paint().apply { color = Color.RED;   style = Paint.Style.STROKE; strokeWidth = 7f; isAntiAlias = true }
    private val jointPaint  = Paint().apply { color = Color.WHITE; style = Paint.Style.FILL;   isAntiAlias = true }
    private val bonePaint   = Paint().apply { color = Color.parseColor("#00FF00"); style = Paint.Style.STROKE; strokeWidth = 3f; isAntiAlias = true }
    private val facePaint   = Paint().apply {
        color = Color.CYAN; style = Paint.Style.STROKE; strokeWidth = 3f
        pathEffect = DashPathEffect(floatArrayOf(5f, 5f), 0f)
    }
    private val textPaint   = Paint().apply {
        color = Color.WHITE; textSize = 32f; isAntiAlias = true
        setShadowLayer(5f, 2f, 2f, Color.BLACK)
    }
    private val smallTextPaint = Paint().apply {
        color = Color.WHITE; textSize = 22f; isAntiAlias = true
        setShadowLayer(5f, 2f, 2f, Color.BLACK)
    }

    // ── EMA smoothing state ───────────────────────────────────────────────────
    // alpha = weight given to the new frame. Lower = smoother but more lag.
    // 0.35 feels fluid while still reacting in ~3-4 frames.
    private val ALPHA = 0.35f

    private val smoothedKpts  = mutableMapOf<Int, PointF>()    // keyed by keypoint id
    private var smoothedBox   = RectF()
    private var smoothedFace  = RectF()
    private var hasBox  = false
    private var hasFace = false

    // ── Skeleton connections (COCO 17-keypoint format) ────────────────────────
    private val BONES = listOf(
        5 to 6,   6 to 12,  5 to 11,  11 to 12,   // torso
        5 to 7,   7 to 9,   6 to 8,   8 to 10,    // arms
        11 to 13, 13 to 15, 12 to 14, 14 to 16    // legs
    )

    // ─────────────────────────────────────────────────────────────────────────

    fun setResults(result: SafetyPipeline.DetectionResult?) {
        currentResult = result
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val result = currentResult ?: return

        // AI model always outputs 640×640 coordinates.
        // Scale to this view's actual pixel dimensions.
        val sx = width.toFloat()  / 640f
        val sy = height.toFloat() / 640f

        // ── 1. Skeleton (bones + joints) ──────────────────────────────────────
        if (result.keypoints.isNotEmpty()) {
            // Smooth each visible keypoint with EMA
            for (kpt in result.keypoints) {
                if (kpt.confidence < 0.3f) continue
                val prev = smoothedKpts[kpt.id]
                smoothedKpts[kpt.id] = if (prev == null) {
                    PointF(kpt.position.x, kpt.position.y)
                } else {
                    PointF(
                        prev.x + ALPHA * (kpt.position.x - prev.x),
                        prev.y + ALPHA * (kpt.position.y - prev.y)
                    )
                }
            }
            // Draw bones using smoothed positions
            for ((a, b) in BONES) {
                val pa = smoothedKpts[a]; val pb = smoothedKpts[b]
                if (pa != null && pb != null &&
                    (result.keypoints.getOrNull(a)?.confidence ?: 0f) > 0.3f &&
                    (result.keypoints.getOrNull(b)?.confidence ?: 0f) > 0.3f) {
                    canvas.drawLine(pa.x * sx, pa.y * sy, pb.x * sx, pb.y * sy, bonePaint)
                }
            }
            // Draw joints
            for ((id, sp) in smoothedKpts) {
                if ((result.keypoints.getOrNull(id)?.confidence ?: 0f) > 0.3f) {
                    canvas.drawCircle(sp.x * sx, sp.y * sy, 6f, jointPaint)
                }
            }
        } else {
            // No keypoints this frame — gradually decay smoothed state
            smoothedKpts.clear()
        }

        // ── 2. Body bounding box (EMA smoothed) ───────────────────────────────
        result.bodyBox?.let { box ->
            if (!hasBox) { smoothedBox.set(box); hasBox = true }
            else {
                smoothedBox.set(
                    smoothedBox.left   + ALPHA * (box.left   - smoothedBox.left),
                    smoothedBox.top    + ALPHA * (box.top    - smoothedBox.top),
                    smoothedBox.right  + ALPHA * (box.right  - smoothedBox.right),
                    smoothedBox.bottom + ALPHA * (box.bottom - smoothedBox.bottom)
                )
            }
            val paint = if (result.isStanding || result.isProne) dangerPaint else safePaint
            canvas.drawRect(
                smoothedBox.left   * sx, smoothedBox.top    * sy,
                smoothedBox.right  * sx, smoothedBox.bottom * sy,
                paint
            )
            canvas.drawText(result.status,
                smoothedBox.left * sx,
                (smoothedBox.top * sy) - 10f,
                textPaint)
        } ?: run { hasBox = false }

        // ── 3. Face bounding box (EMA smoothed) ───────────────────────────────
        result.faceRect?.let { r ->
            if (!hasFace) { smoothedFace.set(r); hasFace = true }
            else {
                smoothedFace.set(
                    smoothedFace.left   + ALPHA * (r.left   - smoothedFace.left),
                    smoothedFace.top    + ALPHA * (r.top    - smoothedFace.top),
                    smoothedFace.right  + ALPHA * (r.right  - smoothedFace.right),
                    smoothedFace.bottom + ALPHA * (r.bottom - smoothedFace.bottom)
                )
            }
            canvas.drawRect(
                smoothedFace.left   * sx, smoothedFace.top    * sy,
                smoothedFace.right  * sx, smoothedFace.bottom * sy,
                facePaint
            )
            canvas.drawText(
                "Head: ${result.mood}",
                smoothedFace.left * sx,
                (smoothedFace.top * sy) - 5f,
                smallTextPaint
            )
        } ?: run { hasFace = false }
    }
}
