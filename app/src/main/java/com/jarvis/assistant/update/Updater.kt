package com.jarvis.assistant.update

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.jarvis.assistant.BuildConfig
import com.jarvis.assistant.MainActivity
import com.jarvis.assistant.R
import com.jarvis.assistant.ai.Http
import com.jarvis.assistant.core.Launcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File

/**
 * Otomatik güncelleme: GitHub'daki "latest" sürümü kontrol eder, daha yeniyse APK'yı
 * arka planda indirir ve kurulum ekranını açar. Android son onayı ("Güncelle") her zaman
 * kullanıcıya bıraktığı için tek dokunuş gerekir.
 */
object Updater {

    private const val REPO = "em11r06/jarvis"
    private const val CHANNEL = "jarvis_update"
    private const val NOTIF_ID = 77
    private const val CHECK_INTERVAL_MS = 30 * 60 * 1000L // 30 dakika

    sealed class State {
        data object Idle : State()
        data object Checking : State()
        data object UpToDate : State()
        data class Downloading(val version: Int, val percent: Int) : State()
        data class Ready(val version: Int, val notes: String, val file: File) : State()
        data class Error(val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()

    val currentVersion: Int get() = BuildConfig.VERSION_CODE

    /** Uygulama açılınca çağrılır; son kontrolden beri yeterli zaman geçtiyse kontrol eder. */
    fun checkInBackground(context: Context, force: Boolean = false) {
        val prefs = context.getSharedPreferences("jarvis_update", Context.MODE_PRIVATE)
        val last = prefs.getLong("last_check", 0)
        if (!force && System.currentTimeMillis() - last < CHECK_INTERVAL_MS && _state.value !is State.Ready) return
        prefs.edit().putLong("last_check", System.currentTimeMillis()).apply()
        scope.launch { checkAndDownload(context.applicationContext, background = !force) }
    }

    /** Ölçülü bağlantıda (mobil veri) mıyız? Büyük indirmeyi sormadan yapmamak için. */
    private fun isMetered(context: Context): Boolean =
        context.getSystemService(android.net.ConnectivityManager::class.java).isActiveNetworkMetered

    /** Bildirime dokununca: indirilmediyse indir, sonra kurulum ekranını aç. */
    fun downloadAndInstall(context: Context) {
        if (_state.value is State.Ready || fromCache(context) != null) {
            install(context)
            return
        }
        scope.launch {
            checkAndDownload(context.applicationContext)
            if (_state.value is State.Ready) kotlinx.coroutines.withContext(Dispatchers.Main) { install(context) }
        }
    }

    /**
     * Yeni sürüm varsa indirir. Sesli komut aracı da bunu kullanır.
     * @return kullanıcıya söylenecek kısa durum metni
     */
    suspend fun checkAndDownload(context: Context, background: Boolean = false): String = lock.withLock {
        (_state.value as? State.Ready)?.let { if (it.file.exists()) return "Sürüm ${it.version} indirildi, kurulmayı bekliyor." }
        _state.value = State.Checking
        try {
            val res = Http.get("https://api.github.com/repos/$REPO/releases/latest", mapOf("Accept" to "application/vnd.github+json"))
            if (res.code == 404) {
                _state.value = State.Error("Sürüm bulunamadı. Depo public mi?")
                return "Güncellemeye ulaşamadım; GitHub deposu public olmalı."
            }
            if (res.code !in 200..299) {
                _state.value = State.Error("GitHub hatası ${res.code}")
                return "Güncellemeyi kontrol edemedim (GitHub ${res.code})."
            }
            val json = JSONObject(res.body)
            val version = Regex("#(\\d+)").find(json.optString("name"))?.groupValues?.get(1)?.toIntOrNull()
                ?: return "Sürüm numarasını okuyamadım.".also { _state.value = State.Error(it) }
            if (version <= currentVersion) {
                _state.value = State.UpToDate
                return "Zaten en güncel sürümdeyim (sürüm $currentVersion)."
            }
            val assets = json.optJSONArray("assets")
            val url = (0 until (assets?.length() ?: 0)).map { assets!!.getJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk") }
                ?.optString("browser_download_url")
                ?: return "Yeni sürümde APK yok.".also { _state.value = State.Error(it) }
            val notes = json.optString("body").lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()

            // Arka plan kontrolünde mobil verideyse indirmeden sadece haber ver.
            if (background && isMetered(context)) {
                _state.value = State.Idle
                notifyAvailable(context, version, notes)
                return "Sürüm $version var; mobil veride olduğunuz için indirmedim."
            }
            val file = download(context, url, version)
            _state.value = State.Ready(version, notes, file)
            notifyReady(context, version, notes)
            "Yeni sürüm $version indirildi. Kurmak için onay ekranında Güncelle'ye dokunun."
        } catch (e: Exception) {
            Log.w("JarvisUpdate", "Güncelleme hatası", e)
            _state.value = State.Error(e.message ?: "bilinmeyen hata")
            "Güncelleme sırasında bir sorun çıktı: ${e.message}"
        }
    }

    private suspend fun download(context: Context, url: String, version: Int): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val out = File(dir, "Jarvis-$version.apk")
        // Daha önce indirildiyse tekrar indirme.
        if (out.exists() && out.length() > 0) return@withContext out
        dir.listFiles()?.filter { it != out }?.forEach { it.delete() }
        val tmp = File(dir, "Jarvis-$version.part")
        val req = Request.Builder().url(url).get().build()
        Http.client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw IllegalStateException("İndirme hatası ${res.code}")
            val body = res.body ?: throw IllegalStateException("Boş cevap")
            val total = body.contentLength()
            var read = 0L
            var lastPct = -1
            body.byteStream().use { input ->
                tmp.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        read += n
                        val pct = if (total > 0) (read * 100 / total).toInt() else 0
                        if (pct != lastPct) {
                            lastPct = pct
                            _state.value = State.Downloading(version, pct)
                        }
                    }
                }
            }
        }
        tmp.renameTo(out)
        out
    }

    /** Bilinmeyen uygulama yükleme izni var mı? (Jarvis'in APK açabilmesi için) */
    fun canInstall(context: Context) = context.packageManager.canRequestPackageInstalls()

    fun permissionIntent(context: Context) =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    /** Kurulum ekranını açar. İzin yoksa izin ekranını açar. */
    fun install(context: Context): String {
        val ready = _state.value as? State.Ready ?: fromCache(context) ?: return "Kurulacak güncelleme yok."
        if (!ready.file.exists()) {
            _state.value = State.Idle
            return "İndirilen dosya bulunamadı, tekrar denerim."
        }
        if (!canInstall(context)) {
            Launcher.start(context, permissionIntent(context))
            return "Önce Jarvis'e 'bilinmeyen uygulamaları yükleme' izni verin, sonra tekrar güncelle deyin."
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", ready.file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return if (Launcher.start(context, intent)) "Kurulum ekranını açtım, Güncelle'ye dokunun." else "Kurulum ekranı açılamadı."
    }

    /** Uygulama yeniden açıldıysa daha önce indirilmiş güncellemeyi bulur. */
    private fun fromCache(context: Context): State.Ready? {
        val f = File(context.cacheDir, "updates").listFiles()?.firstOrNull { it.name.endsWith(".apk") } ?: return null
        val v = Regex("Jarvis-(\\d+)\\.apk").find(f.name)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        if (v <= currentVersion) return null
        return State.Ready(v, "", f).also { _state.value = it }
    }

    private fun notifyAvailable(context: Context, version: Int, notes: String) {
        // Aynı sürüm için tekrar tekrar bildirim gösterme.
        val prefs = context.getSharedPreferences("jarvis_update", Context.MODE_PRIVATE)
        if (prefs.getInt("notified_version", 0) >= version) return
        prefs.edit().putInt("notified_version", version).apply()
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Güncellemeler", NotificationManager.IMPORTANCE_DEFAULT))
        val pi = PendingIntent.getActivity(
            context, 4,
            Intent(context, MainActivity::class.java).putExtra(EXTRA_INSTALL, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Jarvis'in yeni sürümü var (sürüm $version)")
            .setContentText(notes.ifBlank { "İndirip kurmak için dokunun" })
            .setStyle(Notification.BigTextStyle().bigText((notes.ifBlank { "" } + "\nİndirip kurmak için dokunun (~110 MB).").trim()))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(NOTIF_ID, n) }
    }

    private fun notifyReady(context: Context, version: Int, notes: String) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Güncellemeler", NotificationManager.IMPORTANCE_DEFAULT))
        val pi = PendingIntent.getActivity(
            context, 3,
            Intent(context, MainActivity::class.java).putExtra(EXTRA_INSTALL, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Jarvis güncellemesi hazır (sürüm $version)")
            .setContentText(notes.ifBlank { "Kurmak için dokunun" })
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(NOTIF_ID, n) }
    }

    const val EXTRA_INSTALL = "install_update"
}
