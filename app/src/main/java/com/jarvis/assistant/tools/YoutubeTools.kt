package com.jarvis.assistant.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.jarvis.assistant.ai.Http
import com.jarvis.assistant.core.Launcher
import org.json.JSONObject
import java.net.URLEncoder

/**
 * YouTube'da videoyu arka planda bulur ve uygulamayı doğrudan o videoda açar
 * (ekranda arama yapıp sonuca tıklamaya gerek kalmaz).
 */
class YoutubeAcTool : Tool {
    override val name = "youtube_ac"
    override val description =
        "YouTube'da bir videoyu bulup doğrudan açar ve oynatır. YouTube'da video açmak için HER ZAMAN bunu kullan; ekran araçlarıyla arama yapma. " +
            "Yanlış video açılırsa aynı sorguyla sira=2, 3... ver."
    override val parameters = Schema.obj(
        "sorgu" to Schema.str("Aranacak video, ör. 'Barış Manço Arkadaşım Eşek'"),
        "sira" to Schema.int("Kaçıncı sonuç açılsın (varsayılan 1)"),
        required = listOf("sorgu"),
    )

    override suspend fun run(ctx: Context, args: JSONObject): String {
        val q = args.optString("sorgu").trim()
        if (q.isEmpty()) return "Hata: sorgu boş."
        val index = (args.optInt("sira", 1) - 1).coerceAtLeast(0)
        val results = runCatching { searchYoutube(q) }.getOrDefault(emptyList())
            .ifEmpty { runCatching { searchViaDuckDuckGo(q) }.getOrDefault(emptyList()) }
        if (results.isEmpty()) {
            // Son çare: YouTube'u arama sonuçlarıyla aç.
            val search = Intent(Intent.ACTION_SEARCH).setPackage(YT).putExtra("query", q)
            return if (Launcher.start(ctx, search)) "Videoyu kesin bulamadım, YouTube'da '$q' aramasını açtım." else "YouTube açılamadı."
        }
        val video = results.getOrNull(index) ?: return "Sadece ${results.size} sonuç var."
        val app = Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:${video.id}")).setPackage(YT)
        val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=${video.id}"))
        if (!Launcher.start(ctx, app) && !Launcher.start(ctx, web)) return "YouTube açılamadı."
        val others = results.drop(index + 1).take(3).joinToString("; ") { it.title }
        return "Açılan video: ${video.title}${video.channel?.let { " ($it)" } ?: ""}." +
            if (others.isNotBlank()) " Sonraki sonuçlar: $others" else ""
    }

    private data class Video(val id: String, val title: String, val channel: String?)

    /** YouTube arama sayfasındaki ytInitialData'dan video sonuçlarını çıkarır (reklamlar hariç). */
    private suspend fun searchYoutube(q: String): List<Video> {
        val url = "https://www.youtube.com/results?search_query=${URLEncoder.encode(q, "UTF-8")}&hl=tr&gl=TR"
        val res = Http.get(
            url,
            mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0 Safari/537.36",
                "Accept-Language" to "tr-TR,tr;q=0.9",
                // Çerez onay sayfasına düşmemek için.
                "Cookie" to "SOCS=CAI; CONSENT=YES+1",
            ),
        )
        if (res.code !in 200..299) return emptyList()
        val out = mutableListOf<Video>()
        val re = Regex("\"videoRenderer\":\\{\"videoId\":\"([\\w-]{11})\"")
        for (m in re.findAll(res.body)) {
            val id = m.groupValues[1]
            if (out.any { it.id == id }) continue
            // Bu videoRenderer bloğunun yakınındaki başlık ve kanal adı.
            val chunk = res.body.substring(m.range.first, minOf(res.body.length, m.range.first + 6000))
            val title = Regex("\"title\":\\{\"runs\":\\[\\{\"text\":\"(.*?)\"\\}").find(chunk)?.groupValues?.get(1)
                ?: Regex("\"title\":\\{.*?\"simpleText\":\"(.*?)\"").find(chunk)?.groupValues?.get(1)
                ?: "Video"
            val channel = Regex("\"ownerText\":\\{\"runs\":\\[\\{\"text\":\"(.*?)\"").find(chunk)?.groupValues?.get(1)
            out += Video(id, unescape(title), channel?.let { unescape(it) })
            if (out.size >= 6) break
        }
        return out
    }

    /** Yedek: DuckDuckGo'da YouTube izleme bağlantısı arar. */
    private suspend fun searchViaDuckDuckGo(q: String): List<Video> {
        val text = WebAraTool().run(com.jarvis.assistant.JarvisApp.instance, JSONObject().put("sorgu", "$q site:youtube.com"))
        val re = Regex("youtube\\.com/watch\\?v=([\\w-]{11})")
        return text.lines().mapNotNull { line ->
            re.find(line)?.groupValues?.get(1)
        }.distinct().map { Video(it, q, null) }
    }

    private fun unescape(s: String) = runCatching { JSONObject("{\"v\":\"$s\"}").getString("v") }.getOrDefault(s)

    companion object {
        const val YT = "com.google.android.youtube"
    }
}
