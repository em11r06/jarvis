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

        val toolsJson = if (tools.isEmpty()) null else {
            val decls = JSONArray()
            tools.forEach {
                val d = JSONObject().put("name", it.name).put("description", it.description)
                // Gemini boş "properties" olan nesne şemasını kabul etmiyor; parametresiz araçta şemayı gönderme.
                val props = it.parameters.optJSONObject("properties")
                if (props != null && props.length() > 0) d.put("parameters", it.parameters)
                decls.put(d)
            }
            JSONArray().put(JSONObject().put("functionDeclarations", decls))
        }
        val headers = mapOf("x-goog-api-key" to key)

        // Her Gemini modelinin ayrı ücretsiz kotası var: seçili modelin kotası dolarsa
        // sıradaki modele geçilir. Kotası dolan model bir süre atlanır.
        val now = System.currentTimeMillis()
        val candidates = (listOf(model()) + FALLBACK_MODELS).distinct().filter { (exhausted[it] ?: 0) < now }
        if (candidates.isEmpty()) throw AiException(QUOTA_MSG)

        var res: Http.Result? = null
        var usedModel = candidates.first()
        for (m in candidates) {
            usedModel = m
            val body = JSONObject()
                .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
                .put("contents", buildContents(history, m))
            toolsJson?.let { body.put("tools", it) }
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent"
            // Sesli asistan için hız önemli: en düşük düşünme seviyesi. Model desteklemezse
            // sıradaki ayarı dener (Gemini 2.5 için thinkingBudget 0, en sonda ayarsız).
            for (thinking in THINKING_OPTIONS) {
                if (thinking != null) body.put("generationConfig", JSONObject().put("thinkingConfig", JSONObject(thinking)))
                else body.remove("generationConfig")
                res = Http.postJson(url, body.toString(), headers)
                if (!(res.code == 400 && res.body.contains("thinking", ignoreCase = true))) break
            }
            val code = res!!.code
            when {
                code == 429 -> {
                    // Günlük kota dolduysa saatlerce, dakikalık sınırsa kısa süre atla.
                    val daily = res.body.contains("PerDay", ignoreCase = true) || res.body.contains("free_tier_requests")
                    exhausted[m] = now + if (daily) 6 * 3600_000L else 60_000L
                }
                code == 404 || code >= 500 -> exhausted[m] = now + 10 * 60_000L
                else -> break
            }
        }
        res!!
        if (res.code == 429) throw AiException(QUOTA_MSG)
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
        // Ham içerik sadece aynı modele aynen geri gönderilir (thoughtSignature modele özel).
        return AiReply(Turn.Assistant(text.toString().trim(), calls, content, "$id:$usedModel"))
    }

    private fun buildContents(history: List<Turn>, model: String): JSONArray {
        val out = JSONArray()
        var lastWasNative = false
        for (t in history) {
            when (t) {
                is Turn.User -> out.put(textContent("user", t.text))
                is Turn.Assistant -> {
                    lastWasNative = t.rawProvider == "$id:$model" && t.raw != null
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

    companion object {
        /** Seçili modelin kotası dolunca sırayla denenecek modeller. */
        val FALLBACK_MODELS = listOf(
            "gemini-flash-latest",
            "gemini-flash-lite-latest",
            "gemini-2.5-flash",
            "gemini-2.5-flash-lite",
        )

        private val THINKING_OPTIONS = listOf(
            """{"thinkingLevel":"minimal"}""",
            """{"thinkingLevel":"low"}""",
            """{"thinkingBudget":0}""",
            null,
        )

        /** Kotası dolan modeller ve ne zamana kadar atlanacakları. */
        private val exhausted = java.util.concurrent.ConcurrentHashMap<String, Long>()

        const val QUOTA_MSG = "Gemini'nin bugünkü ücretsiz kotası doldu efendim. Kota her gün saat on civarında yenileniyor. " +
            "Beklemek istemezseniz Ayarlar'dan Claude'a geçebilir ya da Google AI Studio'da faturalandırmayı açabilirsiniz."
    }

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
