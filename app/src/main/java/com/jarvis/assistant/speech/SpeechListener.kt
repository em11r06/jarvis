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

/**
 * Android SpeechRecognizer ile Türkçe (tr-TR) konuşma tanıma.
 * listen() kullanıcı konuşup susunca tanınan metni, konuşmazsa null döndürür.
 */
class SpeechListener(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null

    fun isAvailable() = SpeechRecognizer.isRecognitionAvailable(context)

    /**
     * @param startTimeoutMs bu süre içinde konuşma başlamazsa dinlemeyi bırakır
     * @param onPartial konuşurken ara sonuçlar (ekranda göstermek için)
     */
    suspend fun listen(startTimeoutMs: Long, onPartial: (String) -> Unit = {}): String? =
        suspendCancellableCoroutine { cont ->
            main.post {
                val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also { recognizer = it }
                var started = false
                var finished = false
                fun finish(value: String?) {
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
                        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                        finish(text?.takeIf { it.isNotBlank() })
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
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    // Sustuktan sonra daha kısa bekle (tanıyıcı destekliyorsa).
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 900L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 700L)
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
