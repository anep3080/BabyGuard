package com.example.babyguard

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View

/**
 * Overlay that renders the YOLO skeleton, bounding box, and emotion label.
 *
 * Design:
 *  • YOLO fires at a variable rate (100 ms – 8 s depending on motion/mode).
 *    Without a render loop the overlay would stutter at that same rate even
 *    though the camera preview is silky-smooth at 30+ FPS.
 *  • A 60 FPS Handler loop calls invalidate() continuously. onDraw() runs
 *    EMA interpolation on every frame, so keypoints glide smoothly toward
 *    the latest YOLO target instead of jumping when a new detection arrives.
 *  • ALPHA = 0.15: settles to 95 % of target in ~18 frames (~300 ms at 60 FPS),
 *    which roughly matches the YOLO interval in BALANCED-active mode.
 */
class OverlayView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    // Latest YOLO result — updated on the worker thread, read on the main thread.
    // Volatile so the render loop always sees the freshest value without locking.
    @Volatile private var targetResult: SafetyPipeline.DetectionResult? = null

    // ── Paints ────────────────────────────────────────────────────────────────
    private val safePaint = Paint().apply {
        color = Color.GREEN; style = Paint.Style.STROKE; strokeWidth = 5f; isAntiAlias = true
    }
    private val dangerPaint = Paint().apply {
        color = Color.RED; style = Paint.Style.STROKE; strokeWidth = 7f; isAntiAlias = true
    }
    private val jointPaint = Paint().apply {
        color = Color.WHITE; style = Paint.Style.FILL; isAntiAlias = true
    }
    private val bonePaint = Paint().apply {
        color = Color.parseColor("#00FF00")
        style = Paint.Style.STROKE; strokeWidth = 3f; isAntiAlias = true
    }
    private val facePaint = Paint().apply {
        color = Color.CYAN; style = Paint.Style.STROKE; strokeWidth = 3f
        pathEffect = DashPathEffect(floatArrayOf(5f, 5f), 0f)
    }
    private val textPaint = Paint().apply {
        color = Color.WHITE; textSize = 32f; isAntiAlias = true
        setShadowLayer(5f, 2f, 2f, Color.BLACK)
    }
    private val smallTextPaint = Paint().apply {
        color = Color.WHITE; textSize = 22f; isAntiAlias = true
        setShadowLayer(5f, 2f, 2f, Color.BLACK)
    }

    // ── EMA smoothing ─────────────────────────────────────────────────────────
    // Lower alpha → smoother but more lag. At 60 FPS, ALPHA=0.15 converges to
    // the new position in ~18 frames (≈300 ms), matching the YOLO interval.
    private val ALPHA = 0.15f

    private val smoothedKpts = mutableMapOf<Int, PointF>()
    private var smoothedBox  = RectF()
    private var smoothedFace = RectF()
    private var hasBox  = false
    private var hasFace = false

    // ── Skeleton connections (COCO 17-keypoint pairs) ─────────────────────────
    private val BONES = listOf(
        5 to 6,  6 to 12,  5 to 11, 11 to 12,   // torso
        5 to 7,  7 to 9,   6 to 8,   8 to 10,   // arms
        11 to 13, 13 to 15, 12 to 14, 14 to 16  // legs
    )

    // ── 60 FPS render loop ────────────────────────────────────────────────────
    private var isRenderLoopRunning = false
    private val renderHandler = Handler(Looper.getMainLooper())
    private val renderRunnable = object : Runnable {
        override fun run() {
            if (isRenderLoopRunning) {
                invalidate()
                renderHandler.postDelayed(this, 16L) // ≈60 FPS
            }
        }
    }

    fun startRenderLoop() {
        if (!isRenderLoopRunning) {
            isRenderLoopRunning = true
            renderHandler.post(renderRunnable)
        }
    }

    fun stopRenderLoop() {
        isRenderLoopRunning = false
        renderHandler.removeCallbacks(renderRunnable)
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    /** Called by SafetyPipeline on each YOLO frame. Stores the target; the render
     *  loop handles drawing — no postInvalidate() needed here. */
    fun setResults(result: SafetyPipeline.DetectionResult?) {
        targetResult = result
    }

    // ── Drawing ────────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val result = targetResult ?: run {
            // No detection — fade out by clearing smoothed state
            if (smoothedKpts.isNotEmpty()) { smoothedKpts.clear(); hasBox = false; hasFace = false }
            return
        }

        // YOLOv8n-pose outputs keypoints in a 640×640 coordinate space.
        // Scale to this view's actual pixel dimensions.
        val sx = width.toFloat()  / 640f
        val sy = height.toFloat() / 640f

        // ── 1. Skeleton — continuous EMA toward latest keypoint targets ────────
        if (result.keypoints.isNotEmpty()) {
            for (kpt in result.keypoints) {
                if (kpt.confidence < 0.3f) {
                    smoothedKpts.remove(kpt.id)
                    continue
                }
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
            // Bones
            for ((a, b) in BONES) {
                val pa = smoothedKpts[a]; val pb = smoothedKpts[b]
                val ca = result.keypoints.getOrNull(a)?.confidence ?: 0f
                val cb = result.keypoints.getOrNull(b)?.confidence ?: 0f
                if (pa != null && pb != null && ca > 0.3f && cb > 0.3f) {
                    canvas.drawLine(pa.x * sx, pa.y * sy, pb.x * sx, pb.y * sy, bonePaint)
                }
            }
            // Joints
            for ((id, sp) in smoothedKpts) {
                if ((result.keypoints.getOrNull(id)?.confidence ?: 0f) > 0.3f) {
                    canvas.drawCircle(sp.x * sx, sp.y * sy, 6f, jointPaint)
                }
            }
        } else {
            smoothedKpts.clear()
        }

        // ── 2. Body bounding box (EMA smoothed) ───────────────────────────────
        result.bodyBox?.let { box ->
            if (!hasBox) { smoothedBox.set(box); hasBox = true }
            else smoothedBox.set(
                smoothedBox.left   + ALPHA * (box.left   - smoothedBox.left),
                smoothedBox.top    + ALPHA * (box.top    - smoothedBox.top),
                smoothedBox.right  + ALPHA * (box.right  - smoothedBox.right),
                smoothedBox.bottom + ALPHA * (box.bottom - smoothedBox.bottom)
            )
            val paint = if (result.isStanding || result.isProne) dangerPaint else safePaint
            canvas.drawRect(smoothedBox.left * sx, smoothedBox.top * sy,
                            smoothedBox.right * sx, smoothedBox.bottom * sy, paint)
            canvas.drawText(result.status,
                smoothedBox.left * sx, (smoothedBox.top * sy) - 10f, textPaint)
        } ?: run { hasBox = false }

        // ── 3. Face bounding box + emotion label ──────────────────────────────
        result.faceRect?.let { r ->
            if (!hasFace) { smoothedFace.set(r); hasFace = true }
            else smoothedFace.set(
                smoothedFace.left   + ALPHA * (r.left   - smoothedFace.left),
                smoothedFace.top    + ALPHA * (r.top    - smoothedFace.top),
                smoothedFace.right  + ALPHA * (r.right  - smoothedFace.right),
                smoothedFace.bottom + ALPHA * (r.bottom - smoothedFace.bottom)
            )
            canvas.drawRect(smoothedFace.left * sx, smoothedFace.top * sy,
                            smoothedFace.right * sx, smoothedFace.bottom * sy, facePaint)
            canvas.drawText(result.mood,
                smoothedFace.left * sx, (smoothedFace.top * sy) - 5f, smallTextPaint)
        } ?: run { hasFace = false }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopRenderLoop()
    }
}
