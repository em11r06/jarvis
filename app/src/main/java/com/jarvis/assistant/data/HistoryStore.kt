package com.jarvis.assistant.data

import android.content.Context
import com.jarvis.assistant.ai.FAKE_CALL
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
                cleanFakeCalls()
            }
        }
    }

    /**
     * Eski sürümlerde model, araç çağırmak yerine "[Araç kullanıldı: ...]" metni yazıyordu.
     * Bu satırlar yeni cevaplarda taklit edilmesin diye geçmişten temizlenir.
     */
    private fun cleanFakeCalls() {
        var changed = false
        val cleaned = turns.mapNotNull { t ->
            if (t is Turn.Assistant && t.calls.isEmpty() && FAKE_CALL.containsMatchIn(t.text)) {
                changed = true
                val text = FAKE_CALL.replace(t.text, "").trim()
                if (text.isBlank()) null else t.copy(text = text)
            } else {
                t
            }
        }
        if (changed) {
            turns.clear()
            turns += cleaned
            save()
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
