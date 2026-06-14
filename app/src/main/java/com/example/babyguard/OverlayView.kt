package com.example.babyguard

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

class OverlayView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private var currentResult: SafetyPipeline.DetectionResult? = null

    private val safePaint = Paint().apply { color = Color.GREEN; style = Paint.Style.STROKE; strokeWidth = 5f; isAntiAlias = true }
    private val dangerPaint = Paint().apply { color = Color.RED; style = Paint.Style.STROKE; strokeWidth = 7f; isAntiAlias = true }
    private val jointPaint = Paint().apply { color = Color.WHITE; style = Paint.Style.FILL; isAntiAlias = true }
    private val bonePaint = Paint().apply { color = Color.parseColor("#00FF00"); style = Paint.Style.STROKE; strokeWidth = 3f; isAntiAlias = true }
    private val facePaint = Paint().apply { color = Color.CYAN; style = Paint.Style.STROKE; strokeWidth = 3f; pathEffect = DashPathEffect(floatArrayOf(5f, 5f), 0f) }
    private val textPaint = Paint().apply { color = Color.WHITE; textSize = 32f; isAntiAlias = true; setShadowLayer(5f, 2f, 2f, Color.BLACK) }

    fun setResults(result: SafetyPipeline.DetectionResult?) {
        currentResult = result
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        currentResult?.let { result ->
            // AI scale is always 640x640 (squashed square)
            val sx = width.toFloat() / 640f
            val sy = height.toFloat() / 640f

            // 1. Draw Bones
            val conn = listOf(Pair(5,6), Pair(5,11), Pair(6,12), Pair(11,12), Pair(5,7), Pair(7,9), Pair(6,8), Pair(8,10), Pair(11,13), Pair(13,15), Pair(12,14), Pair(14,16))
            if (result.keypoints.isNotEmpty()) {
                for (p in conn) {
                    val a = result.keypoints[p.first]; val b = result.keypoints[p.second]
                    if (a.confidence > 0.3f && b.confidence > 0.3f) {
                        canvas.drawLine(a.position.x * sx, a.position.y * sy, b.position.x * sx, b.position.y * sy, bonePaint)
                    }
                }
                for (k in result.keypoints) {
                    if (k.confidence > 0.3f) canvas.drawCircle(k.position.x * sx, k.position.y * sy, 5f, jointPaint)
                }
            }

            // 2. Body Box
            result.bodyBox?.let { box ->
                val paint = if (result.isStanding || result.isProne) dangerPaint else safePaint
                canvas.drawRect(box.left * sx, box.top * sy, box.right * sx, box.bottom * sy, paint)
                canvas.drawText(result.status, box.left * sx, (box.top * sy) - 10f, textPaint)
            }

            // 3. Face Box
            result.faceRect?.let { r ->
                canvas.drawRect(r.left * sx, r.top * sy, r.right * sx, r.bottom * sy, facePaint)
                canvas.drawText("Head: ${result.mood}", r.left * sx, (r.top * sy) - 5f, textPaint.apply { textSize = 22f })
            }
        }
    }
}