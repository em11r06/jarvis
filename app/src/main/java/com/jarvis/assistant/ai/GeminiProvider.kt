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
        // Her istekte en üstteki (seçili) modelden başlanır; kotası yenilenmemiş olanlar atlanır.
        val candidates = (listOf(model()) + FALLBACK_MODELS).distinct().filter { GeminiQuota.isAvailable(it, now) }
        if (candidates.isEmpty()) throw AiException(QUOTA_MSG)

        var res: Http.Result? = null
        var usedModel = candidates.first()
        for (m in candidates) {
            usedModel = m
            val body = JSONObject()
                .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
                .put("contents", buildContents(history, m, dummySignature = true))
            toolsJson?.let { body.put("tools", it) }
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent"
            // Sesli asistan için hız önemli: en düşük düşünme seviyesi. Model desteklemezse
            // sıradaki ayarı dener (Gemini 2.5 için thinkingBudget 0, en sonda ayarsız).
            for (thinking in THINKING_OPTIONS) {
                if (thinking != null) body.put("generationConfig", JSONObject().put("thinkingConfig", JSONObject(thinking)))
                else body.remove("generationConfig")
                res = Http.postJson(url, body.toString(), headers)
                if (res.code == 400 && res.body.contains("signature", ignoreCase = true)) {
                    // Sahte imza kabul edilmezse eski araç adımlarını sade metinle ver.
                    body.put("contents", buildContents(history, m, dummySignature = false))
                    res = Http.postJson(url, body.toString(), headers)
                }
                if (!(res.code == 400 && res.body.contains("thinking", ignoreCase = true))) break
            }
            val code = res!!.code
            when {
                code == 429 -> GeminiQuota.markExhausted(m, res.body, now)
                code == 404 || code >= 500 -> GeminiQuota.markUnavailable(m, 10 * 60_000L, now)
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
        GeminiQuota.markUsed(usedModel)
        // Ham içerik sadece aynı modele aynen geri gönderilir (thoughtSignature modele özel).
        return AiReply(Turn.Assistant(text.toString().trim(), calls, content, "$id:$usedModel"))
    }

    private fun buildContents(history: List<Turn>, model: String, dummySignature: Boolean): JSONArray {
        val out = JSONArray()
        var lastWasNative = false
        val send = recentImages(history)
        for (t in history) {
            when (t) {
                is Turn.User -> out.put(userContent(t, send))
                is Turn.Assistant -> {
                    lastWasNative = t.rawProvider == "$id:$model" && t.raw != null
                    if (lastWasNative) {
                        out.put(t.raw)
                    } else if (t.calls.isNotEmpty() && dummySignature) {
                        // Başka model/sağlayıcıdan gelen araç çağrısını gerçek functionCall olarak ver.
                        // Düz metin verilirse model o metni taklit edip aracı çağırmadan "yaptım" yazıyor.
                        // İmza bu modele ait olmadığı için Google'ın belgelediği sahte imza kullanılır.
                        val parts = JSONArray()
                        if (t.text.isNotBlank()) parts.put(JSONObject().put("text", t.text))
                        t.calls.forEachIndexed { i, c ->
                            val fc = JSONObject().put("name", c.name).put("args", c.args)
                            if (!c.id.startsWith("local_")) fc.put("id", c.id)
                            val part = JSONObject().put("functionCall", fc)
                            if (i == 0) part.put("thoughtSignature", DUMMY_SIGNATURE)
                            parts.put(part)
                        }
                        out.put(JSONObject().put("role", "model").put("parts", parts))
                        lastWasNative = true
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
                        t.results.forEach { r -> r.image?.takeIf { it in send }?.let { imagePart(it) }?.let { parts.put(it) } }
                        out.put(JSONObject().put("role", "user").put("parts", parts))
                    } else {
                        val c = textContent("user", flattenTools(t))
                        t.results.forEach { r -> r.image?.takeIf { it in send }?.let { imagePart(it) }?.let { c.getJSONArray("parts").put(it) } }
                        out.put(c)
                    }
                }
            }
        }
        return out
    }

    private fun imagePart(path: String): JSONObject? = Images.base64(path)?.let {
        JSONObject().put("inlineData", JSONObject().put("mimeType", "image/jpeg").put("data", it))
    }

    private fun userContent(t: Turn.User, send: Set<String>): JSONObject {
        val parts = JSONArray()
        t.images.forEach { p -> if (p in send) imagePart(p)?.let { parts.put(it) } }
        val old = t.images.count { it !in send }
        val text = if (old > 0) "[$old görsel - artık gönderilmiyor] ${t.text}" else t.text
        parts.put(JSONObject().put("text", text.ifBlank { "..." }))
        return JSONObject().put("role", "user").put("parts", parts)
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

        /** Google'ın, başka modelden aktarılan çağrılar için belgelediği imza doğrulamasını atlama değeri. */
        private const val DUMMY_SIGNATURE = "skip_thought_signature_validator"

        private val THINKING_OPTIONS = listOf(
            """{"thinkingLevel":"minimal"}""",
            """{"thinkingLevel":"low"}""",
            """{"thinkingBudget":0}""",
            null,
        )

        const val QUOTA_MSG = "Gemini'nin bugünkü ücretsiz kotası doldu efendim. Kota her gün saat on civarında yenileniyor. " +
            "Beklemek istemezseniz Ayarlar'dan Claude'a geçebilir ya da Google AI Studio'da faturalandırmayı açabilirsiniz."
    }

    private fun errorMessage(body: String): String =
        runCatching { JSONObject(body).getJSONObject("error").getString("message") }.getOrDefault(body.take(300))
}

/** Başka sağlayıcıdan kalan araç adımlarını düz metne çevirir. */
internal fun flattenAssistant(t: Turn.Assistant): String {
    // Sadece söylenen metni ver; araç kalıbı yazılırsa model onu taklit edip aracı çağırmıyor.
    if (t.calls.isEmpty()) return t.text
    return t.text.ifBlank { "Tamam, hallediyorum." }
}

internal fun flattenTools(t: Turn.Tools): String =
    "(Bilgi: az önceki işlemin sonucu: " + t.results.joinToString("; ") { it.content.take(800) } + ")"

/** Zayıf modellerin araç çağırmak yerine düz metin olarak yazdığı sahte çağrı kalıbı. */
internal val FAKE_CALL = Regex("""\[\s*Araç kullanıldı:\s*([a-z_]+)\s*\(?\s*(\{.*?\})?\s*\)?\s*]""", RegexOption.DOT_MATCHES_ALL)
