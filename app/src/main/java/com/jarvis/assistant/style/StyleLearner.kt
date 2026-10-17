package com.jarvis.assistant.style

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.jarvis.assistant.JarvisApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

/** Dışa aktarılmış sohbetteki tek mesaj. */
data class ChatLine(val sender: String, val text: String)

/**
 * WhatsApp "Sohbeti dışa aktar" dosyalarından kullanıcının kendi mesajlarını ayıklar ve
 * yapay zekaya konuşma tarzını analiz ettirir. Karşı tarafın mesajları yapay zekaya gönderilmez.
 */
object StyleLearner {

    private fun file(ctx: Context) = File(ctx.filesDir, "style_chats.json")

    // Android: "17.10.2026 08:23 - Emir: mesaj"   iOS: "[17.10.2026 08:23:11] Emir: mesaj"
    private val LINE = Regex(
        """^\[?(\d{1,2}[./-]\d{1,2}[./-]\d{2,4}),?\s+(\d{1,2}:\d{2})(?::\d{2})?(?:\s?[APap][Mm])?\]?\s+(?:-\s+)?([^:]{1,40}):\s(.*)$""",
    )
    private val SKIP = listOf(
        "<medya dahil edilmedi>", "<media omitted>", "bu mesaj silindi", "this message was deleted",
        "görüntü dahil edilmedi", "image omitted", "video dahil edilmedi", "ses dahil edilmedi",
        "çıkartma dahil edilmedi", "sticker omitted", "<bu mesaj düzenlendi>", "null",
    )

    /** Seçilen dosyaları (txt ya da zip) okur, mevcut kayda ekler. @return eklenen mesaj sayısı */
    suspend fun import(ctx: Context, uris: List<Uri>): Int = withContext(Dispatchers.IO) {
        val all = load(ctx).toMutableList()
        var added = 0
        for (uri in uris) {
            val text = readText(ctx, uri) ?: continue
            val lines = parse(text)
            all += lines
            added += lines.size
        }
        save(ctx, all.takeLast(30000))
        added
    }

    private fun readText(ctx: Context, uri: Uri): String? = runCatching {
        val name = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }.orEmpty()
        val type = ctx.contentResolver.getType(uri).orEmpty()
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            if (name.endsWith(".zip", true) || type.contains("zip")) {
                // WhatsApp bazen .zip içinde .txt verir.
                val zip = ZipInputStream(input)
                var entry = zip.nextEntry
                while (entry != null) {
                    if (entry.name.endsWith(".txt", true)) return@runCatching zip.readBytes().toString(Charsets.UTF_8)
                    entry = zip.nextEntry
                }
                null
            } else {
                input.readBytes().toString(Charsets.UTF_8)
            }
        }
    }.getOrNull()

    fun parse(text: String): List<ChatLine> {
        val out = mutableListOf<ChatLine>()
        for (raw in text.lines()) {
            val line = raw.replace("‎", "").replace(" ", " ").trimEnd()
            val m = LINE.find(line)
            if (m != null) {
                out += ChatLine(m.groupValues[3].trim(), m.groupValues[4])
            } else if (line.isNotBlank() && out.isNotEmpty()) {
                // Çok satırlı mesajın devamı.
                val last = out.removeAt(out.lastIndex)
                out += last.copy(text = last.text + "\n" + line)
            }
        }
        return out.filter { l -> SKIP.none { l.text.trim().lowercase().contains(it) } && l.text.isNotBlank() }
    }

    fun load(ctx: Context): List<ChatLine> = runCatching {
        val arr = JSONArray(file(ctx).readText())
        (0 until arr.length()).map { arr.getJSONObject(it).let { o -> ChatLine(o.getString("s"), o.getString("t")) } }
    }.getOrDefault(emptyList())

    private fun save(ctx: Context, lines: List<ChatLine>) {
        val arr = JSONArray()
        lines.forEach { arr.put(JSONObject().put("s", it.sender).put("t", it.text)) }
        file(ctx).writeText(arr.toString())
    }

    fun clear(ctx: Context) {
        file(ctx).delete()
    }

    /** Sohbetlerdeki kişiler ve mesaj sayıları (kullanıcı kendini seçsin diye). */
    fun senders(ctx: Context): List<Pair<String, Int>> =
        load(ctx).groupingBy { it.sender }.eachCount().toList().sortedByDescending { it.second }

    /**
     * Kullanıcının mesajlarından konuşma tarzı rehberi çıkarır ve ayarlara kaydeder.
     * Sadece kullanıcının kendi mesajları yapay zekaya gönderilir.
     */
    suspend fun analyze(ctx: Context, me: String): String {
        val mine = load(ctx).filter { it.sender == me }.map { it.text.replace("\n", " ") }
        if (mine.size < 20) return "Analiz için en az 20 mesajınız olmalı (şu an ${mine.size})."
        // Farklı sohbetlerden çeşitli örnekler: en fazla ~12 bin karakter.
        val sample = StringBuilder()
        val step = maxOf(1, mine.size / 600)
        for (i in mine.indices step step) {
            val m = mine[i].take(300)
            if (sample.length + m.length > 12000) break
            sample.append("- ").append(m).append('\n')
        }
        val system = "Sen bir dil ve üslup analistisin. Türkçe yaz."
        val prompt = """
Aşağıda bir kişinin WhatsApp'ta yazdığı mesajlardan örnekler var. Bu kişinin konuşma tarzını analiz et ve
bir sesli asistanın onunla AYNI TARZDA konuşabilmesi için kısa bir "konuşma tarzı rehberi" yaz.

Şunlara bak: hitap şekilleri, sık kullandığı kelime ve kalıplar, argo/kısaltmalar, cümle uzunluğu, resmiyet düzeyi,
espri ve tepki biçimi (ör. "aynen", "hahaha", "kanka"), soru sorma şekli, enerji/ton.

Kurallar:
- 8-12 kısa madde yaz, her madde tek cümle. Somut örnek kelimeler ver.
- Yazım hatalarını ya da Türkçe karakter kullanmamasını kural olarak YAZMA (asistan sesli konuşacak, düzgün Türkçe yazmalı).
- Kişisel bilgi (isim, adres, numara) yazma, sadece üslup.
- Madde işareti olarak "-" kullan, başka açıklama ekleme.

Mesajlar:
$sample
""".trim()
        val guide = JarvisApp.instance.assistant.agent.oneShot(system, prompt).trim()
        if (guide.isBlank()) return "Analiz yapılamadı, tekrar deneyin."
        JarvisApp.instance.settings.styleGuide = guide
        JarvisApp.instance.settings.styleOwner = me
        return guide
    }
}
