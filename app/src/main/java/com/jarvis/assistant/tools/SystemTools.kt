package com.jarvis.assistant.tools

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.BatteryManager
import android.provider.AlarmClock
import com.jarvis.assistant.core.Launcher
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SaatTarihTool : Tool {
    override val name = "saat_tarih"
    override val description = "Şu anki saati, tarihi ve günü verir."
    override val parameters = Schema.empty()

    override suspend fun run(ctx: Context, args: JSONObject): String {
        val f = SimpleDateFormat("d MMMM yyyy EEEE, HH:mm", Locale("tr", "TR"))
        return f.format(Date())
    }
}

class PilDurumuTool : Tool {
    override val name = "pil_durumu"
    override val description = "Pil yüzdesini ve şarj olup olmadığını söyler."
    override val parameters = Schema.empty()

    override suspend fun run(ctx: Context, args: JSONObject): String {
        val bm = ctx.getSystemService(BatteryManager::class.java)
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val sticky = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val temp = (sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
        return "Pil %$level, ${if (charging) "şarj oluyor" else "şarjda değil"}, sıcaklık $temp derece."
    }
}

class FenerTool : Tool {
    override val name = "fener"
    override val description = "El fenerini açar veya kapatır."
    override val parameters = Schema.obj("durum" to Schema.str("ac veya kapa", listOf("ac", "kapa")), required = listOf("durum"))

    override suspend fun run(ctx: Context, args: JSONObject): String {
        val cm = ctx.getSystemService(CameraManager::class.java)
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return "Bu telefonda fener bulunamadı."
        val on = args.optString("durum") == "ac"
        cm.setTorchMode(id, on)
        return if (on) "Fener açıldı." else "Fener kapatıldı."
    }
}

class AlarmKurTool : Tool {
    override val name = "alarm_kur"
    override val description = "Saat uygulamasında alarm kurar."
    override val parameters = Schema.obj(
        "saat" to Schema.int("Saat, 0-23"),
        "dakika" to Schema.int("Dakika, 0-59"),
        "etiket" to Schema.str("Alarm notu (isteğe bağlı)"),
        required = listOf("saat", "dakika"),
    )

    override suspend fun run(ctx: Context, args: JSONObject): String {
        val h = args.optInt("saat", -1)
        val m = args.optInt("dakika", 0)
        if (h !in 0..23 || m !in 0..59) return "Hata: geçersiz saat."
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, h)
            putExtra(AlarmClock.EXTRA_MINUTES, m)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            args.optString("etiket").takeIf { it.isNotBlank() }?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        }
        return if (Launcher.start(ctx, intent)) "Alarm %02d:%02d için kuruldu.".format(h, m) else "Alarm kurulamadı."
    }
}

class ZamanlayiciKurTool : Tool {
    override val name = "zamanlayici_kur"
    override val description = "Geri sayım zamanlayıcısı kurar."
    override val parameters = Schema.obj(
        "saniye" to Schema.int("Toplam süre, saniye cinsinden (ör. 5 dakika = 300)"),
        "etiket" to Schema.str("Not (isteğe bağlı)"),
        required = listOf("saniye"),
    )

    override suspend fun run(ctx: Context, args: JSONObject): String {
        val s = args.optInt("saniye", 0)
        if (s <= 0) return "Hata: süre geçersiz."
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, s)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            args.optString("etiket").takeIf { it.isNotBlank() }?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        }
        val text = if (s >= 60) "${s / 60} dakika${if (s % 60 > 0) " ${s % 60} saniye" else ""}" else "$s saniye"
        return if (Launcher.start(ctx, intent)) "$text zamanlayıcı kuruldu." else "Zamanlayıcı kurulamadı."
    }
}

class GuncelleTool : Tool {
    override val name = "guncelle"
    override val description = "Jarvis'in yeni sürümü var mı kontrol eder; varsa indirir ve kurulum ekranını açar. Kullanıcı 'kendini güncelle', 'güncelleme var mı' derse kullan."
    override val parameters = Schema.empty()

    override suspend fun run(ctx: Context, args: JSONObject): String {
        val msg = com.jarvis.assistant.update.Updater.checkAndDownload(ctx)
        return if (com.jarvis.assistant.update.Updater.state.value is com.jarvis.assistant.update.Updater.State.Ready) {
            com.jarvis.assistant.update.Updater.install(ctx)
        } else {
            msg
        }
    }
}
