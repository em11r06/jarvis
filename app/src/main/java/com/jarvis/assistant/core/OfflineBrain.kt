package com.jarvis.assistant.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.jarvis.assistant.tools.AlarmKurTool
import com.jarvis.assistant.tools.AramaYapTool
import com.jarvis.assistant.tools.FenerTool
import com.jarvis.assistant.tools.MedyaKontrolTool
import com.jarvis.assistant.tools.PilDurumuTool
import com.jarvis.assistant.tools.SaatTarihTool
import com.jarvis.assistant.tools.SesAyarlaTool
import com.jarvis.assistant.tools.SmsGonderTool
import com.jarvis.assistant.tools.Tool
import com.jarvis.assistant.tools.UygulamaAcTool
import com.jarvis.assistant.tools.ZamanlayiciKurTool
import org.json.JSONObject
import java.io.IOException

/** İnternet var mı? */
object Network {
    fun isOnline(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /** Hata internet bağlantısından mı kaynaklanıyor? */
    fun isNetworkError(e: Throwable): Boolean {
        var c: Throwable? = e
        while (c != null) {
            if (c is IOException) return true
            c = c.cause
        }
        return false
    }
}

/**
 * İnternetsiz beyin: sık kullanılan telefon komutlarını yapay zekaya sormadan,
 * basit kurallarla anlar ve çalıştırır. İnternet yokken ya da bağlantı koptuğunda devreye girer.
 */
class OfflineBrain(private val ctx: Context) {

    /** Onay bekleyen iş (arama, SMS); kullanıcı "evet" derse çalışır. */
    private var pending: Pair<Tool, JSONObject>? = null

    /** @return sesli okunacak cevap; anlaşılmadıysa null */
    suspend fun handle(raw: String): String? {
        val t = raw.lowercase(java.util.Locale("tr", "TR")).trim().trimEnd('.', '!', '?')

        pending?.let { (tool, args) ->
            pending = null
            if (YES.any { t == it || t.startsWith("$it ") }) return tool.run(ctx, args.put("onaylandi", true))
            if (NO.any { t.startsWith(it) }) return "Tamam, vazgeçtim."
        }

        // Saat, tarih, pil
        if (Regex("saat kaç|saati söyle|tarih|bugün günlerden|hangi gün").containsMatchIn(t)) return SaatTarihTool().run(ctx, JSONObject())
        if (Regex("pil|şarj|batarya").containsMatchIn(t)) return PilDurumuTool().run(ctx, JSONObject())

        // Fener
        if (t.contains("fener") || t.contains("flaş")) {
            val off = Regex("kapa|kapat|söndür").containsMatchIn(t)
            return FenerTool().run(ctx, JSONObject().put("durum", if (off) "kapa" else "ac"))
        }

        // Zamanlayıcı: "5 dakikalık zamanlayıcı", "10 dakika sonra uyar"
        Regex("(\\d+)\\s*(saniye|dakika|dk|saat)").find(t)?.takeIf {
            Regex("zamanlayıcı|sayaç|geri sayım|sonra (uyar|haber|hatırlat)|kur").containsMatchIn(t) && !t.contains("alarm")
        }?.let { m ->
            val n = m.groupValues[1].toInt()
            val sec = when (m.groupValues[2]) { "saniye" -> n; "saat" -> n * 3600; else -> n * 60 }
            return ZamanlayiciKurTool().run(ctx, JSONObject().put("saniye", sec))
        }

        // Alarm: "7:30'a alarm kur", "sabah 7 buçuğa alarm", "akşam 8'e alarm"
        if (t.contains("alarm")) {
            val m = Regex("(\\d{1,2})(?:[:.](\\d{2}))?").find(t) ?: return "Alarmı saat kaça kurayım?"
            var h = m.groupValues[1].toInt()
            var min = m.groupValues[2].toIntOrNull() ?: 0
            if (t.contains("buçuk")) min = 30
            if (Regex("akşam|öğleden sonra|gece").containsMatchIn(t) && h in 1..11) h += 12
            return AlarmKurTool().run(ctx, JSONObject().put("saat", h).put("dakika", min))
        }

        // Ses
        if (Regex("\\bses").containsMatchIn(t)) {
            Regex("yüzde\\s*(\\d{1,3})|(\\d{1,3})").find(t)?.let { m ->
                val lvl = (m.groupValues[1].ifBlank { m.groupValues[2] }).toInt()
                return SesAyarlaTool().run(ctx, JSONObject().put("seviye", lvl))
            }
            val op = when {
                Regex("full|sonuna|en yüksek|maksimum|fulle").containsMatchIn(t) -> "full"
                Regex("sessiz|kapat|sustur").containsMatchIn(t) -> "kis"
                Regex("kıs|azalt|düşür").containsMatchIn(t) -> "azalt"
                Regex("aç|artır|yükselt").containsMatchIn(t) -> "artir"
                else -> null
            }
            if (op != null) return SesAyarlaTool().run(ctx, JSONObject().put("islem", op))
        }

        // Müzik kontrolü
        val media = when {
            Regex("şarkıyı (durdur|kapat)|müziği (durdur|kapat)|^durdur$|duraklat").containsMatchIn(t) -> "durdur"
            Regex("sonraki|sıradaki|şarkıyı (geç|değiştir)|^geç$").containsMatchIn(t) -> "sonraki"
            Regex("önceki").containsMatchIn(t) -> "onceki"
            Regex("devam (et|ettir)|müziği aç|şarkıyı aç$|^oynat$").containsMatchIn(t) -> "devam"
            else -> null
        }
        if (media != null) return MedyaKontrolTool().run(ctx, JSONObject().put("islem", media))

        // SMS: "Ahmet'e mesaj at geliyorum"
        Regex("^(.+?)'?(?:y?[ea]) (?:sms|mesaj) (?:at|gönder|yaz)\\s*[:,]?\\s*(.+)$").find(t)?.let { m ->
            val args = JSONObject().put("kisi", m.groupValues[1]).put("mesaj", m.groupValues[2])
            pending = SmsGonderTool() to args
            return "${m.groupValues[1]} kişisine SMS ile '${m.groupValues[2]}' yazıyorum, göndereyim mi?"
        }

        // Arama: "annemi ara", "Ahmet'i ara"
        Regex("^(.+?)(?:'?y?[ıiuü])? (?:ara|arar mısın|arayın)$").find(t)?.let { m ->
            val who = m.groupValues[1]
            pending = AramaYapTool() to JSONObject().put("kisi", who)
            return "$who aranacak, arayayım mı?"
        }

        // Uygulama açma: "WhatsApp'ı aç", "kamerayı aç"
        Regex("^(.+?)(?:'?y?[ıiuüae])? (?:aç|açar mısın|başlat)$").find(t)?.let { m ->
            return UygulamaAcTool().run(ctx, JSONObject().put("ad", m.groupValues[1]))
        }

        return null
    }

    companion object {
        private val YES = listOf("evet", "tamam", "olur", "gönder", "ara", "onaylıyorum", "aynen")
        private val NO = listOf("hayır", "vazgeç", "iptal", "dur", "gerek yok")

        const val NO_INTERNET =
            "İnternet yok efendim. Şu an sadece telefon komutlarını yapabiliyorum: alarm, zamanlayıcı, fener, ses, " +
                "müzik kontrolü, uygulama açma, arama, SMS, saat ve pil."
    }
}
