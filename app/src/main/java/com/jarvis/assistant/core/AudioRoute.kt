package com.jarvis.assistant.core

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import com.jarvis.assistant.data.Settings

/** Kulaklık takılı mı, kulaklığın mikrofonu kullanılmalı mı? */
object AudioRoute {
    private val HEADSET_TYPES = setOf(
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET,
    )

    /** Saat gibi kulaklık olmayan Bluetooth cihazları (adından tanınır). */
    private fun isWatch(d: AudioDeviceInfo): Boolean {
        val n = d.productName?.toString()?.lowercase().orEmpty()
        return listOf("watch", "saat", "band", "fit").any { n.contains(it) }
    }

    /** Ses kulaklığa mı gidiyor (kablolu ya da Bluetooth)? */
    fun headset(ctx: Context): Boolean =
        ctx.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .any { it.type in HEADSET_TYPES && !isWatch(it) }

    /** Bluetooth kulaklığın mikrofonu (telefon görüşmesi modunda kullanılabilir), yoksa null. */
    fun bluetoothMic(ctx: Context): AudioDeviceInfo? =
        ctx.getSystemService(AudioManager::class.java).availableCommunicationDevices
            .firstOrNull { (it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET) && !isWatch(it) }

    /**
     * Kulaklığın mikrofonu kullanılacak mı?
     * - Kapalı: hiç. Hep açık: kulaklık varsa her zaman.
     * - Otomatik: komut dinlerken her zaman; uyandırma kelimesini beklerken sadece müzik çalmıyorsa
     *   (kulaklık mikrofonu açılınca kulaklık "görüşme" moduna geçer ve müzik kalitesi düşer).
     */
    fun micFor(ctx: Context, settings: Settings, forCommand: Boolean): AudioDeviceInfo? {
        val dev = bluetoothMic(ctx) ?: return null
        return when (settings.headsetMicMode) {
            Settings.HEADSET_OFF -> null
            Settings.HEADSET_ALWAYS -> dev
            else -> if (forCommand || !ctx.getSystemService(AudioManager::class.java).isMusicActive) dev else null
        }
    }
}

/**
 * Ortam gürültüsü: uyandırma dinlemesi sürekli ölçer. Mikrofon hassasiyeti "Otomatik" iken
 * sessiz odada daha hassas, gürültülü yerde daha seçici davranmak için kullanılır.
 */
object Ambient {
    /** Ortam gürültüsünün (konuşma dışı) ses seviyesi, RMS. */
    @Volatile var noise = 200.0
        private set

    /** Yeni ses parçasının seviyesini ekler; konuşma/ani sesler gürültü tabanını yükseltmesin diye yavaş günceller. */
    fun feed(rms: Double) {
        noise = if (rms < noise * 1.5) noise * 0.97 + rms * 0.03 else noise * 0.999 + rms * 0.001
    }

    /** 0 gürültülü, 1 normal, 2 sessiz ortam. */
    fun quietness(): Int = when {
        noise > 700 -> 0
        noise > 220 -> 1
        else -> 2
    }

    /** Kullanılacak hassasiyet: 0 düşük, 1 normal, 2 yüksek. Otomatikse ortama göre. */
    fun sensitivity(settings: Settings): Int =
        if (settings.micSensitivity == Settings.SENS_AUTO) quietness() else settings.micSensitivity
}
