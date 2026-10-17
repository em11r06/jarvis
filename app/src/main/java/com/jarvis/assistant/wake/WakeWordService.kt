package com.jarvis.assistant.wake

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.IBinder
import android.util.Log
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.MainActivity
import com.jarvis.assistant.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Arka planda "Jarvis" kelimesini dinleyen servis (Vosk veya Picovoice Porcupine).
 * Mikrofon tipinde ön plan servisi olarak çalışır; bildirimde "Jarvis dinliyor" yazar.
 * Kelime duyulunca motor mikrofonu bırakır, sesli oturum başlar, bitince tekrar dinler.
 */
class WakeWordService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var engine: WakeEngine? = null
    private var ready = false
    private var listening = false
    private var inSession = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            JarvisApp.instance.settings.wakeEnabled = false
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            startForeground(NOTIF_ID, buildNotification("Jarvis dinliyor"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (e: Exception) {
            // Android 14+: arka plandan mikrofon servisi başlatılamaz. Kullanıcıdan dokunmasını iste.
            Log.w(TAG, "Ön plan servisi başlatılamadı", e)
            showTapToStart(this)
            stopSelf()
            return START_NOT_STICKY
        }
        getSystemService(NotificationManager::class.java).cancel(TAP_NOTIF_ID)
        running.value = true

        val assistant = JarvisApp.instance.assistant
        // Uygulama içinden mikrofona basılırsa uyandırma motoru mikrofonu bıraksın.
        assistant.beforeListen = { pauseDetection() }
        assistant.afterSession = { if (!inSession) resumeDetection() }

        if (engine == null) initEngine() else resumeDetection()
        startUpdateChecks()
        return START_STICKY
    }

    /** Picovoice anahtarı varsa Porcupine, yoksa ücretsiz Vosk motoru kullanılır. */
    private fun initEngine() {
        val key = JarvisApp.instance.settings.picovoiceKey
        val onWake: () -> Unit = { scope.launch { onWakeWord() } }
        val onFail: (String) -> Unit = { msg -> scope.launch { updateNotification(msg) } }
        val e = if (key.isNotBlank()) {
            PorcupineEngine(applicationContext, key, onWake, onFail)
        } else {
            VoskEngine(applicationContext, JarvisApp.instance.settings, onWake, onFail)
        }
        engine = e
        updateNotification("Jarvis hazırlanıyor...")
        e.init(
            onReady = { scope.launch { ready = true; resumeDetection() } },
            onError = { msg ->
                scope.launch {
                    Log.w(TAG, msg)
                    updateNotification(msg)
                }
            },
        )
    }

    private fun pauseDetection() {
        if (!listening) return
        engine?.stop()
        listening = false
    }

    private fun resumeDetection() {
        if (listening || !ready || inSession) return
        try {
            engine?.start()
            listening = true
            updateNotification("Jarvis dinliyor")
        } catch (e: Exception) {
            Log.w(TAG, "Dinleme başlatılamadı", e)
            updateNotification("Mikrofon açılamadı: ${e.message}")
        }
    }

    private suspend fun onWakeWord() {
        if (inSession) return
        inSession = true
        pauseDetection()
        val speaker = JarvisApp.instance.assistant.speaker
        // Müziği/videoyu hemen kıs; oturum bitene kadar kısık kalsın.
        speaker.acquireFocus()
        if (JarvisApp.instance.settings.wakeGreeting) {
            // "Buyrun efendim" gibi kısa bir cevapla duyduğunu belli et.
            JarvisApp.instance.assistant.speaker.speakShort(WAKE_REPLIES.random())
        } else {
            // Kısa bir "bip" ile duyduğunu belli et.
            runCatching {
                val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 70)
                tone.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
                kotlinx.coroutines.delay(150)
                tone.release()
            }
        }
        try {
            JarvisApp.instance.assistant.voiceSession()
        } finally {
            speaker.releaseFocus()
            inSession = false
            resumeDetection()
        }
    }

    private var updateJob: kotlinx.coroutines.Job? = null

    /** Servis çalıştığı sürece yeni sürüm kontrolü (6 saatte bir; bildirimle haber verir). */
    private fun startUpdateChecks() {
        if (updateJob?.isActive == true) return
        updateJob = scope.launch {
            while (true) {
                com.jarvis.assistant.update.Updater.checkInBackground(applicationContext)
                kotlinx.coroutines.delay(60 * 60 * 1000L)
            }
        }
    }

    override fun onDestroy() {
        running.value = false
        val assistant = JarvisApp.instance.assistant
        assistant.beforeListen = null
        assistant.afterSession = null
        engine?.release()
        engine = null
        scope.cancel()
        super.onDestroy()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, WakeWordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_WAKE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Jarvis")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Kapat", stop).build())
            .build()
    }

    companion object {
        private const val TAG = "JarvisWake"
        private const val CHANNEL_WAKE = "jarvis_wake"
        private const val CHANNEL_ALERT = "jarvis_alert"
        private const val NOTIF_ID = 42
        private const val TAP_NOTIF_ID = 43
        const val ACTION_STOP = "com.jarvis.assistant.STOP_WAKE"
        const val EXTRA_START_WAKE = "start_wake"

        /** Uyanınca söylenen kısa cevaplar; her seferinde rastgele biri seçilir. */
        val WAKE_REPLIES = listOf("Buyrun efendim.", "Efendim?", "Dinliyorum.", "Buyrun.", "Emrinizdeyim.")

        /** Servis çalışıyor mu? Ayarlar ekranı bunu gösterir. */
        val running = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> get() = running

        fun start(ctx: Context) {
            try {
                ctx.startForegroundService(Intent(ctx, WakeWordService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "Servis başlatılamadı", e)
                showTapToStart(ctx)
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, WakeWordService::class.java))
        }

        fun createChannels(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_WAKE, "Uyandırma kelimesi", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Jarvis arka planda dinlerken görünen bildirim"
                    setShowBadge(false)
                },
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ALERT, "Jarvis uyarıları", NotificationManager.IMPORTANCE_HIGH),
            )
        }

        /** "Jarvis'i başlatmak için dokun" bildirimi (telefon yeniden başlayınca vb.). */
        fun showTapToStart(ctx: Context) {
            createChannels(ctx)
            val pi = PendingIntent.getActivity(
                ctx, 2,
                Intent(ctx, MainActivity::class.java)
                    .putExtra(EXTRA_START_WAKE, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = Notification.Builder(ctx, CHANNEL_ALERT)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Jarvis uyuyor")
                .setContentText("Jarvis'i başlatmak için dokun")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            runCatching { ctx.getSystemService(NotificationManager::class.java).notify(TAP_NOTIF_ID, n) }
        }
    }
}
