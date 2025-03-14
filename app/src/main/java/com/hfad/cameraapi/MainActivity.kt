package com.hfad.cameraapi

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.widget.ImageView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.hfad.cameraapi.ml.Movenet
import org.tensorflow.lite.DataType
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer

class MainActivity : AppCompatActivity() {

    //Tag for Debugging
    private val TAG = "MainActivity"

    //Variables
    private lateinit var textureView: TextureView
    private lateinit var cameraManager: CameraManager
    private var cameraDevice: CameraDevice? = null
    private lateinit var handler: Handler
    private lateinit var handlerThread: HandlerThread
    private lateinit var imageView: ImageView
    private lateinit var bitmap: Bitmap
    private lateinit var model: Movenet
    private lateinit var imageProcessor: ImageProcessor

    //Visualizing the keypoints
    val paint = Paint()


    //Main onCreate function for Camera API
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        get_permissions()

        //Connect vars
        model = Movenet.newInstance(this)
        imageProcessor = ImageProcessor.Builder().add(ResizeOp(192, 192, ResizeOp.ResizeMethod.BILINEAR)).build()
        textureView = findViewById(R.id.textureView)
        imageView = findViewById(R.id.imageView)
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        handlerThread = HandlerThread("videoThread")
        handlerThread.start()
        handler = Handler(handlerThread.looper)

        //Visualizer
        paint.setColor(Color.GREEN)

        //Surface Texture Listener
        textureView.surfaceTextureListener = object: TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                try {
                    open_camera()
                } catch (e: Exception) {
                    Log.e(TAG, "Error in onSurfaceTextureAvailable: ${e.message}")
                }
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                //Handle texture size change (most likely not needed)
                Log.d(TAG, "Surface texture size changed to $width x $height")
            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                // Return true if the host should release the SurfaceTexture
                releaseCamera()
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                bitmap = textureView.bitmap!!
                var tensorImage = TensorImage(DataType.FLOAT32)
                tensorImage.load(bitmap)
                tensorImage = imageProcessor.process(tensorImage)

                //Creates inputs for reference.
                val inputFeature0 = TensorBuffer.createFixedSize(intArrayOf(1, 192, 192, 3), DataType.FLOAT32)
                inputFeature0.loadBuffer(tensorImage.buffer)

                //Runs model inference and gets result.
                val outputs = model.process(inputFeature0)
                val outputFeature0 = outputs.outputFeature0AsTensorBuffer.floatArray

                //Visualizer
                var mutable = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                var canvas = Canvas()
                var height = bitmap.height
                var width = bitmap.width
                var x = 0

                Log.d("output___",outputFeature0.size.toString())
                while(x <= 49){
                    if(outputFeature0[x+2] > 0.45){
                        canvas.drawCircle(outputFeature0[x+1] *width, outputFeature0[x] *height, 10f, paint)
                    }
                    x+=3
                }

                imageView.setImageBitmap(mutable)
            }
        }
    }

    private fun getFrontCameraId(): String? {
        val cameraIds = cameraManager.cameraIdList

        // Loop through all available cameras
        for (id in cameraIds) {
            val characteristics = cameraManager.getCameraCharacteristics(id)

            // Check if this camera is front-facing
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
            if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
                return id
            }
        }

        // No front camera found
        Log.w(TAG, "No front camera found, will use default camera")
        return null
    }

    private fun releaseCamera() {
        cameraDevice?.close()
        cameraDevice = null
    }

    private fun get_permissions() {
        if(checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.CAMERA), 101)
        }
    }

    @SuppressLint("MissingPermission")
    private fun open_camera() {
        try {
            if (cameraDevice != null) {
                // Camera already open, avoid reopening
                Log.d(TAG, "Camera already open, skipping")
                return
            }

            //Get front camera ID
            val frontCameraId = getFrontCameraId()

            //Use front camera if available, otherwise fall back to the first camera
            val cameraId = frontCameraId ?: cameraManager.cameraIdList[0]

            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    val captureRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                    val surface = Surface(textureView.surfaceTexture)
                    captureRequest.addTarget(surface)

                    camera.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            session.setRepeatingRequest(captureRequest.build(), null, null)
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            Log.e(TAG, "Failed to configure camera session")
                        }
                    }, handler)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    Log.d(TAG, "Camera disconnected")
                    camera.close()
                    cameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "Camera error: $error")
                    camera.close()
                    cameraDevice = null
                }
            }, handler)
        } catch (e: Exception) {
            Log.e(TAG, "Error opening camera: ${e.message}")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isNotEmpty() && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
            get_permissions()
        }
    }

    override fun onPause() {
        super.onPause()
        releaseCamera()
    }

    override fun onDestroy() {
        super.onDestroy()
        handlerThread.quitSafely()
        model.close()
    }
}