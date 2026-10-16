package com.jarvis.assistant.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.jarvis.assistant.ai.Http
import com.jarvis.assistant.data.Settings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * Jarvis'in sesi. Android TextToSpeech (seçilen motor ve ses) veya ElevenLabs kullanır.
 * ElevenLabs hata verirse (internet yok vb.) otomatik olarak Android sesine döner.
 */
class Speaker(private val context: Context, private val settings: Settings) {

    private var tts: TextToSpeech? = null
    private var ttsEngine: String? = null
    private var ttsReady = CompletableDeferred<Boolean>()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private var player: MediaPlayer? = null
    private val audio = context.getSystemService(AudioManager::class.java)

    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attrs)
        .build()

    /** Seçili motorla TTS'i hazırlar; motor değiştiyse yeniden oluşturur. */
    private suspend fun ensureTts(): TextToSpeech? = withContext(Dispatchers.Main) {
        val wanted = settings.ttsEngine.ifBlank { null }
        if (tts == null || ttsEngine != wanted) {
            tts?.shutdown()
            ttsReady = CompletableDeferred()
            val ready = ttsReady
            ttsEngine = wanted
            tts = TextToSpeech(context, { status -> ready.complete(status == TextToSpeech.SUCCESS) }, wanted)
            tts!!.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { pending.remove(utteranceId)?.complete(Unit) }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { pending.remove(utteranceId)?.complete(Unit) }
                override fun onError(utteranceId: String?, errorCode: Int) { pending.remove(utteranceId)?.complete(Unit) }
                override fun onStop(utteranceId: String?, interrupted: Boolean) { pending.remove(utteranceId)?.complete(Unit) }
            })
        }
        val ok = withTimeoutOrNull(8000) { ttsReady.await() } ?: false
        if (ok) tts else null
    }

    /** TTS'i ayarlardaki ses, hız ve tonla yapılandırır. */
    private fun applyVoice(t: TextToSpeech, voiceName: String, rate: Float, pitch: Float) {
        val voice = runCatching { t.voices?.firstOrNull { it.name == voiceName } }.getOrNull()
        if (voice != null) {
            t.voice = voice
        } else {
            t.language = Locale("tr", "TR")
        }
        t.setSpeechRate(rate)
        t.setPitch(pitch)
        t.setAudioAttributes(attrs)
    }

    /** Metni sesli okur ve okuma bitene kadar bekler. */
    suspend fun speak(text: String) {
        if (text.isBlank()) return
        audio.requestAudioFocus(focus)
        try {
            if (settings.ttsMode == Settings.TTS_ELEVEN && settings.elevenKey.isNotBlank() && settings.elevenVoiceId.isNotBlank()) {
                val ok = runCatching { speakEleven(text, settings.elevenVoiceId) }
                    .onFailure { Log.w(TAG, "ElevenLabs başarısız, Android sesine dönülüyor", it) }
                    .getOrDefault(false)
                if (ok) return
            }
            speakAndroid(text, settings.ttsVoice, settings.ttsRate, settings.ttsPitch)
        } finally {
            audio.abandonAudioFocusRequest(focus)
        }
    }

    /** Ses seçme ekranındaki "Dinle" düğmesi için: belirli bir motor/ses ile okur. */
    suspend fun preview(text: String, engine: String, voiceName: String, rate: Float, pitch: Float) {
        stop()
        val oldEngine = settings.ttsEngine
        if (engine != oldEngine) settings.ttsEngine = engine
        try {
            speakAndroid(text, voiceName, rate, pitch)
        } finally {
            if (engine != oldEngine) settings.ttsEngine = oldEngine
        }
    }

    suspend fun previewEleven(text: String, voiceId: String): Boolean {
        stop()
        return runCatching { speakEleven(text, voiceId) }.getOrDefault(false)
    }

    private suspend fun speakAndroid(text: String, voiceName: String, rate: Float, pitch: Float) {
        val t = ensureTts() ?: return
        val id = UUID.randomUUID().toString()
        val done = CompletableDeferred<Unit>()
        pending[id] = done
        withContext(Dispatchers.Main) {
            applyVoice(t, voiceName, rate, pitch)
            val chunks = splitForTts(text, TextToSpeech.getMaxSpeechInputLength())
            chunks.forEachIndexed { i, c ->
                val uid = if (i == chunks.lastIndex) id else "$id-$i"
                t.speak(c, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, Bundle(), uid)
            }
        }
        // Güvenlik için üst sınır: uzun metinlerde bile takılı kalmasın.
        withTimeoutOrNull(120_000) { done.await() }
        pending.remove(id)
    }

    /** ElevenLabs'ten mp3 alır ve çalar. Başarılıysa true. */
    private suspend fun speakEleven(text: String, voiceId: String): Boolean {
        val file = withContext(Dispatchers.IO) {
            val body = JSONObject().put("text", text).put("model_id", settings.elevenModel.ifBlank { "eleven_multilingual_v2" })
            val req = Request.Builder()
                .url("https://api.elevenlabs.io/v1/text-to-speech/$voiceId?output_format=mp3_44100_128")
                .header("xi-api-key", settings.elevenKey)
                .header("Accept", "audio/mpeg")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            Http.client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) throw IllegalStateException("ElevenLabs ${res.code}: ${res.body?.string()?.take(200)}")
                val f = File(context.cacheDir, "eleven_${System.currentTimeMillis()}.mp3")
                f.outputStream().use { out -> res.body!!.byteStream().copyTo(out) }
                f
            }
        }
        try {
            playFile(file)
        } finally {
            file.delete()
        }
        return true
    }

    private suspend fun playFile(file: File) = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val mp = MediaPlayer()
            player = mp
            mp.setAudioAttributes(attrs)
            mp.setDataSource(file.absolutePath)
            mp.setOnCompletionListener {
                it.release()
                if (player === it) player = null
                if (cont.isActive) cont.resume(Unit)
            }
            mp.setOnErrorListener { p, _, _ ->
                p.release()
                if (player === p) player = null
                if (cont.isActive) cont.resume(Unit)
                true
            }
            mp.prepare()
            mp.start()
            cont.invokeOnCancellation { runCatching { mp.stop(); mp.release() } }
        }
    }

    fun stop() {
        runCatching { tts?.stop() }
        pending.values.forEach { it.complete(Unit) }
        pending.clear()
        runCatching { player?.stop(); player?.release() }
        player = null
    }

    private fun splitForTts(text: String, max: Int): List<String> {
        if (text.length <= max) return listOf(text)
        val out = mutableListOf<String>()
        var rest = text
        while (rest.length > max) {
            var cut = rest.lastIndexOfAny(charArrayOf('.', '!', '?', ',', ' '), max - 1)
            if (cut <= 0) cut = max - 1
            out += rest.substring(0, cut + 1)
            rest = rest.substring(cut + 1)
        }
        if (rest.isNotBlank()) out += rest
        return out
    }

    private companion object {
        const val TAG = "JarvisSpeaker"
    }
}
