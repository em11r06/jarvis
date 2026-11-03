package com.jarvis.assistant.wake

import android.content.Context
import android.util.Log
import com.jarvis.assistant.data.Settings
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import kotlin.math.sqrt

/**
 * Konuşmacı tanıma (sadece kullanıcının sesine tepki): CAM++ modeli (sherpa-onnx).
 * Vosk'un küçük ses izi modelinden çok daha iyi ayırt eder; konuşulan dil ve kelimeden bağımsızdır.
 * Kayıtta alınan seslerin "ses imzaları" saklanır; yeni ses bunlara yeterince benziyorsa kullanıcıdır.
 */
object SpeakerId {
    private const val TAG = "JarvisSpeaker"
    private const val MODEL = "spk/campplus.onnx"
    private const val RATE = 16000

    private var extractor: SpeakerEmbeddingExtractor? = null
    private var failed = false
    private val lock = Any()

    /** Model APK'da var ve yüklenebiliyor mu? */
    fun available(ctx: Context): Boolean = synchronized(lock) { load(ctx) != null }

    private fun load(ctx: Context): SpeakerEmbeddingExtractor? {
        extractor?.let { return it }
        if (failed) return null
        return runCatching {
            SpeakerEmbeddingExtractor(ctx.applicationContext.assets, SpeakerEmbeddingExtractorConfig(model = MODEL, numThreads = 2))
        }.onFailure { failed = true; Log.w(TAG, "Konuşmacı modeli açılamadı", it) }.getOrNull()?.also { extractor = it }
    }

    /** 16 kHz PCM sesin ses imzası (birim uzunlukta); ses çok kısaysa null. */
    fun embed(ctx: Context, pcm: ShortArray): FloatArray? = synchronized(lock) {
        if (pcm.size < RATE * 4 / 10) return null
        val ex = load(ctx) ?: return null
        runCatching {
            val stream = ex.createStream()
            try {
                stream.acceptWaveform(FloatArray(pcm.size) { pcm[it] / 32768f }, RATE)
                stream.inputFinished()
                if (!ex.isReady(stream)) null else normalize(ex.compute(stream))
            } finally {
                stream.release()
            }
        }.onFailure { Log.w(TAG, "Ses imzası çıkarılamadı", it) }.getOrNull()
    }

    fun embedBytes(ctx: Context, pcm: ByteArray): FloatArray? =
        embed(ctx, ShortArray(pcm.size / 2) { ((pcm[it * 2].toInt() and 0xFF) or (pcm[it * 2 + 1].toInt() shl 8)).toShort() })

    private fun normalize(v: FloatArray): FloatArray {
        var n = 0f
        for (x in v) n += x * x
        n = sqrt(n)
        return if (n == 0f) v else FloatArray(v.size) { v[it] / n }
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var d = 0f
        for (i in a.indices) d += a[i] * b[i]
        return d
    }

    // ------------------------------------------------------------------
    // Kayıt
    // ------------------------------------------------------------------

    fun enrolled(settings: Settings) = settings.spkThreshold > 0f && settings.spkSamples.isNotBlank()

    private fun samples(settings: Settings): List<FloatArray> = settings.spkSamples.split(';').filter { it.isNotBlank() }.mapNotNull { r ->
        runCatching { r.split(',').map { it.toFloat() }.toFloatArray() }.getOrNull()
    }

    /** Kayıtlı örneklerin ortalamasına ve en yakın iki örneğe benzerlik (hangisi yüksekse). */
    private fun scoreAgainst(v: FloatArray, list: List<FloatArray>): Float {
        val same = list.filter { it.size == v.size }
        if (same.isEmpty()) return 0f
        val mean = FloatArray(v.size)
        same.forEach { s -> for (i in mean.indices) mean[i] += s[i] / same.size }
        val top = same.map { dot(v, it) }.sortedDescending().take(2).average().toFloat()
        return maxOf(dot(v, normalize(mean)), top)
    }

    fun score(settings: Settings, v: FloatArray): Float = scoreAgainst(v, samples(settings))

    /**
     * Örnekleri saklar, eşiği hesaplar: her örneğin diğerlerine benzerliğinin en düşüğünden biraz aşağısı.
     * Başkalarının sesi bu modelde genelde 0,45'in altında kalır; kullanıcının kendi sesi 0,6-0,8 civarıdır.
     */
    fun saveEnrollment(settings: Settings, list: List<FloatArray>) {
        settings.spkSamples = list.joinToString(";") { s -> s.joinToString(",") { "%.5f".format(java.util.Locale.US, it) } }
        val loo = list.indices.map { i -> scoreAgainst(list[i], list.filterIndexed { j, _ -> j != i }) }.sorted()
        // En kötü örneği gürültü sayıp ikinci en kötüsüne bak.
        val ref = loo.getOrElse(1) { loo.firstOrNull() ?: 0.6f }
        settings.spkThreshold = (ref - 0.12f).coerceIn(0.30f, 0.55f)
        Log.d(TAG, "Kayıt: ${list.size} örnek, benzerlikler $loo, eşik ${settings.spkThreshold}")
    }

    fun clear(settings: Settings) {
        settings.spkSamples = ""
        settings.spkThreshold = 0f
    }

    /**
     * Bu ses kullanıcının mı? @param extraSlack kısa sesler (uyandırma kelimesi) ve kulaklık için hoşgörü
     * @return null: karar verilemedi (ses çok kısa ya da model yok)
     */
    fun isUser(ctx: Context, settings: Settings, pcm: ShortArray, extraSlack: Float = 0f): Boolean? {
        if (!enrolled(settings)) return null
        val v = embed(ctx, pcm) ?: return null
        val sim = score(settings, v)
        // Kısa seste (2 sn altı) ses imzası daha az kesindir.
        val shortSlack = if (pcm.size < RATE * 2) 0.06f else 0f
        val th = settings.spkThreshold - shortSlack - extraSlack
        Log.d(TAG, "Benzerlik $sim (eşik $th, ${pcm.size / RATE.toFloat()} sn)")
        return sim >= th
    }
}
