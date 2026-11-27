package com.theoutcasts.livescanner

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class ScreenCaptureService : Service() {
    companion object {
        const val ACTION_START = "com.theoutcasts.livescanner.START"
        const val ACTION_STOP = "com.theoutcasts.livescanner.STOP"
        const val ACTION_STATUS = "com.theoutcasts.livescanner.STATUS"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_STATUS = "status"
        const val EXTRA_OCR_LANGUAGE = "ocr_language"

        private const val CHANNEL_ID = "outcasts_scanner"
        private const val ALERT_CHANNEL_ID = "outcasts_scammer_alerts"
        private const val NOTIFICATION_ID = 1001
        private const val ALERT_NOTIFICATION_ID = 1002
        private const val TAG = "OutcastsScanner"
        private const val API_URL =
            "https://the-outcast-welcome-bot-production.up.railway.app/api/live-scan"

        private val TAG_PATTERN = Regex("(?i)(?<![a-z0-9])[a-z0-9]{4,6}(?![a-z0-9])")
        private val LEVEL_PATTERN = Regex("(?i)\\b(?:lvl|level)\\.?\\s*\\d+\\b")
        private const val DEFAULT_OCR_LANGUAGE = "eng"
        private val SUPPORTED_OCR_LANGUAGES = setOf(
            "eng",
            "rus",
            "ara"
        )
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private val processingImage = AtomicBoolean(false)
    @Volatile private var firstFrameReported = false
    @Volatile private var stopping = false
    private var tessBaseAPI: TessBaseAPI? = null
    private val ocrExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var lastReportedTag: String? = null
    @Volatile private var lastOcrDiagnosticAt = 0L

    private var candidateTag: String? = null
    private var candidateName: String? = null
    private var candidateCount = 0
    private val submittedTags = mutableSetOf<String>()
    private val networkExecutor = Executors.newSingleThreadExecutor()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopScanner("Scanner stopped.")
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val resultData: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }

                if (resultData == null) {
                    stopScanner("Could not start: screen capture permission data was missing.")
                    return START_NOT_STICKY
                }

                try {
                    stopping = false
                    candidateTag = null
                    candidateName = null
                    candidateCount = 0
                    lastReportedTag = null
                    lastOcrDiagnosticAt = 0L
                    submittedTags.clear()
                    startAsForegroundService()
                    val requestedLanguage = intent.getStringExtra(EXTRA_OCR_LANGUAGE)
                        ?.takeIf { it in SUPPORTED_OCR_LANGUAGES }
                        ?: DEFAULT_OCR_LANGUAGE
                    prepareTesseract(requestedLanguage)
                    startScanner(resultCode, resultData)
                } catch (e: Exception) {
                    Log.e(TAG, "Scanner startup failed", e)
                    stopScanner("Scanner failed to start: ${e.message ?: "unknown error"}")
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startAsForegroundService() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Live Tag Scanner", NotificationManager.IMPORTANCE_LOW)
            )
            manager.createNotificationChannel(
                NotificationChannel(
                    ALERT_CHANNEL_ID,
                    "Scammer match alerts",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Alerts when a scanned player tag exactly matches the scammer masterlist."
                    enableVibration(true)
                }
            )
        }

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("The Outcasts Live Scanner")
            .setContentText("Screen scanning is active")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startScanner(resultCode: Int, resultData: Intent) {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)

        val width = metrics.widthPixels
        val height = metrics.heightPixels
        if (width <= 0 || height <= 0) {
            throw IllegalStateException("Invalid screen dimensions: ${width}x${height}")
        }

        captureThread = HandlerThread("OutcastsScreenCapture").also { it.start() }
        captureHandler = Handler(captureThread!!.looper)
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)

        val projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)
            ?: throw IllegalStateException("Android did not create a screen capture session.")

        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i(TAG, "MediaProjection was stopped by Android")
                if (!stopping) stopScanner("Android stopped screen sharing.")
            }
        }, captureHandler)

        imageReader?.setOnImageAvailableListener({ reader ->
            processLatestImage(reader)
        }, captureHandler)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "OutcastsLiveScanner",
            width,
            height,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            captureHandler
        ) ?: throw IllegalStateException("Android did not create the virtual display.")

        Log.i(TAG, "Screen capture started: ${width}x${height}, density=${metrics.densityDpi}")
        sendStatus("Screen capture is active. Waiting for the first frame...")
    }

    private fun processLatestImage(reader: ImageReader) {
        val image = try {
            reader.acquireLatestImage()
        } catch (e: Exception) {
            Log.e(TAG, "Could not acquire screen image", e)
            null
        } ?: return

        if (!processingImage.compareAndSet(false, true)) {
            image.close()
            return
        }

        // The user's split-screen layout has Discord on the left and the game on the right.
        // Crop away the left pane so Discord message text cannot be mistaken for player tags.
        val bitmap = try {
            cropGamePane(imageToBitmap(image))
        } catch (e: Exception) {
            Log.e(TAG, "Could not convert/crop screen image", e)
            null
        } finally {
            image.close()
        }

        if (bitmap == null) {
            processingImage.set(false)
            sendStatus("A screen frame arrived, but it could not be converted.")
            return
        }

        if (!firstFrameReported) {
            firstFrameReported = true
            val sample = sampleBitmapPixels(bitmap)
            Log.i(
                TAG,
                "First frame bitmap=${bitmap.width}x${bitmap.height}; " +
                    "sample average brightness=${sample.first}; " +
                    "sample colour variation=${sample.second}"
            )
            sendStatus(
                "Screen frame received (${bitmap.width}x${bitmap.height}). " +
                    "Image brightness ${sample.first}/255; colour variation ${sample.second}. Testing OCR..."
            )
        }

        ocrExecutor.execute {
            var ocrBitmap: Bitmap? = null
            try {
                val api = tessBaseAPI ?: throw IllegalStateException("Tesseract is not initialised.")
                ocrBitmap = prepareOcrBitmap(bitmap)
                api.setImage(ocrBitmap)
                val recognisedText = api.getUTF8Text().orEmpty()
                Log.d(
                    TAG,
                    "OCR frame ${ocrBitmap.width}x${ocrBitmap.height}; " +
                        "recognised ${recognisedText.length} characters:\n$recognisedText"
                )

                val detected = findProfileDetails(recognisedText)
                if (detected != null) {
                    recordCandidate(detected.first, detected.second)
                } else {
                    candidateTag = null
                    candidateCount = 0
                    reportOcrNoMatch(recognisedText)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Offline text recognition failed", e)
                sendStatus("Offline text recognition failed: ${e.message ?: "unknown error"}")
            } finally {
                try {
                    tessBaseAPI?.clear()
                } catch (e: Exception) {
                    Log.w(TAG, "Could not clear the previous OCR frame", e)
                }
                if (ocrBitmap != null && !ocrBitmap.isRecycled && ocrBitmap !== bitmap) {
                    ocrBitmap.recycle()
                }
                if (!bitmap.isRecycled) bitmap.recycle()
                processingImage.set(false)
            }
        }
    }

    @Synchronized
    private fun recordCandidate(tag: String, playerName: String?) {
        if (candidateTag == tag) {
            candidateCount++
            if (!playerName.isNullOrBlank()) candidateName = playerName
        } else {
            candidateTag = tag
            candidateName = playerName
            candidateCount = 1
        }

        Log.d(TAG, "Candidate $tag seen $candidateCount time(s)")
        if (candidateCount < 3) {
            sendStatus("Possible player tag: $tag (${candidateCount}/3 confirmations). Keep the profile open.")
        }
        if (candidateCount >= 3 && tag != lastReportedTag) {
            lastReportedTag = tag
            Log.i(TAG, "Stable player tag detected: $tag")
            sendStatus("Player tag detected: $tag\nChecking The Outcasts masterlist...")
            val displayName = candidateName?.takeIf { it.isNotBlank() }
            submitTagToApi(tag, displayName)
        }
    }

    private fun reportOcrNoMatch(recognisedText: String) {
        // Avoid flooding the UI: report OCR diagnostics at most once every 3 seconds.
        val now = SystemClock.elapsedRealtime()
        if (now - lastOcrDiagnosticAt < 3000L) return
        lastOcrDiagnosticAt = now

        if (recognisedText.isBlank()) {
            sendStatus(
                "Screen frames are arriving, but OCR cannot read text yet. " +
                    "Keep a player profile open and check the selected OCR language."
            )
        } else {
            // Show a short sample of the actual OCR result in the app so we can
            // diagnose what Tesseract is reading without needing Logcat.
            val preview = recognisedText
                .replace(Regex("\\s+"), " ")
                .trim()
                .take(350)

            val diagnostic = if (preview.isBlank()) {
                "No readable text was returned by OCR."
            } else {
                "OCR text: $preview"
            }

            sendStatus(
                "OCR is reading the game, but no valid 4–6 character tag was found " +
                    "beside a player level. Keep the profile open.\n$diagnostic"
            )
        }
    }

    private fun submitTagToApi(tag: String, playerName: String?) {
        synchronized(submittedTags) {
            if (!submittedTags.add(tag)) return
        }

        val apiKey = getSharedPreferences(
            "outcasts_scanner_settings",
            Context.MODE_PRIVATE
        ).getString("live_scanner_api_key", "").orEmpty()

        if (apiKey.isBlank()) {
            sendStatus("Tag $tag detected, but the Railway API key is missing. Save it in the app.")
            return
        }

        networkExecutor.execute {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(API_URL).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 12000
                    readTimeout = 12000
                    doOutput = true
                    setRequestProperty("Authorization", "Bearer $apiKey")
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Accept", "application/json")
                }

                val body = JSONObject()
                    .put("tag", tag)
                    .put("playerName", playerName ?: "")
                    .put("deviceName", "${Build.MANUFACTURER} ${Build.MODEL}".take(60))
                    .toString()

                connection.outputStream.use { output ->
                    output.write(body.toByteArray(Charsets.UTF_8))
                }

                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val responseText = stream?.bufferedReader()?.use { it.readText() }.orEmpty()

                if (code !in 200..299) {
                    Log.e(TAG, "Live scan API returned HTTP $code: $responseText")
                    sendStatus("Tag $tag detected, but Railway returned HTTP $code. Check the API key and bot logs.")
                    return@execute
                }

                val response = JSONObject(responseText)
                val status = response.optString("status", "UNKNOWN")
                val discordSent = response.optBoolean("discordAlertSent", false)

                if (status == "SCAMMER_MATCH") {
                    showScammerNotification(tag, playerName)
                    val suffix = if (discordSent) "Discord alert sent." else "Phone alert shown; Discord alert may not have sent."
                    sendStatus("🚨 SCAMMER MATCH: $tag\n$suffix Verify the visible tag before taking action.")
                } else {
                    sendStatus("Player: ${playerName ?: "Name not detected"}\\nTag $tag checked: $status. No scammer-masterlist match.")
                }
                Log.i(TAG, "API result for $tag: $status; discordAlertSent=$discordSent")
            } catch (e: Exception) {
                Log.e(TAG, "Could not contact live scan API for $tag", e)
                sendStatus("Tag $tag detected, but the Railway check failed: ${e.message ?: "network error"}")
            } finally {
                connection?.disconnect()
            }
        }
    }

    private fun showScammerNotification(tag: String, playerName: String?) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("🚨 SCAMMER TAG MATCH")
            .setContentText("${playerName ?: "Player name unknown"} — $tag matches the masterlist.")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "Player: ${playerName ?: "Name not detected"}\\nTag: $tag exactly matches The Outcasts scammer masterlist. Verify the visible tag before taking any moderation action."
                )
            )
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setAutoCancel(true)
            .build()

        try {
            manager.notify(ALERT_NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Log.e(TAG, "Notification permission is not available", e)
        }
    }

    private fun prepareTesseract(language: String) {
        val root = File(filesDir, "tesseract")
        val tessdata = File(root, "tessdata")
        if (!tessdata.exists() && !tessdata.mkdirs()) {
            throw IllegalStateException("Could not create the OCR data directory.")
        }

        // Copy packaged models once. Tesseract loads only the selected model below.
        copyAssetFolder("tessdata", tessdata)

        val modelRelativePath = "$language.traineddata"
        val modelFile = File(tessdata, modelRelativePath)
        if (!modelFile.isFile || modelFile.length() == 0L) {
            throw IllegalStateException(
                "Missing OCR model: assets/tessdata/$modelRelativePath. " +
                    "Add the selected .traineddata file to the app assets."
            )
        }

        val api = TessBaseAPI()
        val initialized = try {
            api.init(root.absolutePath, language)
        } catch (e: Exception) {
            api.recycle()
            throw IllegalStateException("Could not initialise the $language OCR model: ${e.message}", e)
        }

        if (!initialized) {
            api.recycle()
            throw IllegalStateException(
                "Tesseract could not load '$language'. Check that its traineddata file is valid."
            )
        }

        api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
        tessBaseAPI = api
        sendStatus("OCR ready: $language. Only this language model is loaded.")
    }

    private fun copyAssetFolder(assetPath: String, destination: File) {
        val entries = assets.list(assetPath) ?: emptyArray()
        for (entry in entries) {
            val childAssetPath = "$assetPath/$entry"
            val childDestination = File(destination, entry)
            val children = assets.list(childAssetPath)
            if (!children.isNullOrEmpty()) {
                if (!childDestination.exists()) childDestination.mkdirs()
                copyAssetFolder(childAssetPath, childDestination)
            } else {
                if (!childDestination.exists() || childDestination.length() == 0L) {
                    assets.open(childAssetPath).use { input ->
                        childDestination.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }
        }
    }

    /**
     * Keep the full captured screen because the player profile is centred.
     */
    private fun cropGamePane(source: Bitmap): Bitmap = source

    /**
     * Enlarges and enhances the captured game pane for small-text recognition.
     * The original captured bitmap is not modified.
     */
    private fun prepareOcrBitmap(source: Bitmap): Bitmap {
        val scaledWidth = (source.width * 2).coerceAtLeast(1)
        val scaledHeight = (source.height * 2).coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(source, scaledWidth, scaledHeight, true)
        val result = scaled.copy(Bitmap.Config.ARGB_8888, true)
        if (scaled !== source && !scaled.isRecycled) scaled.recycle()

        val width = result.width
        val height = result.height
        val pixels = IntArray(width * height)
        result.getPixels(pixels, 0, width, 0, 0, width, height)

        for (i in pixels.indices) {
            val pixel = pixels[i]
            val grey = (
                0.299 * Color.red(pixel) +
                0.587 * Color.green(pixel) +
                0.114 * Color.blue(pixel)
            ).toInt()
            val enhanced = (((grey - 128) * 1.6) + 128).toInt().coerceIn(0, 255)
            pixels[i] = Color.rgb(enhanced, enhanced, enhanced)
        }

        result.setPixels(pixels, 0, width, 0, 0, width, height)
        return result
    }

    private fun findProfileDetails(ocrText: String): Pair<String, String?>? {
        val lines = ocrText.lines().map { it.trim() }.filter { it.isNotBlank() }
        if (lines.isEmpty()) return null

        // OCR may put the level and tag on separate lines or combine them.
        // Search a small neighbourhood around each 4-6 character candidate.
        for (index in lines.indices) {
            val candidates = TAG_PATTERN.findAll(lines[index])
                .map { it.value.lowercase(Locale.ROOT) }
                .distinct()
                .toList()

            if (candidates.size != 1) continue

            val contextStart = maxOf(0, index - 2)
            val contextEnd = minOf(lines.lastIndex, index + 2)
            val nearbyText = lines.subList(contextStart, contextEnd + 1).joinToString(" ")
            val hasLevelContext = LEVEL_PATTERN.containsMatchIn(nearbyText)
            if (!hasLevelContext) continue

            val tag = candidates.first()
            val name = (contextStart..index)
                .map { lines[it] }
                .firstOrNull { line ->
                    !LEVEL_PATTERN.containsMatchIn(line) &&
                        TAG_PATTERN.findAll(line).none() &&
                        line.any { it.isLetter() } &&
                        line.length <= 100
                }
                ?.take(100)

            Log.i(TAG, "Profile candidate accepted by OCR parser: tag=$tag; context=$nearbyText")
            return tag to name
        }

        Log.d(TAG, "No profile tag candidate found. OCR lines: ${lines.take(18).joinToString(" | ")}")
        return null
    }

    /**
     * Samples a small grid across the bitmap to detect blank/black capture frames.
     * Returns average greyscale brightness and brightness range.
     */
    private fun sampleBitmapPixels(bitmap: Bitmap): Pair<Int, Int> {
        val sampleCount = 12
        var total = 0L
        var minimum = 255
        var maximum = 0
        for (row in 0 until sampleCount) {
            for (column in 0 until sampleCount) {
                val x = (column * (bitmap.width - 1) / (sampleCount - 1)).coerceIn(0, bitmap.width - 1)
                val y = (row * (bitmap.height - 1) / (sampleCount - 1)).coerceIn(0, bitmap.height - 1)
                val pixel = bitmap.getPixel(x, y)
                val grey = (
                    0.299 * Color.red(pixel) +
                    0.587 * Color.green(pixel) +
                    0.114 * Color.blue(pixel)
                ).toInt().coerceIn(0, 255)
                total += grey
                minimum = minOf(minimum, grey)
                maximum = maxOf(maximum, grey)
            }
        }
        val average = (total / (sampleCount * sampleCount)).toInt()
        return average to (maximum - minimum)
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes.firstOrNull()
            ?: throw IllegalStateException("Screen image contained no pixel plane.")
        val buffer: ByteBuffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val width = image.width
        val height = image.height
        if (pixelStride <= 0 || rowStride <= 0 || width <= 0 || height <= 0) {
            throw IllegalStateException("Invalid image buffer dimensions.")
        }

        val rowPadding = rowStride - pixelStride * width
        val paddedWidth = width + (rowPadding / pixelStride)
        val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
        buffer.rewind()
        padded.copyPixelsFromBuffer(buffer)
        val cropped = Bitmap.createBitmap(padded, 0, 0, width, height)
        padded.recycle()
        return cropped
    }

    private fun sendStatus(message: String) {
        sendBroadcast(
            Intent(ACTION_STATUS)
                .setPackage(packageName)
                .putExtra(EXTRA_STATUS, message)
        )
    }

    private fun stopScanner(message: String) {
        if (stopping) return
        stopping = true
        sendStatus(message)

        imageReader?.setOnImageAvailableListener(null, null)
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null

        val projection = mediaProjection
        mediaProjection = null
        if (projection != null) {
            try {
                projection.stop()
            } catch (e: Exception) {
                Log.w(TAG, "Error while stopping MediaProjection", e)
            }
        }

        captureThread?.quitSafely()
        captureThread = null
        captureHandler = null
        processingImage.set(false)
        try {
            tessBaseAPI?.recycle()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing Tesseract during stop", e)
        }
        tessBaseAPI = null
        firstFrameReported = false
        candidateTag = null
        candidateCount = 0
        lastReportedTag = null
        lastOcrDiagnosticAt = 0L
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopping = true
        imageReader?.setOnImageAvailableListener(null, null)
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null

        val projection = mediaProjection
        mediaProjection = null
        try {
            projection?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error while destroying MediaProjection", e)
        }

        captureThread?.quitSafely()
        captureThread = null
        captureHandler = null
        try {
            tessBaseAPI?.recycle()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing Tesseract", e)
        }
        tessBaseAPI = null
        ocrExecutor.shutdown()
        networkExecutor.shutdown()
        super.onDestroy()
    }
}
