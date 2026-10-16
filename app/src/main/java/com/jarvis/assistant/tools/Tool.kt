package com.jarvis.assistant.tools

import android.content.Context
import com.jarvis.assistant.ai.ToolSpec
import org.json.JSONArray
import org.json.JSONObject

/**
 * Jarvis'in kullanabildiği her araç bu arayüzü uygular.
 * Yeni araç eklemek için: bir sınıf yaz, ToolRegistry listesine ekle.
 */
interface Tool {
    val name: String
    val description: String
    /** JSON Schema biçiminde parametreler. */
    val parameters: JSONObject

    /** Aracı çalıştırır, yapay zekaya gidecek kısa bir metin döndürür. */
    suspend fun run(ctx: Context, args: JSONObject): String

    fun spec() = ToolSpec(name, description, parameters)
}

/** Parametre şeması yazmayı kolaylaştıran küçük yardımcılar. */
object Schema {
    fun obj(vararg props: Pair<String, JSONObject>, required: List<String> = emptyList()): JSONObject {
        val p = JSONObject()
        props.forEach { p.put(it.first, it.second) }
        return JSONObject().put("type", "object").put("properties", p).apply {
            if (required.isNotEmpty()) put("required", JSONArray(required))
        }
    }

    fun empty(): JSONObject = JSONObject().put("type", "object").put("properties", JSONObject())

    fun str(desc: String, enum: List<String>? = null): JSONObject =
        JSONObject().put("type", "string").put("description", desc).apply {
            if (enum != null) put("enum", JSONArray(enum))
        }

    fun int(desc: String): JSONObject = JSONObject().put("type", "integer").put("description", desc)

    fun bool(desc: String): JSONObject = JSONObject().put("type", "boolean").put("description", desc)
}
