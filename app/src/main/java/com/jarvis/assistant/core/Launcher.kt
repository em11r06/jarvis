package com.jarvis.assistant.core

import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Uygulama/ekran açmak için yardımcı.
 * Android, arka plandaki uygulamaların ekran açmasını kısıtlar. Erişilebilirlik
 * servisi bağlıysa açma işini onun üzerinden yaparız; o izinli sayılır.
 */
object Launcher {
    /** Erişilebilirlik servisi bağlanınca kendini buraya yazar. */
    @Volatile
    var accessibilityContext: Context? = null

    fun start(ctx: Context, intent: Intent): Boolean {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val host = accessibilityContext ?: ctx
        return try {
            host.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.w("JarvisLauncher", "Açılamadı: $intent", e)
            if (host !== ctx) runCatching { ctx.startActivity(intent); return true }
            false
        }
    }
}
