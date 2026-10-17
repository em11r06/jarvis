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
    private var trModel: Model? = null
    private var recognizers: List<Recognizer> = emptyList()
    private var phrases: List<String> = listOf("jarvis")
    private var loop: MicLoop? = null
    private var lastWake = 0L

    override fun init(onReady: () -> Unit, onError: (String) -> Unit) {
        scope.launch {
            try {
                VoiceId.cleanLearned(settings)
                val (m, s) = VoskModels.load(context.applicationContext)
                model = m
                speaker = s
                trModel = VoskModels.loadTurkish(context.applicationContext)
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
            phrases = VoskModels.wakeWords(settings)
            val recs = listOfNotNull(
                VoskModels.recognizer(m, speaker, phrases, english = true),
                trModel?.let { VoskModels.recognizer(it, speaker, phrases, english = false) },
            )
            recognizers = recs
            val bt = if (settings.headsetMic) com.jarvis.assistant.core.AudioRoute.bluetoothMic(context) else null
            loop = MicLoop(recs, ::onResult, { msg -> onFail("Uyandırma hatası: $msg") }, bt, context).also { it.start() }
        } catch (e: Exception) {
            loop = null
            onFail("Mikrofon açılamadı: ${e.message}")
        }
    }

    override fun stop() {
        // Kaydı durdurur ve mikrofonu bırakır (ses tanıma kullanabilsin diye).
        loop?.stop()
        loop = null
        recognizers.forEach { runCatching { it.close() } }
        recognizers = emptyList()
    }

    override fun release() {
        stop()
        scope.cancel()
    }

    private fun onResult(json: String) {
        val hit = VoiceId.parse(json, phrases) ?: return
        if (!VoiceId.accept(context, settings, hit)) return
        val now = System.currentTimeMillis()
        if (now - lastWake < 1500) return
        lastWake = now
        onWake()
    }
}
