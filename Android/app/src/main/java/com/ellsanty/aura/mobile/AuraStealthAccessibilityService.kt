package com.ellsanty.aura.mobile

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class AuraStealthAccessibilityService : AccessibilityService() {

    private var windowManager: WindowManager? = null
    private var floatingBubble: View? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private var notificationManager: NotificationManager? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        ensureChannels()
        showFloatingBubble()
        Toast.makeText(applicationContext, "AURA Modo Stealth Activado ◉", Toast.LENGTH_SHORT).show()
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {
        // No event tracking needed
    }

    override fun onInterrupt() {
        // Interrupted
    }

    fun takeStealthScreenshot() {
        if (!busy.compareAndSet(false, true)) {
            Toast.makeText(applicationContext, "AURA ya está procesando una consulta.", Toast.LENGTH_SHORT).show()
            return
        }

        val key = KeyVault.read(this)
        if (key.isNullOrBlank()) {
            busy.set(false)
            Toast.makeText(applicationContext, "Guarda primero tu clave API de Groq en AURA.", Toast.LENGTH_LONG).show()
            return
        }

        // Hide floating bubble briefly so it's not captured
        floatingBubble?.visibility = View.INVISIBLE

        Handler(Looper.getMainLooper()).postDelayed({
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        floatingBubble?.visibility = View.VISIBLE
                        val buffer = screenshot.hardwareBuffer
                        val bitmap = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                            ?.copy(Bitmap.Config.ARGB_8888, false)
                        buffer.close()

                        if (bitmap == null) {
                            busy.set(false)
                            Toast.makeText(applicationContext, "No se pudo capturar el fotograma stealth.", Toast.LENGTH_SHORT).show()
                            return
                        }

                        val output = ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 84, output)
                        bitmap.recycle()
                        val jpeg = output.toByteArray()

                        executor.execute { analyze(key, jpeg) }
                    }

                    override fun onFailure(errorCode: Int) {
                        floatingBubble?.visibility = View.VISIBLE
                        busy.set(false)
                        Toast.makeText(applicationContext, "Error capturando pantalla stealth (Código $errorCode).", Toast.LENGTH_SHORT).show()
                    }
                })
            } else {
                floatingBubble?.visibility = View.VISIBLE
                busy.set(false)
                Toast.makeText(applicationContext, "El modo Stealth por Accesibilidad requiere Android 11+.", Toast.LENGTH_LONG).show()
            }
        }, 80)
    }

    private fun analyze(key: String, jpeg: ByteArray) {
        try {
            val answer = GroqClient.analyze(key, jpeg)
            postAnswer(answer)
        } catch (error: Exception) {
            postResult(error.localizedMessage ?: "No se pudo completar el análisis.", isError = true)
        } finally {
            busy.set(false)
        }
    }

    private fun postAnswer(answer: AuraAnswer) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, "AURA: ${answer.answer}", Toast.LENGTH_LONG).show()
        }

        val notification = Notification.Builder(this, STEALTH_CHANNEL)
            .setSmallIcon(R.drawable.ic_aura)
            .setContentTitle("AURA · Respuesta Lista")
            .setContentText(answer.answer)
            .setStyle(Notification.BigTextStyle().bigText("${answer.answer}\n\n${answer.explanation}"))
            .setAutoCancel(true)
            .setColor(0xFF8978FF.toInt())
            .setPriority(Notification.PRIORITY_HIGH)
            .setDefaults(Notification.DEFAULT_ALL)
            .build()
        notificationManager?.notify(STEALTH_NOTIFICATION_ID, notification)
    }

    private fun postResult(message: String, isError: Boolean) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
        }
    }

    fun showFloatingBubble() {
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

            bubbleView.setOnTouchListener { _, event ->
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
                            takeStealthScreenshot()
                        }
                        true
                    }
                    else -> false
                }
            }

            floatingBubble = bubbleView
            windowManager?.addView(floatingBubble, params)
        } catch (e: Exception) {
            // Overlay exception
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

    private fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        notificationManager?.createNotificationChannel(NotificationChannel(STEALTH_CHANNEL, "Respuestas AURA Stealth", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Respuestas y avisos en modo stealth por accesibilidad."
        })
    }

    override fun onDestroy() {
        removeFloatingBubble()
        instance = null
        executor.shutdownNow()
        super.onDestroy()
    }

    companion object {
        var instance: AuraStealthAccessibilityService? = null
            private set
        private const val STEALTH_CHANNEL = "aura_stealth_channel"
        private const val STEALTH_NOTIFICATION_ID = 3009
    }
}
