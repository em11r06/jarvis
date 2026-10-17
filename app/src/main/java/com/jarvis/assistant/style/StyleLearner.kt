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
 * WhatsApp ve Instagram sohbet dökümlerinden kullanıcının kendi mesajlarını ayıklar ve
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

    /**
     * Seçilen dosyaları okur, mevcut kayda ekler. Desteklenenler:
     * WhatsApp dışa aktarma (.txt ya da .zip), Instagram "bilgilerini indir" (.zip içinde
     * messages/inbox/.../message_N.json ya da .html), tek tek .json/.html dosyaları.
     * @return eklenen mesaj sayısı
     */
    suspend fun import(ctx: Context, uris: List<Uri>): Int = withContext(Dispatchers.IO) {
        val all = load(ctx).toMutableList()
        var added = 0
        for (uri in uris) {
            val lines = runCatching { readChats(ctx, uri) }.getOrDefault(emptyList())
            all += lines
            added += lines.size
        }
        save(ctx, all.takeLast(30000))
        added
    }

    private fun readChats(ctx: Context, uri: Uri): List<ChatLine> {
        val name = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }.orEmpty()
        val type = ctx.contentResolver.getType(uri).orEmpty()
        val input = ctx.contentResolver.openInputStream(uri) ?: return emptyList()
        return input.use { ins ->
            if (name.endsWith(".zip", true) || type.contains("zip")) {
                val out = mutableListOf<ChatLine>()
                val zip = ZipInputStream(ins.buffered())
                var entry = zip.nextEntry
                while (entry != null) {
                    val n = entry.name.lowercase()
                    val isWa = n.endsWith(".txt")
                    val isIg = (n.contains("messages/") || n.contains("inbox/")) && Regex("message_\\d+\\.(json|html)$").containsMatchIn(n)
                    if (!entry.isDirectory && (isWa || isIg)) {
                        val bytes = readLimited(zip, 8_000_000)
                        out += parseByName(n, bytes.toString(Charsets.UTF_8))
                    }
                    entry = zip.nextEntry
                }
                out
            } else {
                parseByName(name.lowercase(), ins.readBytes().toString(Charsets.UTF_8))
            }
        }
    }

    private fun readLimited(zip: ZipInputStream, max: Int): ByteArray {
        val buf = java.io.ByteArrayOutputStream()
        val tmp = ByteArray(64 * 1024)
        while (buf.size() < max) {
            val r = zip.read(tmp)
            if (r < 0) break
            buf.write(tmp, 0, r)
        }
        return buf.toByteArray()
    }

    private fun parseByName(name: String, text: String): List<ChatLine> = when {
        name.endsWith(".json") -> parseInstagramJson(text)
        name.endsWith(".html") || name.endsWith(".htm") -> parseInstagramHtml(text)
        else -> parse(text)
    }

    /** Instagram JSON: {"messages":[{"sender_name":..,"content":..}]} */
    fun parseInstagramJson(text: String): List<ChatLine> {
        val arr = runCatching { JSONObject(text).optJSONArray("messages") }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val content = o.optString("content").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            ChatLine(fixMojibake(o.optString("sender_name")), fixMojibake(content))
        }.reversed() // Instagram yeniden eskiye verir
            .filter { l -> IG_SKIP.none { l.text.contains(it, ignoreCase = true) } }
    }

    /** Instagram HTML: her mesaj bir kutu; ilk çocuk gönderen, ikincisi içerik. */
    fun parseInstagramHtml(text: String): List<ChatLine> {
        val doc = org.jsoup.Jsoup.parse(text)
        val blocks = doc.select("div.uiBoxWhite, div._a6-g").distinct()
        return blocks.mapNotNull { b ->
            val kids = b.children()
            if (kids.size < 2) return@mapNotNull null
            val sender = kids[0].text().trim()
            val content = kids[1].text().trim()
            if (sender.isBlank() || content.isBlank() || sender.length > 60) null else ChatLine(sender, content)
        }.reversed()
            .filter { l -> IG_SKIP.none { l.text.contains(it, ignoreCase = true) } }
    }

    /**
     * Instagram JSON'unda Türkçe karakterler bozuk gelir ("Ã¼" gibi): UTF-8 baytlar tek tek
     * karakter olarak yazılmış. Baytlara geri çevirip doğru çözüyoruz.
     */
    private fun fixMojibake(s: String): String {
        if (s.none { it.code in 0x80..0xFF }) return s
        if (s.any { it.code > 0xFF }) return s
        return runCatching {
            val bytes = ByteArray(s.length) { s[it].code.toByte() }
            String(bytes, Charsets.UTF_8)
        }.getOrDefault(s)
    }

    private val IG_SKIP = listOf(
        "sent an attachment", "bir ek gönderdi", "liked a message", "bir mesajı beğendi", "reacted", "tepki verdi",
        "shared a story", "bir hikaye paylaştı", "sent a voice message", "sesli mesaj gönderdi", "started a video chat",
        "görüntülü sohbet", "missed", "cevapsız", "this message was unsent", "mesajı geri aldı",
    )

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
