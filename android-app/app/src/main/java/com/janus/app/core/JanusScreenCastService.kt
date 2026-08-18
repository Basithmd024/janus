package com.janus.app.core

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
import java.io.ByteArrayOutputStream

class JanusScreenCastService : Service() {

    companion object {
        var isRunning = false
            private set
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        handlerThread = HandlerThread("ScreenCastThread").apply { start() }
        backgroundHandler = Handler(handlerThread!!.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // MOB-02 FIX: Synchronously start foreground BEFORE touching any MediaProjection APIs (Android 14+ compliance)
        startForegroundNotification()

        val resultCode = intent?.getIntExtra("resultCode", 0) ?: 0
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>("data")

        if (resultCode != android.app.Activity.RESULT_OK || data == null) {
            Log.e("JanusScreenCast", "Invalid resultCode ($resultCode) or null data. Stopping service.")
            stopSelf()
            return START_NOT_STICKY
        }

        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        try {
            mediaProjection = mpManager.getMediaProjection(resultCode, data)
        } catch (e: Exception) {
            Log.e("JanusScreenCast", "Failed to get MediaProjection", e)
            stopSelf()
            return START_NOT_STICKY
        }

        if (mediaProjection == null) {
            Log.e("JanusScreenCast", "MediaProjection is null. Stopping service.")
            stopSelf()
            return START_NOT_STICKY
        }

        startScreenCapture()

        return START_NOT_STICKY
    }

    private fun startForegroundNotification() {
        val channelId = "janus_screencast_channel"
        val channelName = "Janus Screen Mirroring Channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Janus Mirroring Screen")
            .setContentText("Casting your screen to Mac in real-time")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(2, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(2, notification)
        }
    }

    private fun startScreenCapture() {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            metrics.widthPixels = bounds.width()
            metrics.heightPixels = bounds.height()
            metrics.densityDpi = DisplayMetrics.DENSITY_DEFAULT
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getMetrics(metrics)
        }

        // Downscale resolution for low-latency network performance
        val scale = 0.5f // scale down to half resolution
        val width = (metrics.widthPixels * scale).toInt()
        val height = (metrics.heightPixels * scale).toInt()
        val dpi = metrics.densityDpi

        // Capture in JPEG-friendly format RGBA_8888
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "JanusScreenCast",
            width,
            height,
            dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            backgroundHandler
        )

        var lastFrameTime = 0L
        val frameInterval = 66L // Limit to ~15 FPS max to keep bandwidth low and smooth

        imageReader?.setOnImageAvailableListener({ reader ->
            val now = System.currentTimeMillis()
            if (now - lastFrameTime < frameInterval) {
                // Skip frame to respect interval rate limiting
                val image = reader.acquireLatestImage()
                image?.close()
                return@setOnImageAvailableListener
            }
            lastFrameTime = now

            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val planes = image.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * width

                // Create a bitmap from pixel buffer
                val bitmapWidth = width + rowPadding / pixelStride
                val bitmap = Bitmap.createBitmap(
                    bitmapWidth,
                    height,
                    Bitmap.Config.ARGB_8888
                )
                bitmap.copyPixelsFromBuffer(buffer)
                
                // Crop to target size if rowPadding exists
                val croppedBitmap = if (rowPadding != 0) {
                    Bitmap.createBitmap(bitmap, 0, 0, width, height)
                } else {
                    bitmap
                }

                // Compress bitmap to JPEG
                val bos = ByteArrayOutputStream()
                croppedBitmap.compress(Bitmap.CompressFormat.JPEG, 70, bos) // 70% quality
                val jpegBytes = bos.toByteArray()

                // Header indicates 0x02 (Screencast Frame)
                val fullPacket = ByteArray(jpegBytes.size + 1)
                fullPacket[0] = 0x02 // Packet identifier
                System.arraycopy(jpegBytes, 0, fullPacket, 1, jpegBytes.size)

                // Send frame over active Janus WebSocket connection
                JanusService.instance?.connectionManager?.sendBinary(fullPacket)

                // Clean up bitmaps
                if (croppedBitmap != bitmap) {
                    croppedBitmap.recycle()
                }
                bitmap.recycle()
            } catch (e: Exception) {
                Log.e("JanusScreenCast", "Error processing screen frame", e)
            } finally {
                image.close()
            }
        }, backgroundHandler)
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        mediaProjection?.stop()
        mediaProjection = null
        handlerThread?.quitSafely()
        handlerThread = null
        Log.d("JanusScreenCast", "Service destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
