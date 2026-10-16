package com.jarvis.assistant.data

import android.content.Context
import com.jarvis.assistant.ai.Turn
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Konuşma geçmişini ve özetini telefonda bir JSON dosyasında saklar. */
class HistoryStore(context: Context) {
    private val file = File(context.filesDir, "history.json")

    var summary: String = ""
        private set
    val turns = mutableListOf<Turn>()

    init {
        runCatching {
            if (file.exists()) {
                val o = JSONObject(file.readText())
                summary = o.optString("summary")
                val arr = o.optJSONArray("turns") ?: JSONArray()
                for (i in 0 until arr.length()) Turn.fromJson(arr.getJSONObject(i))?.let { turns += it }
            }
        }
    }

    @Synchronized
    fun save() {
        val arr = JSONArray()
        turns.forEach { arr.put(it.toJson()) }
        runCatching { file.writeText(JSONObject().put("summary", summary).put("turns", arr).toString()) }
    }

    @Synchronized
    fun replaceOld(newSummary: String, keepFrom: Int) {
        summary = newSummary
        val keep = turns.drop(keepFrom)
        turns.clear()
        turns += keep
        save()
    }

    @Synchronized
    fun clear() {
        summary = ""
        turns.clear()
        save()
    }
}
