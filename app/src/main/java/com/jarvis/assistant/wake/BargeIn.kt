package com.jarvis.assistant.wake

import android.content.Context
import android.util.Log
import com.jarvis.assistant.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.vosk.Recognizer

/**
 * Jarvis konuşurken araya girme: konuşma sürerken mikrofon uyandırma kelimesini dinler.
 * Kullanıcı (kayıtlıysa sadece kendi sesiyle) kelimeyi söylerse onBarge çağrılır ve konuşma kesilir.
 * Yankı engelleme Jarvis'in kendi sesini mikrofondan çıkarır.
 */
class BargeIn private constructor(private val loop: MicLoop, private val recs: List<Recognizer>) {
    @Volatile var triggered = false
        private set

    suspend fun stop() {
        withContext(Dispatchers.IO) { loop.stop() }
        recs.forEach { runCatching { it.close() } }
    }

    companion object {
        /** Dinlemeyi başlatır; açılamazsa null (konuşma yine normal sürer). */
        suspend fun start(context: Context, settings: Settings, spokenText: String, onBarge: () -> Unit): BargeIn? = runCatching {
            val words = VoskModels.wakeWords(settings)
            // Ses kaydı yoksa ve Jarvis kendisi uyandırma kelimesini söyleyecekse, kendi sesine takılmasın.
            if (settings.voicePrint.isBlank() && words.any { spokenText.lowercase(java.util.Locale("tr", "TR")).contains(it) }) return null
            val (en, spk) = VoskModels.load(context)
            val tr = VoskModels.loadTurkish(context)
            val recs = listOfNotNull(
                VoskModels.recognizer(en, spk, words, english = true),
                tr?.let { VoskModels.recognizer(it, spk, words, english = false) },
            )
            var self: BargeIn? = null
            val loop = MicLoop(
                recs,
                onResult = { json ->
                    val hit = VoiceId.parse(json, words)
                    val b = self
                    if (hit != null && b != null && !b.triggered && VoiceId.accept(context, settings, hit)) {
                        b.triggered = true
                        onBarge()
                    }
                },
                onError = { Log.w("JarvisBarge", it) },
            )
            BargeIn(loop, recs).also {
                self = it
                withContext(Dispatchers.Main) { loop.start() }
            }
        }.onFailure { Log.w("JarvisBarge", "Araya girme dinlemesi açılamadı", it) }.getOrNull()
    }
}
