package com.jarvis.assistant.tools

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.jarvis.assistant.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.core.Launcher
import com.jarvis.assistant.notify.JarvisNotificationService
import com.jarvis.assistant.notify.MessageLog
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Bildirimlerden kaydedilen mesajları okur (WhatsApp, SMS, Instagram...). */
class MesajlariOkuTool : Tool {
    override val name = "mesajlari_oku"
    override val description =
        "Telefona gelen mesajları (bildirimlerden kaydedilenleri) eksiksiz listeler. 'Sabah altıdan beri gelen mesajlar', " +
            "'Ahmet ne yazmış', 'WhatsApp'ta ne var' gibi isteklerde ekranı okumak yerine BUNU kullan."
    override val parameters = Schema.obj(
        "baslangic" to Schema.str("Bugün bu saatten itibaren, SS:DD biçiminde (ör. 06:00). Verilmezse son 24 saat."),
        "uygulama" to Schema.str("İsteğe bağlı: WhatsApp, Mesajlar, Instagram..."),
        "kisi" to Schema.str("İsteğe bağlı: kişi ya da grup adı"),
    )

    override suspend fun run(ctx: Context, args: JSONObject): String {
        if (JarvisNotificationService.instance == null && !com.jarvis.assistant.core.Permissions.notificationAccess(ctx)) {
            return "Bildirim erişimi kapalı. Kullanıcıdan Kurulum sihirbazındaki 'Bildirim erişimi' adımını açmasını iste."
        }
        val since = parseStart(args.optString("baslangic"))
        val list = MessageLog.query(ctx, since, args.optString("uygulama"), args.optString("kisi"))
        if (list.isEmpty()) return "Bu aralıkta kayıtlı mesaj yok. (Jarvis sadece bildirim erişimi açıldıktan sonra gelen mesajları görebilir.)"
        val f = SimpleDateFormat("HH:mm", Locale.getDefault())
        val lines = list.takeLast(200).map { m ->
            val who = if (m.sender != m.chat) "${m.chat} / ${m.sender}" else m.chat
            "${f.format(Date(m.time))} ${m.app} - $who: ${m.text.take(300)}"
        }
        val head = if (list.size > 200) "Toplam ${list.size} mesaj, son 200'ü:\n" else "Toplam ${list.size} mesaj:\n"
        return head + lines.joinToString("\n")
    }

    private fun parseStart(s: String): Long {
        val m = Regex("(\\d{1,2})[:.](\\d{2})").find(s)
            ?: return System.currentTimeMillis() - 24 * 3600 * 1000L
        val c = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, m.groupValues[1].toInt().coerceIn(0, 23))
            set(Calendar.MINUTE, m.groupValues[2].toInt().coerceIn(0, 59))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        // Saat henüz gelmediyse dün o saati kastetmiştir.
        if (c.timeInMillis > System.currentTimeMillis()) c.add(Calendar.DAY_OF_MONTH, -1)
        return c.timeInMillis
    }
}

/**
 * WhatsApp mesajı gönderir, klavye ve ekran gezintisi olmadan:
 * 1) O kişiden yanıtlanabilir bildirim varsa bildirimin "Yanıtla"sıyla (tamamen arka planda).
 * 2) Yoksa sohbeti mesaj yazılı hâlde açar, gönder'e basar ve önceki uygulamaya döner.
 */
class WhatsappGonderTool : Tool {
    override val name = "whatsapp_gonder"
    override val description =
        "WhatsApp'tan mesaj gönderir (arka planda, klavye gerekmez). WhatsApp mesajı için ekran araçları yerine HER ZAMAN bunu kullan. " +
            "Geri alınamaz: önce alıcıyı ve mesajı söyleyip sesli onay al, sonra onaylandi=true ile çağır."
    override val parameters = Schema.obj(
        "kisi" to Schema.str("Rehberdeki kişi adı, grup adı ya da telefon numarası"),
        "mesaj" to Schema.str("Gönderilecek metin"),
        "onaylandi" to Schema.bool("Kullanıcı gönderimi sesli olarak onayladı mı"),
        required = listOf("kisi", "mesaj", "onaylandi"),
    )

    override suspend fun run(ctx: Context, args: JSONObject): String {
        val who = args.optString("kisi").trim()
        val text = args.optString("mesaj").trim()
        if (who.isEmpty() || text.isEmpty()) return "Hata: kişi ve mesaj gerekli."
        if (!args.optBoolean("onaylandi", false)) {
            return "ONAY GEREKLİ: Kullanıcıya '$who' kişisine '$text' yazacağını söyle ve sesli onay iste. Evet derse onaylandi=true ile tekrar çağır."
        }

        // 1) Tamamen arka planda: bildirimden yanıtla.
        if (JarvisNotificationService.instance?.replyTo(WA_PACKAGES, who, text) == true) {
            return "$who kişisine WhatsApp'tan gönderildi (arka planda)."
        }

        // 2) Sohbeti mesaj yazılı açıp gönder'e bas.
        val pkg = WA_PACKAGES.firstOrNull { ctx.packageManager.getLaunchIntentForPackage(it) != null }
            ?: return "WhatsApp yüklü değil."
        val (contact, options) = Contacts.resolve(ctx, who)
        if (contact == null) {
            return if (options.isEmpty()) "'$who' rehberde bulunamadı. Grup ise önce o gruptan bir bildirim gelmesi gerekir; ya da ekran araçlarıyla dene."
            else "Birden fazla eşleşme var, hangisi? " + options.joinToString { "${it.name} (${it.number})" }
        }
        val phone = waNumber(contact.number) ?: return "${contact.name} için geçerli bir numara bulunamadı."
        val acc = JarvisAccessibilityService.instance
            ?: return "Erişilebilirlik servisi kapalı; gönder düğmesine basamam. Kullanıcıdan erişilebilirliği açmasını iste."

        val previous = acc.foregroundPackage()?.takeIf { it != ctx.packageName && it !in WA_PACKAGES }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send?phone=$phone&text=${Uri.encode(text)}"))
            .setPackage(pkg)
        if (!Launcher.start(ctx, intent)) return "WhatsApp açılamadı."

        // Gönder düğmesi görünene kadar bekle (en fazla ~8 sn).
        var sent = false
        for (i in 0 until 27) {
            delay(300)
            val btn = acc.findById("$pkg:id/send") ?: acc.findByText("Gönder") ?: acc.findByText("Send")
            if (btn != null && btn.packageName?.toString() == pkg && acc.click(btn)) {
                sent = true
                break
            }
        }
        if (!sent) return "WhatsApp açıldı ama gönder düğmesini bulamadım. ${contact.name} WhatsApp kullanmıyor olabilir; mesaj kutuda yazılı duruyor."
        delay(700)
        // Kullanıcıyı önceki uygulamaya geri götür.
        val back = previous?.let { ctx.packageManager.getLaunchIntentForPackage(it) }
        if (back == null || !Launcher.start(ctx, back)) acc.global(AccessibilityService.GLOBAL_ACTION_HOME)
        return "${contact.name} kişisine WhatsApp'tan gönderildi."
    }

    /** Türkiye numaralarını uluslararası biçime çevirir: 0532... -> 90532... */
    private fun waNumber(raw: String): String? {
        var d = raw.filter { it.isDigit() }
        if (d.startsWith("00")) d = d.drop(2)
        if (d.startsWith("0") && d.length == 11) d = "90" + d.drop(1)
        if (d.length == 10 && d.startsWith("5")) d = "90$d"
        return d.takeIf { it.length >= 10 }
    }

    companion object {
        val WA_PACKAGES = linkedSetOf("com.whatsapp", "com.whatsapp.w4b")
    }
}
