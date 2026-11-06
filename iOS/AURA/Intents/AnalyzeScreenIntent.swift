import Foundation
import AppIntents
import UIKit
import UserNotifications

/// App Intent que permite ejecutar "Analizar AURA" desde el Botón de Acción,
/// el gesto Tocar Atrás (Back Tap), Atajos o el Centro de Control en iOS.
@available(iOS 16.0, *)
public struct AnalyzeScreenIntent: AppIntent {
    public static var title: LocalizedStringResource = "Analizar AURA"
    public static var description = IntentDescription("Captura un fotograma de pantalla o la imagen actual y lo analiza con el modelo AURA.")

    public static var openAppWhenRun: Bool = false

    @Parameter(title: "Imagen de Pantalla", description: "Opcional: Imagen pasada por un Atajo")
    var imageFile: IntentFile?

    public init() {}

    @MainActor
    public func perform() async throws -> some IntentResult & ProvidesDialog {
        guard let apiKey = KeyVault.read(), !apiKey.isEmpty else {
            let msg = "Configura primero tu clave API de Groq en la aplicación AURA."
            NotificationManager.shared.sendErrorNotification(message: msg)
            return .result(dialog: IntentDialog(stringLiteral: msg))
        }

        var jpegData: Data? = imageFile?.data

        if jpegData == nil {
            // Si el atajo no pasa una imagen directamente, capturamos la ventana visible actual de la app
            if let window = UIApplication.shared.connectedScenes
                .compactMap({ $0 as? UIWindowScene })
                .flatMap({ $0.windows })
                .first(where: { $0.isKeyWindow }) {
                let renderer = UIGraphicsImageRenderer(bounds: window.bounds)
                let uiImage = renderer.image { ctx in
                    window.drawHierarchy(in: window.bounds, afterScreenUpdates: true)
                }
                jpegData = uiImage.jpegData(compressionQuality: 0.84)
            }
        }

        guard let validJpeg = jpegData else {
            let errorMsg = "No se pudo obtener la imagen de pantalla para analizar."
            NotificationManager.shared.sendErrorNotification(message: errorMsg)
            return .result(dialog: IntentDialog(stringLiteral: errorMsg))
        }

        do {
            let answer = try await GroqClient.analyze(apiKey: apiKey, jpegData: validJpeg)
            NotificationManager.shared.sendAnswerNotification(answer: answer)
            return .result(dialog: IntentDialog(stringLiteral: answer.answer))
        } catch {
            let errorMsg = error.localizedDescription
            NotificationManager.shared.sendErrorNotification(message: errorMsg)
            return .result(dialog: IntentDialog(stringLiteral: errorMsg))
        }
    }
}

/// Registra los atajos nativos en iOS para que aparezcan en la app Atajos y opciones de sistema
@available(iOS 16.0, *)
public struct AURAShortcuts: AppShortcutsProvider {
    public static var appShortcuts: [AppShortcut] {
        AppShortcut(
            intent: AnalyzeScreenIntent(),
            phrases: [
                "Analizar pantalla con \(.applicationName)",
                "Analizar AURA con \(.applicationName)",
                "Preguntar a \(.applicationName)"
            ],
            shortTitle: "Analizar AURA",
            systemImageName: "sparkles"
        )
    }
}
