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
        if (needsUnlock && com.jarvis.assistant.core.ScreenReturn.isLocked(ctx)) return com.jarvis.assistant.core.ScreenReturn.LOCKED_MSG
        return run(s, args)
    }

    abstract suspend fun run(s: JarvisAccessibilityService, args: JSONObject): String

    /** Bu araç kilit ekranının arkasında çalışamaz mı? */
    protected open val needsUnlock: Boolean = true

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

/** Hedef seçimi: metin, ekrani_oku numarası ya da ekran yüzdesi (x, y). */
private fun target(s: JarvisAccessibilityService, args: JSONObject, prefix: String = ""): Pair<Pair<Float, Float>?, String?> {
    val idxKey = "${prefix}indeks"
    val txtKey = "${prefix}metin"
    if (args.has("${prefix}x") && args.has("${prefix}y")) {
        return s.percentToPx(args.optDouble("${prefix}x"), args.optDouble("${prefix}y")) to null
    }
    val node = if (args.has(idxKey) && !args.isNull(idxKey)) {
        s.nodeAt(args.optInt(idxKey)) ?: return null to "Bu numarada öğe yok, önce ekrani_oku çağır."
    } else {
        val t = args.optString(txtKey)
        if (t.isBlank()) return null to "Hata: metin, indeks ya da x/y gerekli."
        s.findByText(t) ?: return null to "'$t' ekranda bulunamadı. ekrani_oku ya da ekrana_bak ile bak."
    }
    return (s.centerOf(node) ?: return null to "Öğenin ekrandaki yeri bulunamadı.") to null
}

private fun durationArg(args: JSONObject): Long? =
    if (args.has("sure_ms") && !args.isNull("sure_ms")) args.optLong("sure_ms").takeIf { it > 0 } else null

class DokunTool : ScreenTool() {
    override val name = "dokun"
    override val description = "Ekrandaki bir öğeye dokunur. 'metin' (görünen yazı), 'indeks' (ekrani_oku numarası) ya da ekrana_bak görüntüsündeki konum " +
        "için 'x','y' (ekran yüzdesi 0-100) ver. cift=true çift dokunur. sure_ms verilmezse uygun süreyi kendisi seçer."
    override val parameters = Schema.obj(
        "metin" to Schema.str("Dokunulacak öğenin yazısı"),
        "indeks" to Schema.int("ekrani_oku sonucundaki numara"),
        "x" to Schema.num("Yatay konum, ekran genişliğinin yüzdesi (0 sol, 100 sağ)"),
        "y" to Schema.num("Dikey konum, ekran yüksekliğinin yüzdesi (0 üst, 100 alt)"),
        "cift" to Schema.bool("Çift dokunma (ör. fotoğrafı yakınlaştırma, beğenme)"),
        "sure_ms" to Schema.int("İsteğe bağlı dokunma süresi (ms); normalde boş bırak"),
    )

    override suspend fun run(s: JarvisAccessibilityService, args: JSONObject): String {
        val double = args.optBoolean("cift", false)
        val dur = durationArg(args)
        // Metin/indeks verildiyse önce uygulamanın kendi tıklama işlemi denenir (en güvenilir yol).
        if (!double && dur == null && !args.has("x")) {
            val node = if (args.has("indeks") && !args.isNull("indeks")) s.nodeAt(args.optInt("indeks"))
            else args.optString("metin").takeIf { it.isNotBlank() }?.let { s.findByText(it) }
            if (node != null) return if (s.click(node)) after(s, "Dokunuldu.") else "Dokunulamadı."
        }
        val (pt, err) = target(s, args)
        if (pt == null) return err ?: "Hedef bulunamadı."
        val (x, y) = pt
        val before = s.screenSignature()
        val ok = if (double) {
            val first = s.tap(x, y, dur ?: 40)
            delay(90)
            first && s.tap(x, y, dur ?: 40)
        } else {
            s.tap(x, y, dur ?: JarvisAccessibilityService.TAP_MS)
        }
        if (!ok) return "Dokunulamadı."
        // Ekran hiç değişmediyse dokunuş fazla kısa kalmış olabilir: biraz daha uzun bir kez daha dene.
        if (!double && dur == null) {
            delay(600)
            if (s.screenSignature() == before) {
                s.tap(x, y, 150)
                return after(s, "Dokunuldu (ilk dokunuşta ekran değişmediği için biraz daha uzun bir kez daha dokundum).")
            }
        }
        return after(s, "Dokunuldu.")
    }
}

class BasiliTutTool : ScreenTool() {
    override val name = "basili_tut"
    override val description = "Bir öğeye uzun basar (basılı tutar): menü açma, seçme, silme, taşıma moduna geçme için. " +
        "Hedef: 'metin', 'indeks' ya da 'x','y' (ekran yüzdesi). sure_ms verilmezse telefonun ayarına göre süreyi kendisi seçer."
    override val parameters = Schema.obj(
        "metin" to Schema.str("Basılı tutulacak öğenin yazısı"),
        "indeks" to Schema.int("ekrani_oku sonucundaki numara"),
        "x" to Schema.num("Yatay konum, ekran yüzdesi"),
        "y" to Schema.num("Dikey konum, ekran yüzdesi"),
        "sure_ms" to Schema.int("İsteğe bağlı basılı tutma süresi (ms), ör. 1500; normalde boş bırak"),
    )

    override suspend fun run(s: JarvisAccessibilityService, args: JSONObject): String {
        val dur = durationArg(args)
        if (!args.has("x")) {
            val node = if (args.has("indeks") && !args.isNull("indeks")) s.nodeAt(args.optInt("indeks"))
            else args.optString("metin").takeIf { it.isNotBlank() }?.let { s.findByText(it) }
            if (node != null) return if (s.longPress(node, dur)) after(s, "Basılı tutuldu.", 700) else "Basılı tutulamadı."
        }
        val (pt, err) = target(s, args)
        if (pt == null) return err ?: "Hedef bulunamadı."
        val used = dur ?: s.autoLongPressMs()
        return if (s.longPressAt(pt.first, pt.second, used)) after(s, "Basılı tutuldu ($used ms).", 700) else "Basılı tutulamadı."
    }
}

class SurukleTool : ScreenTool() {
    override val name = "surukle"
    override val description = "Bir yerden başka bir yere sürükler. Başlangıç: 'metin'/'indeks'/'x','y'; hedef: 'hedef_metin'/'hedef_indeks'/'hedef_x','hedef_y' " +
        "(x,y ekran yüzdesi). Simge/öğe taşırken tut=true (önce basılı tutar), kaydırıcı çekerken ya da çizerken tut=false. " +
        "sure_ms verilmezse mesafeye göre hızı kendisi ayarlar."
    override val parameters = Schema.obj(
        "metin" to Schema.str("Sürüklenecek öğenin yazısı"),
        "indeks" to Schema.int("Sürüklenecek öğenin ekrani_oku numarası"),
        "x" to Schema.num("Başlangıç yatay konum (yüzde)"),
        "y" to Schema.num("Başlangıç dikey konum (yüzde)"),
        "hedef_metin" to Schema.str("Bırakılacak yerdeki öğenin yazısı"),
        "hedef_indeks" to Schema.int("Bırakılacak yerdeki öğenin ekrani_oku numarası"),
        "hedef_x" to Schema.num("Hedef yatay konum (yüzde)"),
        "hedef_y" to Schema.num("Hedef dikey konum (yüzde)"),
        "tut" to Schema.bool("Önce basılı tutup sonra taşı (varsayılan true)"),
        "sure_ms" to Schema.int("İsteğe bağlı taşıma süresi (ms); normalde boş bırak"),
    )

    override suspend fun run(s: JarvisAccessibilityService, args: JSONObject): String {
        val (from, e1) = target(s, args)
        if (from == null) return e1 ?: "Başlangıç bulunamadı."
        val (to, e2) = target(s, args, "hedef_")
        if (to == null) return (e2 ?: "Hedef bulunamadı.").replace("metin, indeks", "hedef_metin, hedef_indeks")
        val ok = s.drag(from.first, from.second, to.first, to.second, args.optBoolean("tut", true), durationArg(args))
        return if (ok) after(s, "Sürüklendi.", 800) else "Sürüklenemedi (hareket iptal edildi). tut değerini değiştirip ya da sure_ms ile tekrar dene."
    }
}

/** Ekran görüntüsünü yapay zekaya gösterir (görsel olarak inceler). */
class EkranaBakTool : ScreenTool() {
    override val name = "ekrana_bak"
    override val description = "Ekranın görüntüsünü alıp sana görsel olarak gösterir. ekrani_oku'nun göremediği resim, video, oyun, harita, " +
        "grafik gibi şeyleri anlamak ya da kullanıcı 'ekrana bak' deyince kullan."
    override val parameters = Schema.empty()

    override suspend fun run(s: JarvisAccessibilityService, args: JSONObject): String {
        val jpeg = s.screenshotJpeg() ?: return "Ekran görüntüsü alınamadı (Erişilebilirlik'te Jarvis'i kapatıp açmak gerekebilir)."
        val path = com.jarvis.assistant.ai.Images.save(s, jpeg)
        return "Ekran görüntüsü ekte. Konum verirken x,y'yi ekranın yüzdesi olarak kullan.[[GORSEL:$path]]"
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
    override val needsUnlock: Boolean = false,
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

/** İş bitince kullanıcıyı işten önceki ekrana (uygulama ya da ana ekran) geri götürür. */
class OncekiEkranaDonTool : Tool {
    override val name = "onceki_ekrana_don"
    override val description = "Başka bir uygulamada yaptığın iş bitince kullanıcıyı kaldığı ekrana geri götürür."
    override val parameters = Schema.empty()
    override suspend fun run(ctx: android.content.Context, args: JSONObject) =
        com.jarvis.assistant.core.ScreenReturn.restore(ctx)
}
