package com.jarvis.assistant.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import com.jarvis.assistant.ai.Http
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/** Telefondaki bir TTS sesi. */
data class VoiceItem(
    val engine: String,
    val engineLabel: String,
    val name: String,
    val localeTag: String,
    val localeName: String,
    val needsNetwork: Boolean,
) {
    val isTurkish get() = localeTag.startsWith("tr")
}

/** ElevenLabs hesabındaki bir ses. */
data class ElevenVoice(val id: String, val name: String, val info: String)

/** Yüklü TTS motorlarını ve seslerini, ayrıca ElevenLabs seslerini listeler. */
object VoiceCatalog {

    /** Tüm motorlardaki tüm sesler; Türkçe olanlar başta. */
    suspend fun androidVoices(context: Context): List<VoiceItem> = withContext(Dispatchers.Main) {
        val probe = createTts(context, null)
        val engines = probe?.engines.orEmpty().map { it.name to it.label }
        probe?.shutdown()

        val all = mutableListOf<VoiceItem>()
        for ((pkg, label) in engines) {
            val tts = createTts(context, pkg) ?: continue
            val voices = runCatching { tts.voices }.getOrNull().orEmpty()
            voices.filter { !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
                .forEach { v ->
                    all += VoiceItem(
                        engine = pkg,
                        engineLabel = label,
                        name = v.name,
                        localeTag = v.locale.toLanguageTag(),
                        localeName = v.locale.getDisplayName(java.util.Locale("tr", "TR")),
                        needsNetwork = v.isNetworkConnectionRequired,
                    )
                }
            tts.shutdown()
        }
        all.sortedWith(compareByDescending<VoiceItem> { it.isTurkish }.thenBy { it.engineLabel }.thenBy { it.localeTag }.thenBy { it.name })
    }

    private suspend fun createTts(context: Context, engine: String?): TextToSpeech? {
        val ready = CompletableDeferred<Boolean>()
        val tts = TextToSpeech(context, { ready.complete(it == TextToSpeech.SUCCESS) }, engine)
        val ok = withTimeoutOrNull(6000) { ready.await() } ?: false
        if (!ok) {
            tts.shutdown()
            return null
        }
        return tts
    }

    suspend fun elevenVoices(apiKey: String): List<ElevenVoice> {
        val res = Http.get("https://api.elevenlabs.io/v1/voices", mapOf("xi-api-key" to apiKey))
        if (res.code !in 200..299) throw IllegalStateException("ElevenLabs hatası ${res.code}")
        val arr = JSONObject(res.body).optJSONArray("voices") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val v = arr.getJSONObject(i)
            val labels = v.optJSONObject("labels")
            val info = listOfNotNull(
                v.optString("category").takeIf { it.isNotBlank() },
                labels?.optString("gender")?.takeIf { it.isNotBlank() },
                labels?.optString("accent")?.takeIf { it.isNotBlank() },
            ).joinToString(" · ")
            ElevenVoice(v.optString("voice_id"), v.optString("name"), info)
        }
    }

    const val SAMPLE = "Merhaba efendim, ben Jarvis. Size nasıl yardımcı olabilirim?"
}
