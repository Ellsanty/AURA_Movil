# AURA para iOS (Proyecto Swift / SwiftUI Nativo)

La implementación nativa de AURA para iOS está completa y lista para ser abierta y compilada en Xcode en un sistema macOS.

## Estructura del Proyecto

- `AURA.xcodeproj/`: Proyecto nativo de Xcode listo para abrirse en macOS.
- `AURA/AURAApp.swift`: Punto de entrada `@main` de SwiftUI.
- `AURA/Views/ContentView.swift`: Interfaz de usuario con tema oscuro `#090D18`, gestión de clave API y prueba de conectividad.
- `AURA/Services/KeyVault.swift`: Almacenamiento seguro cifrado en el `Keychain` de iOS.
- `AURA/Services/GroqClient.swift`: Cliente asíncrono para enviar capturas a la API de Groq (`qwen/qwen3.8-27b`) con gestión de límites de cuotas.
- `AURA/Services/NotificationManager.swift`: Alertas y respuestas mediante `UNUserNotificationCenter`.
- `AURA/Intents/AnalyzeScreenIntent.swift`: Integración con `AppIntents` de iOS para lanzar la captura desde:
  - Botón de Acción (iPhone 15 Pro / 16 / 16 Pro)
  - Tocar Atrás (Back Tap en Accesibilidad)
  - App Atajos (Shortcuts) o Widget del Centro de Control

## Compilación y Despliegue

1. Abre la carpeta `iOS/AURA.xcodeproj` en Xcode en un equipo Mac.
2. Selecciona tu equipo de desarrollo (Development Team) en **Signing & Capabilities**.
3. Selecciona tu iPhone físico o un Simulador de iOS.
4. Presiona **⌘R** para compilar e instalar la aplicación.
