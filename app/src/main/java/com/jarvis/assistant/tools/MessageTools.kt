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
            if (pressSend(acc, pkg)) {
                delay(800)
                // Kutu boşaldıysa mesaj gitmiştir.
                val entry = acc.findById("$pkg:id/entry")?.text?.toString().orEmpty()
                if (!entry.contains(text.take(15))) {
                    sent = true
                    break
                }
            }
        }
        if (!sent) return "WhatsApp açıldı ama gönder düğmesini bulamadım. ${contact.name} WhatsApp kullanmıyor olabilir; mesaj kutuda yazılı duruyor."
        // Kullanıcıyı önceki uygulamaya geri götür.
        val back = previous?.let { ctx.packageManager.getLaunchIntentForPackage(it) }
        if (back == null || !Launcher.start(ctx, back)) acc.global(AccessibilityService.GLOBAL_ACTION_HOME)
        return "${contact.name} kişisine WhatsApp'tan gönderildi."
    }

    /**
     * WhatsApp'ın yeşil gönder düğmesine basar: önce iç kimliğiyle, sonra "Gönder" etiketiyle,
     * o da olmazsa mesaj kutusunun hemen sağındaki konuma dokunarak.
     */
    private suspend fun pressSend(acc: JarvisAccessibilityService, pkg: String): Boolean {
        val entry = acc.findById("$pkg:id/entry") ?: return false
        if (entry.text.isNullOrBlank()) return false
        val byId = acc.findById("$pkg:id/send")
        if (byId != null && acc.click(byId)) return true
        val byLabel = (acc.findByText("Gönder") ?: acc.findByText("Send"))
            ?.takeIf { it.packageName?.toString() == pkg && !it.isEditable && it.text.isNullOrBlank() }
        if (byLabel != null && acc.click(byLabel)) return true
        val r = android.graphics.Rect()
        entry.getBoundsInScreen(r)
        val screenW = acc.resources.displayMetrics.widthPixels
        if (r.isEmpty || r.right >= screenW) return false
        return acc.tap((r.right + screenW) / 2f, r.exactCenterY())
    }

    companion object {
        val WA_PACKAGES = linkedSetOf("com.whatsapp", "com.whatsapp.w4b")

        /** Türkiye numaralarını uluslararası biçime çevirir: 0532... -> 90532... */
        fun waNumber(raw: String): String? {
            var d = raw.filter { it.isDigit() }
            if (d.startsWith("00")) d = d.drop(2)
            if (d.startsWith("0") && d.length == 11) d = "90" + d.drop(1)
            if (d.length == 10 && d.startsWith("5")) d = "90$d"
            return d.takeIf { it.length >= 10 }
        }
    }
}

/**
 * WhatsApp'ta bir sohbetin mesajlarını baloncuklardan okur, eski mesajlar için listeyi
 * kendisi yukarı kaydırır. Kendine attığın mesajlar dahil her sohbette çalışır.
 */
class SohbetiOkuTool : Tool {
    override val name = "sohbeti_oku"
    override val description =
        "WhatsApp'ta bir sohbetteki mesajları okur (kullanıcının KENDİNE attığı mesajlar dahil; bunlar bildirim oluşturmadığı için mesajlari_oku göremez). " +
            "kisi verilirse o sohbeti açar; 'kendim' derse kullanıcının kendi sohbetini açar. kisi verilmezse şu an açık sohbeti okur. " +
            "Mesajları kopyalamak istenirse sonucu panoya_kopyala ile kopyala."
    override val parameters = Schema.obj(
        "kisi" to Schema.str("İsteğe bağlı: kişi adı, numara ya da 'kendim'"),
        "adet" to Schema.int("En fazla kaç son mesaj okunacak (varsayılan 30)"),
    )

    override suspend fun run(ctx: Context, args: JSONObject): String {
        val acc = JarvisAccessibilityService.instance
            ?: return "Erişilebilirlik servisi kapalı; sohbeti okuyamam."
        val want = args.optInt("adet", 30).coerceIn(1, 200)
        val pkg = WhatsappGonderTool.WA_PACKAGES.firstOrNull { ctx.packageManager.getLaunchIntentForPackage(it) != null }
            ?: return "WhatsApp yüklü değil."

        val who = args.optString("kisi").trim()
        if (who.isNotEmpty()) {
            val self = normalizeName(who) in setOf("kendim", "ben", "kendime", "kendi", "kendimle", "kendi sohbetim")
            val number = if (self) {
                val own = com.jarvis.assistant.JarvisApp.instance.settings.ownNumber
                if (own.isBlank()) return "Kullanıcının kendi numarası kayıtlı değil. Kullanıcıdan Jarvis Ayarlar > Mesajlar > 'Kendi numaranız' alanını doldurmasını iste ya da WhatsApp'ta kendi sohbetini açmasını iste, sonra kisi vermeden tekrar çağır."
                own
            } else {
                val (c, options) = Contacts.resolve(ctx, who)
                c?.number ?: return if (options.isEmpty()) "'$who' rehberde bulunamadı." else "Birden fazla eşleşme var, hangisi? " + options.joinToString { it.name }
            }
            val phone = WhatsappGonderTool.waNumber(number) ?: return "Geçerli numara yok."
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send?phone=$phone")).setPackage(pkg)
            if (!Launcher.start(ctx, intent)) return "WhatsApp açılamadı."
            // Sohbet yüklenene kadar bekle.
            for (i in 0 until 20) {
                delay(300)
                if (acc.nodesById("$pkg:id/message_text").isNotEmpty() || acc.findById("$pkg:id/entry") != null) break
            }
            delay(500)
        }

        if (acc.foregroundPackage() != pkg) return "Ekranda WhatsApp sohbeti açık değil."
        val collected = mutableListOf<String>()
        var stale = 0
        for (round in 0 until 15) {
            // Kesik uzun mesajları ("Devamını okuyun") önce aç.
            var expanded = false
            for (n in acc.nodesById("$pkg:id/message_text")) {
                if (acc.expandReadMore(n)) expanded = true
            }
            if (expanded) delay(500)
            val visible = acc.nodesById("$pkg:id/message_text").mapNotNull { n ->
                var t = n.text?.toString() ?: return@mapNotNull null
                // Hâlâ kesikse "… Devamını okuyun" kısmını temizle.
                JarvisAccessibilityService.READ_MORE.firstOrNull { t.contains(it, ignoreCase = true) }?.let { m ->
                    t = t.replace(m, "", ignoreCase = true).trim().trimEnd('…').trimEnd('.').trim() + " (devamı okunamadı)"
                }
                t.trim().takeIf { it.isNotBlank() }
            }
            val before = collected.size
            mergeOlder(collected, visible)
            if (collected.size >= want) break
            stale = if (collected.size == before) stale + 1 else 0
            if (stale >= 2) break
            if (!acc.scrollListBackward(pkg)) break
            delay(450)
        }
        if (collected.isEmpty()) return "Sohbette okunabilir mesaj bulamadım (sadece fotoğraf/ses olabilir)."
        val last = collected.takeLast(want)
        return "Sohbetteki son ${last.size} mesaj (eskiden yeniye):\n" + last.mapIndexed { i, t -> "${i + 1}. $t" }.joinToString("\n")
    }

    /** Yukarı kaydırınca görünen (daha eski) mesajları, çakışan kısmı atlayarak başa ekler. */
    private fun mergeOlder(collected: MutableList<String>, visible: List<String>) {
        if (collected.isEmpty()) {
            collected += visible
            return
        }
        // visible'ın sonu ile collected'ın başı arasındaki en uzun çakışma.
        var overlap = 0
        for (k in minOf(visible.size, collected.size) downTo 1) {
            if (visible.takeLast(k) == collected.take(k)) {
                overlap = k
                break
            }
        }
        collected.addAll(0, visible.dropLast(overlap))
    }
}

/** Metni panoya kopyalar; kullanıcı istediği yere yapıştırabilir. */
class PanoyaKopyalaTool : Tool {
    override val name = "panoya_kopyala"
    override val description = "Verilen metni panoya kopyalar (kullanıcı sonra istediği yere yapıştırır)."
    override val parameters = Schema.obj("metin" to Schema.str("Kopyalanacak metin"), required = listOf("metin"))

    override suspend fun run(ctx: Context, args: JSONObject): String {
        val text = args.optString("metin")
        if (text.isBlank()) return "Hata: metin boş."
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
            cm.setPrimaryClip(android.content.ClipData.newPlainText("Jarvis", text))
        }
        return "Panoya kopyalandı (${text.length} karakter)."
    }
}
