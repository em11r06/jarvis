package com.jarvis.assistant.tools

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.provider.MediaStore
import android.view.KeyEvent
import com.jarvis.assistant.core.Launcher
import kotlinx.coroutines.delay
import org.json.JSONObject
import kotlin.math.roundToInt

/** Spotify'da arama yapıp çalar (MEDIA_PLAY_FROM_SEARCH, SDK gerekmez). */
class SarkiCalTool : Tool {
    override val name = "sarki_cal"
    override val description = "Spotify'da şarkı, sanatçı, albüm veya çalma listesi çalar. Örnek sorgu: 'Tarkan Kuzu Kuzu'."
    override val parameters = Schema.obj(
        "sorgu" to Schema.str("Ne çalınacak, ör. 'Tarkan Kuzu Kuzu'"),
        "sanatci" to Schema.str("Varsa sanatçı adı"),
        "sarki" to Schema.str("Varsa şarkı adı"),
        required = listOf("sorgu"),
    )

    override suspend fun run(ctx: Context, args: JSONObject): String {
        val q = args.optString("sorgu")
        val pm = ctx.packageManager
        if (pm.getLaunchIntentForPackage(SPOTIFY) == null) return "Spotify yüklü değil."
        val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            setPackage(SPOTIFY)
            putExtra(SearchManager.QUERY, q)
            val artist = args.optString("sanatci")
            val title = args.optString("sarki")
            if (title.isNotBlank()) {
                putExtra(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Media.ENTRY_CONTENT_TYPE)
                putExtra(MediaStore.EXTRA_MEDIA_TITLE, title)
                if (artist.isNotBlank()) putExtra(MediaStore.EXTRA_MEDIA_ARTIST, artist)
            } else if (artist.isNotBlank()) {
                putExtra(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE)
                putExtra(MediaStore.EXTRA_MEDIA_ARTIST, artist)
            } else {
                putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            }
        }
        if (!Launcher.start(ctx, intent)) return "Spotify açılamadı."
        // Spotify bazen sadece arama sonucunu açar; çalmazsa "oynat" tuşu gönder.
        val audio = ctx.getSystemService(AudioManager::class.java)
        delay(3500)
        if (!audio.isMusicActive) sendMediaKey(ctx, KeyEvent.KEYCODE_MEDIA_PLAY)
        return "Spotify'da '$q' çalınıyor."
    }

    companion object {
        const val SPOTIFY = "com.spotify.music"
    }
}

fun sendMediaKey(ctx: Context, code: Int) {
    val audio = ctx.getSystemService(AudioManager::class.java)
    audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
    audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
}

/** Çalan müziği kontrol eder (Spotify, YouTube Music vb. hepsinde çalışır). */
class MedyaKontrolTool : Tool {
    override val name = "medya_kontrol"
    override val description = "Çalan müziği/videoyu kontrol eder: durdur, devam, sonraki, onceki."
    override val parameters = Schema.obj(
        "islem" to Schema.str("Yapılacak işlem", listOf("durdur", "devam", "sonraki", "onceki")),
        required = listOf("islem"),
    )

    override suspend fun run(ctx: Context, args: JSONObject): String {
        val (code, msg) = when (args.optString("islem")) {
            "durdur" -> KeyEvent.KEYCODE_MEDIA_PAUSE to "Durduruldu."
            "devam" -> KeyEvent.KEYCODE_MEDIA_PLAY to "Devam ediyor."
            "sonraki" -> KeyEvent.KEYCODE_MEDIA_NEXT to "Sonrakine geçildi."
            "onceki" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS to "Öncekine geçildi."
            else -> return "Hata: bilinmeyen işlem."
        }
        sendMediaKey(ctx, code)
        return msg
    }
}

/** Ses seviyesini ayarlar (AudioManager). */
class SesAyarlaTool : Tool {
    override val name = "ses_ayarla"
    override val description = "Telefonun ses seviyesini ayarlar. Ya 0-100 arası 'seviye' ver ya da 'islem': artir, azalt, full (en yüksek), kis (sessize al)."
    override val parameters = Schema.obj(
        "seviye" to Schema.int("Yüzde olarak ses seviyesi, 0-100"),
        "islem" to Schema.str("Seviye yerine yapılacak işlem", listOf("artir", "azalt", "full", "kis")),
        "tur" to Schema.str("Hangi ses: medya (varsayılan), zil, alarm", listOf("medya", "zil", "alarm")),
    )

    override suspend fun run(ctx: Context, args: JSONObject): String {
        val audio = ctx.getSystemService(AudioManager::class.java)
        val stream = when (args.optString("tur")) {
            "zil" -> AudioManager.STREAM_RING
            "alarm" -> AudioManager.STREAM_ALARM
            else -> AudioManager.STREAM_MUSIC
        }
        val max = audio.getStreamMaxVolume(stream)
        val cur = audio.getStreamVolume(stream)
        val step = maxOf(1, max / 8)
        val target = when {
            args.has("seviye") && !args.isNull("seviye") -> (args.optDouble("seviye", 50.0).coerceIn(0.0, 100.0) / 100 * max).roundToInt()
            else -> when (args.optString("islem")) {
                "artir" -> cur + step
                "azalt" -> cur - step
                "full" -> max
                "kis" -> 0
                else -> return "Hata: seviye veya islem gerekli."
            }
        }.coerceIn(0, max)
        return try {
            audio.setStreamVolume(stream, target, AudioManager.FLAG_SHOW_UI)
            "Ses %${(target * 100f / max).roundToInt()} yapıldı."
        } catch (e: SecurityException) {
            "Rahatsız Etmeyin modu açıkken bu ses değiştirilemiyor."
        }
    }
}
