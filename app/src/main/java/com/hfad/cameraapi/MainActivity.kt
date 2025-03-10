package com.hfad.cameraapi

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.hfad.cameraapi.ml.PosenetMobilenetQuantized
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.tensorflow.lite.DataType
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.util.concurrent.Executors
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale

data class Keypoint(val bodyPart: BodyPart, val position: Position, val score: Float)
data class Position(val x: Float, val y: Float)

enum class BodyPart{
    NOSE, LEFT_EYE, RIGHT_EYE, LEFT_EAR, RIGHT_EAR, LEFT_SHOULDER, RIGHT_SHOULDER,
    LEFT_ELBOW, RIGHT_ELBOW, LEFT_WRIST, RIGHT_WRIST, LEFT_HIP, RIGHT_HIP,
    LEFT_KNEE, RIGHT_KNEE, LEFT_ANKLE, RIGHT_ANKLE
}

class MainActivity : ComponentActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var poseOverlayView: PoseOverlayView
    private lateinit var debugTextView: TextView //Textview for testing purposes
    private val TAG = "PoseNetActivity"

    private var posenetModel: PosenetMobilenetQuantized? = null

    //Launcher for requesting camera permission
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
           try{
               if (isGranted) {
                   startCamera()
               } else {
                   Toast.makeText(this, "Camera permission denied", Toast.LENGTH_SHORT).show()
               }
           }
            catch (exc: Exception) {
                Log.e("APP_CRASH", "Error in method X: " + exc.message, exc)
            }
        }

    //Main onCreate function for running the app
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        previewView = findViewById(R.id.previewView)
        poseOverlayView = findViewById(R.id.poseOverlay)
        debugTextView = findViewById(R.id.debugTextView) //Debug testing. Delete later

        // Set background color to check if the view is visible
        previewView.setBackgroundColor(Color.BLUE)

        try {
            // Check if we already have camera permission
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED
            ) {
                startCamera()
            } else {
                // Request camera permission
                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
        catch (exc: Exception) {
            Log.e("APP_CRASH", "Error in method X: " + exc.message, exc)
        }
    }

    private fun getOrCreateModel(): PosenetMobilenetQuantized {
        if (posenetModel == null) {
            posenetModel = PosenetMobilenetQuantized.newInstance(this)
        }
        return posenetModel!!
    }

    private fun startCamera() {
        debugTextView.text = "Starting camera..." //Debug
        //Get the camera provider
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            //Create the Preview use case
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            //Create image analysis use case
            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            //Set up the analyzer for pose detection
            imageAnalysis.setAnalyzer(ContextCompat.getMainExecutor(this), ImageAnalyzer())

            //Select the front camera
            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            try {
                //Unbind any existing use cases
                cameraProvider.unbindAll()

                //Bind the preview use case to the lifecycle with the selected camera
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalysis
                )

            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
        debugTextView.text = "Camera started"
    }

    override fun onDestroy() {
        super.onDestroy()
        posenetModel?.close()
        posenetModel = null
    }

    //ImageAnalyzer
    private inner class ImageAnalyzer : ImageAnalysis.Analyzer{
        private var frameCount = 0

        override fun analyze(imageProxy: ImageProxy) {
            frameCount++
            if (frameCount % 5 != 0) {
                imageProxy.close()
                return
            }

            // Existing code for bitmap conversion and processing
            val bitmap = imageProxyToBitmap(imageProxy)
            bitmap?.let {
                processImage(it, imageProxy.imageInfo.rotationDegrees)
            }

            imageProxy.close()
        }

        @OptIn(ExperimentalGetImage::class)
        private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
            val image = imageProxy.image ?: return null

            return try {
                val planes = image.planes

                // Y plane - brightness
                val yBuffer = planes[0].buffer
                val ySize = yBuffer.remaining()
                val yPixelStride = planes[0].pixelStride
                val yRowStride = planes[0].rowStride

                // U and V planes - color data
                val uvBuffer = planes[1].buffer
                val uvPixelStride = planes[1].pixelStride
                val uvRowStride = planes[1].rowStride

                val imageWidth = image.width
                val imageHeight = image.height

                //Create output bitmap
                val bitmap = createBitmap(imageWidth, imageHeight)

                //Should not crash, but might have color issues (shouldn't be an issue)
                val rgba = IntArray(imageWidth * imageHeight)

                for (y in 0 until imageHeight) {
                    for (x in 0 until imageWidth) {
                        val yIndex = y * yRowStride + x * yPixelStride

                        //Skip out of bounds
                        if (yIndex >= ySize) continue

                        //Get Y value
                        val yValue = yBuffer.get(yIndex).toInt() and 0xFF

                        //Simple conversion
                        rgba[y * imageWidth + x] = Color.rgb(yValue, yValue, yValue)
                    }
                }

                bitmap.setPixels(rgba, 0, imageWidth, 0, 0, imageWidth, imageHeight)

                //Resize to fit model input dimensions
                val scaledBitmap = bitmap.scale(257, 513)
                bitmap.recycle() //Free the original bitmap memory

                scaledBitmap
            } catch (e: Exception) {
                Log.e("ImageConversion", "Failed to convert image: ${e.message}", e)
                null
            }
        }

        //Resize function just in case
        private fun resizeBitmap(bitmap: Bitmap, targetHeight: Int, targetWidth: Int): Bitmap {
            val scaleWidth = targetWidth.toFloat() / bitmap.width
            val scaleHeight = targetHeight.toFloat() / bitmap.height
            val matrix = Matrix()
            matrix.postScale(scaleWidth, scaleHeight)

            return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }

        private val mlExecutor = Executors.newSingleThreadExecutor()

        private fun processImage(bitmap: Bitmap, rotation: Int) {
            mlExecutor.execute{
            try {

                Log.d(TAG, "Starting model processing")

                // Log model loading
                Log.d(TAG, "Loading model...")
                val model = getOrCreateModel()
                Log.d(TAG, "Model loaded successfully")

                // Log input preparation
                Log.d(TAG, "Preparing input buffer for bitmap: ${bitmap.width}x${bitmap.height}")
                val inputBuffer = bitmaptoByteBuffer(bitmap)
                Log.d(TAG, "Input buffer prepared, size: ${inputBuffer.capacity()}")

                // Log tensor creation
                Log.d(TAG, "Creating input tensor")
                val inputFeature0 = TensorBuffer.createFixedSize(intArrayOf(1, 513, 257, 3), DataType.UINT8)
                inputFeature0.loadBuffer(inputBuffer)
                Log.d(TAG, "Input tensor created successfully")

                // Log model processing
                Log.d(TAG, "Running model inference")
                val outputs = model.process(inputFeature0)
                Log.d(TAG, "Model inference completed successfully")

                val heatmaps = outputs.outputFeature0AsTensorBuffer
                val offsets = outputs.outputFeature1AsTensorBuffer

                val keypoints = decodeKeypoints(heatmaps, offsets)

                val keypointsForOverlay = keypoints.map {
                    Keypoint(it.bodyPart, Position(it.position.x, it.position.y), it.score)
                }

                runOnUiThread {
                    poseOverlayView.updateKeypoints(keypointsForOverlay)
                    debugTextView.text = "Detected ${keypointsForOverlay.size} keypoints"
                }

            } catch (e: Exception) {
                //Catching any errors with processing images
                Log.e(TAG, "Error processing image with PoseNet: ${e.message}", e)
                e.printStackTrace()
            }
            }
        }

        private fun bitmaptoByteBuffer(bitmap: Bitmap): ByteBuffer{
            // Make sure bitmap is exactly the right size
            val resizedBitmap = if (bitmap.width != 257 || bitmap.height != 513) {
                bitmap.scale(257, 513)
            } else {
                bitmap
            }

            // Allocate buffer - size is height * width * channels
            val byteBuffer = ByteBuffer.allocateDirect(1 * 513 * 257 * 3)
            byteBuffer.order(ByteOrder.nativeOrder())

            Log.d(TAG, "Converting ${resizedBitmap.width}x${resizedBitmap.height} bitmap to buffer")

            val intValues = IntArray(resizedBitmap.width * resizedBitmap.height)
            resizedBitmap.getPixels(intValues, 0, resizedBitmap.width, 0, 0,
                resizedBitmap.width, resizedBitmap.height)

            // Convert ARGB to RGB and add to buffer
            var pixel = 0
            for (i in 0 until 513) {
                for (j in 0 until 257) {
                    if (pixel >= intValues.size) continue

                    val value = intValues[pixel++]
                    byteBuffer.put((value shr 16 and 0xFF).toByte())  // R
                    byteBuffer.put((value shr 8 and 0xFF).toByte())   // G
                    byteBuffer.put((value and 0xFF).toByte())         // B
                }
            }

            // If we created a new bitmap, recycle it
            if (resizedBitmap != bitmap) {
                resizedBitmap.recycle()
            }

            byteBuffer.rewind()
            return byteBuffer
        }

        private fun decodeKeypoints(heatmaps: TensorBuffer, offsets: TensorBuffer): List<Keypoint> {
            //Get raw data
            val heatmapData = heatmaps.floatArray
            val offsetsData = offsets.floatArray

            //PoseNet output dimensions
            val height = 9
            val width = 9
            val numKeypoints = 17 //Standard for PoseNet

            val keypointList = mutableListOf<Keypoint>()

            //For each keypoint type
            for (keypoint in 0 until numKeypoints) {
                var maxVal = 0f
                var maxRow = 0
                var maxCol = 0

                //Find position with highest confidence
                for (row in 0 until height) {
                    for (col in 0 until width) {
                        val index = row * width * numKeypoints + col * numKeypoints + keypoint
                        if (index < heatmapData.size && heatmapData[index] > maxVal) {
                            maxVal = heatmapData[index]
                            maxRow = row
                            maxCol = col
                        }
                    }
                }

                //Skip low confidence detections
                if (maxVal < 0.3f) continue

                //Get offset values - adjust position based on offsets
                val offsetIndex = maxRow * width * numKeypoints * 2 + maxCol * numKeypoints * 2
                val offsetX = offsetsData[offsetIndex + keypoint]
                val offsetY = offsetsData[offsetIndex + keypoint + numKeypoints]

                //Calculate normalized position (0-1)
                val x = (maxCol + offsetX) / width
                val y = (maxRow + offsetY) / height

                //Create keypoint
                val bodyPart = BodyPart.entries[keypoint]
                keypointList.add(Keypoint(bodyPart, Position(x, y), maxVal))

                //Log keypoint for debugging
                Log.d(TAG, "Detected $bodyPart at ($x, $y) with confidence $maxVal")
            }

            return keypointList
        }

    }
}

