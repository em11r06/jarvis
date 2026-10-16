package com.jarvis.assistant

import android.app.Application
import com.jarvis.assistant.core.AssistantController

/**
 * Uygulama sınıfı. Tüm ekranların ve servislerin ortak kullandığı
 * nesneleri (asistan beyni, ayarlar vb.) tek bir yerde tutar.
 */
class JarvisApp : Application() {

    lateinit var assistant: AssistantController
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        assistant = AssistantController(this)
    }

    companion object {
        lateinit var instance: JarvisApp
            private set
    }
}
