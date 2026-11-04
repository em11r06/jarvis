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

    // ------------------------------------------------------------------
    // Türkçe model (isteğe bağlı): "Can" gibi Türkçe uyandırma kelimeleri için.
    // APK büyümesin diye telefona bir kez indirilir (~35 MB).
    // ------------------------------------------------------------------
    private var trModel: Model? = null
    const val TR_URL = "https://alphacephei.com/vosk/models/vosk-model-small-tr-0.3.zip"

    private fun trDir(context: Context) = File(context.filesDir, "vosk-tr")
    private fun trReady(context: Context) = File(trDir(context), "ready")

    fun hasTurkish(context: Context) = trReady(context).exists()

    /** İndirilmişse Türkçe modeli yükler; yoksa null. */
    suspend fun loadTurkish(context: Context): Model? = mutex.withLock {
        if (!hasTurkish(context)) return@withLock null
        trModel ?: withContext(Dispatchers.IO) {
            runCatching { Model(modelRoot(File(trDir(context), "model")).absolutePath) }
                .onFailure { Log.w("JarvisVoice", "Türkçe model açılamadı", it) }.getOrNull()
        }?.also { trModel = it }
    }

    /** Türkçe modeli indirir ve açar. @param onProgress yüzde (0-100) */
    suspend fun downloadTurkish(context: Context, onProgress: (Int) -> Unit): Boolean = withContext(Dispatchers.IO) {
        val dir = trDir(context)
        runCatching {
            dir.deleteRecursively()
            val tmp = File(dir, "tmp").apply { mkdirs() }
            val req = okhttp3.Request.Builder().url(TR_URL).build()
            com.jarvis.assistant.ai.Http.client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) throw java.io.IOException("İndirme hatası ${res.code}")
                val body = res.body ?: throw java.io.IOException("Boş cevap")
                val total = body.contentLength().takeIf { it > 0 } ?: 40_000_000L
                val counting = object : java.io.FilterInputStream(body.byteStream()) {
                    var read = 0L
                    var lastPct = -1
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        val n = super.read(b, off, len)
                        if (n > 0) {
                            read += n
                            val pct = (read * 100 / total).toInt().coerceAtMost(99)
                            if (pct != lastPct) { lastPct = pct; onProgress(pct) }
                        }
                        return n
                    }
                }
                java.util.zip.ZipInputStream(counting.buffered()).use { zip ->
                    var e = zip.nextEntry
                    while (e != null) {
                        val out = File(tmp, e.name)
                        // Zip içinden klasör dışına yazmayı engelle.
                        if (!out.canonicalPath.startsWith(tmp.canonicalPath)) throw java.io.IOException("Geçersiz dosya")
                        if (e.isDirectory) out.mkdirs() else {
                            out.parentFile?.mkdirs()
                            out.outputStream().use { zip.copyTo(it) }
                        }
                        e = zip.nextEntry
                    }
                }
            }
            val root = modelRoot(tmp)
            if (!File(root, "final.mdl").exists() && !File(root, "am/final.mdl").exists()) throw java.io.IOException("Model dosyaları bulunamadı")
            if (!root.renameTo(File(dir, "model"))) throw java.io.IOException("Model taşınamadı")
            tmp.deleteRecursively()
            trReady(context).writeText("ok")
            onProgress(100)
            true
        }.getOrElse {
            Log.w("JarvisVoice", "Türkçe model indirilemedi", it)
            dir.deleteRecursively()
            false
        }
    }

    /**
     * Modelin asıl klasörünü bulur. Yeni modellerde "am/final.mdl", eski Türkçe modelde
     * doğrudan "final.mdl" bulunur; zip içinde bir klasör daha olabilir.
     */
    private fun modelRoot(dir: File, depth: Int = 0): File {
        if (File(dir, "final.mdl").exists() || File(dir, "am/final.mdl").exists()) return dir
        if (depth < 3) dir.listFiles()?.filter { it.isDirectory }?.forEach { sub ->
            val r = modelRoot(sub, depth + 1)
            if (File(r, "final.mdl").exists() || File(r, "am/final.mdl").exists()) return r
        }
        return dir
    }

    fun deleteTurkish(context: Context) {
        runCatching { trModel?.close() }
        trModel = null
        trDir(context).deleteRecursively()
    }

    /** Ayarlardaki uyandırma kelimeleri (küçük harf). Boşsa "jarvis". */
    fun wakeWords(settings: Settings): List<String> =
        settings.wakeWords.split(',', '\n').map { it.trim().lowercase(java.util.Locale("tr", "TR")).replace(Regex("\\s+"), " ") }
            .filter { it.isNotEmpty() }.distinct().ifEmpty { listOf("jarvis") }

    /** Kelimeleri İngilizce modele uygun yazar (Türkçe ı/İ yerine i). */
    private fun forEnglish(w: String) = w.replace('ı', 'i').replace('ç', 'c').replace('ş', 's').replace('ğ', 'g').replace('ö', 'o').replace('ü', 'u')

    /**
     * Sadece uyandırma kelimelerini arayan, ses izi de çıkaran tanıyıcı.
     * Modelin bilmediği kelimeler Vosk tarafından yok sayılır; bu yüzden her kelime hem
     * İngilizce hem (varsa) Türkçe modelde aranır, hangisi tanırsa o yakalar.
     */
    fun recognizer(model: Model, speaker: SpeakerModel?, words: List<String> = listOf("jarvis"), english: Boolean = true): Recognizer {
        val list = (if (english) words.map(::forEnglish) else words).distinct()
        val grammar = org.json.JSONArray(list + "[unk]").toString()
        return Recognizer(model, SAMPLE_RATE, grammar).apply {
            setWords(true)
            speaker?.let { setSpeakerModel(it) }
            setEndpointerMode(1) // SHORT: kelimeden sonra kısa sessizlikte sonuç ver
        }
    }

    /** Serbest tanıyıcı (her kelimeyi yazıya çevirir): testte "ne duydu" göstermek için. */
    fun freeRecognizer(model: Model): Recognizer = Recognizer(model, SAMPLE_RATE).apply { setWords(true) }

    const val SAMPLE_RATE = 16000f
}

/** Vosk sonucunun çözümlenmiş hâli. */
data class WakeHit(val confidence: Double, val voice: FloatArray?)

object VoiceId {

    /**
     * Sonuçta uyandırma kelimelerinden biri (çok kelimeli de olabilir: "hey can") varsa
     * güven puanı ve ses izini döndürür.
     */
    fun parse(json: String?, phrases: List<String> = listOf("jarvis")): WakeHit? {
        val o = runCatching { JSONObject(json ?: return null) }.getOrNull() ?: return null
        val arr = o.optJSONArray("result") ?: return null
        val words = (0 until arr.length()).map { arr.getJSONObject(it) }
        val tokens = words.map { it.optString("word") }
        var conf = -1.0
        for (p in phrases) {
            val parts = p.split(' ')
            for (variant in listOf(parts, parts.map { it.replace('ı', 'i').replace('ç', 'c').replace('ş', 's').replace('ğ', 'g').replace('ö', 'o').replace('ü', 'u') })) {
                for (start in 0..tokens.size - variant.size) {
                    if (tokens.subList(start, start + variant.size) == variant) {
                        val c = words.subList(start, start + variant.size).minOf { it.optDouble("conf", 0.0) }
                        conf = maxOf(conf, c)
                    }
                }
            }
        }
        if (conf < 0) return null
        val spk = o.optJSONArray("spk")?.let { a -> FloatArray(a.length()) { a.getDouble(it).toFloat() } }
        return WakeHit(conf, spk)
    }

    /**
     * Uyanmalı mı? Sıkı kurallar (yanlışlıkla uyanmasın):
     * - Uyandırma kelimesi yüksek güvenle duyulmuş olmalı.
     * - Ses kaydı varsa konuşmadan ses izi çıkarılabilmiş ve kullanıcıya benziyor olmalı.
     *   Ses izi yoksa (çok kısa/gürültü) reddedilir. Reddedilen sesler asla öğrenilmez.
     * - Bir oturum bittikten hemen sonra (Jarvis'in kendi sesinin yankısı) uyanmaz.
     */
    fun accept(context: Context, settings: Settings, hit: WakeHit, audio: ShortArray? = null): Boolean {
        if (System.currentTimeMillis() < quietUntil) {
            Log.d(TAG, "Reddedildi: oturum yeni bitti")
            return false
        }
        val music = context.getSystemService(AudioManager::class.java).isMusicActive
        val print = loadPrint(settings)
        // Ses kaydı varsa ses izi zaten doğruluyor; kelime güveni hassasiyete göre biraz esneyebilir.
        val minConf = when {
            music -> 0.95
            print == null -> 0.85
            else -> when (com.jarvis.assistant.core.Ambient.sensitivity(settings)) { 0 -> 0.85; 2 -> 0.65; else -> 0.75 }
        }
        if (hit.confidence < minConf) {
            Log.d(TAG, "Reddedildi: güven ${hit.confidence} < $minConf")
            return false
        }
        // Yeni konuşmacı tanıma (CAM++) kayıtlıysa asıl karar onunla verilir.
        if (audio != null && SpeakerId.enrolled(settings)) {
            val slack = (if (com.jarvis.assistant.core.AudioRoute.headset(context)) HEADSET_SLACK else 0f) - (if (music) MUSIC_STRICT else 0f)
            val ok = SpeakerId.isUser(context, settings, audio, slack)
            if (ok != null) {
                Log.d(TAG, "Konuşmacı tanıma: ${if (ok) "kabul" else "ret"}")
                return ok
            }
        }
        if (print == null) return true
        val v = hit.voice ?: return false.also { Log.d(TAG, "Reddedildi: ses izi yok") }
        val sim = score(settings, v, print)
        // Kulaklıkla ses farklı mikrofondan/uzaktan gelir; ses izi biraz daha az benzer çıkar.
        // Telefon müzik/video çalarken şarkıdaki sesler karışmasın diye daha sıkı.
        val th = settings.voiceThreshold -
            (if (com.jarvis.assistant.core.AudioRoute.headset(context)) HEADSET_SLACK else 0f) +
            (if (music) MUSIC_STRICT else 0f)
        val ok = sim >= th
        Log.d(TAG, "Ses benzerliği $sim (eşik $th) -> ${if (ok) "kabul" else "ret"}")
        return ok
    }

    /** Bu zamana kadar uyandırma kabul edilmez (oturum sonrası yankı ve kuyruk sesler için). */
    @Volatile var quietUntil = 0L

    /**
     * Uyandıktan sonra söylenen komutun sesi kullanıcıya mı ait? (TV, başka biri konuşuyorsa reddedilir.)
     * Ses kaydı yoksa ya da kontrol edilemiyorsa true döner.
     */
    suspend fun commandIsUser(context: Context, settings: Settings, pcm: ByteArray): Boolean = withContext(Dispatchers.IO) {
        if (SpeakerId.enrolled(settings)) {
            val slack = if (com.jarvis.assistant.core.AudioRoute.headset(context)) HEADSET_SLACK else 0f
            val pcm16 = ShortArray(pcm.size / 2) { ((pcm[it * 2].toInt() and 0xFF) or (pcm[it * 2 + 1].toInt() shl 8)).toShort() }
            // Çok kısa cevaplar ("evet") ölçülemezse geçir.
            return@withContext SpeakerId.isUser(context, settings, pcm16, slack) ?: true
        }
        val print = loadPrint(settings) ?: return@withContext true
        val (v, frames) = voiceVector(context, pcm) ?: return@withContext true
        // Çok kısa cevaplar ("evet", "tamam") güvenilir ölçülemez: geçir.
        if (frames < 30) return@withContext true
        val headsetSlack = if (com.jarvis.assistant.core.AudioRoute.headset(context)) HEADSET_SLACK else 0f
        // Cümle kaydı varsa asıl kontrol onunla: normal konuşma sesi kelime sesinden farklıdır.
        val long = loadLong(settings)
        if (long.isNotEmpty() && settings.voiceThresholdLong > 0f) {
            val simLong = scoreAgainst(v, long)
            val thLong = settings.voiceThresholdLong - headsetSlack - if (frames < 120) SHORT_SLACK else 0f
            Log.d(TAG, "Komut sesi (cümle kaydı) benzerliği $simLong (eşik $thLong)")
            return@withContext simLong >= thLong
        }
        val sim = score(settings, v, print)
        // Komutlar uzun ve farklı kelimeler içerdiği için kelime eşiğinden biraz daha hoşgörülü.
        val th = settings.voiceThreshold - COMMAND_SLACK - if (com.jarvis.assistant.core.AudioRoute.headset(context)) HEADSET_SLACK else 0f
        Log.d(TAG, "Komut sesi benzerliği $sim (eşik $th)")
        sim >= th
    }

    /**
     * Kaydedilmiş sesin (PCM) ses izi ve konuşma uzunluğu (10 ms'lik çerçeve sayısı).
     * Komut doğrulaması ve cümle kaydı aynı yoldan geçer ki karşılaştırma adil olsun.
     */
    suspend fun voiceVector(context: Context, pcm: ByteArray): Pair<FloatArray, Int>? = withContext(Dispatchers.IO) {
        val (model, spk) = runCatching { VoskModels.load(context) }.getOrNull() ?: return@withContext null
        if (spk == null) return@withContext null
        runCatching {
            Recognizer(model, VoskModels.SAMPLE_RATE, spk).use { rec ->
                rec.acceptWaveForm(pcm, pcm.size)
                val o = JSONObject(rec.finalResult)
                val frames = o.optInt("spk_frames", 0)
                o.optJSONArray("spk")?.let { a -> FloatArray(a.length()) { a.getDouble(it).toFloat() } to frames }
            }
        }.getOrNull()
    }

    private fun loadLong(settings: Settings): List<FloatArray> = settings.voiceSamplesLong.split(';').filter { it.isNotBlank() }.mapNotNull { r ->
        runCatching { r.split(',').map { it.toFloat() }.toFloatArray() }.getOrNull()
    }

    /** Örneklerin ortalamasına ve en yakın iki örneğe göre benzerlik (hangisi yüksekse). */
    private fun scoreAgainst(v: FloatArray, samples: List<FloatArray>): Float {
        val dim = v.size
        val mean = FloatArray(dim)
        val same = samples.filter { it.size == dim }
        if (same.isEmpty()) return 0f
        same.forEach { s -> for (i in 0 until dim) mean[i] += s[i] / same.size }
        val top = same.map { cosine(v, it) }.sortedDescending().take(2).average().toFloat()
        return maxOf(cosine(v, mean), top)
    }

    /** Cümle kaydını saklar; eşiği her örneğin diğerlerine benzerliğinden hesaplar. */
    fun saveLongEnrollment(settings: Settings, samples: List<FloatArray>) {
        settings.voiceSamplesLong = samples.joinToString(";") { s -> s.joinToString(",") { "%.4f".format(java.util.Locale.US, it) } }
        val loo = samples.indices.map { i -> scoreAgainst(samples[i], samples.filterIndexed { j, _ -> j != i }) }.sorted()
        settings.voiceThresholdLong = (loo.first() - 0.08f).coerceIn(0.3f, 0.75f)
    }

    fun hasLongEnrollment(settings: Settings) = settings.voiceThresholdLong > 0f && settings.voiceSamplesLong.isNotBlank()

    /**
     * Önceki sürümlerin otomatik "öğrenme"si gürültüleri de ses kaydına eklemiş olabilir:
     * sadece ses tanıtırken alınan ilk örnekleri bırakır.
     */
    fun cleanLearned(settings: Settings) {
        if (settings.voiceCleaned) return
        settings.voiceCleaned = true
        val samples = loadSamples(settings)
        if (samples.size > ENROLL_KEEP) storeSamples(settings, samples.take(ENROLL_KEEP))
    }

    /** Ortalama ses izine ve kayıtlı örneklere göre benzerlik: hangisi daha yakınsa. */
    private fun score(settings: Settings, v: FloatArray, mean: FloatArray): Float {
        val base = cosine(v, mean)
        val samples = loadSamples(settings)
        if (samples.isEmpty()) return base
        val top = samples.map { cosine(v, it) }.sortedDescending().take(2)
        return maxOf(base, top.average().toFloat())
    }

    fun loadPrint(settings: Settings): FloatArray? {
        val raw = settings.voicePrint
        if (raw.isBlank()) return null
        return runCatching { raw.split(',').map { it.toFloat() }.toFloatArray() }.getOrNull()
    }

    private fun loadSamples(settings: Settings): List<FloatArray> = settings.voiceSamples.split(';').filter { it.isNotBlank() }.mapNotNull { r ->
        runCatching { r.split(',').map { it.toFloat() }.toFloatArray() }.getOrNull()
    }

    private fun storeSamples(settings: Settings, samples: List<FloatArray>) {
        settings.voiceSamples = samples.takeLast(MAX_SAMPLES).joinToString(";") { s -> s.joinToString(",") { "%.4f".format(java.util.Locale.US, it) } }
        val dim = samples.first().size
        val mean = FloatArray(dim)
        val list = samples.takeLast(MAX_SAMPLES)
        list.forEach { s -> for (i in 0 until dim) mean[i] += s[i] / list.size }
        settings.voicePrint = mean.joinToString(",")
    }

    /** Kayıtlı örneklerden ses izi ve eşik hesaplar, ayarlara kaydeder. */
    fun saveEnrollment(settings: Settings, samples: List<FloatArray>) {
        settings.voiceCleaned = true
        storeSamples(settings, samples)
        // Eşik: her örneğin, KENDİSİ HARİÇ diğerlerine benzerliğine bakılır (kendisi dahil edilirse
        // benzerlik olduğundan yüksek çıkar ve eşik fazla sıkı olur; sesinizi zor tanır).
        val loo = samples.indices.map { i ->
            val others = samples.filterIndexed { j, _ -> j != i }
            val dim = samples[i].size
            val m = FloatArray(dim)
            others.forEach { s -> for (k in 0 until dim) m[k] += s[k] / others.size }
            maxOf(cosine(samples[i], m), others.map { cosine(samples[i], it) }.sortedDescending().take(2).average().toFloat())
        }.sorted()
        // En kötü örneği gürültü sayıp ikinci en kötüsünden biraz aşağısını eşik al.
        val ref = loo.getOrElse(1) { loo.first() }
        settings.voiceThreshold = (ref - 0.08f).coerceIn(0.2f, 0.65f)
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
                            // "Jarvis" farklı telaffuz edilip tanınmasa bile, yeterince uzun konuşma varsa ses izini al.
                            val v = parse(json)?.voice ?: anyVoice(json)
                            if (v != null && cont.isActive) cont.resume(v)
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

    /** Kelime ne olursa olsun, sonuçta yeterince uzun bir ses izi varsa onu döndürür. */
    private fun anyVoice(json: String?): FloatArray? {
        val o = runCatching { JSONObject(json ?: return null) }.getOrNull() ?: return null
        if (o.optInt("spk_frames", 0) < 25) return null
        return o.optJSONArray("spk")?.let { a -> FloatArray(a.length()) { a.getDouble(it).toFloat() } }
    }

    private const val TAG = "JarvisVoice"
    private const val HEADSET_SLACK = 0.05f
    /** Komut sesi sadece açıkça başka birine aitse reddedilir (kayıt kısa "Jarvis" sözlerinden yapıldığı için). */
    private const val COMMAND_SLACK = 0.22f
    /** Müzik/video çalarken uyandırma için ek sıkılık. */
    private const val MUSIC_STRICT = 0.05f
    /** Kısa komutlarda ses izi daha az kesin: biraz hoşgörü. */
    private const val SHORT_SLACK = 0.06f
    private const val MAX_SAMPLES = 24
    /** Ses tanıtırken alınan örnek sayısı. */
    private const val ENROLL_KEEP = 8
}

/**
 * Mikrofonu okuyup Vosk tanıyıcısına veren döngü. Vosk'un hazır SpeechService'i yerine bunu
 * kullanıyoruz çünkü yankı engelleme (AEC) ve gürültü azaltma ekleyebiliyoruz: telefonun
 * kendi çaldığı müzik/video mikrofondan çıkarılır, kullanıcının sesi daha net duyulur.
 * Ses tanıtma kaydı da aynı yoldan geçer ki ses izi aynı koşullarda alınsın.
 */
class MicLoop(
    private val recognizers: List<Recognizer>,
    private val onResult: (String) -> Unit,
    private val onError: (String) -> Unit,
    /** Verilirse kayıt bu cihazın (ör. Bluetooth kulaklık) mikrofonundan yapılır. */
    private val btMic: android.media.AudioDeviceInfo? = null,
    private val context: Context? = null,
) {
    constructor(recognizer: Recognizer, onResult: (String) -> Unit, onError: (String) -> Unit) :
        this(listOf(recognizer), onResult, onError, null, null)

    private var routedBt = false

    /** Son 4 saniyelik ses (uyandırma kelimesinin kimin sesi olduğunu kontrol etmek için). */
    private val ring = ShortArray(VoskModels.SAMPLE_RATE.toInt() * 4)
    private var ringPos = 0
    private var ringFilled = 0

    /** Son tanıma sonucu geldiği anda alınan son ~2 saniyelik ses. */
    @Volatile var lastResultAudio: ShortArray? = null
        private set

    private fun pushRing(buf: ShortArray, n: Int) {
        for (i in 0 until n) {
            ring[ringPos] = buf[i]
            ringPos = (ringPos + 1) % ring.size
        }
        ringFilled = minOf(ring.size, ringFilled + n)
    }

    private fun snapshot(samples: Int): ShortArray {
        val n = minOf(samples, ringFilled)
        val out = ShortArray(n)
        var p = (ringPos - n + ring.size) % ring.size
        for (i in 0 until n) {
            out[i] = ring[p]
            p = (p + 1) % ring.size
        }
        return out
    }

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
        val ctx = context
        val dev = btMic
        if (dev != null && ctx != null) {
            // Bluetooth kulaklığın mikrofonunu aç (kulaklık "görüşme" moduna geçer).
            routedBt = runCatching { ctx.getSystemService(AudioManager::class.java).setCommunicationDevice(dev) }.getOrDefault(false)
        }
        // VOICE_COMMUNICATION telefonun yankı engelleme ve gürültü azaltmasını açar; ama Bluetooth eller serbest
        // cihazı (araba, kulaklık) bağlıyken Samsung bu kaynakla onu "telefon görüşmesi" moduna sokuyor.
        // Bu yüzden Bluetooth cihazı varken (bilerek kulaklık mikrofonu kullanılmıyorsa) normal tanıma kaynağı kullanılır.
        val anyCtx = ctx ?: com.jarvis.assistant.JarvisApp.instance
        val source = if (routedBt || !com.jarvis.assistant.core.AudioRoute.hasBluetoothComm(anyCtx))
            android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION
        else android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION
        val r = android.media.AudioRecord(
            source,
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
                    // Ortam gürültüsünü ölç (otomatik hassasiyet için).
                    var sum = 0.0
                    for (i in 0 until n) sum += buf[i].toDouble() * buf[i]
                    com.jarvis.assistant.core.Ambient.feed(kotlin.math.sqrt(sum / n))
                    pushRing(buf, n)
                    // Aynı ses parçası her tanıyıcıya verilir (ör. İngilizce + Türkçe model).
                    for (rec in recognizers) {
                        if (rec.acceptWaveForm(buf, n)) {
                            val res = rec.result
                            lastResultAudio = snapshot(VoskModels.SAMPLE_RATE.toInt() * 2)
                            main.post { if (running) onResult(res) }
                        }
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
        val ctx = context
        if (routedBt && ctx != null) {
            runCatching { ctx.getSystemService(AudioManager::class.java).clearCommunicationDevice() }
            routedBt = false
        }
    }
}
