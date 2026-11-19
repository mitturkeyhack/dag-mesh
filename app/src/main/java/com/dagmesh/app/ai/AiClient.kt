package com.dagmesh.app.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/**
 * OpenAI uyumlu yapay zekâ servisine (EVREN gibi) bağlanan küçük istemci.
 * Ek kütüphane kullanmaz: düz HttpURLConnection + org.json.
 */
object AiClient {

    /** Sohbetteki tek bir satır: "user" ya da "assistant". */
    data class Turn(val role: String, val content: String)

    sealed class Result {
        data class Ok(val text: String) : Result()
        data class Fail(val reason: String) : Result()
    }

    private const val SYSTEM_PROMPT =
        "Sen Dağ Mesh uygulamasının yapay zekâ asistanısın. Kullanıcı büyük ihtimalle dağda, köyde ya da " +
            "şebekenin zayıf olduğu bir yerde; sorusu internetsiz telefonlar üzerinden zincirleme aktarılarak sana ulaştı. " +
            "Bu yüzden Türkçe, kısa ve net cevap ver: en fazla birkaç kısa paragraf ya da madde. " +
            "Markdown, tablo ve emoji kullanma. Bilmediğin bir şeyi uydurma. " +
            "Hayati tehlike, yaralanma ya da kaybolma varsa önce mümkünse 112'yi aramasını, " +
            "Dağ Mesh'teki SOS özelliğini kullanmasını söyle, sonra kısa ilk yardım bilgisini ver."

    /** Kullanıcının girdiği adresi temizler: sondaki "/" ve "/chat/completions" atılır. */
    fun normalizeBaseUrl(raw: String): String {
        var url = raw.trim().trimEnd('/')
        if (url.endsWith("/chat/completions")) url = url.removeSuffix("/chat/completions")
        if (url.isNotEmpty() && !url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"
        return url
    }

    suspend fun chat(
        config: AiConfig,
        history: List<Turn>,
        question: String,
        maxTokens: Int = 700,
    ): Result = withContext(Dispatchers.IO) {
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
        history.forEach { messages.put(JSONObject().put("role", it.role).put("content", it.content)) }
        messages.put(JSONObject().put("role", "user").put("content", question))

        val body = JSONObject()
            .put("model", config.model)
            .put("messages", messages)
            .put("max_tokens", maxTokens)
            .put("temperature", 0.5)
            .put("stream", false)

        when (val response = request(config, "/chat/completions", body.toString(), timeoutMs = 90_000)) {
            is Http.Fail -> Result.Fail(response.reason)
            is Http.Ok -> try {
                val content = JSONObject(response.body)
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .optString("content")
                val clean = cleanup(content)
                if (clean.isBlank()) Result.Fail("Yapay zekâ boş cevap verdi") else Result.Ok(clean)
            } catch (e: Exception) {
                Result.Fail("Cevap okunamadı (beklenmeyen biçim)")
            }
        }
    }

    /** Servisteki modellerin adlarını getirir (ayarlarda seçmek için). */
    suspend fun listModels(config: AiConfig): Pair<List<String>, String?> = withContext(Dispatchers.IO) {
        when (val response = request(config, "/models", null, timeoutMs = 20_000)) {
            is Http.Fail -> emptyList<String>() to response.reason
            is Http.Ok -> try {
                val data = JSONObject(response.body).getJSONArray("data")
                val ids = (0 until data.length()).mapNotNull { data.getJSONObject(it).optString("id").takeIf { id -> id.isNotBlank() } }
                ids.sorted() to (if (ids.isEmpty()) "Model listesi boş geldi" else null)
            } catch (e: Exception) {
                emptyList<String>() to "Model listesi okunamadı"
            }
        }
    }

    // ---------------------------------------------------------------- HTTP

    private sealed class Http {
        data class Ok(val body: String) : Http()
        data class Fail(val reason: String) : Http()
    }

    private fun request(config: AiConfig, path: String, jsonBody: String?, timeoutMs: Int): Http {
        val base = normalizeBaseUrl(config.baseUrl)
        if (base.isEmpty()) return Http.Fail("Yapay zekâ adresi girilmemiş")
        val connection = try {
            URL(base + path).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            return Http.Fail("Adres geçersiz")
        }
        return try {
            connection.connectTimeout = 15_000
            connection.readTimeout = timeoutMs
            connection.setRequestProperty("Accept", "application/json")
            if (config.apiKey.isNotBlank()) connection.setRequestProperty("Authorization", "Bearer ${config.apiKey.trim()}")
            if (jsonBody != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }
            } else {
                connection.requestMethod = "GET"
            }
            val code = connection.responseCode
            if (code in 200..299) {
                Http.Ok(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
            } else {
                val detail = runCatching {
                    connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                }.getOrNull()
                Http.Fail(describeError(code, detail))
            }
        } catch (e: UnknownHostException) {
            Http.Fail("Sunucu bulunamadı (internet yok ya da adres yanlış)")
        } catch (e: SocketTimeoutException) {
            Http.Fail("Sunucu zamanında cevap vermedi")
        } catch (e: IOException) {
            Http.Fail("Bağlantı hatası: ${e.message ?: "bilinmiyor"}")
        } finally {
            connection.disconnect()
        }
    }

    private fun describeError(code: Int, detail: String?): String {
        val message = detail?.let {
            runCatching { JSONObject(it).optJSONObject("error")?.optString("message") }.getOrNull()
                ?: it.take(160)
        }?.takeIf { it.isNotBlank() }
        val base = when (code) {
            401, 403 -> "Anahtar kabul edilmedi ($code)"
            402 -> "Kredi yetersiz (402)"
            404 -> "Adres ya da model bulunamadı (404)"
            429 -> "Çok fazla istek ya da kredi sınırı (429)"
            in 500..599 -> "Sunucu hatası ($code)"
            else -> "Hata ($code)"
        }
        return if (message != null) "$base: $message" else base
    }

    /** Düşünme etiketlerini ve basit Markdown işaretlerini temizler (mesaj balonunda düz metin görünsün). */
    private fun cleanup(raw: String): String = raw
        .replace(Regex("(?s)<think>.*?</think>"), "")
        .replace("**", "")
        .replace(Regex("(?m)^#{1,6}\\s*"), "")
        .replace(Regex("(?m)^\\s*[*-]\\s+"), "• ")
        .trim()
}
