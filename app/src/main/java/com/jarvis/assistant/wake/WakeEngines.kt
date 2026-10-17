package com.jarvis.assistant.wake

import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineManager
import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService

/**
 * "Jarvis" kelimesini dinleyen motor. İki seçenek var:
 * - Porcupine: en az pil harcar ama Picovoice anahtarı (şirket e-postası) ister.
 * - Vosk: ücretsiz, anahtarsız, tamamen çevrimdışı. Biraz daha fazla işlemci kullanır.
 */
interface WakeEngine {
    /** Motoru hazırlar. Hazır olunca onReady, hata olursa onError çağrılır. */
    fun init(onReady: () -> Unit, onError: (String) -> Unit)
    fun start()
    fun stop()
    fun release()
}

class PorcupineEngine(
    private val context: Context,
    private val accessKey: String,
    private val onWake: () -> Unit,
    private val onFail: (String) -> Unit,
) : WakeEngine {
    private var manager: PorcupineManager? = null

    override fun init(onReady: () -> Unit, onError: (String) -> Unit) {
        try {
            manager = PorcupineManager.Builder()
                .setAccessKey(accessKey)
                .setKeyword(Porcupine.BuiltInKeyword.JARVIS)
                .setSensitivity(0.65f)
                .setErrorCallback { e -> onFail("Uyandırma hatası: ${e.message}") }
                .build(context.applicationContext) { onWake() }
            onReady()
        } catch (e: Exception) {
            onError("Porcupine başlatılamadı: ${e.message}")
        }
    }

    override fun start() { manager?.start() }
    override fun stop() { runCatching { manager?.stop() } }
    override fun release() {
        runCatching { manager?.stop() }
        runCatching { manager?.delete() }
        manager = null
    }
}

/**
 * Vosk ile anahtar kelime yakalama. Model APK'nın içinde (assets/model-en-us) gelir;
 * ilk açılışta telefona bir kez kopyalanır. Tanıyıcı sadece "jarvis" kelimesini
 * arayacak şekilde kısıtlanır ([unk] = başka her şey), bu yüzden hafif çalışır.
 */
class VoskEngine(
    private val context: Context,
    private val onWake: () -> Unit,
    private val onFail: (String) -> Unit,
) : WakeEngine {
    private var model: Model? = null
    private var service: SpeechService? = null
    private var lastWake = 0L

    override fun init(onReady: () -> Unit, onError: (String) -> Unit) {
        StorageService.unpack(
            context.applicationContext, MODEL_ASSET, "vosk",
            { m ->
                model = m
                onReady()
            },
            { e -> onError("Ses modeli açılamadı: ${e.message}") },
        )
    }

    override fun start() {
        val m = model ?: return
        if (service != null) return
        try {
            val rec = Recognizer(m, SAMPLE_RATE, GRAMMAR)
            service = SpeechService(rec, SAMPLE_RATE).also { it.startListening(listener) }
        } catch (e: Exception) {
            onFail("Mikrofon açılamadı: ${e.message}")
        }
    }

    override fun stop() {
        // cancel: kaydı durdurur ve mikrofonu bırakır (ses tanıma kullanabilsin diye).
        runCatching { service?.cancel() }
        runCatching { service?.shutdown() }
        service = null
    }

    override fun release() {
        stop()
        runCatching { model?.close() }
        model = null
    }

    private val listener = object : RecognitionListener {
        override fun onPartialResult(hypothesis: String?) = check(hypothesis, "partial")
        override fun onResult(hypothesis: String?) = check(hypothesis, "text")
        override fun onFinalResult(hypothesis: String?) {}
        override fun onError(exception: Exception?) { onFail("Uyandırma hatası: ${exception?.message}") }
        override fun onTimeout() {}
    }

    private fun check(json: String?, field: String) {
        val text = runCatching { JSONObject(json ?: "{}").optString(field) }.getOrDefault("")
        if (!text.contains(KEYWORD)) return
        val now = System.currentTimeMillis()
        if (now - lastWake < 1500) return
        lastWake = now
        Log.d("JarvisWake", "Vosk duydu: $text")
        onWake()
    }

    companion object {
        const val MODEL_ASSET = "model-en-us"
        private const val SAMPLE_RATE = 16000f
        private const val KEYWORD = "jarvis"
        private const val GRAMMAR = "[\"jarvis\", \"[unk]\"]"
    }
}
