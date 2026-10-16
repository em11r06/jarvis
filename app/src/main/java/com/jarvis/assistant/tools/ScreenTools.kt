package com.jarvis.assistant.tools

import android.accessibilityservice.AccessibilityService
import android.content.Context
import com.jarvis.assistant.accessibility.JarvisAccessibilityService
import kotlinx.coroutines.delay
import org.json.JSONObject

private const val NO_SERVICE =
    "Erişilebilirlik servisi kapalı. Kullanıcıdan Ayarlar > Erişilebilirlik > Yüklü uygulamalar > Jarvis'i açmasını iste."

/** Ekran araçları için ortak taban: servis yoksa hata döndürür. */
abstract class ScreenTool : Tool {
    final override suspend fun run(ctx: Context, args: JSONObject): String {
        val s = JarvisAccessibilityService.instance ?: return NO_SERVICE
        return run(s, args)
    }

    abstract suspend fun run(s: JarvisAccessibilityService, args: JSONObject): String

    /** İşlemden sonra ekranın yeni hâlini kısaca döndürür; yapay zeka bir sonraki adımı buna göre seçer. */
    protected suspend fun after(s: JarvisAccessibilityService, msg: String, wait: Long = 900): String {
        delay(wait)
        return "$msg\n\nEkranın yeni hâli:\n" + s.readScreen(80)
    }
}

class EkraniOkuTool : ScreenTool() {
    override val name = "ekrani_oku"
    override val description = "Ekranda görünen öğeleri numaralı liste olarak verir. Bir uygulamada işlem yapmadan önce ve sonra kullan."
    override val parameters = Schema.empty()
    override suspend fun run(s: JarvisAccessibilityService, args: JSONObject) = s.readScreen()
}

class DokunTool : ScreenTool() {
    override val name = "dokun"
    override val description = "Ekrandaki bir öğeye dokunur. 'metin' (görünen yazı) veya 'indeks' (ekrani_oku listesindeki numara) ver."
    override val parameters = Schema.obj(
        "metin" to Schema.str("Dokunulacak öğenin yazısı"),
        "indeks" to Schema.int("ekrani_oku sonucundaki numara"),
    )

    override suspend fun run(s: JarvisAccessibilityService, args: JSONObject): String {
        val node = if (args.has("indeks") && !args.isNull("indeks")) {
            s.nodeAt(args.optInt("indeks")) ?: return "Bu numarada öğe yok, önce ekrani_oku çağır."
        } else {
            val t = args.optString("metin")
            if (t.isBlank()) return "Hata: metin veya indeks gerekli."
            s.findByText(t) ?: return "'$t' ekranda bulunamadı. ekrani_oku ile bak ya da kaydir dene."
        }
        return if (s.click(node)) after(s, "Dokunuldu.") else "Dokunulamadı."
    }
}

class YazTool : ScreenTool() {
    override val name = "yaz"
    override val description = "Odaktaki yazı alanına (veya verilen indeksteki alana) metin yazar. Gönder butonuna ayrıca dokun."
    override val parameters = Schema.obj(
        "metin" to Schema.str("Yazılacak metin"),
        "indeks" to Schema.int("İsteğe bağlı: yazı alanının ekrani_oku numarası"),
        required = listOf("metin"),
    )

    override suspend fun run(s: JarvisAccessibilityService, args: JSONObject): String {
        val target = if (args.has("indeks") && !args.isNull("indeks")) s.nodeAt(args.optInt("indeks")) else null
        return if (s.typeText(args.optString("metin"), target)) after(s, "Yazıldı.", 500) else "Yazı alanı bulunamadı."
    }
}

class KaydirTool : ScreenTool() {
    override val name = "kaydir"
    override val description = "Ekranı kaydırır. asagi: aşağıdaki içeriği göster, yukari: yukarıdakini göster, sol/sag: yatay."
    override val parameters = Schema.obj(
        "yon" to Schema.str("Kaydırma yönü", listOf("asagi", "yukari", "sol", "sag")),
        required = listOf("yon"),
    )

    override suspend fun run(s: JarvisAccessibilityService, args: JSONObject): String =
        if (s.scroll(args.optString("yon", "asagi"))) after(s, "Kaydırıldı.", 700) else "Kaydırılamadı."
}

/** Tek bir global işlem yapan basit araçlar (geri, ana ekran, ...). */
open class GlobalActionTool(
    override val name: String,
    override val description: String,
    private val action: Int,
    private val okMsg: String,
    private val readAfter: Boolean = true,
) : ScreenTool() {
    override val parameters = Schema.empty()
    override suspend fun run(s: JarvisAccessibilityService, args: JSONObject): String {
        if (!s.global(action)) return "Yapılamadı."
        return if (readAfter) after(s, okMsg) else okMsg
    }
}

class GeriTool : GlobalActionTool("geri", "Geri tuşuna basar.", AccessibilityService.GLOBAL_ACTION_BACK, "Geri gidildi.")
class AnaEkranTool : GlobalActionTool("ana_ekran", "Ana ekrana döner.", AccessibilityService.GLOBAL_ACTION_HOME, "Ana ekrandayız.", readAfter = false)
class BildirimlerTool : GlobalActionTool("bildirimler", "Bildirim panelini açar ve bildirimleri okur.", AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS, "Bildirim paneli açıldı.")
class EkranGoruntusuTool : GlobalActionTool("ekran_goruntusu", "Ekran görüntüsü alıp galeriye kaydeder.", AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT, "Ekran görüntüsü alındı.", readAfter = false)
class EkraniKilitleTool : GlobalActionTool("ekrani_kilitle", "Ekranı kilitler.", AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN, "Ekran kilitlendi.", readAfter = false)

/**
 * Root olmadan yeniden başlatma: güç menüsünü açar ve Samsung'un
 * "Yeniden başlat" düğmesine basar (onay ekranı çıkarsa bir kez daha).
 */
class YenidenBaslatTool : ScreenTool() {
    override val name = "yeniden_baslat"
    override val description = "Telefonu yeniden başlatır. Önce kullanıcıdan sesli onay al, sonra onaylandi=true ile çağır."
    override val parameters = Schema.obj(
        "onaylandi" to Schema.bool("Kullanıcı yeniden başlatmayı onayladı mı"),
        required = listOf("onaylandi"),
    )

    private val labels = listOf("Yeniden başlat", "Yeniden Başlat", "Restart", "Reboot")

    override suspend fun run(s: JarvisAccessibilityService, args: JSONObject): String {
        if (!args.optBoolean("onaylandi", false)) return "ONAY GEREKLİ: Kullanıcıya telefonu yeniden başlatacağını söyle ve sesli onay iste."
        if (!s.global(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG)) return "Güç menüsü açılamadı."
        var clicks = 0
        repeat(6) {
            delay(1000)
            val node = labels.firstNotNullOfOrNull { s.findByText(it) }
            if (node != null) {
                if (s.click(node)) clicks++
                if (clicks >= 2) return "Yeniden başlatılıyor."
            } else if (clicks >= 1) {
                // İkinci onay çıkmadıysa ilk dokunuş yeterli olmuştur.
                return "Yeniden başlatılıyor."
            }
        }
        return if (clicks > 0) "Yeniden başlatma komutu verildi." else "Güç menüsünde 'Yeniden başlat' bulunamadı."
    }
}
