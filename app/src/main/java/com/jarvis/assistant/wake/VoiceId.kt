package com.jarvis.assistant.wake

import android.content.Context
import android.media.AudioManager
import android.util.Log
import com.jarvis.assistant.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.SpeakerModel
import org.vosk.android.StorageService
import java.io.File
import kotlin.coroutines.resume
import kotlin.math.sqrt

/** Vosk modellerini (kelime tanıma + ses izi) bir kez yükleyip paylaşır. */
object VoskModels {
    private var model: Model? = null
    private var speaker: SpeakerModel? = null
    private val mutex = Mutex()

    suspend fun load(context: Context): Pair<Model, SpeakerModel?> = mutex.withLock {
        withContext(Dispatchers.IO) {
            // Eski sürümün kopyaladığı klasörü temizle (yer kaplamasın).
            context.getExternalFilesDir(null)?.let { File(it, "vosk").takeIf { d -> d.exists() }?.deleteRecursively() }
            val m = model ?: Model(StorageService.sync(context, "model-en-us", "vosk-asr")).also { model = it }
            val s = speaker ?: runCatching { SpeakerModel(StorageService.sync(context, "model-spk", "vosk-spk")) }
                .onFailure { Log.w("JarvisVoice", "Ses izi modeli yüklenemedi", it) }
                .getOrNull()?.also { speaker = it }
            m to s
        }
    }

    /** Sadece "jarvis" kelimesini arayan, ses izi de çıkaran tanıyıcı. */
    fun recognizer(model: Model, speaker: SpeakerModel?): Recognizer =
        Recognizer(model, SAMPLE_RATE, GRAMMAR).apply {
            setWords(true)
            speaker?.let { setSpeakerModel(it) }
            setEndpointerMode(1) // SHORT: kelimeden sonra kısa sessizlikte sonuç ver
        }

    const val SAMPLE_RATE = 16000f
    private const val GRAMMAR = "[\"jarvis\", \"[unk]\"]"
}

/** Vosk sonucunun çözümlenmiş hâli. */
data class WakeHit(val confidence: Double, val voice: FloatArray?)

object VoiceId {

    /** Sonuç JSON'unda "jarvis" varsa güven puanı ve ses izini döndürür. */
    fun parse(json: String?): WakeHit? {
        val o = runCatching { JSONObject(json ?: return null) }.getOrNull() ?: return null
        val words = o.optJSONArray("result") ?: return null
        var conf = -1.0
        for (i in 0 until words.length()) {
            val w = words.getJSONObject(i)
            if (w.optString("word") == "jarvis") conf = maxOf(conf, w.optDouble("conf", 0.0))
        }
        if (conf < 0) return null
        val spk = o.optJSONArray("spk")?.let { a -> FloatArray(a.length()) { a.getDouble(it).toFloat() } }
        return WakeHit(conf, spk)
    }

    /**
     * Uyanmalı mı? Kelime güveni yeterli olmalı; ses kaydı varsa konuşan da kullanıcı olmalı.
     * Müzik çalarken daha sıkı davranır.
     */
    fun accept(context: Context, settings: Settings, hit: WakeHit): Boolean {
        val music = context.getSystemService(AudioManager::class.java).isMusicActive
        val print = loadPrint(settings)
        val minConf = when {
            print != null -> 0.7
            music -> 0.95
            else -> 0.85
        }
        if (hit.confidence < minConf) {
            Log.d(TAG, "Reddedildi: güven ${hit.confidence} < $minConf")
            return false
        }
        if (print == null) return true
        val v = hit.voice ?: return false.also { Log.d(TAG, "Reddedildi: ses izi yok") }
        val sim = cosine(v, print)
        val ok = sim >= settings.voiceThreshold
        Log.d(TAG, "Ses benzerliği $sim (eşik ${settings.voiceThreshold}) -> $ok")
        return ok
    }

    fun loadPrint(settings: Settings): FloatArray? {
        val raw = settings.voicePrint
        if (raw.isBlank()) return null
        return runCatching { raw.split(',').map { it.toFloat() }.toFloatArray() }.getOrNull()
    }

    /** Kayıtlı örneklerden ses izi ve eşik hesaplar, ayarlara kaydeder. */
    fun saveEnrollment(settings: Settings, samples: List<FloatArray>) {
        val dim = samples.first().size
        val mean = FloatArray(dim)
        samples.forEach { s -> for (i in 0 until dim) mean[i] += s[i] / samples.size }
        // Kendi örneklerin birbirine ne kadar benzediğine bakarak eşiği kişiye göre ayarla.
        val sims = samples.map { cosine(it, mean) }
        val threshold = (sims.min() - 0.12f).coerceIn(0.25f, 0.8f)
        settings.voicePrint = mean.joinToString(",")
        settings.voiceThreshold = threshold
    }

    fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dot = 0f
        var na = 0f
        var nb = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        return if (na == 0f || nb == 0f) 0f else dot / (sqrt(na) * sqrt(nb))
    }

    /**
     * Ses tanıtma için bir örnek kaydeder: kullanıcı "Jarvis" deyince ses izini döndürür.
     * @return ses izi, süre dolarsa veya hata olursa null
     */
    suspend fun captureSample(context: Context, timeoutMs: Long = 6000): FloatArray? {
        val (model, speaker) = VoskModels.load(context)
        if (speaker == null) return null
        val rec = VoskModels.recognizer(model, speaker)
        var loop: MicLoop? = null
        try {
            return withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    loop = MicLoop(
                        rec,
                        onResult = { json ->
                            val hit = parse(json)
                            if (hit?.voice != null && cont.isActive) cont.resume(hit.voice)
                        },
                        onError = { if (cont.isActive) cont.resume(null) },
                    )
                    try {
                        loop!!.start()
                    } catch (e: Exception) {
                        if (cont.isActive) cont.resume(null)
                    }
                }
            }
        } finally {
            withContext(Dispatchers.IO) { loop?.stop() }
            runCatching { rec.close() }
        }
    }

    private const val TAG = "JarvisVoice"
}

/**
 * Mikrofonu okuyup Vosk tanıyıcısına veren döngü. Vosk'un hazır SpeechService'i yerine bunu
 * kullanıyoruz çünkü yankı engelleme (AEC) ve gürültü azaltma ekleyebiliyoruz: telefonun
 * kendi çaldığı müzik/video mikrofondan çıkarılır, kullanıcının sesi daha net duyulur.
 * Ses tanıtma kaydı da aynı yoldan geçer ki ses izi aynı koşullarda alınsın.
 */
class MicLoop(
    private val recognizer: Recognizer,
    private val onResult: (String) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile private var running = false
    private var thread: Thread? = null
    private var record: android.media.AudioRecord? = null
    private var aec: android.media.audiofx.AcousticEchoCanceler? = null
    private var ns: android.media.audiofx.NoiseSuppressor? = null

    @android.annotation.SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        val rate = VoskModels.SAMPLE_RATE.toInt()
        val min = android.media.AudioRecord.getMinBufferSize(
            rate, android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT,
        )
        val chunk = rate / 5 // 0,2 saniyelik parçalar
        val r = android.media.AudioRecord(
            // VOICE_COMMUNICATION: telefonun yankı engelleme ve gürültü azaltma işlemini açar.
            android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            rate, android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT,
            maxOf(min, chunk * 2 * 4),
        )
        if (r.state != android.media.AudioRecord.STATE_INITIALIZED) {
            r.release()
            throw java.io.IOException("Mikrofon açılamadı (başka uygulama kullanıyor olabilir).")
        }
        record = r
        if (android.media.audiofx.AcousticEchoCanceler.isAvailable()) {
            aec = runCatching { android.media.audiofx.AcousticEchoCanceler.create(r.audioSessionId)?.apply { enabled = true } }.getOrNull()
        }
        if (android.media.audiofx.NoiseSuppressor.isAvailable()) {
            ns = runCatching { android.media.audiofx.NoiseSuppressor.create(r.audioSessionId)?.apply { enabled = true } }.getOrNull()
        }
        running = true
        r.startRecording()
        thread = Thread({
            val buf = ShortArray(chunk)
            try {
                while (running) {
                    val n = r.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    if (recognizer.acceptWaveForm(buf, n)) {
                        val res = recognizer.result
                        main.post { if (running) onResult(res) }
                    }
                }
            } catch (e: Exception) {
                main.post { onError(e.message ?: "mikrofon hatası") }
            }
        }, "jarvis-mic").apply { start() }
    }

    fun stop() {
        running = false
        runCatching { thread?.join(800) }
        thread = null
        runCatching { record?.stop() }
        runCatching { record?.release() }
        record = null
        runCatching { aec?.release() }
        runCatching { ns?.release() }
        aec = null
        ns = null
    }
}
