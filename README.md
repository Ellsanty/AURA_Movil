# AURA móvil

Proyecto móvil independiente de AURA para PC. Android es el primer prototipo funcional; iOS tendrá una implementación nativa aparte porque sus APIs de captura, gestos y distribución son distintas.

## Android: flujo previsto

1. Guardar la clave propia de Groq en Ajustes.
2. Pulsar **Iniciar sesión de captura** y aceptar el selector del sistema. Elegir **Toda la pantalla** si se desea cambiar entre aplicaciones.
3. Mantener activa la sesión autorizada. Android mostrará sus controles/indicador de captura.
4. Tocar el mosaico **Analizar AURA** en Ajustes rápidos. Se toma un único fotograma en memoria, se envía a Groq y se muestra una notificación con el resultado.
5. Usar **Detener captura** para cerrar la sesión. Si Android la detiene o el proceso termina, hay que iniciarla y autorizarla otra vez.

La imagen no se guarda en Fotos ni en almacenamiento local. Se envía a Groq para el análisis. AURA no usa micrófono ni cámara.

## Compilar y probar Android

Requisitos: Android Studio, Android SDK 36 y JDK 17. Abre `Android/` en Android Studio y usa **Build > Build Bundle(s) / APK(s) > Build APK(s)**. El APK de depuración queda en `Android/app/build/outputs/apk/debug/app-debug.apk`. Para ejecutarlo, conecta un teléfono Android y selecciona la configuración `app`. La compilación de escritorio requiere acceso al servicio local que inicia Gradle; si la terminal externa no puede abrir esa conexión, compila desde Android Studio.

La compilación y las pruebas en un teléfono todavía están pendientes. La captura y las notificaciones dependen de los permisos y de las restricciones de segundo plano de cada versión de Android.

## iOS

La captura continua autorizada requiere ScreenCaptureKit y su selector/indicador del sistema. Se debe implementar y probar desde un Mac con Xcode. La carpeta `iOS/` documenta el diseño y las limitaciones antes de crear ese target.
