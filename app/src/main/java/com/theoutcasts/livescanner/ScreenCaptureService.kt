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
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var processingImage = false
    private var firstFrameReported = false
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

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
                    startAsForegroundService()
                    startScanner(resultCode, resultData)
                } catch (e: Exception) {
                    Log.e(TAG, "Scanner startup failed", e)
                    stopScanner("Scanner failed to start: ${e.message}")
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
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startScanner(resultCode: Int, resultData: Intent) {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        if (width <= 0 || height <= 0) throw IllegalStateException("Invalid screen dimensions.")

        captureThread = HandlerThread("OutcastsScreenCapture").also { it.start() }
        captureHandler = Handler(captureThread!!.looper)
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)

        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = manager.getMediaProjection(resultCode, resultData)
            ?: throw IllegalStateException("Android did not create a screen capture session.")

        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopScanner("Android stopped screen sharing.")
            }
        }, captureHandler)

        imageReader?.setOnImageAvailableListener({ reader -> processLatestImage(reader) }, captureHandler)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "OutcastsLiveScanner", width, height, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, captureHandler
        ) ?: throw IllegalStateException("Android did not create the virtual display.")

        sendStatus("Screen capture is active. Waiting for the first frame...")
        Log.i(TAG, "Screen capture started: ${width}x${height}")
    }

    private fun processLatestImage(reader: ImageReader) {
        if (processingImage) return
        val image = try { reader.acquireLatestImage() } catch (e: Exception) {
            Log.e(TAG, "Could not acquire screen image", e)
            null
        } ?: return

        processingImage = true
        val bitmap = try { imageToBitmap(image) } catch (e: Exception) {
            Log.e(TAG, "Could not convert screen image", e)
            null
        } finally {
            image.close()
        }

        if (bitmap == null) {
            processingImage = false
            sendStatus("Screen capture is active, but the image could not be read.")
            return
        }

        if (!firstFrameReported) {
            firstFrameReported = true
            sendStatus("Screen capture is working. Reading screen text...")
            Log.i(TAG, "First screen frame received")
        }

        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result ->
                if (result.text.isNotBlank()) {
                    Log.d(TAG, "OCR recognised text (${result.text.length} characters)")
                    sendStatus("Screen capture and text recognition are working.")
                } else {
                    sendStatus("Screen capture is working. No text detected in this frame.")
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Text recognition failed", e)
                sendStatus("Screen capture is active, but text recognition failed.")
            }
            .addOnCompleteListener {
                bitmap.recycle()
                processingImage = false
            }
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val buffer: ByteBuffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val width = image.width
        val height = image.height
        val paddedWidth = width + (rowStride - pixelStride * width) / pixelStride
        val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
        buffer.rewind()
        padded.copyPixelsFromBuffer(buffer)
        val cropped = Bitmap.createBitmap(padded, 0, 0, width, height)
        padded.recycle()
        return cropped
    }

    private fun sendStatus(message: String) {
        sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName).putExtra(EXTRA_STATUS, message))
    }

    private fun stopScanner(message: String) {
        sendStatus(message)
        imageReader?.setOnImageAvailableListener(null, null)
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        val projection = mediaProjection
        mediaProjection = null
        projection?.stop()
        captureThread?.quitSafely()
        captureThread = null
        captureHandler = null
        processingImage = false
        firstFrameReported = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        imageReader?.setOnImageAvailableListener(null, null)
        virtualDisplay?.release()
        imageReader?.close()
        val projection = mediaProjection
        mediaProjection = null
        projection?.stop()
        captureThread?.quitSafely()
        recognizer.close()
        super.onDestroy()
    }
}
