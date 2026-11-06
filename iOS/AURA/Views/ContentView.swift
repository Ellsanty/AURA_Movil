import SwiftUI

public struct ContentView: View {
    @State private var apiKeyInput: String = ""
    @State private var hasSavedKey: Bool = false
    @State private var statusMessage: String = ""
    @State private var isTestingKey: Bool = false
    @State private var isKeyValid: Bool? = nil

    private let bg = Color(red: 9/255, green: 13/255, blue: 24/255)
    private let card = Color(red: 20/255, green: 30/255, blue: 49/255)
    private let cardAlt = Color(red: 34/255, green: 49/255, blue: 74/255)
    private let accent = Color(red: 137/255, green: 120/255, blue: 255/255)
    private let muted = Color(red: 139/255, green: 152/255, blue: 179/255)
    private let green = Color(red: 82/255, green: 214/255, blue: 161/255)

    public init() {}

    public var body: some View {
        ZStack {
            bg.ignoresSafeArea()

            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    // Header Brand
                    VStack(alignment: .leading, spacing: 4) {
                        Text("◉  AURA")
                            .font(.system(size: 26, weight: .bold))
                            .foregroundColor(.white)
                        Text("ASISTENTE VISUAL")
                            .font(.system(size: 10, weight: .bold))
                            .foregroundColor(accent)
                            .padding(.bottom, 6)
                        Text("Tu pantalla, entendida.")
                            .font(.system(size: 24, weight: .bold))
                            .foregroundColor(.white)
                        Text("En iOS, usa el Botón de Acción, Tocar Atrás (Back Tap) o Atajos para pedir el análisis en cualquier momento.")
                            .font(.system(size: 14))
                            .foregroundColor(muted)
                    }

                    // Card Privacidad
                    VStack(alignment: .leading, spacing: 8) {
                        Text("PRIVACIDAD Y ALMACENAMIENTO")
                            .font(.system(size: 11, weight: .bold))
                            .foregroundColor(accent)
                        Text("Tu clave de Groq se almacena de forma cifrada en el Keychain de este iPhone. Las capturas no se guardan en la fototeca de iOS, se procesan únicamente en memoria para el análisis.")
                            .font(.system(size: 13))
                            .foregroundColor(.white)
                    }
                    .padding(16)
                    .background(card)
                    .cornerRadius(16)

                    // Sección Clave API
                    VStack(alignment: .leading, spacing: 10) {
                        Text("Clave API de Groq")
                            .font(.system(size: 15, weight: .bold))
                            .foregroundColor(.white)

                        SecureField(hasSavedKey ? "Clave guardada en Keychain" : "Pega tu clave API (gsk_...)", text: $apiKeyInput)
                            .padding(14)
                            .background(card)
                            .cornerRadius(12)
                            .foregroundColor(.white)
                            .autocapitalization(.none)

                        HStack(spacing: 10) {
                            Button(action: saveKey) {
                                Text("Guardar clave")
                                    .font(.system(size: 13, weight: .medium))
                                    .foregroundColor(.white)
                                    .frame(maxWidth: .infinity)
                                    .padding(.vertical, 10)
                                    .background(cardAlt)
                                    .cornerRadius(12)
                            }

                            Button(action: clearKey) {
                                Text("Borrar clave")
                                    .font(.system(size: 13, weight: .medium))
                                    .foregroundColor(.white)
                                    .frame(maxWidth: .infinity)
                                    .padding(.vertical, 10)
                                    .background(cardAlt)
                                    .cornerRadius(12)
                            }
                        }

                        Button(action: testKey) {
                            HStack {
                                if isTestingKey {
                                    ProgressView().tint(.white).padding(.trailing, 4)
                                }
                                Text("Probar conexión Groq")
                                    .font(.system(size: 13, weight: .semibold))
                                    .foregroundColor(.white)
                            }
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 12)
                            .background(accent)
                            .cornerRadius(12)
                        }
                        .disabled(isTestingKey)
                    }

                    if !statusMessage.isEmpty {
                        Text(statusMessage)
                            .font(.system(size: 13, weight: .medium))
                            .foregroundColor(isKeyValid == true ? green : (isKeyValid == false ? Color.red : muted))
                    }

                    // Instrucciones de Atajos iOS
                    VStack(alignment: .leading, spacing: 10) {
                        Text("CÓMO USAR EN iOS")
                            .font(.system(size: 12, weight: .bold))
                            .foregroundColor(accent)
                        Text("1. Abre la app **Atajos** de iOS.")
                            .font(.system(size: 13)).foregroundColor(.white)
                        Text("2. Busca el atajo autogenerado **'Analizar AURA'**.")
                            .font(.system(size: 13)).foregroundColor(.white)
                        Text("3. En **Ajustes > Accesibilidad > Tocar > Tocar Atrás**, asigna 'Analizar AURA' a dos o tres toques.")
                            .font(.system(size: 13)).foregroundColor(.white)
                        Text("4. En iPhone 15 Pro / 16, asigna el atajo al **Botón de Acción**.")
                            .font(.system(size: 13)).foregroundColor(.white)
                    }
                    .padding(16)
                    .background(card)
                    .cornerRadius(16)
                }
                .padding(24)
            }
        }
        .onAppear(perform: checkSavedKey)
    }

    private func checkSavedKey() {
        if let key = KeyVault.read(), !key.isEmpty {
            hasSavedKey = true
            statusMessage = "Clave configurada en Keychain."
            isKeyValid = true
        } else {
            hasSavedKey = false
            statusMessage = "No hay una clave guardada."
            isKeyValid = nil
        }
    }

    private func saveKey() {
        guard !apiKeyInput.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        if KeyVault.save(key: apiKeyInput) {
            apiKeyInput = ""
            hasSavedKey = true
            statusMessage = "Clave guardada de forma segura."
            isKeyValid = true
        } else {
            statusMessage = "No se pudo guardar la clave."
            isKeyValid = false
        }
    }

    private func clearKey() {
        KeyVault.clear()
        apiKeyInput = ""
        hasSavedKey = false
        statusMessage = "Clave eliminada del Keychain."
        isKeyValid = nil
    }

    private func testKey() {
        let key = apiKeyInput.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? (KeyVault.read() ?? "") : apiKeyInput
        guard !key.isEmpty else {
            statusMessage = "Ingresa o guarda primero una clave API."
            isKeyValid = false
            return
        }
        isTestingKey = true
        statusMessage = "Verificando clave con Groq..."

        Task {
            do {
                let success = try await GroqClient.testKey(apiKey: key)
                await MainActor.run {
                    isTestingKey = false
                    if success {
                        statusMessage = "Conexión exitosa con Groq API."
                        isKeyValid = true
                    } else {
                        statusMessage = "Groq rechazó la clave."
                        isKeyValid = false
                    }
                }
            } catch {
                await MainActor.run {
                    isTestingKey = false
                    statusMessage = "Error: \(error.localizedDescription)"
                    isKeyValid = false
                }
            }
        }
    }
}
