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
    var geminiKey: String
        get() = prefs.getString("gemini_key", "") ?: ""
        set(value) {
            val v = value.trim()
            // Anahtar değişince eski anahtarın kota bilgisi geçersiz: ana modelden yeniden başla.
            if (v != geminiKey) com.jarvis.assistant.ai.GeminiQuota.reset()
            prefs.edit().putString("gemini_key", v).apply()
        }
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
    var elevenModel by str("eleven_model", ELEVEN_FAST)

    // Uyandırma
    var picovoiceKey by str("picovoice_key", "")
    var wakeEnabled by bool("wake_enabled", false)
    var followUpSeconds by int("follow_up_seconds", 6)
    /** Uyanınca bip yerine "Buyrun efendim" gibi sesli cevap ver. */
    var wakeGreeting by bool("wake_greeting", true)
    /** Kullanıcının ses izi (virgülle ayrılmış sayılar); boşsa herkese tepki verir. */
    var voicePrint by str("voice_print", "")
    /** Ses izi benzerlik eşiği; büyüdükçe daha seçici. */
    var voiceThreshold by float("voice_threshold", 0.5f)

    /** Kullanıcının kendi numarası (WhatsApp'ta kendine attığı mesajları açmak için). */
    var ownNumber by str("own_number", "")

    /** Ekranda yapılan işlerde (WhatsApp, sohbet okuma...) üstte "Jarvis çalışıyor" perdesi göster. */
    var curtainEnabled by bool("curtain_enabled", true)

    /** Kullanıcının sohbetlerinden çıkarılan konuşma tarzı rehberi. */
    var styleGuide by str("style_guide", "")
    /** Sohbet dökümlerinde kullanıcının göründüğü ad. */
    var styleOwner by str("style_owner", "")

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
        /** Hızlı, düşük gecikmeli, Türkçe destekli model. */
        const val ELEVEN_FAST = "eleven_flash_v2_5"
        /** Daha kaliteli ama daha yavaş çok dilli model. */
        const val ELEVEN_QUALITY = "eleven_multilingual_v2"
    }
}
