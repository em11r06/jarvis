package com.jarvis.assistant.core

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

/** Kulaklık takılı mı, kulaklığın mikrofonu kullanılabilir mi? */
object AudioRoute {
    private val HEADSET_TYPES = setOf(
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET,
    )

    /** Ses kulaklığa mı gidiyor (kablolu ya da Bluetooth)? */
    fun headset(ctx: Context): Boolean =
        ctx.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in HEADSET_TYPES }

    /** Bluetooth kulaklığın mikrofonu (telefon görüşmesi modunda kullanılabilir), yoksa null. */
    fun bluetoothMic(ctx: Context): AudioDeviceInfo? =
        ctx.getSystemService(AudioManager::class.java).availableCommunicationDevices
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }
}
