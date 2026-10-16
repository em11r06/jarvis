package com.jarvis.assistant.ai

import org.json.JSONArray
import org.json.JSONObject

/**
 * Google Gemini REST (generateContent) istemcisi.
 * ÖNEMLİ: Gemini 3 modelleri fonksiyon çağrısı yaptığında parçalarda
 * "thoughtSignature" döner. Model cevabını (content) hiç değiştirmeden
 * geçmişe koyup geri göndermek gerekir; bu yüzden ham JSON saklanır.
 */
class GeminiProvider(
    private val apiKey: () -> String,
    private val model: () -> String,
) : AiProvider {

    override val id = "gemini"

    override suspend fun complete(system: String, history: List<Turn>, tools: List<ToolSpec>): AiReply {
        val key = apiKey()
        if (key.isBlank()) throw AiException("Gemini API anahtarı girilmemiş. Ayarlardan ekleyin.")

        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", buildContents(history))
        if (tools.isNotEmpty()) {
            val decls = JSONArray()
            tools.forEach {
                decls.put(JSONObject().put("name", it.name).put("description", it.description).put("parameters", it.parameters))
            }
            body.put("tools", JSONArray().put(JSONObject().put("functionDeclarations", decls)))
        }
        // Sesli asistan için hız önemli: düşük düşünme seviyesi.
        body.put("generationConfig", JSONObject().put("thinkingConfig", JSONObject().put("thinkingLevel", "low")))

        val url = "https://generativelanguage.googleapis.com/v1beta/models/${model()}:generateContent"
        val headers = mapOf("x-goog-api-key" to key)
        var res = Http.postJson(url, body.toString(), headers)
        if (res.code == 400 && res.body.contains("thinking", ignoreCase = true)) {
            // Eski modeller thinkingLevel desteklemiyor; ayarsız tekrar dene.
            body.remove("generationConfig")
            res = Http.postJson(url, body.toString(), headers)
        }
        if (res.code !in 200..299) throw AiException("Gemini hatası (${res.code}): ${errorMessage(res.body)}")

        val json = JSONObject(res.body)
        val cand = json.optJSONArray("candidates")?.optJSONObject(0)
            ?: throw AiException("Gemini boş cevap verdi: ${json.optJSONObject("promptFeedback")?.optString("blockReason") ?: ""}")
        val content = cand.optJSONObject("content")
            ?: throw AiException("Gemini cevap üretemedi (${cand.optString("finishReason")}).")
        if (!content.has("role")) content.put("role", "model")

        val parts = content.optJSONArray("parts") ?: JSONArray()
        val text = StringBuilder()
        val calls = mutableListOf<ToolCall>()
        for (i in 0 until parts.length()) {
            val p = parts.getJSONObject(i)
            if (p.optBoolean("thought", false)) continue
            if (p.has("text")) text.append(p.getString("text"))
            p.optJSONObject("functionCall")?.let { fc ->
                calls += ToolCall(
                    id = fc.optString("id").ifBlank { "local_${System.nanoTime()}_$i" },
                    name = fc.optString("name"),
                    args = fc.optJSONObject("args") ?: JSONObject(),
                )
            }
        }
        return AiReply(Turn.Assistant(text.toString().trim(), calls, content, id))
    }

    private fun buildContents(history: List<Turn>): JSONArray {
        val out = JSONArray()
        var lastWasNative = false
        for (t in history) {
            when (t) {
                is Turn.User -> out.put(textContent("user", t.text))
                is Turn.Assistant -> {
                    lastWasNative = t.rawProvider == id && t.raw != null
                    if (lastWasNative) {
                        out.put(t.raw)
                    } else {
                        out.put(textContent("model", flattenAssistant(t)))
                    }
                }
                is Turn.Tools -> {
                    if (lastWasNative) {
                        val parts = JSONArray()
                        t.results.forEach { r ->
                            val fr = JSONObject().put("name", r.name)
                                .put("response", JSONObject().put("result", r.content))
                            if (!r.callId.startsWith("local_")) fr.put("id", r.callId)
                            parts.put(JSONObject().put("functionResponse", fr))
                        }
                        out.put(JSONObject().put("role", "user").put("parts", parts))
                    } else {
                        out.put(textContent("user", flattenTools(t)))
                    }
                }
            }
        }
        return out
    }

    private fun textContent(role: String, text: String) = JSONObject().put("role", role)
        .put("parts", JSONArray().put(JSONObject().put("text", text.ifBlank { "..." })))

    private fun errorMessage(body: String): String =
        runCatching { JSONObject(body).getJSONObject("error").getString("message") }.getOrDefault(body.take(300))
}

/** Başka sağlayıcıdan kalan araç adımlarını düz metne çevirir. */
internal fun flattenAssistant(t: Turn.Assistant): String {
    if (t.calls.isEmpty()) return t.text
    val names = t.calls.joinToString { "${it.name}(${it.args})" }
    return (t.text + "\n[Araç kullanıldı: $names]").trim()
}

internal fun flattenTools(t: Turn.Tools): String =
    "[Araç sonuçları]\n" + t.results.joinToString("\n") { "${it.name}: ${it.content.take(1500)}" }
