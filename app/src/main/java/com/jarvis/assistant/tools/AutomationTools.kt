package com.jarvis.assistant.tools

import android.content.Context
import com.jarvis.assistant.auto.Automation
import com.jarvis.assistant.auto.Automations
import org.json.JSONObject
import java.util.Calendar

/** Otomatik görev kurar: belli bir saatte ya da belli bir mesaj gelince Jarvis kendiliğinden iş yapar. */
class GorevEkleTool : Tool {
    override val name = "gorev_ekle"
    override val description = "Arka planda nöbet tutan otomatik görev kurar. tur='saat': belli saatte/tarihte ya da X dakika sonra; " +
        "tur='mesaj': belli kişiden/uygulamadan ya da içinde belli kelime geçen mesaj/bildirim gelince. " +
        "gorev: o an Jarvis'in yapacağı iş, tek başına anlaşılır yaz (ör. 'Hava durumunu söyle', 'Mesajı bana sesli oku', " +
        "'Gönderene WhatsApp'tan \"yoldayım\" diye cevap ver'). Kurmadan önce kullanıcıya ne kuracağını kısaca söyleyip onay al."
    override val parameters = Schema.obj(
        "tur" to Schema.str("Görev türü", listOf("saat", "mesaj")),
        "gorev" to Schema.str("Zamanı gelince yapılacak iş (talimat)"),
        "saat" to Schema.str("Saatli görevde saat, 'HH:mm' (ör. '07:30')"),
        "tarih" to Schema.str("Tek seferlik görevde tarih 'YYYY-MM-DD' (boşsa bugün, saat geçtiyse yarın)"),
        "dakika_sonra" to Schema.int("'20 dakika sonra' gibi göreli zaman (saat yerine)"),
        "tekrar" to Schema.str("Tekrar", listOf("yok", "her_gun", "hafta_ici", "hafta_sonu")),
        "kimden" to Schema.str("Mesaj görevinde gönderen kişi/grup adı"),
        "uygulama" to Schema.str("Mesaj görevinde uygulama (ör. WhatsApp, Instagram, Trendyol)"),
        "icerik" to Schema.str("Mesaj görevinde mesajda geçmesi gereken kelime"),
        "sesli" to Schema.bool("Sonucu sesli söylesin mi (varsayılan evet)"),
        "ad" to Schema.str("Kısa ad (ör. 'Sabah hava durumu')"),
        required = listOf("tur", "gorev"),
    )

    override suspend fun run(ctx: Context, args: JSONObject): String {
        val task = args.optString("gorev").trim()
        if (task.isBlank()) return "Hata: gorev boş."
        val id = "g" + System.currentTimeMillis().toString(36)
        val speak = if (args.has("sesli")) args.optBoolean("sesli", true) else true
        val name = args.optString("ad").ifBlank { task.take(40) }
        val a = when (args.optString("tur")) {
            "mesaj" -> {
                val from = args.optString("kimden").trim()
                val app = args.optString("uygulama").trim()
                val contains = args.optString("icerik").trim()
                if (from.isBlank() && app.isBlank() && contains.isBlank())
                    return "Hata: mesaj görevi için kimden, uygulama ya da icerik'ten en az biri gerekli (yoksa her bildirimde çalışır)."
                Automation(id, name, task, Automation.TYPE_MESSAGE, from = from, app = app, contains = contains, speak = speak)
            }
            else -> {
                val repeat = when (args.optString("tekrar")) {
                    "her_gun" -> Automation.REPEAT_DAILY
                    "hafta_ici" -> Automation.REPEAT_WEEKDAYS
                    "hafta_sonu" -> Automation.REPEAT_WEEKENDS
                    else -> Automation.REPEAT_NONE
                }
                val after = args.optInt("dakika_sonra", 0)
                if (after > 0) {
                    Automation(id, name, task, Automation.TYPE_TIME, at = System.currentTimeMillis() + after * 60_000L, speak = speak)
                } else {
                    val m = Regex("(\\d{1,2})[:.](\\d{2})").find(args.optString("saat")) ?: return "Hata: saat 'HH:mm' biçiminde olmalı."
                    val h = m.groupValues[1].toInt()
                    val mi = m.groupValues[2].toInt()
                    if (h !in 0..23 || mi !in 0..59) return "Hata: geçersiz saat."
                    if (repeat != Automation.REPEAT_NONE) {
                        Automation(id, name, task, Automation.TYPE_TIME, hour = h, minute = mi, repeat = repeat, speak = speak)
                    } else {
                        val c = Calendar.getInstance()
                        Regex("(\\d{4})-(\\d{1,2})-(\\d{1,2})").find(args.optString("tarih"))?.let { d ->
                            c.set(d.groupValues[1].toInt(), d.groupValues[2].toInt() - 1, d.groupValues[3].toInt())
                        }
                        c.set(Calendar.HOUR_OF_DAY, h); c.set(Calendar.MINUTE, mi); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
                        if (c.timeInMillis <= System.currentTimeMillis() && args.optString("tarih").isBlank()) c.add(Calendar.DAY_OF_MONTH, 1)
                        if (c.timeInMillis <= System.currentTimeMillis()) return "Hata: bu zaman geçmişte kalmış."
                        Automation(id, name, task, Automation.TYPE_TIME, at = c.timeInMillis, speak = speak)
                    }
                }
            }
        }
        Automations.add(ctx, a)
        val extra = if (a.type == Automation.TYPE_MESSAGE && !com.jarvis.assistant.core.Permissions.notificationAccess(ctx))
            " UYARI: Bildirim erişimi kapalı, mesaj görevleri çalışmaz; kullanıcıdan Ayarlar > Telefon ve mesajlar > Bildirim erişimini açmasını iste." else ""
        return "Görev kuruldu: ${a.describe()}.$extra"
    }
}

class GorevleriListeleTool : Tool {
    override val name = "gorevleri_listele"
    override val description = "Kurulu otomatik görevleri numaralı listeler."
    override val parameters = Schema.empty()
    override suspend fun run(ctx: Context, args: JSONObject): String {
        val list = Automations.all(ctx)
        if (list.isEmpty()) return "Kurulu otomatik görev yok."
        return list.mapIndexed { i, a -> "${i + 1}. ${a.describe()}" }.joinToString("\n")
    }
}

class GorevSilTool : Tool {
    override val name = "gorev_sil"
    override val description = "Otomatik görevi siler. numara: gorevleri_listele'deki sıra numarası."
    override val parameters = Schema.obj("numara" to Schema.int("Silinecek görevin numarası"), required = listOf("numara"))
    override suspend fun run(ctx: Context, args: JSONObject): String {
        val list = Automations.all(ctx)
        val a = list.getOrNull(args.optInt("numara", 0) - 1) ?: return "Bu numarada görev yok, önce gorevleri_listele çağır."
        Automations.remove(ctx, a.id)
        return "Silindi: ${a.describe()}"
    }
}
