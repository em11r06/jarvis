package com.jarvis.assistant.ai

import org.json.JSONArray
import org.json.JSONObject

/** Anthropic Claude Messages API istemcisi. */
class ClaudeProvider(
    private val apiKey: () -> String,
    private val model: () -> String,
) : AiProvider {

    override val id = "claude"

    override suspend fun complete(system: String, history: List<Turn>, tools: List<ToolSpec>): AiReply {
        val key = apiKey()
        if (key.isBlank()) throw AiException("Claude API anahtarı girilmemiş. Ayarlardan ekleyin.")

        val body = JSONObject()
            .put("model", model())
            .put("max_tokens", 1024)
            .put("system", system)
            .put("messages", buildMessages(history))
        if (tools.isNotEmpty()) {
            val arr = JSONArray()
            tools.forEach {
                arr.put(JSONObject().put("name", it.name).put("description", it.description).put("input_schema", it.parameters))
            }
            body.put("tools", arr)
        }

        val res = Http.postJson(
            "https://api.anthropic.com/v1/messages",
            body.toString(),
            mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01"),
        )
        if (res.code !in 200..299) {
            val msg = runCatching { JSONObject(res.body).getJSONObject("error").getString("message") }
                .getOrDefault(res.body.take(300))
            throw AiException("Claude hatası (${res.code}): $msg")
        }

        val json = JSONObject(res.body)
        val content = json.optJSONArray("content") ?: JSONArray()
        val text = StringBuilder()
        val calls = mutableListOf<ToolCall>()
        for (i in 0 until content.length()) {
            val b = content.getJSONObject(i)
            when (b.optString("type")) {
                "text" -> text.append(b.optString("text"))
                "tool_use" -> calls += ToolCall(b.optString("id"), b.optString("name"), b.optJSONObject("input") ?: JSONObject())
            }
        }
        val raw = JSONObject().put("role", "assistant").put("content", content)
        return AiReply(Turn.Assistant(text.toString().trim(), calls, raw, id))
    }

    /** Claude'da roller sırayla gelmeli; aynı rolden ardışık mesajlar birleştirilir. */
    private fun buildMessages(history: List<Turn>): JSONArray {
        val msgs = mutableListOf<Pair<String, JSONArray>>()
        fun add(role: String, blocks: JSONArray) {
            val last = msgs.lastOrNull()
            if (last != null && last.first == role) {
                for (i in 0 until blocks.length()) last.second.put(blocks.get(i))
            } else {
                msgs += role to blocks
            }
        }
        fun textBlock(t: String) = JSONArray().put(JSONObject().put("type", "text").put("text", t.ifBlank { "..." }))

        var lastWasNative = false
        for (t in history) {
            when (t) {
                is Turn.User -> add("user", textBlock(t.text))
                is Turn.Assistant -> {
                    lastWasNative = t.rawProvider == id && t.raw != null
                    if (lastWasNative) {
                        add("assistant", JSONArray(t.raw!!.getJSONArray("content").toString()))
                    } else if (t.calls.isNotEmpty()) {
                        // Başka sağlayıcıdan gelen araç çağrısını gerçek tool_use olarak ver (taklit edilmesin).
                        val blocks = JSONArray()
                        if (t.text.isNotBlank()) blocks.put(JSONObject().put("type", "text").put("text", t.text))
                        t.calls.forEach {
                            blocks.put(JSONObject().put("type", "tool_use").put("id", safeId(it.id)).put("name", it.name).put("input", it.args))
                        }
                        add("assistant", blocks)
                        lastWasNative = true
                    } else {
                        add("assistant", textBlock(flattenAssistant(t)))
                    }
                }
                is Turn.Tools -> {
                    if (lastWasNative) {
                        val blocks = JSONArray()
                        t.results.forEach {
                            blocks.put(JSONObject().put("type", "tool_result").put("tool_use_id", safeId(it.callId)).put("content", it.content))
                        }
                        add("user", blocks)
                    } else {
                        add("user", textBlock(flattenTools(t)))
                    }
                }
            }
        }
        // İlk mesaj kullanıcıdan olmalı.
        while (msgs.isNotEmpty() && msgs.first().first != "user") msgs.removeAt(0)
        return JSONArray().apply { msgs.forEach { put(JSONObject().put("role", it.first).put("content", it.second)) } }
    }

    /** Claude araç kimliklerinde sadece harf, rakam, _ ve - kabul ediyor. */
    private fun safeId(id: String) = id.replace(Regex("[^a-zA-Z0-9_-]"), "_").ifBlank { "call" }
}
