package com.jarvis.assistant

import android.app.Application
import com.jarvis.assistant.core.AssistantController
import com.jarvis.assistant.data.HistoryStore
import com.jarvis.assistant.data.Settings

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
    }

    companion object {
        lateinit var instance: JarvisApp
            private set
    }
}
