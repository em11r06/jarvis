package com.jarvis.assistant.core

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import com.jarvis.assistant.accessibility.JarvisAccessibilityService
import kotlinx.coroutines.delay

/**
 * Jarvis bir iş için başka uygulamayı ekrana getirdiyse, iş bitince kullanıcıyı
 * kaldığı yere (önceki uygulama ya da ana ekran) geri götürür.
 */
object ScreenReturn {
    /** Kullanıcı isteği başladığında ön planda olan uygulama. */
    @Volatile
    var startPackage: String? = null
        private set

    /** Her yeni istekte çağrılır. */
    fun remember() {
        startPackage = JarvisAccessibilityService.instance?.foregroundPackage()
    }

    /** Ekran kilitli mi? (Kilitliyken uygulama açıp dokunmak mümkün değil.) */
    fun isLocked(ctx: Context): Boolean = ctx.getSystemService(KeyguardManager::class.java).isKeyguardLocked

    const val LOCKED_MSG = "Telefonun kilidi kapalı: kilit ekranının arkasında uygulama açıp dokunamam. " +
        "Kullanıcıdan kilidi açmasını iste; açınca 'tamam' derse işi yap."

    /**
     * Kullanıcıyı kaldığı yere döndürür.
     * @param target dönülecek paket; verilmezse isteğin başındaki uygulama
     */
    suspend fun restore(ctx: Context, target: String? = startPackage): String {
        val acc = JarvisAccessibilityService.instance ?: return "Erişilebilirlik kapalı."
        val current = acc.foregroundPackage()
        if (target == null || current == target) return "Zaten önceki ekrandayız."
        val home = ctx.packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0,
        )?.activityInfo?.packageName
        val ok = if (target == home || target == "com.android.systemui") {
            acc.global(AccessibilityService.GLOBAL_ACTION_HOME)
        } else {
            // Uygulamanın görevini olduğu gibi öne getirir (kaldığı ekran korunur).
            ctx.packageManager.getLaunchIntentForPackage(target)?.let { Launcher.start(ctx, it) } ?: false
        }
        if (!ok) acc.global(AccessibilityService.GLOBAL_ACTION_HOME)
        delay(300)
        return "Önceki ekrana dönüldü."
    }
}
