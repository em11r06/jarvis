package com.jarvis.assistant

import android.app.Application
import com.jarvis.assistant.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.core.AssistantController
import com.jarvis.assistant.data.HistoryStore
import com.jarvis.assistant.data.Settings
import com.jarvis.assistant.wake.WakeWordService

/**
 * Uygulama sınıfı. Tüm ekranların ve servislerin ortak kullandığı
 * nesneleri (asistan beyni, ayarlar vb.) tek bir yerde tutar.
 */
class JarvisApp : Application() {

    lateinit var settings: Settings
        private set
    lateinit var assistant: AssistantController
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = Settings(this)
        assistant = AssistantController(this, settings, HistoryStore(this))
        WakeWordService.createChannels(this)

        // Telefon açılınca erişilebilirlik servisi bağlanır; uyandırma açıksa dinlemeyi başlatmayı dene.
        JarvisAccessibilityService.onConnectedHook = { service ->
            if (settings.wakeEnabled && !WakeWordService.isRunning.value) WakeWordService.start(service)
        }
    }

    companion object {
        lateinit var instance: JarvisApp
            private set
    }
}
