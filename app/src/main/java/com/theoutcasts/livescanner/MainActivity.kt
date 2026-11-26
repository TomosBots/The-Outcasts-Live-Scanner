package com.theoutcasts.livescanner

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
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
            if (!granted) Toast.makeText(
                this,
                "Phone scammer alerts may be blocked. Enable notifications in Android settings.",
                Toast.LENGTH_LONG
            ).show()
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
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(serviceIntent)
                    else startService(serviceIntent)
                    statusText.text = "Starting screen capture…"
                } catch (e: Exception) {
                    statusText.text = "Could not start scanner: ${e.message ?: "unknown error"}"
                }
            } else {
                statusText.text = "Screen-capture permission was not granted."
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(17, 15, 29)
        window.navigationBarColor = Color.rgb(17, 15, 29)
        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(26), dp(22), dp(28))
            background = solid(Color.rgb(17, 15, 29))
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            background = solid(Color.rgb(17, 15, 29))
            addView(page)
        }

        val brand = TextView(this).apply {
            text = "THE OUTCASTS"
            textSize = 13f
            letterSpacing = 0.24f
            setTextColor(Color.rgb(207, 178, 255))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        val heading = TextView(this).apply {
            text = "LIVE TAG SCANNER"
            textSize = 29f
            letterSpacing = 0.02f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, dp(5), 0, dp(5))
        }
        val subtitle = TextView(this).apply {
            text = "PLAYER CHECK  •  MASTERLIST ALERTS"
            textSize = 11f
            letterSpacing = 0.12f
            setTextColor(Color.rgb(169, 160, 190))
            gravity = Gravity.CENTER
        }
        val emblem = TextView(this).apply {
            text = "⚔"
            textSize = 34f
            setTextColor(Color.rgb(244, 199, 107))
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(12))
        }
        page.addView(brand, match())
        page.addView(heading, match())
        page.addView(subtitle, match())
        page.addView(emblem, match())

        val keyCard = card()
        keyCard.addView(sectionLabel("RAILWAY CONNECTION"))
        keyCard.addView(bodyText(
            "Enter the private API key from your Railway Variables. It is stored in this app's private storage.",
            13f
        ))
        apiKeyInput = EditText(this).apply {
            hint = "LIVE_SCANNER_API_KEY"
            setHintTextColor(Color.rgb(137, 130, 154))
            setTextColor(Color.WHITE)
            textSize = 15f
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setPadding(dp(14), dp(13), dp(14), dp(13))
            background = rounded(Color.rgb(35, 31, 51), Color.rgb(91, 76, 125), dp(12).toFloat())
            setText(preferences.getString("live_scanner_api_key", "") ?: "")
        }
        keyCard.addView(apiKeyInput, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(52)
        ).apply { topMargin = dp(16) })

        val saveButton = actionButton("SAVE API KEY", Color.rgb(91, 65, 145), Color.WHITE)
        saveButton.setOnClickListener {
            val key = apiKeyInput.text?.toString()?.trim().orEmpty()
            if (key.isBlank()) {
                Toast.makeText(this, "Please enter your Railway API key.", Toast.LENGTH_LONG).show()
            } else {
                preferences.edit().putString("live_scanner_api_key", key).apply()
                (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(apiKeyInput.windowToken, 0)
                Toast.makeText(this, "API key saved on this device.", Toast.LENGTH_SHORT).show()
                statusText.text = "API key saved. Ready to scan."
            }
        }
        keyCard.addView(saveButton, buttonParams())
        page.addView(keyCard, sectionParams())

        val statusCard = card()
        statusCard.addView(sectionLabel("SCANNER STATUS"))
        statusText = TextView(this).apply {
            text = "Scanner is stopped. Save your API key, then start a scan."
            textSize = 14f
            setTextColor(Color.rgb(232, 226, 245))
            gravity = Gravity.CENTER_VERTICAL
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = rounded(Color.rgb(35, 31, 51), Color.rgb(69, 61, 91), dp(12).toFloat())
        }
        statusCard.addView(statusText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) })
        page.addView(statusCard, sectionParams())

        val startButton = actionButton("▶   START LIVE SCANNER", Color.rgb(126, 86, 196), Color.WHITE)
        startButton.setOnClickListener {
            val savedKey = preferences.getString("live_scanner_api_key", "").orEmpty()
            if (savedKey.isBlank()) {
                statusText.text = "Add and save your Railway API key before starting."
                return@setOnClickListener
            }
            requestNotificationPermissionIfNeeded()
            statusText.text = "Waiting for screen-capture permission…"
            screenCaptureRequest.launch(projectionManager.createScreenCaptureIntent())
        }
        page.addView(startButton, sectionParams())

        val stopButton = actionButton("■   STOP SCANNER", Color.rgb(48, 43, 62), Color.rgb(235, 225, 245))
        stopButton.setOnClickListener {
            val stopIntent = Intent(this@MainActivity, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_STOP
            }
            startService(stopIntent)
            statusText.text = "Stopping scanner…"
        }
        page.addView(stopButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(54)
        ).apply { topMargin = dp(10) })

        val footer = TextView(this).apply {
            text = "THE OUTCASTS  •  LIVE PLAYER TAG CHECKS"
            textSize = 10f
            letterSpacing = 0.08f
            setTextColor(Color.rgb(119, 111, 137))
            gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, dp(4))
        }
        page.addView(footer, match())
        setContentView(scroll)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) notificationPermissionRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(17), dp(16), dp(17))
        background = rounded(Color.rgb(28, 25, 42), Color.rgb(57, 49, 76), dp(18).toFloat())
    }

    private fun sectionLabel(value: String) = TextView(this).apply {
        text = value
        textSize = 12f
        letterSpacing = 0.13f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.rgb(244, 199, 107))
    }

    private fun bodyText(value: String, size: Float) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(Color.rgb(174, 166, 192))
        setPadding(0, dp(9), 0, 0)
        setLineSpacing(dp(2).toFloat(), 1f)
    }

    private fun actionButton(label: String, color: Int, textColor: Int) = Button(this).apply {
        text = label
        textSize = 14f
        letterSpacing = 0.04f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(textColor)
        isAllCaps = false
        background = rounded(color, color, dp(14).toFloat())
        stateListAnimator = null
        minHeight = dp(54)
        setPadding(dp(12), dp(10), dp(12), dp(10))
    }

    private fun buttonParams() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, dp(52)
    ).apply { topMargin = dp(14) }

    private fun sectionParams() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(16) }

    private fun match() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    )

    private fun rounded(fill: Int, stroke: Int, radius: Float) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = radius
        setStroke(dp(1), stroke)
    }

    private fun solid(color: Int) = GradientDrawable().apply { setColor(color) }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

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
