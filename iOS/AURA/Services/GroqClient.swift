import Foundation

public struct AuraAnswer: Codable, Sendable {
    public let question: String
    public let answer: String
    public let explanation: String

    enum CodingKeys: String, CodingKey {
        case question = "enunciado"
        case answer = "respuesta"
        case explanation = "explicacion"
    }
}

public enum GroqClient {
    private static let model = "qwen/qwen3.8-27b"
    private static let endpoint = URL(string: "https://api.groq.com/openai/v1/chat/completions")!

    private static let systemPrompt = """
Eres AURA, asistente experto principalmente en matemáticas y física. Analiza la imagen y responde en español.

Lee el enunciado, los datos, símbolos, unidades, diagramas y opciones exactamente. Conserva signos, exponentes y decimales. Si algo no se distingue, dilo; no inventes.

MATEMÁTICAS: usa la definición o regla correcta, desarrolla pasos algebraicos verificables y comprueba signos, dominio y resultado.

FÍSICA MECÁNICA: identifica datos, incógnita, sistema y modelo; distingue escalares y vectores y conceptos como distancia/desplazamiento, rapidez/velocidad y masa/peso. Declara ejes y convención de signos. Elige entre cinemática, leyes de Newton/diagrama de fuerzas, trabajo y energía, impulso y momento, movimiento circular, estática o rotación según corresponda. Descompón vectores, convierte unidades, resuelve simbólicamente y luego sustituye. Comprueba unidades, dirección, signo y plausibilidad física. No mezcles modelos incompatibles.

Si hay alternativas, evalúa cada una y atiende negaciones (NO, EXCEPTO, FALSO) y si pide una o varias. No elijas por palabras parecidas. Si falta un dato o ninguna opción sirve, dilo. Responde de forma precisa y breve, con pasos suficientes para verificar.

Devuelve solo JSON válido con exactamente estas claves: enunciado, respuesta, explicacion, procedimiento. No repitas los mismos pasos en explicacion y procedimiento.
"""

    public static func analyze(apiKey: String, jpegData: Data) async throws -> AuraAnswer {
        let base64String = jpegData.base64EncodedString()
        let content: [[String: Any]] = [
            ["type": "text", "text": systemPrompt],
            ["type": "image_url", "image_url": ["url": "data:image/jpeg;base64,\(base64String)"]]
        ]
        let messages: [[String: Any]] = [
            ["role": "user", "content": content]
        ]

        let budgets = [1400, 1000, 700, 480]

        for budget in budgets {
            let requestBody: [String: Any] = [
                "model": model,
                "messages": messages,
                "temperature": 0.1,
                "max_tokens": budget,
                "response_format": ["type": "json_object"]
            ]

            var request = URLRequest(url: endpoint)
            request.httpMethod = "POST"
            request.setValue("Bearer \(apiKey)", forHTTPHeaderField: "Authorization")
            request.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
            request.timeoutInterval = 60
            request.httpBody = try JSONSerialization.data(withJSONObject: requestBody)

            let (data, response) = try await URLSession.shared.data(for: request)
            guard let httpResponse = response as? HTTPURLResponse else {
                throw NSError(domain: "GroqClient", code: -1, userInfo: [NSLocalizedDescriptionKey: "Respuesta no válida del servidor."])
            }

            if (200...299).contains(httpResponse.statusCode) {
                let jsonEnvelope = try JSONSerialization.jsonObject(with: data) as? [String: Any]
                guard let choices = jsonEnvelope?["choices"] as? [[String: Any]],
                      let firstMessage = choices.first?["message"] as? [String: Any],
                      let rawContent = firstMessage["content"] as? String else {
                    throw NSError(domain: "GroqClient", code: -2, userInfo: [NSLocalizedDescriptionKey: "Formato de respuesta inesperado de Groq."])
                }

                let cleanJson = stripFence(rawContent)
                guard let jsonData = cleanJson.data(using: .utf8),
                      let parsedDict = try? JSONSerialization.jsonObject(with: jsonData) as? [String: Any] else {
                    throw NSError(domain: "GroqClient", code: -3, userInfo: [NSLocalizedDescriptionKey: "No se pudo interpretar el JSON devuelto."])
                }

                let question = (parsedDict["enunciado"] as? String) ?? "Enunciado no identificado"
                let answer = (parsedDict["respuesta"] as? String) ?? "No se identificó una respuesta clara"
                let explanation = (parsedDict["explicacion"] as? String) ?? (parsedDict["procedimiento"] as? String) ?? "Sin explicación"

                return AuraAnswer(question: question, answer: answer, explanation: explanation)
            }

            let responseText = String(data: data, encoding: .utf8)?.lowercased() ?? ""
            let isOutputLimit = httpResponse.statusCode == 429 &&
                (responseText.contains("output tokens per minute") || responseText.contains("otpm") ||
                    (responseText.contains("request too large") && responseText.contains("output")))

            if !isOutputLimit {
                throw apiError(code: httpResponse.statusCode)
            }
        }

        throw NSError(domain: "GroqClient", code: 429, userInfo: [NSLocalizedDescriptionKey: "Groq agotó el límite temporal de tokens de salida. Espera un minuto y vuelve a intentarlo."])
    }

    public static func testKey(apiKey: String) async throws -> Bool {
        let requestBody: [String: Any] = [
            "model": model,
            "messages": [["role": "user", "content": "Hola"]],
            "max_tokens": 10
        ]
        var request = URLRequest(url: endpoint)
        request.httpMethod = "POST"
        request.setValue("Bearer \(apiKey)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        request.timeoutInterval = 15
        request.httpBody = try JSONSerialization.data(withJSONObject: requestBody)

        let (_, response) = try await URLSession.shared.data(for: request)
        if let httpResponse = response as? HTTPURLResponse, (200...299).contains(httpResponse.statusCode) {
            return true
        }
        return false
    }

    private static func apiError(code: Int) -> NSError {
        let msg: String
        switch code {
        case 401: msg = "Groq rechazó la clave API. Revísala en Ajustes."
        case 429: msg = "Groq está limitando las consultas de tu cuenta. Espera un momento y vuelve a intentarlo."
        default: msg = "Groq no pudo completar el análisis (HTTP \(code)). Comprueba Internet e inténtalo de nuevo."
        }
        return NSError(domain: "GroqClient", code: code, userInfo: [NSLocalizedDescriptionKey: msg])
    }

    private static func stripFence(_ text: String) -> String {
        var trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if !trimmed.hasPrefix("```") { return trimmed }
        if trimmed.hasPrefix("```json") { trimmed = String(trimmed.dropFirst(7)) }
        else if trimmed.hasPrefix("```JSON") { trimmed = String(trimmed.dropFirst(7)) }
        else if trimmed.hasPrefix("```") { trimmed = String(trimmed.dropFirst(3)) }
        if trimmed.hasSuffix("```") { trimmed = String(trimmed.dropLast(3)) }
        return trimmed.trimmingCharacters(in: .whitespacesAndNewlines)
    }
}
