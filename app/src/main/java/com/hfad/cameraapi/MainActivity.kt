package com.hfad.cameraapi

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.ImageView
import androidx.activity.ComponentActivity

class MainActivity : ComponentActivity() {

    private lateinit var imageView: ImageView
    private lateinit var posebutton: Button
    private lateinit var repbutton: Button
    private lateinit var progressbutton: Button


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        posebutton = findViewById(R.id.poseExample)
        repbutton = findViewById(R.id.repCounter)
        progressbutton = findViewById(R.id.poseDetection)

        repbutton.setOnClickListener(){
            var intent = Intent(this, RepCounter::class.java)
            startActivity(intent)
        }

        posebutton.setOnClickListener(){
            try{
                var intent = Intent(this, PoseComparison::class.java)
                startActivity(intent)
            }
            catch (exc: Exception){
                Log.e("Main", "Failed to go to PoseComparison Screen", exc)
            }
        }

        progressbutton.setOnClickListener(){
            //Once model progress is done, add here
            //var intent = Intent(this, PoseComparison::class.java)
            //startActivity(intent)
        }


    }
}
