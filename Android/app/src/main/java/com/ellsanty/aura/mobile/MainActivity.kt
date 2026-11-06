package com.ellsanty.aura.mobile

import android.Manifest
import android.app.Activity
import android.app.StatusBarManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var keyInput: EditText
    private lateinit var statusText: TextView
    private var awaitingNotificationPermission = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(9, 13, 24)
        window.navigationBarColor = Color.rgb(9, 13, 24)
        buildUi()
        if (intent.getBooleanExtra(EXTRA_OPEN_CAPTURE, false)) startCaptureSession()
    }

    override fun onResume() {
        super.onResume()
        if (::statusText.isInitialized) {
            statusText.text = if (CaptureService.isRunning) "Sesión de captura activa" else "Sesión de captura detenida"
            statusText.setTextColor(if (CaptureService.isRunning) GREEN else MUTED)
        }
    }

    private fun buildUi() {
        val scroll = ScrollView(this).apply { setBackgroundColor(BG) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(30), dp(24), dp(32))
        }
        scroll.addView(root)

        val brand = TextView(this).apply {
            text = "◉  AURA"
            textSize = 26f
            setTextColor(WHITE)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }
        root.addView(brand)
        root.addView(text("ASISTENTE VISUAL", 10f, ACCENT, true, 0, 22))
        root.addView(text("Tu pantalla, entendida.", 24f, WHITE, true, 0, 8))
        root.addView(text("Inicia una sesión de captura autorizada. Después usa el mosaico Analizar AURA en Ajustes rápidos para pedir cada respuesta.", 14f, MUTED, false, 0, 18))

        val notice = card()
        notice.addView(text("PRIVACIDAD Y CAPTURA", 11f, ACCENT, true, 0, 9))
        notice.addView(text("Android mostrará que la sesión de pantalla está activa. AURA toma un fotograma solo cuando pides el análisis, lo guarda únicamente en memoria y lo envía a Groq. No lo guarda en Fotos. Puedes detener la sesión en cualquier momento.", 13f, WHITE, false, 0, 0))
        root.addView(notice, margin(bottom = 18))

        root.addView(text("Clave API de Groq", 15f, WHITE, true, 0, 7))
        keyInput = EditText(this).apply {
            hint = if (KeyVault.read(this@MainActivity).isNullOrBlank()) "Pega tu clave API (gsk_...)" else "Clave guardada de forma cifrada"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(WHITE)
            setHintTextColor(MUTED)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(CARD, 12)
            setSingleLine(true)
        }
        root.addView(keyInput, margin(bottom = 10))

        val keyButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val saveBtn = button("Guardar clave", CARD_ALT) { saveKey() }
        val clearBtn = button("Borrar clave", CARD_ALT) { clearKey() }
        keyButtons.addView(saveBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) })
        keyButtons.addView(clearBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(6) })
        root.addView(keyButtons, margin(bottom = 20))

        statusText = text(
            if (CaptureService.isRunning) "Sesión de captura activa" else "Sesión de captura detenida",
            13f, if (CaptureService.isRunning) GREEN else MUTED, true, 0, 12
        )
        root.addView(statusText)
        root.addView(button("Iniciar sesión de captura", ACCENT) { startCaptureSession() }, margin(bottom = 10))
        root.addView(button("Detener captura", CARD_ALT) { stopCaptureSession() }, margin(bottom = 12))
        root.addView(button("Activar Botón Flotante Discreto ◉", CARD_ALT) { requestOverlayPermission() }, margin(bottom = 12))
        root.addView(button("Activar Modo Stealth (Sin MediaProjection) 🕶️", CARD_ALT) { openAccessibilitySettings() }, margin(bottom = 12))
        root.addView(button("Añadir Analizar AURA a Ajustes rápidos", CARD_ALT) { requestAddTile() }, margin(bottom = 18))
        root.addView(text("El Modo Stealth por Accesibilidad toma capturas silenciosas de hardware sin activar avisos ni servicios detectables de proyección de pantalla.", 12f, MUTED, false, 0, 0))

        setContentView(scroll)
    }

    private fun saveKey(): Boolean {
        val value = keyInput.text.toString().trim()
        if (value.isEmpty()) {
            Toast.makeText(this, "Pega primero tu clave API de Groq.", Toast.LENGTH_SHORT).show()
            return false
        }
        return runCatching {
            KeyVault.save(this, value)
            keyInput.text.clear()
            keyInput.hint = "Clave guardada de forma cifrada"
            Toast.makeText(this, "Clave guardada en este dispositivo.", Toast.LENGTH_SHORT).show()
            true
        }.getOrElse {
            Toast.makeText(this, "No se pudo guardar la clave en el almacenamiento seguro.", Toast.LENGTH_LONG).show()
            false
        }
    }

    private fun clearKey() {
        KeyVault.clear(this)
        keyInput.text.clear()
        keyInput.hint = "Pega tu clave API (gsk_...)"
        Toast.makeText(this, "Clave eliminada de este dispositivo.", Toast.LENGTH_SHORT).show()
    }

    private fun startCaptureSession() {
        if (CaptureService.isRunning) {
            Toast.makeText(this, "La sesión de captura ya está activa.", Toast.LENGTH_SHORT).show()
            return
        }
        if (KeyVault.read(this).isNullOrBlank()) {
            if (!saveKey()) return
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            awaitingNotificationPermission = true
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
            return
        }
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_SCREEN_CAPTURE)
    }

    @Deprecated("Uses the system MediaProjection consent dialog")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_SCREEN_CAPTURE) return
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "No se inició la captura.", Toast.LENGTH_SHORT).show()
            return
        }
        val service = Intent(this, CaptureService::class.java)
            .setAction(CaptureService.ACTION_START)
            .putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
            .putExtra(CaptureService.EXTRA_RESULT_DATA, data)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(service) else startService(service)
        statusText.text = "Iniciando sesión de captura…"
        statusText.setTextColor(ACCENT)
    }

    @Deprecated("Permission callback")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIFICATIONS && awaitingNotificationPermission) {
            awaitingNotificationPermission = false
            // Notification permission is requested before capture so responses can be delivered as alerts.
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startCaptureSession()
            else Toast.makeText(this, "Activa las notificaciones de AURA para recibir respuestas. Puedes habilitarlas en Ajustes.", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopCaptureSession() {
        startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
        statusText.text = "Sesión de captura detenida"
        statusText.setTextColor(MUTED)
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            startActivity(intent)
            Toast.makeText(this, "Concede el permiso 'Mostrar sobre otras apps' para activar el Botón Flotante Discreto ◉", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "El Botón Flotante Discreto ◉ ya está activado.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestAddTile() {
        if (Build.VERSION.SDK_INT < 33) {
            Toast.makeText(this, "Abre Ajustes rápidos, toca Editar y añade Analizar AURA.", Toast.LENGTH_LONG).show()
            return
        }
        runCatching {
            getSystemService(StatusBarManager::class.java).requestAddTileService(
                android.content.ComponentName(this, AuraTileService::class.java),
                "Analizar AURA",
                android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_aura_tile),
                mainExecutor
            ) { result ->
                val message = if (result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED || result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED)
                    "Acceso rápido añadido." else "Puedes añadirlo después desde Editar en Ajustes rápidos."
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            }
        }.onFailure {
            Toast.makeText(this, "Abre Ajustes rápidos, toca Editar y añade Analizar AURA.", Toast.LENGTH_LONG).show()
        }
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = rounded(CARD, 16)
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean, top: Int, bottom: Int): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif", Typeface.BOLD)
        setPadding(0, dp(top), 0, dp(bottom))
        gravity = Gravity.START
    }

    private fun button(label: String, color: Int, action: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = 13f
        isAllCaps = false
        setTextColor(WHITE)
        background = rounded(color, 13)
        setPadding(dp(12), dp(9), dp(12), dp(9))
        setOnClickListener { action() }
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun margin(bottom: Int): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = dp(bottom) }

    companion object {
        const val EXTRA_OPEN_CAPTURE = "com.ellsanty.aura.mobile.OPEN_CAPTURE"
        private const val REQUEST_SCREEN_CAPTURE = 4001
        private const val REQUEST_NOTIFICATIONS = 4002
        private val BG = Color.rgb(9, 13, 24)
        private val CARD = Color.rgb(20, 30, 49)
        private val CARD_ALT = Color.rgb(34, 49, 74)
        private val ACCENT = Color.rgb(137, 120, 255)
        private val WHITE = Color.rgb(243, 245, 252)
        private val MUTED = Color.rgb(139, 152, 179)
        private val GREEN = Color.rgb(82, 214, 161)
    }
}
