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

    /** Araba ses sistemlerinin adlarında sık geçenler (cihaz sınıfı okunamazsa yedek). */
    private val CAR_NAMES = listOf(
        "car", "carkit", "auto", "araba", "ozkurt cros", "cross", "corolla", "touch 2", "toyota touch", "multimedia", "media nav", "medianav", "uconnect", "sync", "mzd", "carplay", "hands-free", "handsfree",
        "toyota", "renault", "fiat", "volkswagen", "vw ", "ford", "hyundai", "kia", "bmw", "mercedes", "audi", "skoda", "seat",
        "peugeot", "citroen", "opel", "honda", "nissan", "volvo", "dacia", "tesla", "togg", "chery", "mg ", "byd", "suzuki", "mazda", "jeep",
    )

    /**
     * Bu Bluetooth cihazı bir araba mı? Araba sistemlerinde mikrofonu açmak arabayı "telefon görüşmesi"
     * moduna sokar; bu yüzden arabada kulaklık mikrofonu hiç kullanılmaz, telefonun mikrofonu yeter.
     */
    private fun isCar(ctx: Context, d: AudioDeviceInfo): Boolean {
        // Android Auto bağlıysa kesin araba.
        val ui = ctx.getSystemService(android.app.UiModeManager::class.java)
        if (ui?.currentModeType == android.content.res.Configuration.UI_MODE_TYPE_CAR) return true
        // İzin verilmişse cihaz sınıfına bak (araba ses sistemi / eller serbest).
        val byClass = runCatching {
            if (ctx.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED) return@runCatching null
            val bt = ctx.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter ?: return@runCatching null
            val cls = bt.getRemoteDevice(d.address).bluetoothClass ?: return@runCatching null
            cls.deviceClass == android.bluetooth.BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO ||
                cls.deviceClass == android.bluetooth.BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE
        }.getOrNull()
        if (byClass != null) return byClass
        val n = " " + d.productName?.toString()?.lowercase().orEmpty() + " "
        // Kullanıcının Ayarlar'a yazdığı araba adları da sayılır.
        val custom = com.jarvis.assistant.JarvisApp.instance.settings.carNames.split(',', '\n')
            .map { it.trim().lowercase() }.filter { it.length >= 3 }
        return (CAR_NAMES + custom).any { n.contains(it) }
    }

    /** Arabaya bağlı mı (Android Auto ya da araba Bluetooth'u)? */
    fun inCar(ctx: Context): Boolean =
        ctx.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .any { (it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER) && isCar(ctx, it) }

    /** Ses kulaklığa mı gidiyor (kablolu ya da Bluetooth)? Araba hoparlörü kulaklık sayılmaz. */
    fun headset(ctx: Context): Boolean =
        ctx.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .any { it.type in HEADSET_TYPES && !isWatch(it) && !isCar(ctx, it) }

    /** Bluetooth kulaklığın mikrofonu (telefon görüşmesi modunda kullanılabilir), yoksa null. */
    fun bluetoothMic(ctx: Context): AudioDeviceInfo? =
        ctx.getSystemService(AudioManager::class.java).availableCommunicationDevices
            .firstOrNull { (it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET) && !isWatch(it) && !isCar(ctx, it) }

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
