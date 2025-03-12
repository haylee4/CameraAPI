package com.hfad.cameraapi

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PoseDetector(private val context: Context) {
    companion object {
        private const val INPUT_SIZE = 192
        private const val NUM_KEYPOINTS = 17
        private const val TAG = "PoseDetector"
    }

    // Use the Movenet class from wrapper
    private val model = Movenet.newInstance(context)
    private val inputBuffer = ByteBuffer.allocateDirect(1 * INPUT_SIZE * INPUT_SIZE * 3 * 4)

    init {
        inputBuffer.order(ByteOrder.nativeOrder())
        Log.d(TAG, "MoveNet model loaded successfully")
    }

    fun estimatePose(bitmap: Bitmap, rotation: Int = 0): List<KeyPoint> {
        val startTime = SystemClock.elapsedRealtime()

        try {
            // Log input characteristics
            Log.d(TAG, "Input bitmap: ${bitmap.width}x${bitmap.height}, rotation: $rotation")

            // Preprocess the image: resize to 192x192
            val processedBitmap = preprocessBitmap(bitmap)

            // Load bitmap data into the input buffer
            inputBuffer.rewind()
            loadPixelData(processedBitmap, inputBuffer)

            // Create a TensorBuffer from the ByteBuffer
            val inputFeature0 = TensorBuffer.createFixedSize(intArrayOf(1, INPUT_SIZE, INPUT_SIZE, 3), DataType.FLOAT32)
            inputFeature0.loadBuffer(inputBuffer)

            // Run inference using the wrapper
            val outputs = model.process(inputFeature0)
            val outputTensor = outputs.outputFeature0AsTensorBuffer

            // Process outputs
            val keyPoints = parseOutputs(outputTensor, bitmap.width, bitmap.height)

            val inferenceTime = SystemClock.elapsedRealtime() - startTime
            Log.d(TAG, "Inference time: $inferenceTime ms")

            // Log first few keypoints to verify
            keyPoints.take(5).forEach { keyPoint ->
                Log.d(
                    TAG,
                    "${keyPoint.bodyPart.name}: x=${keyPoint.coordinate.x}, y=${keyPoint.coordinate.y}, score=${keyPoint.score}"
                )
            }

            return keyPoints
        } catch (e: Exception) {
            Log.e(TAG, "Error in pose estimation: ${e.message}", e)
            return emptyList() // Return empty list on error to avoid crashing
        }
    }

    private fun preprocessBitmap(bitmap: Bitmap): Bitmap {
        // Create a bitmap scaled to the input dimensions
        val imageProcessor = ImageProcessor.Builder()
            .add(ResizeOp(INPUT_SIZE, INPUT_SIZE, ResizeOp.ResizeMethod.BILINEAR))
            .build()

        val tensorImage = TensorImage(DataType.UINT8)
        tensorImage.load(bitmap)
        val processedImage = imageProcessor.process(tensorImage)

        return processedImage.bitmap
    }

    private fun loadPixelData(bitmap: Bitmap, byteBuffer: ByteBuffer) {
        // Convert bitmap to float input tensor
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        var pixel = 0
        for (i in 0 until INPUT_SIZE) {
            for (j in 0 until INPUT_SIZE) {
                val pixelValue = pixels[pixel++]
                // Extract RGB values and normalize to [0,1]
                byteBuffer.putFloat(((pixelValue shr 16) and 0xFF) / 255.0f)
                byteBuffer.putFloat(((pixelValue shr 8) and 0xFF) / 255.0f)
                byteBuffer.putFloat((pixelValue and 0xFF) / 255.0f)
            }
        }
    }

    private fun parseOutputs(
        tensorBuffer: TensorBuffer,
        imageWidth: Int,
        imageHeight: Int
    ): List<KeyPoint> {
        val output = tensorBuffer.floatArray
        val keyPoints = mutableListOf<KeyPoint>()

        try {
            Log.d(TAG, "Output tensor shape: ${tensorBuffer.shape.contentToString()}")
            Log.d(TAG, "Output array size: ${output.size}")

            // MoveNet outputs a [1, 17, 3] tensor - check if we have the expected length
            if (output.size != NUM_KEYPOINTS * 3) {
                Log.e(TAG, "Unexpected output size: ${output.size}, expected: ${NUM_KEYPOINTS * 3}")
                return emptyList()
            }

            // Find min/max confidence to determine if we have reasonable detection
            var minConf = 1.0f
            var maxConf = 0.0f

            // First loop just to gather statistics
            for (i in 0 until NUM_KEYPOINTS) {
                val confidence = output[i * 3 + 2]
                minConf = minOf(minConf, confidence)
                maxConf = maxOf(maxConf, confidence)
            }

            Log.d(TAG, "Confidence range: $minConf to $maxConf")

            // Each keypoint has y, x, confidence
            for (i in 0 until NUM_KEYPOINTS) {
                val y = output[i * 3] * imageHeight
                val x = output[i * 3 + 1] * imageWidth
                val confidence = output[i * 3 + 2]

                val bodyPart = BodyPart.values()[i]
                keyPoints.add(KeyPoint(bodyPart, Coordinate(x, y), confidence))
            }

            // Log summary statistics
            val avgConfidence = keyPoints.map { it.score }.average()
            val highConfidence = keyPoints.count { it.score > 0.7f }

            Log.d(
                TAG,
                "Average confidence: $avgConfidence, High confidence keypoints: $highConfidence"
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error parsing outputs: ${e.message}", e)
            return emptyList()
        }

        return keyPoints
    }

    fun close() {
        try {
            model.close()
            Log.d(TAG, "MoveNet model closed")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing model: ${e.message}", e)
        }
    }
}
