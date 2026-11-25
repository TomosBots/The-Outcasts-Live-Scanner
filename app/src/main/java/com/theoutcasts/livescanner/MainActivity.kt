package com.theoutcasts.livescanner

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.inputmethod.InputMethodManager
import android.content.Context.INPUT_METHOD_SERVICE
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private lateinit var statusText: TextView
    private lateinit var apiKeyInput: EditText
    private lateinit var projectionManager: MediaProjectionManager
    private var receiverRegistered = false

    private val preferences by lazy {
        getSharedPreferences("outcasts_scanner_settings", Context.MODE_PRIVATE)
    }

    private val notificationPermissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Toast.makeText(
                    this,
                    "Phone scammer alerts may be blocked. Enable notifications in Android settings.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ScreenCaptureService.ACTION_STATUS) {
                val message = intent.getStringExtra(ScreenCaptureService.EXTRA_STATUS)
                if (!message.isNullOrBlank()) statusText.text = message
            }
        }
    }

    private val screenCaptureRequest =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                    action = ScreenCaptureService.ACTION_START
                    putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, result.data)
                }
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(serviceIntent)
                    } else {
                        startService(serviceIntent)
                    }
                    statusText.text = "Permission granted. Starting screen capture..."
                } catch (e: Exception) {
                    statusText.text = "Could not start scanner: ${e.message}"
                }
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
            setPadding(32, 36, 32, 36)
        }

        val heading = TextView(this).apply {
            text = "THE OUTCASTS\nLIVE TAG SCANNER"
            textSize = 25f
            gravity = Gravity.CENTER
        }

        val keyHeading = TextView(this).apply {
            text = "RAILWAY API KEY"
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 28, 0, 8)
        }

        apiKeyInput = EditText(this).apply {
            hint = "Paste LIVE_SCANNER_API_KEY here"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
            setText(preferences.getString("live_scanner_api_key", "") ?: "")
        }

        val saveKeyButton = Button(this).apply {
            text = "SAVE API KEY"
            setOnClickListener {
                val key = apiKeyInput.text?.toString()?.trim().orEmpty()
                if (key.isBlank()) {
                    Toast.makeText(
                        this@MainActivity,
                        "Please enter your Railway LIVE_SCANNER_API_KEY.",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    preferences.edit().putString("live_scanner_api_key", key).apply()
                    (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                        .hideSoftInputFromWindow(apiKeyInput.windowToken, 0)
                    Toast.makeText(
                        this@MainActivity,
                        "API key saved privately on this device.",
                        Toast.LENGTH_SHORT
                    ).show()
                    statusText.text = "API key saved. Ready to scan."
                }
            }
        }

        statusText = TextView(this).apply {
            text = "Scanner is currently stopped."
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 24)
        }

        val startButton = Button(this).apply {
            text = "START LIVE SCANNER"
            setOnClickListener {
                val savedKey = preferences.getString("live_scanner_api_key", "").orEmpty()
                if (savedKey.isBlank()) {
                    statusText.text = "Enter and save your Railway API key first."
                    return@setOnClickListener
                }
                requestNotificationPermissionIfNeeded()
                statusText.text = "Waiting for screen capture permission..."
                screenCaptureRequest.launch(projectionManager.createScreenCaptureIntent())
            }
        }

        val stopButton = Button(this).apply {
            text = "STOP SCANNER"
            setOnClickListener {
                val stopIntent = Intent(this@MainActivity, ScreenCaptureService::class.java).apply {
                    action = ScreenCaptureService.ACTION_STOP
                }
                startService(stopIntent)
                statusText.text = "Stopping scanner..."
            }
        }

        layout.addView(heading)
        layout.addView(keyHeading)
        layout.addView(apiKeyInput)
        layout.addView(saveKeyButton)
        layout.addView(statusText)
        layout.addView(startButton)
        layout.addView(stopButton)
        setContentView(layout)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onStart() {
        super.onStart()
        if (!receiverRegistered) {
            val filter = IntentFilter(ScreenCaptureService.ACTION_STATUS)
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(statusReceiver, filter)
            }
            receiverRegistered = true
        }
    }

    override fun onStop() {
        if (receiverRegistered) {
            unregisterReceiver(statusReceiver)
            receiverRegistered = false
        }
        super.onStop()
    }
}
