import Foundation
import UserNotifications

public final class NotificationManager: @unchecked Sendable {
    public static let shared = NotificationManager()

    private init() {}

    public func requestPermission(completion: @escaping (Bool) -> Void = { _ in }) {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { granted, _ in
            DispatchQueue.main.async {
                completion(granted)
            }
        }
    }

    public func sendAnswerNotification(answer: AuraAnswer) {
        let content = UNMutableNotificationContent()
        content.title = "AURA · Respuesta lista"
        content.subtitle = answer.question
        content.body = answer.explanation.isEmpty ? answer.answer : "\(answer.answer)\n\n\(answer.explanation)"
        content.sound = .default

        let request = UNNotificationRequest(
            identifier: "aura_result_\(Date().timeIntervalSince1970)",
            content: content,
            trigger: nil // Entrega inmediata
        )

        UNUserNotificationCenter.current().add(request)
    }

    public func sendErrorNotification(message: String) {
        let content = UNMutableNotificationContent()
        content.title = "AURA · Aviso"
        content.body = message
        content.sound = .default

        let request = UNNotificationRequest(
            identifier: "aura_error_\(Date().timeIntervalSince1970)",
            content: content,
            trigger: nil
        )

        UNUserNotificationCenter.current().add(request)
    }
}
