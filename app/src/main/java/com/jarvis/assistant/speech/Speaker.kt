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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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

    private var focusHolds = 0

    /**
     * Diğer uygulamaların sesini (müzik, video) kısar. Oturum boyunca da tutulabilir;
     * herkes bıraktığında ses eski hâline döner.
     */
    @Synchronized
    fun acquireFocus() {
        if (focusHolds++ == 0) audio.requestAudioFocus(focus)
    }

    @Synchronized
    fun releaseFocus() {
        if (focusHolds > 0 && --focusHolds == 0) audio.abandonAudioFocusRequest(focus)
    }

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
        acquireFocus()
        try {
            if (settings.ttsMode == Settings.TTS_ELEVEN && settings.elevenKey.isNotBlank() && settings.elevenVoiceId.isNotBlank()) {
                val spoken = speakElevenStreamed(text, settings.elevenVoiceId)
                if (spoken >= 0) {
                    // Yarıda hata olursa kalan kısmı telefon sesiyle tamamla.
                    val rest = splitSentences(text).drop(spoken).joinToString(" ")
                    if (rest.isNotBlank()) speakAndroid(rest, settings.ttsVoice, settings.ttsRate, settings.ttsPitch)
                    return
                }
            }
            speakAndroid(text, settings.ttsVoice, settings.ttsRate, settings.ttsPitch)
        } finally {
            releaseFocus()
        }
    }

    /**
     * Cevabı cümlelere böler; ilk cümle iner inmez çalar, o çalarken sonrakini indirir.
     * Böylece uzun cevaplarda beklemeden konuşmaya başlar.
     * @return başarıyla okunan cümle sayısı; ilk cümle bile alınamadıysa -1
     */
    private suspend fun speakElevenStreamed(text: String, voiceId: String): Int = coroutineScope {
        val parts = splitSentences(text)
        fun fetch(i: Int) = async(Dispatchers.IO) {
            runCatching {
                downloadEleven(
                    parts[i], voiceId,
                    previous = parts.getOrNull(i - 1),
                    next = parts.getOrNull(i + 1),
                )
            }
        }
        var next = fetch(0)
        var done = 0
        for (i in parts.indices) {
            val file = next.await().onFailure { Log.w(TAG, "ElevenLabs başarısız", it) }.getOrNull()
                ?: return@coroutineScope if (done == 0) -1 else done
            if (i + 1 < parts.size) next = fetch(i + 1)
            try {
                playFile(file)
            } finally {
                file.delete()
            }
            done++
        }
        done
    }

    /** Metni cümlelere böler; çok kısa parçaları bir sonrakiyle birleştirir. */
    private fun splitSentences(text: String): List<String> {
        val raw = text.trim().split(Regex("(?<=[.!?…])\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
        val out = mutableListOf<String>()
        for (r in raw) {
            val last = out.lastOrNull()
            // İlk parça kısa olsa da tek başına kalsın ki hemen konuşmaya başlasın.
            if (last != null && out.size > 1 && last.length < 40) out[out.lastIndex] = "$last $r" else out += r
        }
        return out.ifEmpty { listOf(text) }
    }

    /**
     * Kısa, sık tekrarlanan cümleler (ör. "Buyrun efendim") için. ElevenLabs sesi bir kez
     * indirilip telefonda saklanır; sonraki seferlerde internet beklemeden anında çalar.
     */
    suspend fun speakShort(text: String) {
        acquireFocus()
        try {
            if (settings.ttsMode == Settings.TTS_ELEVEN && settings.elevenKey.isNotBlank() && settings.elevenVoiceId.isNotBlank()) {
                val ok = runCatching {
                    val cached = cachedEleven(text, settings.elevenVoiceId)
                    playFile(cached)
                    true
                }.onFailure { Log.w(TAG, "Kısa cümle ElevenLabs ile çalınamadı", it) }.getOrDefault(false)
                if (ok) return
            }
            speakAndroid(text, settings.ttsVoice, settings.ttsRate, settings.ttsPitch)
        } finally {
            releaseFocus()
        }
    }

    private suspend fun cachedEleven(text: String, voiceId: String): File {
        val dir = File(context.filesDir, "voice_cache").apply { mkdirs() }
        val key = (voiceId + "|" + settings.elevenModel + "|" + text).hashCode().toUInt().toString(16)
        val f = File(dir, "$key.mp3")
        if (f.exists() && f.length() > 0) return f
        val tmp = downloadEleven(text, voiceId)
        tmp.copyTo(f, overwrite = true)
        tmp.delete()
        return f
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

    /** @return hata varsa kullanıcıya gösterilecek Türkçe açıklama, başarılıysa null */
    suspend fun previewEleven(text: String, voiceId: String): String? {
        stop()
        return try {
            speakEleven(text, voiceId)
            null
        } catch (e: ElevenException) {
            e.message
        } catch (e: Exception) {
            "Bağlantı hatası: ${e.message}"
        }
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
        val file = downloadEleven(text, voiceId)
        try {
            playFile(file)
        } finally {
            file.delete()
        }
        return true
    }

    /** ElevenLabs'ten mp3 indirir, geçici dosya olarak döndürür. */
    private suspend fun downloadEleven(
        text: String,
        voiceId: String,
        previous: String? = null,
        next: String? = null,
    ): File = withContext(Dispatchers.IO) {
        val model = settings.elevenModel.ifBlank { Settings.ELEVEN_FAST }
        val body = JSONObject()
            .put("text", text)
            .put("model_id", model)
            // Daha canlı, doğal tonlama: düşük kararlılık biraz duygu katar.
            .put(
                "voice_settings",
                JSONObject()
                    .put("stability", 0.4)
                    .put("similarity_boost", 0.8)
                    .put("style", 0.2)
                    .put("use_speaker_boost", true)
                    .put("speed", settings.ttsRate.coerceIn(0.7f, 1.2f).toDouble()),
            )
        // Önceki/sonraki cümleyi bildirmek, cümleler arası tonlamayı akıcı yapar.
        previous?.let { body.put("previous_text", it) }
        next?.let { body.put("next_text", it) }
        // Dil sabitleme yalnızca v2.5 modellerinde destekleniyor.
        if (model.endsWith("v2_5")) body.put("language_code", "tr")
        val req = Request.Builder()
            .url("https://api.elevenlabs.io/v1/text-to-speech/$voiceId?output_format=mp3_44100_128")
            .header("xi-api-key", settings.elevenKey)
            .header("Accept", "audio/mpeg")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        Http.client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw ElevenException.from(res.code, res.body?.string().orEmpty())
            val f = File(context.cacheDir, "eleven_${System.nanoTime()}.mp3")
            f.outputStream().use { out -> res.body!!.byteStream().copyTo(out) }
            f
        }
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

/** ElevenLabs hatası; mesajı kullanıcıya gösterilecek Türkçe açıklamadır. */
class ElevenException(message: String) : Exception(message) {
    companion object {
        fun from(code: Int, body: String): ElevenException {
            val detail = runCatching { JSONObject(body).optJSONObject("detail") }.getOrNull()
            val status = detail?.optString("status").orEmpty()
            val raw = detail?.optString("message")?.takeIf { it.isNotBlank() } ?: body.take(200)
            val text = when {
                status.contains("paid_plan") || status.contains("free_users") || code == 402 ->
                    "Bu ses ücretsiz hesapta uygulamalardan kullanılamıyor (Voice Library sesi). " +
                        "ElevenLabs'in hazır seslerinden birini seçin ya da ücretli plana geçin."
                status.contains("quota") || status.contains("credits") ->
                    "ElevenLabs aylık karakter kotanız dolmuş."
                status.contains("voice_not_found") || code == 404 ->
                    "Bu ses ID hesabınızda bulunamadı. Sesi önce ElevenLabs'te 'My Voices'a ekleyin."
                status.contains("missing_permissions") ->
                    "API anahtarında gerekli izin yok. Anahtarı 'Text to Speech' ve 'Voices: Read' izinleriyle yeniden oluşturun."
                code == 401 -> "ElevenLabs anahtarı geçersiz."
                code == 429 -> "ElevenLabs çok fazla istek dedi, biraz sonra deneyin."
                else -> "ElevenLabs hatası ($code)"
            }
            return ElevenException("$text\n(Ayrıntı: $raw)")
        }
    }
}
