package com.hfad.cameraapi

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.android.material.slider.Slider

class PoseComparison : ComponentActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var textView: TextView
    private lateinit var slider: Slider

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
        setContentView(R.layout.posecomparison)

        previewView = findViewById(R.id.previewView)

        //For Demo, will be removed when model is fully implemented
        textView = findViewById(R.id.demoText)
        slider = findViewById(R.id.debugSlider)

        textView.text = slider.value.toInt().toString()

        slider.addOnChangeListener {slider, value, fromUser ->
            textView.text = slider.value.toInt().toString() + "%"
        }


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

    private fun startCamera() {
        // Get the camera provider
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // Create the Preview use case
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            // Select the back camera (change to DEFAULT_FRONT_CAMERA if needed)
            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            try {
                // Unbind any existing use cases
                cameraProvider.unbindAll()

                // Bind the preview use case to the lifecycle with the selected camera
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview
                )

            } catch (exc: Exception) {
                Log.e("CameraXExample", "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }
}
