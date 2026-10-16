package com.jarvis.assistant.data

import android.content.Context
import android.content.SharedPreferences
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * Tüm ayarlar telefonda, uygulamaya özel SharedPreferences dosyasında saklanır.
 * API anahtarları koda yazılmaz; Ayarlar ekranından girilir.
 */
class Settings(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("jarvis_settings", Context.MODE_PRIVATE)

    // Yapay zeka
    var provider by str("provider", PROVIDER_GEMINI)
    var geminiKey by str("gemini_key", "")
    var geminiModel by str("gemini_model", "gemini-3.8-flash")
    var claudeKey by str("claude_key", "")
    var claudeModel by str("claude_model", "claude-haiku-4-5")

    // Ses (TTS)
    var ttsMode by str("tts_mode", TTS_ANDROID)
    var ttsEngine by str("tts_engine", "")
    var ttsVoice by str("tts_voice", "")
    var ttsRate by float("tts_rate", 1.0f)
    var ttsPitch by float("tts_pitch", 1.0f)
    var elevenKey by str("eleven_key", "")
    var elevenVoiceId by str("eleven_voice_id", "")
    var elevenVoiceName by str("eleven_voice_name", "")
    var elevenModel by str("eleven_model", "eleven_multilingual_v2")

    // Uyandırma
    var picovoiceKey by str("picovoice_key", "")
    var wakeEnabled by bool("wake_enabled", false)
    var followUpSeconds by int("follow_up_seconds", 6)

    var setupDone by bool("setup_done", false)

    private fun str(key: String, def: String) = object : ReadWriteProperty<Any?, String> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = prefs.getString(key, def) ?: def
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: String) =
            prefs.edit().putString(key, value.trim()).apply()
    }

    private fun float(key: String, def: Float) = object : ReadWriteProperty<Any?, Float> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = prefs.getFloat(key, def)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Float) =
            prefs.edit().putFloat(key, value).apply()
    }

    private fun int(key: String, def: Int) = object : ReadWriteProperty<Any?, Int> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = prefs.getInt(key, def)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) =
            prefs.edit().putInt(key, value).apply()
    }

    private fun bool(key: String, def: Boolean) = object : ReadWriteProperty<Any?, Boolean> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = prefs.getBoolean(key, def)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) =
            prefs.edit().putBoolean(key, value).apply()
    }

    companion object {
        const val PROVIDER_GEMINI = "gemini"
        const val PROVIDER_CLAUDE = "claude"
        const val TTS_ANDROID = "android"
        const val TTS_ELEVEN = "eleven"
    }
}
