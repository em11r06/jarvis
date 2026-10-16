package com.jarvis.assistant.tools

import android.content.Context
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SaatTarihTool : Tool {
    override val name = "saat_tarih"
    override val description = "Şu anki saati, tarihi ve günü verir."
    override val parameters = Schema.empty()

    override suspend fun run(ctx: Context, args: JSONObject): String {
        val f = SimpleDateFormat("d MMMM yyyy EEEE, HH:mm", Locale("tr", "TR"))
        return f.format(Date())
    }
}
