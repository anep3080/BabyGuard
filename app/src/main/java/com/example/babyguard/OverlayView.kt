package com.example.babyguard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class OverlayView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private var boundingBox: BoundingBox? = null

    // Normal Safe Box (Yellow)
    private val safeBoxPaint = Paint().apply {
        color = Color.parseColor("#FFEA7A")
        style = Paint.Style.STROKE
        strokeWidth = 8f
    }

    // Danger Box (Red for standing)
    private val dangerBoxPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 10f
    }

    // Text settings
    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 55f
        style = Paint.Style.FILL
        isAntiAlias = true
        setShadowLayer(5f, 2f, 2f, Color.BLACK) // Adds a shadow so it's readable on any background
    }

    fun setResults(box: BoundingBox?) {
        boundingBox = box
        invalidate() // Tells Android to wipe the screen and redraw
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        boundingBox?.let { result ->
            // MATH: Scale the 640x640 YOLO grid to fit your phone's actual width and height
            val scaleX = width.toFloat() / 640f
            val scaleY = height.toFloat() / 640f

            // Apply the scaling to the bounding box coordinates
            val scaledLeft = result.box.left * scaleX
            val scaledTop = result.box.top * scaleY
            val scaledRight = result.box.right * scaleX
            val scaledBottom = result.box.bottom * scaleY

            // Choose colors and text based on our geometry logic!
            val currentPaint = if (result.isStanding) dangerBoxPaint else safeBoxPaint
            val displayStatus = if (result.isStanding) "🚨 DANGER: Upright!" else "Safe: ${result.label.replaceFirstChar { it.uppercase() }}"

            // Draw the Box
            canvas.drawRect(scaledLeft, scaledTop, scaledRight, scaledBottom, currentPaint)

            // Draw the Text slightly above the box
            canvas.drawText(displayStatus, scaledLeft, scaledTop - 20f, textPaint)
        }
    }
}