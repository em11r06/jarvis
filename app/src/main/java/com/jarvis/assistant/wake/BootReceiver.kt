package com.jarvis.assistant.wake

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.jarvis.assistant.JarvisApp

/**
 * Telefon açılınca çalışır. Android 15+ açılışta mikrofon servisine izin vermediği için
 * "başlatmak için dokun" bildirimi gösterir. Erişilebilirlik servisi bağlanıp servisi
 * başarıyla başlatırsa bu bildirim kendiliğinden kalkar.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Saatli otomatik görevler yeniden başlatmada silinir: yeniden kur.
        runCatching { com.jarvis.assistant.auto.Automations.rescheduleAll(context.applicationContext) }
        val settings = JarvisApp.instance.settings
        if (!settings.wakeEnabled || WakeWordService.isRunning.value) return
        WakeWordService.showTapToStart(context)
    }
}
