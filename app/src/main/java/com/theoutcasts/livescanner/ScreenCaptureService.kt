package com.theoutcasts.livescanner

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
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
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class ScreenCaptureService : Service() {
    companion object {
        const val ACTION_START = "com.theoutcasts.livescanner.START"
        const val ACTION_STOP = "com.theoutcasts.livescanner.STOP"
        const val ACTION_STATUS = "com.theoutcasts.livescanner.STATUS"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_STATUS = "status"
        private const val CHANNEL_ID = "outcasts_scanner"
        private const val NOTIFICATION_ID = 1001
        private const val TAG = "OutcastsScanner"
        private val TAG_PATTERN = Regex("(?i)\\b[a-z0-9]{5,6}\\b")
        private val LEVEL_PATTERN = Regex("(?i)\\blvl\\.?\\s*\\d*")
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private val processingImage = AtomicBoolean(false)
    @Volatile private var firstFrameReported = false
    @Volatile private var stopping = false
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    @Volatile private var lastReportedTag: String? = null

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
                    startAsForegroundService()
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
            Log.v(TAG, "ImageReader callback received")
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
        // Always acquire the newest frame first so old frames do not fill ImageReader's queue.
        val image = try {
            reader.acquireLatestImage()
        } catch (e: Exception) {
            Log.e(TAG, "Could not acquire screen image", e)
            null
        } ?: return

        // If ML Kit is still processing, close this frame immediately instead of leaving it queued.
        if (!processingImage.compareAndSet(false, true)) {
            image.close()
            return
        }

        val bitmap = try {
            imageToBitmap(image)
        } catch (e: Exception) {
            Log.e(TAG, "Could not convert screen image", e)
            null
        } finally {
            image.close()
        }

        if (bitmap == null) {
            processingImage.set(false)
            sendStatus("A screen frame arrived, but it could not be converted. Check the scanner log.")
            return
        }

        if (!firstFrameReported) {
            firstFrameReported = true
            Log.i(TAG, "First screen frame received; starting text recognition")
            sendStatus("First screen frame received. Reading screen text...")
        }

        try {
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { result ->
                    if (result.text.isNotBlank()) {
                        Log.d(TAG, "OCR recognised text:\\n${result.text}")
                        val detectedTag = findProfileTag(result.text)
                        if (detectedTag != null) {
                            if (detectedTag != lastReportedTag) {
                                lastReportedTag = detectedTag
                                Log.i(TAG, "Possible player tag detected: $detectedTag")
                                sendStatus("Possible player tag detected: $detectedTag")
                            }
                        } else {
                            sendStatus("Screen capture and text recognition are working. No profile tag found yet.")
                        }
                    } else {
                        sendStatus("Screen capture is working. No text detected in this frame.")
                    }
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Text recognition failed", e)
                    sendStatus("Screen capture is active, but text recognition failed: ${e.message}")
                }
                .addOnCompleteListener {
                    if (!bitmap.isRecycled) bitmap.recycle()
                    processingImage.set(false)
                }
        } catch (e: Exception) {
            Log.e(TAG, "Could not submit frame to text recognition", e)
            if (!bitmap.isRecycled) bitmap.recycle()
            processingImage.set(false)
            sendStatus("Could not start text recognition: ${e.message}")
        }
    }


    // The game can place the tag on the same OCR line as "Lvl", or OCR can
    // split that row into neighbouring lines. Search that small group of lines.
    private fun findProfileTag(ocrText: String): String? {
        val lines = ocrText.lines()
        for (index in lines.indices) {
            if (!LEVEL_PATTERN.containsMatchIn(lines[index])) continue

            val nearbyText = buildString {
                append(lines[index])
                if (index > 0) append(" ").append(lines[index - 1])
                if (index < lines.lastIndex) append(" ").append(lines[index + 1])
            }

            val candidates = TAG_PATTERN.findAll(nearbyText)
                .map { it.value }
                .filter { token ->
                    token.any { it.isLetter() } && token.any { it.isDigit() }
                }
                .map { it.lowercase() }
                .distinct()
                .toList()

            if (candidates.size == 1) return candidates.first()
        }
        return null
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
        firstFrameReported = false
        lastReportedTag = null
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
        recognizer.close()
        super.onDestroy()
    }
}
