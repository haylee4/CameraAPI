package com.hfad.cameraapi

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

//Create a custom view for drawing the pose
class PoseOverlayView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    //List of keypoints to display
    private var keypoints: List<Keypoint> = emptyList()

    private val pointPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.FILL
        strokeWidth = 10f
    }

    private val linePaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }

    // Update keypoints from detection
    fun updateKeypoints(points: List<Keypoint>) {
        keypoints = points
        invalidate() // Request redraw
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Draw each keypoint as a circle
        keypoints.forEach { keypoint ->
            // Scale normalized coordinates to view dimensions
            val x = keypoint.position.x * width
            val y = keypoint.position.y * height

            // Draw circle with radius based on confidence
            val radius = 5f + (keypoint.score * 5f).coerceAtMost(15f)
            canvas.drawCircle(x, y, radius, pointPaint)
        }

        //Draw skeleton lines between keypoints
        drawLine(canvas, "LEFT_WRIST", "LEFT_ELBOW")
        drawLine(canvas, "LEFT_ELBOW", "LEFT_SHOULDER")
        drawLine(canvas, "LEFT_SHOULDER", "RIGHT_SHOULDER")
        drawLine(canvas, "RIGHT_SHOULDER", "RIGHT_ELBOW")
        drawLine(canvas, "RIGHT_ELBOW", "RIGHT_WRIST")
        //Add more connections as needed
    }

    private fun drawLine(canvas: Canvas, from: String, to: String) {
        val fromPoint = keypoints.find { it.bodyPart.name == from }
        val toPoint = keypoints.find { it.bodyPart.name == to }

        if (fromPoint != null && toPoint != null &&
            fromPoint.score > 0.5f && toPoint.score > 0.5f
        ) {
            canvas.drawLine(
                fromPoint.position.x * width,
                fromPoint.position.y * height,
                toPoint.position.x * width,
                toPoint.position.y * height,
                linePaint
            )
        }
    }
}