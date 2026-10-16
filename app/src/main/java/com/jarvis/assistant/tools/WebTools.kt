package com.jarvis.assistant.tools

import android.content.Context
import com.jarvis.assistant.ai.Http
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLDecoder
import java.net.URLEncoder

private const val UA = "Mozilla/5.0 (Linux; Android 16; SM-S936B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0 Mobile Safari/537.36"

/** DuckDuckGo HTML sürümünden arama sonuçlarını çeker (ücretsiz, anahtarsız). */
class WebAraTool : Tool {
    override val name = "web_ara"
    override val description = "İnternette arama yapar. Güncel bilgi, haber, hava durumu, fiyat, kişi/yer bilgisi gibi konularda kullan. Başlık, adres ve özet döndürür."
    override val parameters = Schema.obj("sorgu" to Schema.str("Aranacak ifade"), required = listOf("sorgu"))

    override suspend fun run(ctx: Context, args: JSONObject): String {
        val q = args.optString("sorgu").trim()
        if (q.isEmpty()) return "Hata: sorgu boş."
        val url = "https://html.duckduckgo.com/html/?q=${URLEncoder.encode(q, "UTF-8")}&kl=tr-tr"
        val res = Http.get(url, mapOf("User-Agent" to UA, "Accept-Language" to "tr-TR,tr;q=0.9"))
        if (res.code !in 200..299) return "Arama başarısız (${res.code})."
        val doc = Jsoup.parse(res.body)
        val items = doc.select(".result").mapNotNull { r ->
            val a = r.selectFirst(".result__a") ?: return@mapNotNull null
            if (r.hasClass("result--ad")) return@mapNotNull null
            val title = a.text()
            val link = cleanLink(a.attr("href"))
            val snippet = r.selectFirst(".result__snippet")?.text().orEmpty()
            "$title\n$link\n$snippet"
        }.take(6)
        if (items.isEmpty()) return "Sonuç bulunamadı."
        return items.mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("\n\n")
    }

    private fun cleanLink(href: String): String {
        // DuckDuckGo bağlantıları yönlendirme içinde gelir: //duckduckgo.com/l/?uddg=...
        val i = href.indexOf("uddg=")
        if (i >= 0) {
            val enc = href.substring(i + 5).substringBefore('&')
            return runCatching { URLDecoder.decode(enc, "UTF-8") }.getOrDefault(href)
        }
        return if (href.startsWith("//")) "https:$href" else href
    }
}

/** Bir web sayfasının düz metnini okur. */
class SayfaOkuTool : Tool {
    override val name = "sayfa_oku"
    override val description = "Verilen web adresindeki sayfanın metnini okur. web_ara sonuçlarındaki bir sayfanın ayrıntısı gerekince kullan."
    override val parameters = Schema.obj("url" to Schema.str("Sayfa adresi (https://...)"), required = listOf("url"))

    override suspend fun run(ctx: Context, args: JSONObject): String {
        val url = args.optString("url").trim()
        if (!url.startsWith("http")) return "Hata: geçerli bir adres değil."
        val res = Http.get(url, mapOf("User-Agent" to UA, "Accept-Language" to "tr-TR,tr;q=0.9"))
        if (res.code !in 200..299) return "Sayfa açılamadı (${res.code})."
        val doc = Jsoup.parse(res.body, url)
        doc.select("script, style, noscript, nav, footer, header, aside, form, svg").remove()
        val title = doc.title()
        val text = (doc.selectFirst("article") ?: doc.selectFirst("main") ?: doc.body())?.text().orEmpty()
        return "Başlık: $title\n\n" + text.take(5000)
    }
}
