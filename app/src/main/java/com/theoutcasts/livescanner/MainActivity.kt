
package com.theoutcasts.livescanner

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.view.Gravity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var projectionManager: MediaProjectionManager

    private val screenCaptureRequest =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->

            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                    action = ScreenCaptureService.ACTION_START
                    putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, result.data)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }

                statusText.text = "Scanner starting..."
            } else {
                statusText.text = "Screen capture permission was not granted."
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(32, 48, 32, 48)
        }

        val heading = TextView(this).apply {
            text = "THE OUTCASTS\nLIVE TAG SCANNER"
            textSize = 25f
            gravity = Gravity.CENTER
        }

        statusText = TextView(this).apply {
            text = "Scanner is currently stopped."
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 32, 0, 32)
        }

        val startButton = Button(this).apply {
            text = "START LIVE SCANNER"
            setOnClickListener {
                statusText.text = "Waiting for screen capture permission..."
                screenCaptureRequest.launch(projectionManager.createScreenCaptureIntent())
            }
        }

        val stopButton = Button(this).apply {
            text = "STOP SCANNER"
            setOnClickListener {
                val stopIntent = Intent(
                    this@MainActivity,
                    ScreenCaptureService::class.java
                ).apply {
                    action = ScreenCaptureService.ACTION_STOP
                }
                startService(stopIntent)
                statusText.text = "Scanner stopped."
            }
        }

        layout.addView(heading)
        layout.addView(statusText)
        layout.addView(startButton)
        layout.addView(stopButton)

        setContentView(layout)
    }
}
