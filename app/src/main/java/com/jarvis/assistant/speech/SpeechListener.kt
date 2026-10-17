package com.jarvis.assistant.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Tanınan konuşma: en olası metin ve diğer tahminler. */
data class Heard(val text: String, val alternatives: List<String>, val confidence: Float?)

/**
 * Android SpeechRecognizer ile Türkçe (tr-TR) konuşma tanıma.
 * listen() kullanıcı konuşup susunca tanınan metni, konuşmazsa null döndürür.
 */
class SpeechListener(private val context: Context) {

    /** Tanıyıcıya ipucu olarak verilen kelimeler (rehberdeki isimler, uygulama adları...). */
    @Volatile
    var hints: List<String> = emptyList()

    /** Türkçede daha iyi olduğu için varsa Google'ın ses tanıyıcısını tercih et. */
    private fun createRecognizer(): SpeechRecognizer {
        val google = runCatching {
            context.packageManager.queryIntentServices(Intent(android.speech.RecognitionService.SERVICE_INTERFACE), 0)
                .firstOrNull { it.serviceInfo.packageName == "com.google.android.googlequicksearchbox" }
                ?.serviceInfo?.let { android.content.ComponentName(it.packageName, it.name) }
        }.getOrNull()
        return if (google != null) SpeechRecognizer.createSpeechRecognizer(context, google)
        else SpeechRecognizer.createSpeechRecognizer(context)
    }

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null

    fun isAvailable() = SpeechRecognizer.isRecognitionAvailable(context)

    /**
     * @param startTimeoutMs bu süre içinde konuşma başlamazsa dinlemeyi bırakır
     * @param onPartial konuşurken ara sonuçlar (ekranda göstermek için)
     */
    suspend fun listen(startTimeoutMs: Long, onPartial: (String) -> Unit = {}): Heard? =
        suspendCancellableCoroutine { cont ->
            main.post {
                val r = recognizer ?: createRecognizer().also { recognizer = it }
                var started = false
                var finished = false
                fun finish(value: Heard?) {
                    if (finished) return
                    finished = true
                    main.removeCallbacksAndMessages(TIMEOUT_TOKEN)
                    if (cont.isActive) cont.resume(value)
                }
                main.postAtTime({
                    if (!started) {
                        r.cancel()
                        finish(null)
                    }
                }, TIMEOUT_TOKEN, android.os.SystemClock.uptimeMillis() + startTimeoutMs)

                r.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() { started = true }
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onError(error: Int) { finish(null) }
                    override fun onResults(results: Bundle?) {
                        val all = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                            .filter { it.isNotBlank() }
                        val conf = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)?.firstOrNull()?.takeIf { it >= 0f }
                        finish(all.firstOrNull()?.let { Heard(it, all.drop(1).distinct().take(4), conf) })
                    }
                    override fun onPartialResults(partialResults: Bundle?) {
                        partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()?.takeIf { it.isNotBlank() }?.let {
                                started = true
                                onPartial(it)
                            }
                    }
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR")
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "tr-TR")
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    // Birkaç tahmin al; emin olunmazsa yapay zeka bağlama göre doğrusunu seçer.
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                    // Rehberdeki isimler, uygulama adları gibi kelimeleri doğru yazması için ipucu (Android 13+).
                    if (android.os.Build.VERSION.SDK_INT >= 33 && hints.isNotEmpty()) {
                        putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, ArrayList(hints.take(500)))
                    }
                    // İnternet yoksa telefondaki çevrimdışı Türkçe modeli kullan.
                    if (!com.jarvis.assistant.core.Network.isOnline(context)) {
                        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    }
                    // Cümle arasındaki kısa duraklamalarda kesmesin (tanıyıcı destekliyorsa).
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1600L)
                    putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                }
                try {
                    r.startListening(intent)
                } catch (e: Exception) {
                    finish(null)
                }
            }
            cont.invokeOnCancellation { main.post { recognizer?.cancel() } }
        }

    fun stop() = main.post { recognizer?.cancel() }

    fun release() = main.post {
        recognizer?.destroy()
        recognizer = null
    }

    private companion object {
        val TIMEOUT_TOKEN = Any()
    }
}
