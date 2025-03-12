package com.hfad.cameraapi

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.Log
import android.view.View

class PoseOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // Debug settings
    private var debugMode = true
    private var showLabels = true
    private var drawBackground = false

    // Appearance settings
    private val pointSize = 30f
    private val labelTextSize = 40f
    private val lineThickness = 10f

    // Paints
    private val pointPaint = Paint().apply {
        style = Paint.Style.FILL
        strokeWidth = 10f
    }

    private val linePaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = lineThickness
        strokeCap = Paint.Cap.ROUND
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = labelTextSize
        setShadowLayer(5f, 2f, 2f, Color.BLACK) // Shadow for visibility
    }

    private val debugTextPaint = Paint().apply {
        color = Color.WHITE
        textSize = 30f
        setShadowLayer(3f, 1f, 1f, Color.BLACK)
    }

    private val backgroundPaint = Paint().apply {
        color = Color.argb(100, 0, 0, 0)
        style = Paint.Style.FILL
    }

    // Data
    private var keyPoints: List<KeyPoint> = emptyList()
    private var imageWidth = 0
    private var imageHeight = 0
    private var scaleFactor: Float = 1.0f
    private var offsetX: Float = 0f
    private var offsetY: Float = 0f

    // Connections between keypoints to form a skeleton
    private val skeleton = listOf(
        Pair(BodyPart.NOSE, BodyPart.LEFT_EYE),
        Pair(BodyPart.NOSE, BodyPart.RIGHT_EYE),
        Pair(BodyPart.LEFT_EYE, BodyPart.LEFT_EAR),
        Pair(BodyPart.RIGHT_EYE, BodyPart.RIGHT_EAR),
        Pair(BodyPart.NOSE, BodyPart.LEFT_SHOULDER),
        Pair(BodyPart.NOSE, BodyPart.RIGHT_SHOULDER),
        Pair(BodyPart.LEFT_SHOULDER, BodyPart.LEFT_ELBOW),
        Pair(BodyPart.LEFT_ELBOW, BodyPart.LEFT_WRIST),
        Pair(BodyPart.RIGHT_SHOULDER, BodyPart.RIGHT_ELBOW),
        Pair(BodyPart.RIGHT_ELBOW, BodyPart.RIGHT_WRIST),
        Pair(BodyPart.LEFT_SHOULDER, BodyPart.RIGHT_SHOULDER),
        Pair(BodyPart.LEFT_SHOULDER, BodyPart.LEFT_HIP),
        Pair(BodyPart.RIGHT_SHOULDER, BodyPart.RIGHT_HIP),
        Pair(BodyPart.LEFT_HIP, BodyPart.RIGHT_HIP),
        Pair(BodyPart.LEFT_HIP, BodyPart.LEFT_KNEE),
        Pair(BodyPart.LEFT_KNEE, BodyPart.LEFT_ANKLE),
        Pair(BodyPart.RIGHT_HIP, BodyPart.RIGHT_KNEE),
        Pair(BodyPart.RIGHT_KNEE, BodyPart.RIGHT_ANKLE)
    )

    // Color mapping for body parts
    private val bodyPartColors = mapOf(
        BodyPart.NOSE to Color.RED,
        BodyPart.LEFT_EYE to Color.YELLOW,
        BodyPart.RIGHT_EYE to Color.YELLOW,
        BodyPart.LEFT_EAR to Color.YELLOW,
        BodyPart.RIGHT_EAR to Color.YELLOW,
        BodyPart.LEFT_SHOULDER to Color.GREEN,
        BodyPart.RIGHT_SHOULDER to Color.GREEN,
        BodyPart.LEFT_ELBOW to Color.CYAN,
        BodyPart.RIGHT_ELBOW to Color.CYAN,
        BodyPart.LEFT_WRIST to Color.BLUE,
        BodyPart.RIGHT_WRIST to Color.BLUE,
        BodyPart.LEFT_HIP to Color.MAGENTA,
        BodyPart.RIGHT_HIP to Color.MAGENTA,
        BodyPart.LEFT_KNEE to Color.YELLOW,
        BodyPart.RIGHT_KNEE to Color.YELLOW,
        BodyPart.LEFT_ANKLE to Color.WHITE,
        BodyPart.RIGHT_ANKLE to Color.WHITE
    )

    fun updatePose(keyPoints: List<KeyPoint>, imageWidth: Int, imageHeight: Int) {
        this.keyPoints = keyPoints
        this.imageWidth = imageWidth
        this.imageHeight = imageHeight

        // Calculate scale factors to map model coordinates to view coordinates
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()

        scaleFactor = minOf(viewWidth / imageWidth, viewHeight / imageHeight)
        offsetX = (viewWidth - imageWidth * scaleFactor) / 2
        offsetY = (viewHeight - imageHeight * scaleFactor) / 2

        // Log the transformation factors
        Log.d(TAG, "View size: ${viewWidth}x${viewHeight}, Image size: ${imageWidth}x${imageHeight}")
        Log.d(TAG, "Scale factor: $scaleFactor, Offset: ($offsetX, $offsetY)")

        // Count visible high-confidence points
        val highConfidenceCount = keyPoints.count { it.score > HIGH_CONFIDENCE }
        Log.d(TAG, "High confidence keypoints: $highConfidenceCount / ${keyPoints.size}")

        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (keyPoints.isEmpty()) {
            drawNoKeypoints(canvas)
            return
        }

        // Draw semi-transparent background if enabled
        if (drawBackground) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)
        }

        // First draw all lines
        drawSkeletonLines(canvas)

        // Then draw all points (so they're on top of lines)
        drawKeypoints(canvas)

        // Finally draw debug info if enabled
        if (debugMode) {
            drawDebugInfo(canvas)
        }
    }

    private fun drawNoKeypoints(canvas: Canvas) {
        canvas.drawText("No keypoints detected", 50f, 100f, debugTextPaint)
        canvas.drawText("Try showing your full body", 50f, 150f, debugTextPaint)
    }

    private fun drawSkeletonLines(canvas: Canvas) {
        for ((firstPart, secondPart) in skeleton) {
            val firstPoint = keyPoints.firstOrNull { it.bodyPart == firstPart }
            val secondPoint = keyPoints.firstOrNull { it.bodyPart == secondPart }

            if (firstPoint != null && secondPoint != null) {
                // Only draw if at least one point has reasonable confidence
                if (firstPoint.score > MEDIUM_CONFIDENCE || secondPoint.score > MEDIUM_CONFIDENCE) {
                    val x1 = translateX(firstPoint.coordinate.x)
                    val y1 = translateY(firstPoint.coordinate.y)
                    val x2 = translateX(secondPoint.coordinate.x)
                    val y2 = translateY(secondPoint.coordinate.y)

                    // Set line color based on average confidence
                    val avgConfidence = (firstPoint.score + secondPoint.score) / 2

                    // Color based on confidence - green for high, yellow for medium, red for low
                    val lineColor = when {
                        avgConfidence > HIGH_CONFIDENCE -> Color.GREEN
                        avgConfidence > MEDIUM_CONFIDENCE -> Color.YELLOW
                        else -> Color.RED
                    }

                    // Set line thickness based on confidence
                    val thickness = lineThickness * (0.5f + avgConfidence)

                    linePaint.color = lineColor
                    linePaint.strokeWidth = thickness
                    canvas.drawLine(x1, y1, x2, y2, linePaint)
                }
            }
        }
    }

    private fun drawKeypoints(canvas: Canvas) {
        for (keyPoint in keyPoints) {
            // Skip very low confidence points
            if (keyPoint.score < LOW_CONFIDENCE) continue

            val x = translateX(keyPoint.coordinate.x)
            val y = translateY(keyPoint.coordinate.y)

            // Get color for this body part
            val color = bodyPartColors[keyPoint.bodyPart] ?: Color.WHITE

            // Draw with varying size based on confidence
            val radius = pointSize * (0.5f + keyPoint.score / 2)
            pointPaint.color = color
            canvas.drawCircle(x, y, radius, pointPaint)

            // Draw label if enabled
            if (showLabels && keyPoint.score > MEDIUM_CONFIDENCE) {
                canvas.drawText(
                    keyPoint.bodyPart.name,
                    x + radius + 5,
                    y,
                    textPaint
                )

                // Draw confidence percentage
                val confidenceText = "${(keyPoint.score * 100).toInt()}%"
                canvas.drawText(
                    confidenceText,
                    x + radius + 5,
                    y + textPaint.textSize + 5,
                    textPaint
                )
            }
        }
    }

    private fun drawDebugInfo(canvas: Canvas) {
        // Draw background for debug panel
        val rect = RectF(10f, 10f, 350f, 250f)
        canvas.drawRect(rect, backgroundPaint)

        // Draw debug info
        var y = 50f

        canvas.drawText("Total keypoints: ${keyPoints.size}", 20f, y, debugTextPaint)
        y += 40f

        val highConf = keyPoints.count { it.score > HIGH_CONFIDENCE }
        val medConf = keyPoints.count { it.score > MEDIUM_CONFIDENCE && it.score <= HIGH_CONFIDENCE }
        val lowConf = keyPoints.count { it.score > LOW_CONFIDENCE && it.score <= MEDIUM_CONFIDENCE }

        canvas.drawText("High confidence: $highConf", 20f, y, debugTextPaint)
        y += 40f
        canvas.drawText("Medium confidence: $medConf", 20f, y, debugTextPaint)
        y += 40f
        canvas.drawText("Low confidence: $lowConf", 20f, y, debugTextPaint)
        y += 40f

        // Draw head keypoints confidence (these are likely to be visible in selfie mode)
        val nose = keyPoints.firstOrNull { it.bodyPart == BodyPart.NOSE }
        if (nose != null) {
            canvas.drawText("Nose: ${(nose.score * 100).toInt()}%", 20f, y, debugTextPaint)
        }
    }

    // Convert model coordinates to screen coordinates
    private fun translateX(x: Float): Float = x * scaleFactor + offsetX
    private fun translateY(y: Float): Float = y * scaleFactor + offsetY

    // Functions to toggle debug features
    fun toggleDebugMode() {
        debugMode = !debugMode
        invalidate()
    }

    fun toggleLabels() {
        showLabels = !showLabels
        invalidate()
    }

    fun toggleBackground() {
        drawBackground = !drawBackground
        invalidate()
    }

    companion object {
        const val TAG = "PoseOverlayView"

        // Confidence thresholds
        const val HIGH_CONFIDENCE = 0.70f
        const val MEDIUM_CONFIDENCE = 0.35f
        const val LOW_CONFIDENCE = 0.15f
    }
}

// Data classes for pose estimation
data class KeyPoint(val bodyPart: BodyPart, val coordinate: Coordinate, val score: Float)
data class Coordinate(val x: Float, val y: Float)

enum class BodyPart(val position: Int) {
    NOSE(0),
    LEFT_EYE(1),
    RIGHT_EYE(2),
    LEFT_EAR(3),
    RIGHT_EAR(4),
    LEFT_SHOULDER(5),
    RIGHT_SHOULDER(6),
    LEFT_ELBOW(7),
    RIGHT_ELBOW(8),
    LEFT_WRIST(9),
    RIGHT_WRIST(10),
    LEFT_HIP(11),
    RIGHT_HIP(12),
    LEFT_KNEE(13),
    RIGHT_KNEE(14),
    LEFT_ANKLE(15),
    RIGHT_ANKLE(16)
}