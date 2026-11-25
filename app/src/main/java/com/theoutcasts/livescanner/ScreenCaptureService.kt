
package com.theoutcasts.livescanner

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
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
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        private const val CHANNEL_ID = "outcasts_scanner"
        private const val NOTIFICATION_ID = 1001
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null

    private val recognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private var processingImage = false
    private var lastDetectedText = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        when (intent?.action) {
            ACTION_STOP -> {
                stopScanner()
                return START_NOT_STICKY
            }

            ACTION_START -> {
                val resultCode = intent.getIntExtra(
                    EXTRA_RESULT_CODE,
                    0
                )

                val resultData = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(
                        EXTRA_RESULT_DATA,
                        Intent::class.java
                    )
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }

                if (resultData == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }

                startForeground(
                    NOTIFICATION_ID,
                    createNotification()
                )

                startScanner(resultCode, resultData)
            }
        }

        return START_NOT_STICKY
    }

    private fun createNotification(): Notification {
        val manager =
            getSystemService(Context.NOTIFICATION_SERVICE)
                as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Live Tag Scanner",
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("The Outcasts Live Scanner")
            .setContentText("Screen scanning is active")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setOngoing(true)
            .build()
    }

    private fun startScanner(
        resultCode: Int,
        resultData: Intent
    ) {
        try {
            val metrics = DisplayMetrics()

            @Suppress("DEPRECATION")
            val windowManager =
                getSystemService(Context.WINDOW_SERVICE) as WindowManager

            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)

            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val density = metrics.densityDpi

            captureThread =
                HandlerThread("OutcastsScreenCapture").also {
                    it.start()
                }

            captureHandler = Handler(captureThread!!.looper)

            imageReader = ImageReader.newInstance(
                width,
                height,
                PixelFormat.RGBA_8888,
                2
            )

            val manager =
                getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                    as MediaProjectionManager

            mediaProjection =
                manager.getMediaProjection(resultCode, resultData)

            mediaProjection?.registerCallback(
                object : MediaProjection.Callback() {
                    override fun onStop() {
                        stopScanner()
                    }
                },
                captureHandler
            )

            imageReader?.setOnImageAvailableListener(
                { reader ->
                    processLatestImage(reader)
                },
                captureHandler
            )

            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "OutcastsLiveScanner",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                captureHandler
            )

        } catch (exception: Exception) {
            android.util.Log.e(
                "OutcastsScanner",
                "Could not start screen capture",
                exception
            )
            stopScanner()
        }
    }

    private fun processLatestImage(reader: ImageReader) {
        if (processingImage) return

        val image = try {
            reader.acquireLatestImage()
        } catch (exception: Exception) {
            null
        } ?: return

        processingImage = true

        val bitmap = try {
            imageToBitmap(image)
        } catch (exception: Exception) {
            null
        } finally {
            image.close()
        }

        if (bitmap == null) {
            processingImage = false
            return
        }

        val inputImage = InputImage.fromBitmap(bitmap, 0)

        recognizer.process(inputImage)
            .addOnSuccessListener { result ->
                val detectedText = result.text.trim()

                if (detectedText.isNotEmpty() &&
                    detectedText != lastDetectedText
                ) {
                    lastDetectedText = detectedText

                    android.util.Log.d(
                        "OutcastsScanner",
                        "Screen text: $detectedText"
                    )
                }
            }
            .addOnFailureListener { exception ->
                android.util.Log.e(
                    "OutcastsScanner",
                    "Text recognition failed",
                    exception
                )
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

        val rowPadding = rowStride - pixelStride * width
        val paddedWidth = width + rowPadding / pixelStride

        val paddedBitmap = Bitmap.createBitmap(
            paddedWidth,
            height,
            Bitmap.Config.ARGB_8888
        )

        buffer.rewind()
        paddedBitmap.copyPixelsFromBuffer(buffer)

        val croppedBitmap = Bitmap.createBitmap(
            paddedBitmap,
            0,
            0,
            width,
            height
        )

        paddedBitmap.recycle()

        return croppedBitmap
    }

    private fun stopScanner() {
        try {
            imageReader?.setOnImageAvailableListener(null, null)
            virtualDisplay?.release()
            imageReader?.close()

            mediaProjection?.stop()

            captureThread?.quitSafely()

            virtualDisplay = null
            imageReader = null
            mediaProjection = null
            captureThread = null
            captureHandler = null

            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()

        } catch (exception: Exception) {
            android.util.Log.e(
                "OutcastsScanner",
                "Error stopping scanner",
                exception
            )
            stopSelf()
        }
    }

    override fun onDestroy() {
        try {
            virtualDisplay?.release()
            imageReader?.close()
            mediaProjection?.stop()
            captureThread?.quitSafely()
            recognizer.close()
        } catch (_: Exception) {
        }

        super.onDestroy()
    }
}
