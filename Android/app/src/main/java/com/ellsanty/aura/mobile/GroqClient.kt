package com.ellsanty.aura.mobile

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class AuraAnswer(val question: String, val answer: String, val explanation: String)

object GroqClient {
    private const val MODEL = "qwen/qwen3.8-27b"
    private const val ENDPOINT = "https://api.groq.com/openai/v1/chat/completions"

    private val prompt = """Eres AURA, asistente experto principalmente en matemáticas y física. Analiza la imagen y responde en español.

Lee el enunciado, los datos, símbolos, unidades, diagramas y opciones exactamente. Conserva signos, exponentes y decimales. Si algo no se distingue, dilo; no inventes.

MATEMÁTICAS: usa la definición o regla correcta, desarrolla pasos algebraicos verificables y comprueba signos, dominio y resultado.

FÍSICA MECÁNICA: identifica datos, incógnita, sistema y modelo; distingue escalares y vectores y conceptos como distancia/desplazamiento, rapidez/velocidad y masa/peso. Declara ejes y convención de signos. Elige entre cinemática, leyes de Newton/diagrama de fuerzas, trabajo y energía, impulso y momento, movimiento circular, estática o rotación según corresponda. Descompón vectores, convierte unidades, resuelve simbólicamente y luego sustituye. Comprueba unidades, dirección, signo y plausibilidad física. No mezcles modelos incompatibles.

Si hay alternativas, evalúa cada una y atiende negaciones (NO, EXCEPTO, FALSO) y si pide una o varias. No elijas por palabras parecidas. Si falta un dato o ninguna opción sirve, dilo. Responde de forma precisa y breve, con pasos suficientes para verificar.

Devuelve solo JSON válido con exactamente estas claves: enunciado, respuesta, explicacion, procedimiento. No repitas los mismos pasos en explicacion y procedimiento."""

    fun analyze(apiKey: String, jpeg: ByteArray): AuraAnswer {
        val encoded = Base64.encodeToString(jpeg, Base64.NO_WRAP)
        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", prompt))
            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$encoded")))
        val messages = JSONArray().put(JSONObject().put("role", "user").put("content", content))
        for (budget in listOf(1400, 1000, 700, 480)) {
            val body = JSONObject()
                .put("model", MODEL)
                .put("messages", messages)
                .put("temperature", 0.1)
                .put("max_tokens", budget)
                .put("response_format", JSONObject().put("type", "json_object"))
            val response = post(apiKey, body)
            if (response.first in 200..299) {
                val envelope = JSONObject(response.second)
                val raw = envelope.getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").getString("content")
                val result = JSONObject(stripFence(raw))
                return AuraAnswer(
                    result.optString("enunciado", "Enunciado no identificado"),
                    result.optString("respuesta", "No se identificó una respuesta clara"),
                    result.optString("explicacion", result.optString("procedimiento", "Sin explicación"))
                )
            }
            val errorText = response.second.lowercase()
            val isOutputLimit = response.first == 429 &&
                (errorText.contains("output tokens per minute") || errorText.contains("otpm") ||
                    (errorText.contains("request too large") && errorText.contains("output")))
            if (!isOutputLimit) throw apiError(response.first)
        }
        throw IllegalStateException("Groq agotó el límite temporal de tokens de salida. Espera un minuto y vuelve a intentarlo.")
    }

    private fun post(apiKey: String, body: JSONObject): Pair<Int, String> {
        val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 60_000
            doOutput = true
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        return try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            code to text
        } finally {
            connection.disconnect()
        }
    }

    private fun apiError(code: Int): IllegalStateException = when (code) {
        401 -> IllegalStateException("Groq rechazó la clave API. Revísala en Ajustes.")
        429 -> IllegalStateException("Groq está limitando las consultas de tu cuenta. Espera un momento y vuelve a intentarlo.")
        else -> IllegalStateException("Groq no pudo completar el análisis (HTTP $code). Comprueba Internet e inténtalo de nuevo.")
    }

    private fun stripFence(text: String): String {
        val trimmed = text.trim()
        if (!trimmed.startsWith("```")) return trimmed
        return trimmed.removePrefix("```json").removePrefix("```JSON").removePrefix("```").removeSuffix("```").trim()
    }
}
