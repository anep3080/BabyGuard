package com.example.babyguard

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

class OverlayView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private var currentResult: SafetyPipeline.DetectionResult? = null

    // Box Paints
    private val safeBoxPaint = Paint().apply { color = Color.parseColor("#FFEA7A"); style = Paint.Style.STROKE; strokeWidth = 8f }
    private val dangerBoxPaint = Paint().apply { color = Color.RED; style = Paint.Style.STROKE; strokeWidth = 10f }
    private val faceBoxPaint = Paint().apply { color = Color.CYAN; style = Paint.Style.STROKE; strokeWidth = 4f; pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f) }

    // Skeletal Paints
    private val jointPaint = Paint().apply { color = Color.WHITE; style = Paint.Style.FILL; isAntiAlias = true }
    private val bonePaint = Paint().apply { color = Color.parseColor("#00FF00"); style = Paint.Style.STROKE; strokeWidth = 5f; isAntiAlias = true }

    private val textPaint = Paint().apply {
        color = Color.WHITE; textSize = 40f; style = Paint.Style.FILL; isAntiAlias = true
        setShadowLayer(5f, 2f, 2f, Color.BLACK)
    }

    fun setResults(result: SafetyPipeline.DetectionResult?) {
        currentResult = result
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        currentResult?.let { result ->
            val scaleX = width.toFloat() / 640f
            val scaleY = height.toFloat() / 640f

            // 1. Draw Body Bounding Box
            result.bodyBox?.let { box ->
                val paint = if (result.isStanding) dangerBoxPaint else safeBoxPaint
                canvas.drawRect(box.left * scaleX, box.top * scaleY, box.right * scaleX, box.bottom * scaleY, paint)
                canvas.drawText(result.status, box.left * scaleX, (box.top * scaleY) - 10f, textPaint)
            }

            // 2. Draw Face Analysis Box (Tier 3)
            result.faceRect?.let { rect ->
                canvas.drawRect(rect.left * scaleX, rect.top * scaleY, rect.right * scaleX, rect.bottom * scaleY, faceBoxPaint)
                canvas.drawText("Face: ${result.mood}", rect.left * scaleX, (rect.top * scaleY) - 5f, textPaint.apply { textSize = 30f })
            }

            // 3. Draw Skeleton (Tier 2)
            if (result.keypoints.isNotEmpty()) {
                drawSkeleton(canvas, result.keypoints, scaleX, scaleY)
            }
        }
    }

    private fun drawSkeleton(canvas: Canvas, kpts: List<Keypoint>, sx: Float, sy: Float) {
        // Define connections (COCO Format)
        val connections = listOf(
            Pair(PoseJoints.LEFT_SHOULDER, PoseJoints.RIGHT_SHOULDER),
            Pair(PoseJoints.LEFT_SHOULDER, PoseJoints.LEFT_HIP),
            Pair(PoseJoints.RIGHT_SHOULDER, PoseJoints.RIGHT_HIP),
            Pair(PoseJoints.LEFT_HIP, PoseJoints.RIGHT_HIP),
            Pair(PoseJoints.LEFT_SHOULDER, PoseJoints.LEFT_ELBOW),
            Pair(PoseJoints.LEFT_ELBOW, PoseJoints.LEFT_WRIST),
            Pair(PoseJoints.RIGHT_SHOULDER, PoseJoints.RIGHT_ELBOW),
            Pair(PoseJoints.RIGHT_ELBOW, PoseJoints.RIGHT_WRIST),
            Pair(PoseJoints.LEFT_HIP, PoseJoints.LEFT_KNEE),
            Pair(PoseJoints.LEFT_KNEE, PoseJoints.LEFT_ANKLE),
            Pair(PoseJoints.RIGHT_HIP, PoseJoints.RIGHT_KNEE),
            Pair(PoseJoints.RIGHT_KNEE, PoseJoints.RIGHT_ANKLE)
        )

        // Draw Bones
        for (conn in connections) {
            val a = kpts[conn.first]
            val b = kpts[conn.second]
            if (a.confidence > 0.3f && b.confidence > 0.3f) {
                canvas.drawLine(a.position.x * sx, a.position.y * sy, b.position.x * sx, b.position.y * sy, bonePaint)
            }
        }

        // Draw Joints
        for (kpt in kpts) {
            if (kpt.confidence > 0.3f) {
                canvas.drawCircle(kpt.position.x * sx, kpt.position.y * sy, 6f, jointPaint)
            }
        }
    }
}