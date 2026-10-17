package com.jarvis.assistant.ai

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Gemini kotalarını takip eder. Kotası dolan model, kotasının yenileneceği ana kadar
 * atlanır; o an gelince Jarvis kendiliğinden yine en üstteki (seçili) modeli kullanır.
 * Bilgi telefonda saklanır, uygulama kapanıp açılsa da korunur.
 */
object GeminiQuota {
    private var prefs: SharedPreferences? = null

    /** Son cevabı veren model (Ayarlar'da gösterilir). */
    private val _activeModel = MutableStateFlow("")
    val activeModel: StateFlow<String> = _activeModel

    fun init(context: Context) {
        prefs = context.getSharedPreferences("gemini_quota", Context.MODE_PRIVATE)
    }

    /** Model şu an kullanılabilir mi (kotası yenilenmiş mi)? */
    fun isAvailable(model: String, now: Long = System.currentTimeMillis()) = blockedUntil(model) <= now

    fun blockedUntil(model: String): Long = prefs?.getLong(model, 0L) ?: 0L

    fun markUsed(model: String) {
        _activeModel.value = model
    }

    /**
     * 429 cevabına göre modelin ne zamana kadar bekleyeceğini kaydeder.
     * Günlük kota Google'da her gün Pasifik saatiyle gece yarısı sıfırlanır;
     * dakikalık sınırda Google'ın bildirdiği bekleme süresi kullanılır.
     */
    fun markExhausted(model: String, errorBody: String, now: Long = System.currentTimeMillis()) {
        val daily = errorBody.contains("PerDay", ignoreCase = true)
        val until = if (daily) {
            nextPacificMidnight() + 60_000L
        } else {
            val delay = Regex("\"retryDelay\"\\s*:\\s*\"(\\d+(?:\\.\\d+)?)s\"").find(errorBody)
                ?.groupValues?.get(1)?.toDoubleOrNull()
            now + ((delay ?: 60.0) * 1000).toLong() + 1000L
        }
        prefs?.edit()?.putLong(model, until)?.apply()
    }

    /** Model geçici olarak (sunucu hatası, model yok) kısa süre atlansın. */
    fun markUnavailable(model: String, millis: Long, now: Long = System.currentTimeMillis()) {
        prefs?.edit()?.putLong(model, now + millis)?.apply()
    }

    /** Yeni API anahtarı girilince eski anahtarın "kota doldu" bilgisi geçersizdir; hepsini sıfırla. */
    fun reset() {
        prefs?.edit()?.clear()?.apply()
    }

    fun nextPacificMidnight(): Long {
        val zone = ZoneId.of("America/Los_Angeles")
        return ZonedDateTime.now(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    }
}
