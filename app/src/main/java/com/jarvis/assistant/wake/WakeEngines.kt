package com.jarvis.assistant.wake

import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineManager
import android.content.Context
import com.jarvis.assistant.data.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.SpeakerModel

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
 * Vosk ile anahtar kelime yakalama. Model APK'nın içinde gelir; ilk açılışta telefona
 * bir kez kopyalanır. Tanıyıcı sadece "jarvis" kelimesini arar ([unk] = başka her şey).
 * Kelime duyulunca güven puanına ve (kayıtlıysa) kullanıcının ses izine bakılır;
 * böylece şarkılardaki benzer sesler Jarvis'i uyandırmaz.
 */
class VoskEngine(
    private val context: Context,
    private val settings: Settings,
    private val onWake: () -> Unit,
    private val onFail: (String) -> Unit,
) : WakeEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var model: Model? = null
    private var speaker: SpeakerModel? = null
    private var recognizer: Recognizer? = null
    private var loop: MicLoop? = null
    private var lastWake = 0L

    override fun init(onReady: () -> Unit, onError: (String) -> Unit) {
        scope.launch {
            try {
                val (m, s) = VoskModels.load(context.applicationContext)
                model = m
                speaker = s
                onReady()
            } catch (e: Exception) {
                onError("Ses modeli açılamadı: ${e.message}")
            }
        }
    }

    override fun start() {
        val m = model ?: return
        if (loop != null) return
        try {
            val rec = VoskModels.recognizer(m, speaker)
            recognizer = rec
            loop = MicLoop(rec, ::onResult) { msg -> onFail("Uyandırma hatası: $msg") }.also { it.start() }
        } catch (e: Exception) {
            loop = null
            onFail("Mikrofon açılamadı: ${e.message}")
        }
    }

    override fun stop() {
        // Kaydı durdurur ve mikrofonu bırakır (ses tanıma kullanabilsin diye).
        loop?.stop()
        loop = null
        runCatching { recognizer?.close() }
        recognizer = null
    }

    override fun release() {
        stop()
        scope.cancel()
    }

    private fun onResult(json: String) {
        val hit = VoiceId.parse(json) ?: return
        if (!VoiceId.accept(context, settings, hit)) return
        val now = System.currentTimeMillis()
        if (now - lastWake < 1500) return
        lastWake = now
        onWake()
    }
}
