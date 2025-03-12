package com.hfad.cameraapi

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import androidx.core.graphics.createBitmap

class MainActivity : ComponentActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var poseOverlayView: PoseOverlayView
    private lateinit var poseDetector: PoseDetector
    private lateinit var cameraExecutor: ExecutorService

    // Launcher for requesting camera permission
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                startCamera()
            } else {
                Toast.makeText(this, "Camera permission denied", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        poseOverlayView = findViewById(R.id.poseOverlayView)

        // Debug controls
        val debugInfoTextView = findViewById<TextView>(R.id.debugInfoTextView)
        debugInfoTextView?.setOnClickListener {
            poseOverlayView.toggleDebugMode()
        }

        // Let user tap on the overlay to toggle different debug modes
        poseOverlayView.setOnClickListener {
            // Cycle through different debug modes:
            // 1. Toggle background
            // 2. Toggle labels
            poseOverlayView.toggleLabels()
        }

        // Add long press to toggle background
        poseOverlayView.setOnLongClickListener {
            poseOverlayView.toggleBackground()
            true
        }

        // Initialize the pose detector
        poseDetector = PoseDetector(this)

        // Initialize the camera executor
        cameraExecutor = Executors.newSingleThreadExecutor()

        // Check if we already have camera permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            // Request camera permission
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        // Set up the test button
        val testModelButton = findViewById<Button>(R.id.testModelButton)
        testModelButton.setOnClickListener {
            // Don't start the camera at all, just run the test
            testModelWithStaticImage()
        }

        // Only start the camera if we're not in test mode
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            // Request camera permission
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun createTestImageProgrammatically(): Bitmap {
        // Create a blank bitmap with a white background
        val bitmap = createBitmap(500, 800)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        // Draw a simple stick figure
        val paint = Paint().apply {
            color = Color.BLACK
            strokeWidth = 10f
            style = Paint.Style.STROKE
        }

        // Head
        canvas.drawCircle(250f, 150f, 50f, paint)

        // Body
        canvas.drawLine(250f, 200f, 250f, 400f, paint)

        // Arms
        canvas.drawLine(250f, 250f, 150f, 300f, paint)
        canvas.drawLine(250f, 250f, 350f, 300f, paint)

        // Legs
        canvas.drawLine(250f, 400f, 150f, 600f, paint)
        canvas.drawLine(250f, 400f, 350f, 600f, paint)

        return bitmap
    }


    @SuppressLint("ClickableViewAccessibility")
    private fun startCamera() {
        // Get the camera provider
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // Create the Preview use case
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            // Create the ImageAnalysis use case for pose detection
            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, PoseAnalyzer { keyPoints, imageWidth, imageHeight ->
                        runOnUiThread {
                            poseOverlayView.updatePose(keyPoints, imageWidth, imageHeight)
                        }
                    })
                }

            // Select the front camera for selfies
            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            try {
                // Unbind any existing use cases
                cameraProvider.unbindAll()

                // Bind the preview and image analysis use cases to the lifecycle
                val camera = cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalysis
                )

                // Enable tap to focus
                previewView.setOnTouchListener { _, motionEvent ->
                    when (motionEvent.action) {
                        MotionEvent.ACTION_DOWN -> {
                            return@setOnTouchListener true
                        }
                        MotionEvent.ACTION_UP -> {
                            // Convert UI coordinates to sensor coordinates
                            val factory = previewView.meteringPointFactory
                            val point = factory.createPoint(motionEvent.x, motionEvent.y)

                            // Focus action
                            val action = FocusMeteringAction.Builder(point)
                                .setAutoCancelDuration(3, TimeUnit.SECONDS)
                                .build()

                            camera.cameraControl.startFocusAndMetering(action)
                            return@setOnTouchListener true
                        }
                        else -> return@setOnTouchListener false
                    }
                }

            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        poseDetector.close()
    }

    // Analyzer class for processing camera frames
    private inner class PoseAnalyzer(
        private val onPoseDetected: (List<KeyPoint>, Int, Int) -> Unit
    ) : ImageAnalysis.Analyzer {

        private var lastAnalyzedTimestamp = 0L
        private val frameInterval = 100L  // Process a frame every 100ms

        @androidx.camera.core.ExperimentalGetImage
        override fun analyze(imageProxy: ImageProxy) {
            val currentTimestamp = System.currentTimeMillis()

            if (currentTimestamp - lastAnalyzedTimestamp >= frameInterval) {
                val mediaImage = imageProxy.image
                if (mediaImage != null) {
                    try {
                        // Convert the camera image to bitmap
                        val bitmap = imageToBitmap(mediaImage, imageProxy.imageInfo.rotationDegrees)

                        Log.d("PoseAnalyzer", "Processing image: ${bitmap.width}x${bitmap.height}, rotation: ${imageProxy.imageInfo.rotationDegrees}")

                        // Run pose estimation
                        val keyPoints = poseDetector.estimatePose(bitmap)

                        // Debug each keypoint with position and confidence
                        Log.d("PoseAnalyzer", "Raw detection results:")
                        keyPoints.forEach { keyPoint ->
                            Log.d("PoseAnalyzer", "${keyPoint.bodyPart.name}: x=${keyPoint.coordinate.x}, y=${keyPoint.coordinate.y}, confidence=${keyPoint.score}")
                        }

                        // Pass the results to the callback on main thread
                        runOnUiThread {
                            onPoseDetected(keyPoints, bitmap.width, bitmap.height)
                        }

                        lastAnalyzedTimestamp = currentTimestamp
                    } catch (e: Exception) {
                        Log.e("PoseAnalyzer", "Error in analysis: ${e.message}", e)
                    }
                }
            }

            imageProxy.close()
        }

        private fun imageToBitmap(image: android.media.Image, rotation: Int): Bitmap {
            // Convert YUV to RGB
            val yBuffer = image.planes[0].buffer
            val uBuffer = image.planes[1].buffer
            val vBuffer = image.planes[2].buffer

            val ySize = yBuffer.remaining()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()

            val nv21 = ByteArray(ySize + uSize + vSize)

            yBuffer.get(nv21, 0, ySize)
            vBuffer.get(nv21, ySize, vSize)
            uBuffer.get(nv21, ySize + vSize, uSize)

            val yuvImage = android.graphics.YuvImage(
                nv21, android.graphics.ImageFormat.NV21, image.width, image.height, null
            )

            val out = java.io.ByteArrayOutputStream()
            yuvImage.compressToJpeg(android.graphics.Rect(0, 0, image.width, image.height), 100, out)
            val imageBytes = out.toByteArray()

            val bitmap = android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)

            // Rotate the bitmap if needed based on the image rotation
            val rotatedBitmap = rotateBitmap(bitmap, rotation)

            return rotatedBitmap
        }

        private fun rotateBitmap(bitmap: Bitmap, rotation: Int): Bitmap {
            val matrix = Matrix()
            matrix.postRotate(rotation.toFloat())
            return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }
    }

    companion object {
        private const val TAG = "MainActivity"
    }

    // Updated test function
    private fun testModelWithStaticImage() {
        try {
            // Create a test image programmatically
            val testImage = createTestImageProgrammatically()

            Log.d(TAG, "Test image created: ${testImage.width}x${testImage.height}")

            // Run pose detection on the test image
            val keyPoints = poseDetector.estimatePose(testImage)

            // Log the results
            Log.d(TAG, "Test image detection complete, found ${keyPoints.size} keypoints")
            keyPoints.forEach { keyPoint ->
                Log.d(TAG, "${keyPoint.bodyPart.name}: x=${keyPoint.coordinate.x}, y=${keyPoint.coordinate.y}, confidence=${keyPoint.score}")
            }

            // Update the overlay to display the results
            poseOverlayView.updatePose(keyPoints, testImage.width, testImage.height)

            // Display a toast to indicate test is complete
            Toast.makeText(this, "Static image test complete. Check logs for details.", Toast.LENGTH_LONG).show()

        } catch (e: Exception) {
            Log.e(TAG, "Error testing model with static image: ${e.message}", e)
            Toast.makeText(this, "Test failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
