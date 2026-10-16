package com.jarvis.assistant.ai

import org.json.JSONArray
import org.json.JSONObject

/** Yapay zekanın çağırmak istediği bir araç (fonksiyon). */
data class ToolCall(val id: String, val name: String, val args: JSONObject)

/** Aracın çalışınca döndürdüğü sonuç. */
data class ToolResult(val callId: String, val name: String, val content: String)

/** Yapay zekaya tanıtılan araç tanımı. parameters bir JSON Schema'dır. */
data class ToolSpec(val name: String, val description: String, val parameters: JSONObject)

/**
 * Konuşma geçmişindeki bir adım.
 * Asistan adımlarında sağlayıcının döndürdüğü ham içerik (raw) saklanır ve
 * aynı sağlayıcıya AYNEN geri gönderilir (Gemini 3'ün thoughtSignature şartı).
 */
sealed class Turn {
    data class User(val text: String) : Turn()
    data class Assistant(
        val text: String,
        val calls: List<ToolCall>,
        val raw: JSONObject? = null,
        val rawProvider: String? = null,
    ) : Turn()
    data class Tools(val results: List<ToolResult>) : Turn()

    fun toJson(): JSONObject = when (this) {
        is User -> JSONObject().put("t", "user").put("text", text)
        is Assistant -> JSONObject().put("t", "assistant").put("text", text)
            .put("calls", JSONArray().apply {
                calls.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("args", it.args)) }
            })
            .put("raw", raw ?: JSONObject.NULL)
            .put("rawProvider", rawProvider ?: JSONObject.NULL)
        is Tools -> JSONObject().put("t", "tools").put("results", JSONArray().apply {
            results.forEach { put(JSONObject().put("id", it.callId).put("name", it.name).put("content", it.content)) }
        })
    }

    companion object {
        fun fromJson(o: JSONObject): Turn? = when (o.optString("t")) {
            "user" -> User(o.optString("text"))
            "assistant" -> {
                val arr = o.optJSONArray("calls") ?: JSONArray()
                Assistant(
                    text = o.optString("text"),
                    calls = (0 until arr.length()).map {
                        val c = arr.getJSONObject(it)
                        ToolCall(c.optString("id"), c.optString("name"), c.optJSONObject("args") ?: JSONObject())
                    },
                    raw = o.optJSONObject("raw"),
                    rawProvider = if (o.isNull("rawProvider")) null else o.optString("rawProvider"),
                )
            }
            "tools" -> {
                val arr = o.optJSONArray("results") ?: JSONArray()
                Tools((0 until arr.length()).map {
                    val r = arr.getJSONObject(it)
                    ToolResult(r.optString("id"), r.optString("name"), r.optString("content"))
                })
            }
            else -> null
        }
    }
}

/** Sağlayıcının tek bir cevabı. */
data class AiReply(val turn: Turn.Assistant)

class AiException(message: String) : Exception(message)

/** Her yapay zeka sağlayıcısı bu arayüzü uygular. */
interface AiProvider {
    val id: String

    suspend fun complete(system: String, history: List<Turn>, tools: List<ToolSpec>): AiReply
}
