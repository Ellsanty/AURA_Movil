package com.ellsanty.aura.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
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
import android.view.WindowManager
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class CaptureService : Service() {
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var frameThread: HandlerThread? = null
    private var frameHandler: Handler? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private val captureRequested = AtomicBoolean(false)
    private var notificationManager: NotificationManager? = null

    private var windowManager: WindowManager? = null
    private var floatingBubble: View? = null

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        ensureChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startProjection(intent)
            ACTION_ANALYZE -> requestFrame()
            ACTION_STOP -> stopProjection()
        }
        return START_NOT_STICKY
    }

    private fun startProjection(intent: Intent) {
        if (isRunning) return
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data = getProjectionIntent(intent)
        if (resultCode == 0 || data == null) {
            postResult("AURA no pudo iniciar la sesión. Vuelve a autorizar la captura.", isError = true)
            stopSelf()
            return
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(SESSION_NOTIFICATION_ID, sessionNotification("Preparando captura…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(SESSION_NOTIFICATION_ID, sessionNotification("Preparando captura…"))
            }
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(resultCode, data)
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            frameThread = HandlerThread("AURA-screen-frames").apply { start() }
            frameHandler = Handler(frameThread!!.looper)
            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2).apply {
                setOnImageAvailableListener({ reader -> consumeFrame(reader) }, frameHandler)
            }
            projectionCallback = object : MediaProjection.Callback() {
                override fun onStop() {
                    stopProjection()
                }
            }
            projection?.registerCallback(projectionCallback!!, frameHandler)
            virtualDisplay = projection?.createVirtualDisplay(
                "AURA-mobile-screen",
                width,
                height,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                frameHandler
            )
            if (virtualDisplay == null) throw IllegalStateException("No se pudo iniciar el flujo de pantalla.")
            isRunning = true
            showFloatingBubble()
            notificationManager?.notify(SESSION_NOTIFICATION_ID, sessionNotification("Activa · Toca la burbuja flotante ◉ o Ajustes rápidos"))
            requestTileRefresh()
        } catch (error: Exception) {
            isRunning = false
            postResult(error.localizedMessage ?: "No se pudo iniciar la captura.", isError = true)
            stopProjection()
        }
    }

    private fun requestFrame() {
        if (!isRunning || projection == null) {
            postResult("Primero inicia y autoriza la sesión de captura en AURA.", isError = true)
            return
        }
        if (KeyVault.read(this).isNullOrBlank()) {
            postResult("Agrega tu clave API de Groq en Ajustes.", isError = true)
            return
        }
        if (!busy.compareAndSet(false, true)) {
            postResult("AURA ya está analizando una captura.", isError = true)
            return
        }
        captureRequested.set(true)
        notificationManager?.notify(SESSION_NOTIFICATION_ID, sessionNotification("Capturando un fotograma para analizar…"))
        frameHandler?.postDelayed({
            if (captureRequested.compareAndSet(true, false)) {
                busy.set(false)
                postResult("No llegó un fotograma de pantalla. Inténtalo de nuevo.", isError = true)
            }
        }, FRAME_TIMEOUT_MS)
    }

    private fun consumeFrame(reader: ImageReader) {
        var image: Image? = null
        try {
            image = reader.acquireLatestImage() ?: return
            if (!captureRequested.compareAndSet(true, false)) return
            val jpeg = imageToJpeg(image)
            executor.execute { analyze(jpeg) }
        } catch (error: Exception) {
            captureRequested.set(false)
            busy.set(false)
            postResult("No se pudo preparar la captura. Vuelve a intentarlo.", isError = true)
        } finally {
            image?.close()
        }
    }

    private fun imageToJpeg(image: Image): ByteArray {
        val plane = image.planes[0]
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val paddedWidth = rowStride / pixelStride
        val padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(plane.buffer)
        val cropped = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
        val output = ByteArrayOutputStream()
        cropped.compress(Bitmap.CompressFormat.JPEG, 84, output)
        if (cropped !== padded) cropped.recycle()
        padded.recycle()
        return output.toByteArray()
    }

    private fun analyze(jpeg: ByteArray) {
        try {
            val key = KeyVault.read(this) ?: throw IllegalStateException("Agrega tu clave API de Groq en AURA.")
            val answer = GroqClient.analyze(key, jpeg)
            postAnswer(answer)
        } catch (error: Exception) {
            postResult(error.localizedMessage ?: "No se pudo completar el análisis.", isError = true)
        } finally {
            busy.set(false)
        }
    }

    private fun postAnswer(answer: AuraAnswer) {
        val body = buildString {
            append(answer.answer)
            if (answer.explanation.isNotBlank()) append("\n\n").append(answer.explanation)
        }

        // Show Toast on main thread for instant feedback over any app!
        Handler(mainLooper).post {
            android.widget.Toast.makeText(applicationContext, "AURA: ${answer.answer}", android.widget.Toast.LENGTH_LONG).show()
        }

        val openIntent = PendingIntent.getActivity(
            this, RESULT_REQUEST_CODE,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, RESULT_CHANNEL)
            .setSmallIcon(R.drawable.ic_aura)
            .setContentTitle("AURA · Respuesta lista")
            .setContentText(answer.answer)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .setColor(0xFF8978FF.toInt())
            .setPriority(Notification.PRIORITY_HIGH)
            .setDefaults(Notification.DEFAULT_ALL)
            .build()
        notificationManager?.notify(RESULT_NOTIFICATION_ID, notification)
        notificationManager?.notify(SESSION_NOTIFICATION_ID, sessionNotification("Activa · Toca la burbuja flotante ◉ para analizar"))
    }

    private fun postResult(message: String, isError: Boolean) {
        Handler(mainLooper).post {
            android.widget.Toast.makeText(applicationContext, message, android.widget.Toast.LENGTH_LONG).show()
        }
        val notification = Notification.Builder(this, RESULT_CHANNEL)
            .setSmallIcon(R.drawable.ic_aura)
            .setContentTitle(if (isError) "AURA · Aviso" else "AURA")
            .setContentText(message.take(180))
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .setColor(if (isError) 0xFFF17E91.toInt() else 0xFF8978FF.toInt())
            .setPriority(Notification.PRIORITY_HIGH)
            .build()
        notificationManager?.notify(RESULT_NOTIFICATION_ID, notification)
    }

    private fun sessionNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, OPEN_REQUEST_CODE, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, STOP_REQUEST_CODE, Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, SESSION_CHANNEL)
            .setSmallIcon(R.drawable.ic_aura)
            .setContentTitle("AURA · Captura autorizada")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_media_pause),
                "Detener captura", stop
            ).build())
            .build()
    }

    private fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        notificationManager?.createNotificationChannel(NotificationChannel(SESSION_CHANNEL, "Sesión de captura", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Indica que AURA tiene una sesión de captura de pantalla autorizada."
            setShowBadge(false)
        })
        notificationManager?.createNotificationChannel(NotificationChannel(RESULT_CHANNEL, "Respuestas de AURA", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Respuestas y avisos de los análisis solicitados."
        })
    }

    private fun stopProjection() {
        if (!isRunning && projection == null && virtualDisplay == null && imageReader == null) {
            removeFloatingBubble()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        removeFloatingBubble()
        captureRequested.set(false)
        busy.set(false)
        isRunning = false
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { imageReader?.setOnImageAvailableListener(null, null); imageReader?.close() }
        imageReader = null
        val oldProjection = projection
        projection = null
        runCatching { if (oldProjection != null && projectionCallback != null) oldProjection.unregisterCallback(projectionCallback!!) }
        projectionCallback = null
        runCatching { oldProjection?.stop() }
        frameThread?.quitSafely()
        frameThread = null
        frameHandler = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        notificationManager?.cancel(SESSION_NOTIFICATION_ID)
        requestTileRefresh()
        stopSelf()
    }

    private fun requestTileRefresh() {
        if (Build.VERSION.SDK_INT >= 24) runCatching {
            android.service.quicksettings.TileService.requestListeningState(
                this,
                android.content.ComponentName(this, AuraTileService::class.java)
            )
        }
    }

    private fun showFloatingBubble() {
        if (!Settings.canDrawOverlays(this) || floatingBubble != null) return
        try {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            val bubbleSize = (48 * resources.displayMetrics.density).toInt()
            val params = WindowManager.LayoutParams(
                bubbleSize, bubbleSize,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 20
                y = 350
            }

            val bubbleView = TextView(this).apply {
                text = "◉"
                textSize = 20f
                setTextColor(android.graphics.Color.WHITE)
                gravity = Gravity.CENTER
                val shape = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(android.graphics.Color.argb(230, 137, 120, 255))
                    setStroke(2, android.graphics.Color.WHITE)
                }
                background = shape
                elevation = 12f
            }

            var initialX = 0
            var initialY = 0
            var initialTouchX = 0f
            var initialTouchY = 0f
            var isClick = false

            bubbleView.setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isClick = true
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()
                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                            isClick = false
                        }
                        params.x = initialX + dx
                        params.y = initialY + dy
                        windowManager?.updateViewLayout(bubbleView, params)
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (isClick) {
                            // Instant silent capture trigger! Hides bubble briefly so it's not captured in frame.
                            v.visibility = View.INVISIBLE
                            Handler(mainLooper).postDelayed({
                                requestFrame()
                            }, 80)
                            Handler(mainLooper).postDelayed({
                                v.visibility = View.VISIBLE
                            }, 500)
                        }
                        true
                    }
                    else -> false
                }
            }

            floatingBubble = bubbleView
            windowManager?.addView(floatingBubble, params)
        } catch (e: Exception) {
            // Permission missing or window add failed
        }
    }

    private fun removeFloatingBubble() {
        runCatching {
            if (floatingBubble != null && windowManager != null) {
                windowManager?.removeView(floatingBubble)
                floatingBubble = null
            }
        }
    }

    override fun onDestroy() {
        removeFloatingBubble()
        if (isRunning) {
            isRunning = false
            runCatching { virtualDisplay?.release() }
            runCatching { imageReader?.close() }
            runCatching { projection?.let { p -> projectionCallback?.let { p.unregisterCallback(it) } } }
            runCatching { projection?.stop() }
            projection = null
        }
        frameThread?.quitSafely()
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    private fun getProjectionIntent(intent: Intent): Intent? = if (Build.VERSION.SDK_INT >= 33) {
        intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
    } else {
        intent.getParcelableExtra(EXTRA_RESULT_DATA) as? Intent
    }

    companion object {
        const val ACTION_START = "com.ellsanty.aura.mobile.START_CAPTURE"
        const val ACTION_ANALYZE = "com.ellsanty.aura.mobile.ANALYZE_NOW"
        const val ACTION_STOP = "com.ellsanty.aura.mobile.STOP_CAPTURE"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        @Volatile var isRunning = false
            private set

        private const val SESSION_CHANNEL = "aura_capture_session"
        private const val RESULT_CHANNEL = "aura_results"
        private const val SESSION_NOTIFICATION_ID = 3001
        private const val RESULT_NOTIFICATION_ID = 3002
        private const val OPEN_REQUEST_CODE = 3003
        private const val STOP_REQUEST_CODE = 3004
        private const val RESULT_REQUEST_CODE = 3005
        private const val FRAME_TIMEOUT_MS = 5000L
    }
}
