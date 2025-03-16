package com.hfad.cameraapi

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat

class RepCounter : ComponentActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var textView: TextView
    private lateinit var countButton: Button
    private lateinit var backButton: Button

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
        setContentView(R.layout.repcounter)

        previewView = findViewById(R.id.previewView)

        //For Demo, will be removed when model is fully implemented
        textView = findViewById(R.id.demoText)
        countButton = findViewById(R.id.countRepButton)
        backButton = findViewById(R.id.backButton)

        backButton.setOnClickListener {
            var intent = Intent(this, MainActivity::class.java)
            startActivity(intent)
        }

        //Rep Counter for demonstration
        var count = 0
        countButton.setOnClickListener {
            count = count + 1
            textView.text = count.toString()
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