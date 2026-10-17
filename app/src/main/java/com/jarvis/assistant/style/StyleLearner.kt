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
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipInputStream

/** Dışa aktarılmış sohbetteki tek mesaj. */
data class ChatLine(
    val sender: String,
    val text: String,
    /** Gönderenin paylaştığı bir gönderi/reels açıklaması mı? */
    val shared: Boolean = false,
    /** Mesajın geldiği hesap/dosya (ör. "WhatsApp", "Instagram (kullanici)"); aynı ad farklı hesaplarda olabilir. */
    val src: String = "",
)

/**
 * WhatsApp ve Instagram sohbet dökümlerinden kullanıcının kendi mesajlarını ayıklar ve
 * yapay zekaya konuşma tarzını analiz ettirir. Yapay zekaya SADECE kullanıcının kendi mesajları,
 * kendi paylaştığı içerik açıklamaları ve takip ettiği hesaplar gider; başkalarının mesajları telefonda kalır.
 */
object StyleLearner {

    /** Analizde yapay zekaya gönderilen en fazla mesaj metni (karakter). */
    private const val TOTAL_CHARS = 200_000
    /** Telefonda saklanan en fazla mesaj sayısı. */
    private const val MAX_LINES = 150_000
    private const val MAX_MSG = 500

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
        // Aynı dosya tekrar yüklenirse mesajlar iki kez sayılmasın.
        val index = HashMap<String, Int>()
        all.forEachIndexed { i, l -> index[l.sender + "\u0000" + l.text] = i }
        var added = 0
        var tagged = 0
        lastSources = ""
        for (uri in uris) {
            val (raw, src) = runCatching { readChats(ctx, uri) }.getOrDefault(emptyList<ChatLine>() to "")
            val lines = raw.map { it.copy(src = src) }
            if (lines.isNotEmpty()) lastSources = listOf(lastSources, src).filter { it.isNotBlank() }.joinToString(", ")
            for (l in lines) {
                val key = l.sender + "\u0000" + l.text
                val old = index[key]
                if (old == null) {
                    index[key] = all.size
                    all += l
                    added++
                } else if (all[old].src.isBlank() && l.src.isNotBlank()) {
                    // Eski sürümde yüklenen mesaj: hangi hesaptan geldiğini şimdi öğreniyoruz.
                    all[old] = all[old].copy(src = l.src)
                    tagged++
                }
            }
        }
        if (added > 0 || tagged > 0) save(ctx, all.takeLast(MAX_LINES))
        lastTagged = tagged
        added
    }

    /** Son içe aktarmada hesabı belirlenen (önceden yüklenmiş) mesaj sayısı. */
    @Volatile
    var lastTagged = 0

    /** Son içe aktarılan dosyaların kaynak adları (ekranda göstermek için). */
    @Volatile
    var lastSources = ""

    /** Dosyadaki mesajlar ve kaynağın adı (WhatsApp ya da hangi Instagram hesabı). */
    private fun readChats(ctx: Context, uri: Uri): Pair<List<ChatLine>, String> {
        val name = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }.orEmpty()
        val type = ctx.contentResolver.getType(uri).orEmpty()
        val input = ctx.contentResolver.openInputStream(uri) ?: return emptyList<ChatLine>() to ""
        var igSeen = false
        val lines = input.use { ins ->
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
                    if (isIg) igSeen = true
                    if (!entry.isDirectory && (isWa || isIg)) {
                        val bytes = readLimited(zip, 8_000_000)
                        out += parseByName(n, bytes.toString(Charsets.UTF_8))
                    }
                    entry = zip.nextEntry
                }
                out
            } else {
                val n = name.lowercase()
                if (n.endsWith(".json") || n.endsWith(".html") || n.endsWith(".htm")) igSeen = true
                parseByName(n, ins.readBytes().toString(Charsets.UTF_8))
            }
        }
        return lines to sourceName(name, igSeen)
    }

    /**
     * Instagram dışa aktarma dosyası genelde "instagram-KULLANICIADI-2026-10-17-xxxx.zip" adını taşır;
     * oradan hesap adı alınır ki iki Instagram hesabı ayrı ayrı sayılsın.
     */
    private fun sourceName(fileName: String, instagram: Boolean): String {
        if (!instagram) return "WhatsApp"
        val user = Regex("(?i)instagram[-_](.+?)[-_]\\d{4}[-_]\\d{2}").find(fileName)?.groupValues?.get(1)
        return "Instagram (" + (user ?: fileName.substringBeforeLast('.').take(30).ifBlank { "?" }) + ")"
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
                out += ChatLine(sender, fixMojibake(it).take(MAX_MSG), shared = true)
            }
            o.optString("content").takeIf { it.isNotBlank() }?.let { out += ChatLine(sender, fixMojibake(it)) }
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
            val line = raw.replace("‎", "").replace(" ", " ").trimEnd()
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

    /** Kayıt sıkıştırılmış (gzip) tutulur; eski sürümlerin düz JSON kaydı da okunur. */
    fun load(ctx: Context): List<ChatLine> = runCatching {
        val f = file(ctx)
        val bytes = f.readBytes()
        val gz = bytes.size > 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()
        val text = if (gz) GZIPInputStream(bytes.inputStream()).use { it.readBytes() }.toString(Charsets.UTF_8) else bytes.toString(Charsets.UTF_8)
        val arr = JSONArray(text)
        (0 until arr.length()).map {
            arr.getJSONObject(it).let { o -> ChatLine(o.getString("s"), o.getString("t"), o.optBoolean("h", false), o.optString("o")) }
        }
    }.getOrDefault(emptyList())

    private fun save(ctx: Context, lines: List<ChatLine>) {
        val arr = JSONArray()
        lines.forEach {
            val o = JSONObject().put("s", it.sender).put("t", it.text.take(2000))
            if (it.shared) o.put("h", true)
            if (it.src.isNotBlank()) o.put("o", it.src)
            arr.put(o)
        }
        val tmp = File(ctx.filesDir, "style_chats.tmp")
        GZIPOutputStream(tmp.outputStream().buffered()).use { it.write(arr.toString().toByteArray(Charsets.UTF_8)) }
        tmp.renameTo(file(ctx))
    }

    /** Kayıtlı sohbetlerin telefonda kapladığı yer (KB). */
    fun storageKb(ctx: Context): Long = (file(ctx).length() + followingFile(ctx).length()) / 1024

    /**
     * Seçilen adlar dışındaki herkesin mesajlarını telefondan siler (sadece sizinkiler kalır).
     * @return silinen mesaj sayısı
     */
    suspend fun pruneOthers(ctx: Context, me: String): Int = withContext(Dispatchers.IO) {
        val names = ownerNames(me)
        if (names.isEmpty()) return@withContext 0
        val all = load(ctx)
        val mine = all.filter { it.sender in names }
        save(ctx, mine)
        all.size - mine.size
    }

    fun clear(ctx: Context) {
        file(ctx).delete()
        followingFile(ctx).delete()
    }

    private fun ownerNames(me: String) = me.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

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
     * Bütçeyi gruplar arasında adil böler: önce herkese eşit pay, payını dolduramayanın
     * artanı diğerlerine dağıtılır.
     */
    private fun fairShares(sizes: Map<String, Int>, total: Int): Map<String, Int> {
        val out = mutableMapOf<String, Int>()
        var left = total
        var open = sizes.toList().sortedBy { it.second }
        while (open.isNotEmpty()) {
            val share = left / open.size
            val (name, size) = open.first()
            if (size <= share) {
                out[name] = size
                left -= size
                open = open.drop(1)
            } else {
                open.forEach { out[it.first] = share }
                break
            }
        }
        return out
    }

    /** Bir listeden, toplam karakteri bütçeyi aşmayacak şekilde baştan sona eşit aralıklı örnek alır. */
    private fun spread(msgs: List<String>, budget: Int): List<String> {
        val totalLen = msgs.sumOf { it.length + 3 }
        if (totalLen <= budget) return msgs
        val step = totalLen.toDouble() / budget
        val out = mutableListOf<String>()
        var acc = 0.0
        var next = 0.0
        var chars = 0
        for (m in msgs) {
            if (acc >= next && chars + m.length + 3 <= budget) {
                out += m
                chars += m.length + 3
                next += (m.length + 3) * step
            }
            acc += m.length + 3
        }
        return out
    }

    /**
     * Kullanıcının kendi mesajlarından konuşma tarzı rehberi çıkarır ve ayarlara kaydeder.
     * Sadece kullanıcının kendi mesajları yapay zekaya gönderilir.
     * @param me kullanıcının sohbetlerdeki adları (WhatsApp ve her Instagram hesabında farklı olabilir), virgülle ayrılmış
     */
    suspend fun analyze(ctx: Context, me: String): String {
        val names = ownerNames(me)
        val all = withContext(Dispatchers.IO) { load(ctx) }
        val mineLines = all.filter { it.sender in names && !it.shared }
        if (mineLines.size < 20) return "Analiz için en az 20 mesajınız olmalı (şu an ${mineLines.size})."
        // Her ad (WhatsApp adı, 1. ve 2. Instagram adı...) için adil pay ayır ve her birinin tamamından
        // eşit aralıklı örnek al; böylece hiçbir hesap diğerlerini bastırmaz, hepsi harmanlanır.
        // Gruplar ad + hesap: iki Instagram hesabında adınız aynı olsa bile her hesap ayrı pay alır.
        val groups = mineLines.groupBy { if (it.src.isBlank()) it.sender else "${it.sender} · ${it.src}" }.mapValues { (_, v) ->
            v.map { it.text.replace("\n", " ").trim().take(MAX_MSG) }.filter { it.isNotEmpty() }
        }
        val shares = fairShares(groups.mapValues { (_, v) -> v.sumOf { it.length + 3 } }, TOTAL_CHARS)
        val picked = mutableListOf<String>()
        val used = mutableMapOf<String, Int>()
        for ((name, msgs) in groups) {
            val p = spread(msgs, shares[name] ?: 0)
            picked += p
            used[name] = p.size
        }
        val sample = StringBuilder()
        picked.shuffled().forEach { sample.append("- ").append(it).append('\n') }
        lastUsage = used.entries.joinToString(", ") { "${it.key}: ${it.value}/${groups[it.key]?.size ?: 0} mesaj" }
        // Kendi paylaştığı içerikler ve takip ettiği hesaplar (mizah ve ilgi alanları için).
        val sharedText = all.filter { it.shared && it.sender in names }.map { it.text.replace("\n", " ") }.distinct()
            .shuffled().fold(StringBuilder()) { b, t ->
                if (b.length + t.length < 8000) b.append("- ").append(t.take(300)).append('\n')
                b
            }
        val following = loadFollowing(ctx).shuffled().take(1500).joinToString(", ").take(15000)
        val extra = buildString {
            if (sharedText.isNotEmpty()) append("\n\nKişinin arkadaşlarına PAYLAŞTIĞI içeriklerin açıklamaları:\n").append(sharedText)
            if (following.isNotEmpty()) append("\n\nKişinin Instagram'da TAKİP ETTİĞİ hesaplar (kullanıcı adları):\n").append(following)
        }
        val system = "Sen bir dil ve üslup analistisin. Türkçe yaz."
        val prompt = buildString {
            append("Aşağıda AYNI kişinin ${groups.size} farklı hesabından (WhatsApp ve Instagram hesapları) yazdığı mesajlardan karışık örnekler var. ")
            append("Hepsi bu kişinin KENDİ mesajları. Hesapları harmanlayıp tek bir konuşma tarzı çıkar ve bir sesli asistanın onunla ")
            append("AYNI TARZDA konuşabilmesi için bir \"konuşma tarzı rehberi\" yaz.\n\n")
            append("Şunlara bak: hitap şekilleri, sık kullandığı kelime ve kalıplar, argo/kısaltmalar, cümle uzunluğu, resmiyet düzeyi, ")
            append("espri ve tepki biçimi (ör. \"aynen\", \"hahaha\", \"kanka\"), soru sorma şekli, enerji/ton, ruh haline göre değişen tavırları.\n\n")
            append("Kurallar:\n")
            append("- 12-20 kısa madde yaz, her madde tek cümle. Somut örnek kelimeler ver.\n")
            append("- Yazım hatalarını ya da Türkçe karakter kullanmamasını kural olarak YAZMA (asistan sesli konuşacak, düzgün Türkçe yazmalı).\n")
            append("- Kişisel bilgi (isim, adres, numara) yazma, sadece üslup.\n")
            append("- Madde işareti olarak \"-\" kullan, başka açıklama ekleme.\n\n")
            append("Mesajlar:\n").append(sample).append(extra).append("\n\n")
            append("Sonra \"Mizah tarzı:\" başlıklı 4-6 madde ekle: kişinin KENDİ mesajlarındaki esprilerden")
            if (sharedText.isNotEmpty()) append(" ve paylaştığı içeriklerden")
            append(" ne tür mizahı sevdiğini (absürt, kara mizah, laf sokma, futbol, kelime oyunu vb.) ve asistanın ona nasıl ")
            append("espri yapması gerektiğini yaz. Espriyi aynen kopyalama, türünü anlat.\n")
            append("En sona \"İlgi alanları:\" başlıklı 3-6 madde ekle: tuttuğu takım, sevdiği müzik/sanatçılar, hobiler, sık konuştuğu konular.")
            if (following.isNotEmpty()) append(" Takip ettiği hesaplardan sadece ünlü/sayfa/marka olanlardan çıkarım yap; kişisel arkadaş hesaplarını ve kullanıcı adlarını yazma.")
            append(" Asistan bu konuları sohbette ve esprilerde kullanabilsin.")
        }
        val guide = JarvisApp.instance.assistant.agent.oneShot(system, prompt).trim()
        if (guide.isBlank()) return "Analiz yapılamadı, tekrar deneyin."
        JarvisApp.instance.settings.styleGuide = guide
        JarvisApp.instance.settings.styleOwner = me
        return guide
    }
}
