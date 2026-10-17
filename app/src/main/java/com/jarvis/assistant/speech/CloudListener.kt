package com.jarvis.assistant.speech

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Base64
import android.util.Log
import com.jarvis.assistant.ai.GeminiQuota
import com.jarvis.assistant.ai.Http
import com.jarvis.assistant.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import kotlin.coroutines.coroutineContext
import kotlin.math.sqrt

/**
 * Gelişmiş ses tanıma: sesi kendimiz kaydeder, konuşma bitince Gemini'ye yazıya döktürürüz.
 * Gemini rehberdeki isimleri, uygulama adlarını ve konuşmanın bağlamını bildiği için
 * telefonun hazır tanıyıcısından çok daha doğru anlar (özellikle isimler, argo, hızlı konuşma).
 */
class CloudListener(private val context: Context, private val settings: Settings) {

    /** Kayıt sonucu: ses yoksa NoSpeech, Gemini'ye ulaşılamazsa Failed (o zaman telefonun tanıyıcısı kullanılır). */
    sealed class Result {
        data class Text(val heard: Heard) : Result()
        object NoSpeech : Result()
        object Failed : Result()
    }

    @Volatile
    var hints: List<String> = emptyList()

    /** Son konuşmadan kısa bağlam (ör. Jarvis'in son sorusu), doğru anlamaya yardımcı olur. */
    @Volatile
    var context_: String = ""

    suspend fun listen(startTimeoutMs: Long, onPartial: (String) -> Unit): Result {
        val pcm = withContext(Dispatchers.IO) { record(startTimeoutMs, onPartial) } ?: return Result.NoSpeech
        onPartial("Anlıyorum…")
        val text = runCatching { transcribe(pcm) }
            .onFailure { Log.w(TAG, "Gemini yazıya dökemedi", it) }
            .getOrElse { return Result.Failed }
        return if (text.isNullOrBlank()) Result.NoSpeech else Result.Text(Heard(text, emptyList(), 1f))
    }

    /**
     * Konuşma başlayana kadar bekler, sustuktan sonra durur. Basit ses seviyesi ölçümüyle çalışır:
     * ilk anlardaki ortam gürültüsü ölçülür, onun belirgin üstü "konuşma" sayılır.
     * @return 16 kHz mono PCM; konuşma olmadıysa null
     */
    @SuppressLint("MissingPermission")
    private suspend fun record(startTimeoutMs: Long, onPartial: (String) -> Unit): ByteArray? {
        val rate = SAMPLE_RATE
        val frame = rate / 50 // 20 ms
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(min, frame * 2 * 10),
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return null
        }
        val out = ByteArrayOutputStream()
        // Konuşmanın başı kesilmesin diye son 300 ms hep tutulur.
        val preRoll = ArrayDeque<ByteArray>()
        val buf = ShortArray(frame)
        var noise = 0.0
        var frames = 0
        var speechFrames = 0
        var silenceMs = 0
        var started = false
        var elapsed = 0
        try {
            rec.startRecording()
            while (coroutineContext.isActive) {
                val n = rec.read(buf, 0, frame)
                if (n <= 0) continue
                elapsed += 20
                var sum = 0.0
                for (i in 0 until n) sum += buf[i].toDouble() * buf[i]
                val rms = sqrt(sum / n)
                val bytes = ByteArray(n * 2)
                for (i in 0 until n) {
                    bytes[i * 2] = (buf[i].toInt() and 0xFF).toByte()
                    bytes[i * 2 + 1] = (buf[i].toInt() shr 8).toByte()
                }
                frames++
                if (frames <= 15) { // ilk 300 ms: ortam gürültüsü
                    noise = if (frames == 1) rms else noise * 0.8 + rms * 0.2
                }
                val threshold = maxOf(noise * 2.8, 350.0)
                val loud = rms > threshold
                if (!started) {
                    preRoll.addLast(bytes)
                    if (preRoll.size > 15) preRoll.removeFirst()
                    if (loud) speechFrames++ else speechFrames = maxOf(0, speechFrames - 1)
                    if (speechFrames >= 4) { // ~80 ms sürekli ses: konuşma başladı
                        started = true
                        preRoll.forEach { out.write(it) }
                        preRoll.clear()
                        withContext(Dispatchers.Main) { onPartial("Dinliyorum…") }
                    } else if (elapsed > startTimeoutMs) {
                        return null
                    } else if (!loud && frames > 15) {
                        // Konuşma yokken gürültü seviyesini yavaşça güncelle.
                        noise = noise * 0.95 + rms * 0.05
                    }
                    continue
                }
                out.write(bytes)
                silenceMs = if (loud) 0 else silenceMs + 20
                if (silenceMs >= END_SILENCE_MS || out.size() >= rate * 2 * MAX_SECONDS) break
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
        // Çok kısa ses (öksürük, tık) konuşma sayılmaz.
        return out.toByteArray().takeIf { started && it.size > rate * 2 * 4 / 10 }
    }

    private suspend fun transcribe(pcm: ByteArray): String? {
        val key = settings.geminiKey
        if (key.isBlank()) throw IllegalStateException("Gemini anahtarı yok")
        if (GeminiQuota.billingBlocked()) throw IllegalStateException("Gemini bakiyesi bitti")
        val wav = wav(pcm)
        val prompt = buildString {
            append("Bu ses kaydında bir kişi telefonundaki Türkçe sesli asistana (adı Jarvis) bir şey söylüyor. ")
            append("Söyleneni kelimesi kelimesine, doğru Türkçe yazımla yazıya dök. Sadece söylenen metni yaz; açıklama, tırnak, etiket ekleme. ")
            append("Argo, kısaltma ve günlük konuşmayı olduğu gibi koru; düzeltme ya da cevap verme. ")
            append("Kayıtta anlaşılır konuşma yoksa sadece - yaz.")
            if (context_.isNotBlank()) append("\nKonuşmanın bağlamı (doğru anlamana yardımcı olsun): ").append(context_.take(400))
            if (hints.isNotEmpty()) {
                append("\nKişinin rehberindeki isimler ve telefonundaki uygulamalar (bunlardan biri söylenmişse aynen bu yazımla yaz): ")
                append(hints.take(250).joinToString(", "))
            }
        }
        val body = JSONObject().put(
            "contents",
            JSONArray().put(
                JSONObject().put("role", "user").put(
                    "parts",
                    JSONArray()
                        .put(JSONObject().put("inlineData", JSONObject().put("mimeType", "audio/wav").put("data", Base64.encodeToString(wav, Base64.NO_WRAP))))
                        .put(JSONObject().put("text", prompt)),
                ),
            ),
        ).put("generationConfig", JSONObject().put("temperature", 0))
        var lastError = "model yok"
        for (m in MODELS) {
            if (!GeminiQuota.isAvailable(m)) continue
            val res = Http.postJson(
                "https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent",
                body.toString(), mapOf("x-goog-api-key" to key),
            )
            when {
                res.code == 429 -> { GeminiQuota.markExhausted(m, res.body); lastError = "kota"; continue }
                res.code == 402 -> { GeminiQuota.markBilling(); throw IllegalStateException("Gemini bakiyesi bitti") }
                res.code == 404 || res.code >= 500 -> { GeminiQuota.markUnavailable(m, 10 * 60_000L); lastError = "${res.code}"; continue }
                res.code !in 200..299 -> throw IllegalStateException("Gemini ${res.code}: ${res.body.take(200)}")
            }
            val parts = JSONObject(res.body).optJSONArray("candidates")?.optJSONObject(0)
                ?.optJSONObject("content")?.optJSONArray("parts") ?: return null
            val text = (0 until parts.length()).mapNotNull { parts.getJSONObject(it).takeIf { p -> !p.optBoolean("thought") }?.optString("text") }
                .joinToString("").trim().trim('"', '“', '”')
            return text.takeIf { it.isNotBlank() && it != "-" && it != "—" }
        }
        throw IllegalStateException("Gemini'ye ulaşılamadı ($lastError)")
    }

    private fun wav(pcm: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(pcm.size + 44)
        fun int(v: Int) = out.write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))
        fun short(v: Int) = out.write(byteArrayOf(v.toByte(), (v shr 8).toByte()))
        out.write("RIFF".toByteArray()); int(36 + pcm.size); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); int(16); short(1); short(1); int(SAMPLE_RATE); int(SAMPLE_RATE * 2); short(2); short(16)
        out.write("data".toByteArray()); int(pcm.size); out.write(pcm)
        return out.toByteArray()
    }

    companion object {
        private const val TAG = "JarvisCloudStt"
        private const val SAMPLE_RATE = 16000
        private const val END_SILENCE_MS = 1100
        private const val MAX_SECONDS = 30
        /** Hızlı ve ucuz modeller; ses anlama hepsinde var. */
        private val MODELS = listOf("gemini-flash-lite-latest", "gemini-2.5-flash-lite", "gemini-flash-latest", "gemini-2.5-flash")
    }
}
