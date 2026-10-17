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
data class ChatLine(
    val sender: String,
    val text: String,
    /** Bu mesaja gülme tepkisi (😂 vb.) bırakanlar, virgülle ayrılmış (Instagram). */
    val laughBy: String = "",
    /** Gönderenin paylaştığı bir gönderi/reels açıklaması mı? */
    val shared: Boolean = false,
)

/**
 * WhatsApp ve Instagram sohbet dökümlerinden kullanıcının kendi mesajlarını ayıklar ve
 * yapay zekaya konuşma tarzını analiz ettirir. Karşı tarafın mesajlarından sadece kullanıcının GÜLDÜĞÜ
 * kısa espriler (isimsiz) mizah analizi için gönderilir; diğerleri telefonda kalır.
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
            // Aynı dosya tekrar yüklenirse mesajlar iki kez sayılmasın.
            val seen = all.mapTo(HashSet()) { it.sender + "\u0000" + it.text }
            val fresh = lines.filter { seen.add(it.sender + "\u0000" + it.text) }
            all += fresh
            added += fresh.size
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
                    // Takip edilenler listesi: ilgi alanlarını çıkarmak için.
                    if (!entry.isDirectory && Regex("(^|/)following\\.(json|html)$").containsMatchIn(n)) {
                        val txt = readLimited(zip, 8_000_000).toString(Charsets.UTF_8)
                        val names = if (n.endsWith(".json")) parseFollowingJson(txt) else parseFollowingHtml(txt)
                        lastFollowingCount += addFollowing(ctx, names)
                    }
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
        return (0 until arr.length()).flatMap { i ->
            val o = arr.getJSONObject(i)
            val sender = fixMojibake(o.optString("sender_name"))
            val out = mutableListOf<ChatLine>()
            // Paylaşılan reels/gönderi açıklaması: kişinin hoşuna giden içerik türü.
            o.optJSONObject("share")?.optString("share_text")?.takeIf { it.isNotBlank() }?.let {
                out += ChatLine(sender, fixMojibake(it).take(500), shared = true)
            }
            val content = o.optString("content").takeIf { it.isNotBlank() }
            if (content != null) {
                // Bu mesaja gülme tepkisi bırakanlar.
                val reactions = o.optJSONArray("reactions")
                val laughers = (0 until (reactions?.length() ?: 0)).mapNotNull { r ->
                    val re = reactions!!.getJSONObject(r)
                    val emoji = fixMojibake(re.optString("reaction"))
                    if (LAUGH_EMOJI.any { emoji.contains(it) }) fixMojibake(re.optString("actor")) else null
                }
                out += ChatLine(sender, fixMojibake(content), laughBy = laughers.joinToString(","))
            }
            out.reversed()
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
        (0 until arr.length()).map {
            arr.getJSONObject(it).let { o ->
                ChatLine(o.getString("s"), o.getString("t"), o.optString("l"), o.optBoolean("h", false))
            }
        }
    }.getOrDefault(emptyList())

    private fun save(ctx: Context, lines: List<ChatLine>) {
        val arr = JSONArray()
        lines.forEach {
            val o = JSONObject().put("s", it.sender).put("t", it.text)
            if (it.laughBy.isNotBlank()) o.put("l", it.laughBy)
            if (it.shared) o.put("h", true)
            arr.put(o)
        }
        file(ctx).writeText(arr.toString())
    }

    private val LAUGH_EMOJI = listOf("😂", "🤣", "💀", "😹", "😆", "😅")

    /** Kısa bir "gülme" cevabı mı? (hahaha, jsjs, koptum, 😂 ...) */
    private val LAUGH_REPLY = Regex(
        "(?i)(\\b(a?h[ae]){2,}|hah|hehe|jsjs|sksk|asdf|kopt|öldüm|gülmekten|gulmekten|😂|🤣|💀|😹|😆)",
    )

    /**
     * Kullanıcının güldüğü başkalarına ait mesajlar: ya gülme tepkisi bırakmış (Instagram)
     * ya da hemen ardından kısa bir gülme cevabı yazmış.
     */
    private fun funnyForMe(all: List<ChatLine>, names: Set<String>): List<String> {
        val out = LinkedHashSet<String>()
        all.forEachIndexed { i, l ->
            if (l.sender in names || l.shared) return@forEachIndexed
            val reacted = l.laughBy.split(',').any { it.trim() in names }
            val next = all.getOrNull(i + 1)
            val laughedAfter = next != null && next.sender in names && next.text.length <= 40 && LAUGH_REPLY.containsMatchIn(next.text)
            if ((reacted || laughedAfter) && l.text.length in 8..400) out += l.text.replace("\n", " ")
        }
        return out.toList()
    }

    fun clear(ctx: Context) {
        file(ctx).delete()
        followingFile(ctx).delete()
    }

    // ------------------------------------------------------------------
    // Takip edilen hesaplar (Instagram)
    // ------------------------------------------------------------------

    /** Son analizde hangi addan kaç mesaj kullanıldığı (ekranda göstermek için). */
    @Volatile
    var lastUsage = ""

    /** Son içe aktarmada eklenen takip edilen hesap sayısı (ekranda göstermek için). */
    @Volatile
    var lastFollowingCount = 0

    private fun followingFile(ctx: Context) = File(ctx.filesDir, "style_following.json")

    fun loadFollowing(ctx: Context): List<String> = runCatching {
        val arr = JSONArray(followingFile(ctx).readText())
        (0 until arr.length()).map { arr.getString(it) }
    }.getOrDefault(emptyList())

    /** @return yeni eklenen hesap sayısı */
    private fun addFollowing(ctx: Context, names: List<String>): Int {
        val set = LinkedHashSet(loadFollowing(ctx))
        val before = set.size
        names.map { it.trim().removePrefix("@") }.filter { it.isNotBlank() && it.length <= 40 }.forEach { set += it }
        followingFile(ctx).writeText(JSONArray(set.toList()).toString())
        return set.size - before
    }

    /** {"relationships_following":[{"title":"kullanici","string_list_data":[{"value":"kullanici"}]}]} ya da düz dizi. */
    private fun parseFollowingJson(text: String): List<String> {
        val root = runCatching { org.json.JSONTokener(text).nextValue() }.getOrNull() ?: return emptyList()
        val arr = when (root) {
            is JSONObject -> root.optJSONArray("relationships_following") ?: root.keys().asSequence().mapNotNull { root.optJSONArray(it) }.firstOrNull()
            is JSONArray -> root
            else -> null
        } ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            o.optJSONArray("string_list_data")?.optJSONObject(0)?.optString("value")?.takeIf { it.isNotBlank() }
                ?: o.optString("title").takeIf { it.isNotBlank() }
        }.map { fixMojibake(it) }
    }

    private fun parseFollowingHtml(text: String): List<String> =
        org.jsoup.Jsoup.parse(text).select("a[href*=instagram.com]").map { it.text().trim() }.filter { it.isNotBlank() }

    /** Sohbetlerdeki kişiler ve mesaj sayıları (kullanıcı kendini seçsin diye). */
    fun senders(ctx: Context): List<Pair<String, Int>> =
        load(ctx).groupingBy { it.sender }.eachCount().toList().sortedByDescending { it.second }

    /**
     * Kullanıcının mesajlarından konuşma tarzı rehberi çıkarır ve ayarlara kaydeder.
     * Sadece kullanıcının kendi mesajları yapay zekaya gönderilir.
     */
    /**
     * @param me kullanıcının sohbetlerdeki adları (WhatsApp ve Instagram'da farklı olabilir), virgülle ayrılmış
     */
    suspend fun analyze(ctx: Context, me: String): String {
        val names = me.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val mineLines = load(ctx).filter { it.sender in names && !it.shared }
        if (mineLines.size < 20) return "Analiz için en az 20 mesajınız olmalı (şu an ${mineLines.size})."
        // Her ad (WhatsApp adı, Instagram adı...) için eşit pay ayır ve her birinin tamamından eşit aralıklı örnek al;
        // böylece bir platformun mesajları diğerini bastırmaz, ikisi harmanlanır.
        val groups = mineLines.groupBy { it.sender }.mapValues { (_, v) -> v.map { it.text.replace("\n", " ") } }
        val perGroup = 12000 / groups.size
        val used = mutableMapOf<String, Int>()
        val picked = mutableListOf<String>()
        for ((name, msgs) in groups) {
            val budgetCount = minOf(msgs.size, maxOf(20, perGroup / 40))
            val step = msgs.size.toDouble() / budgetCount
            var chars = 0
            var count = 0
            var i = 0.0
            while (i < msgs.size && chars < perGroup) {
                val m = msgs[i.toInt()].take(300)
                picked += m
                chars += m.length + 3
                count++
                i += step
            }
            used[name] = count
        }
        val sample = StringBuilder()
        picked.shuffled().forEach { sample.append("- ").append(it).append('\n') }
        lastUsage = used.entries.joinToString(", ") { "${it.key}: ${it.value} mesaj" }
        // Güldüğü espriler ve paylaştığı içerikler (mizah tarzı için).
        val all = load(ctx)
        val funny = funnyForMe(all, names).shuffled().fold(StringBuilder()) { b, t ->
            if (b.length + t.length < 3500) b.append("- ").append(t.take(300)).append('\n')
            b
        }
        val shares = all.filter { it.shared && it.sender in names }.map { it.text.replace("\n", " ") }.distinct()
            .shuffled().fold(StringBuilder()) { b, t ->
                if (b.length + t.length < 2500) b.append("- ").append(t.take(250)).append('\n')
                b
            }
        val following = loadFollowing(ctx).shuffled().take(600).joinToString(", ").take(6000)
        val humor = buildString {
            if (funny.isNotEmpty()) append("\n\nKişinin GÜLDÜĞÜ mesajlar (başkalarının yazdığı espriler):\n").append(funny)
            if (shares.isNotEmpty()) append("\n\nKişinin arkadaşlarına PAYLAŞTIĞI içeriklerin açıklamaları:\n").append(shares)
            if (following.isNotEmpty()) append("\n\nKişinin Instagram'da TAKİP ETTİĞİ hesaplar (kullanıcı adları):\n").append(following)
        }
        val system = "Sen bir dil ve üslup analistisin. Türkçe yaz."
        val prompt = """
Aşağıda AYNI kişinin WhatsApp ve Instagram'da (farklı platformlarda) yazdığı mesajlardan karışık örnekler var; ikisini harmanlayıp tek bir tarz çıkar. Bu kişinin konuşma tarzını analiz et ve
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
$humor

Güldüğü mesajlar ya da paylaştığı içerikler verildiyse, sonuna "Mizah tarzı:" başlıklı 4-6 madde daha ekle:
ne tür esprilere güldüğünü (absürt, kara mizah, laf sokma, futbol, kelime oyunu vb.), sevdiği konuları ve asistanın
ona nasıl espri yapması gerektiğini yaz. Espriyi aynen kopyalama, türünü anlat.

Takip ettiği hesaplar verildiyse, en sona "İlgi alanları:" başlıklı 3-6 madde ekle: tuttuğu takım, sevdiği müzik/sanatçılar,
hobiler, takip ettiği mizah sayfaları gibi. Sadece ünlü/sayfa/marka hesaplarından çıkarım yap; kişisel arkadaş hesaplarını
ve kullanıcı adlarını yazma. Asistan bu konuları sohbette ve esprilerde kullanabilsin.
""".trim()
        val guide = JarvisApp.instance.assistant.agent.oneShot(system, prompt).trim()
        if (guide.isBlank()) return "Analiz yapılamadı, tekrar deneyin."
        JarvisApp.instance.settings.styleGuide = guide
        JarvisApp.instance.settings.styleOwner = me
        return guide
    }
}
