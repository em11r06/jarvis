package com.jarvis.assistant.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import com.jarvis.assistant.tools.AppFinder

/**
 * Ses tanıyıcıya verilecek ipucu kelimeler: rehberdeki isimler, yüklü uygulama adları ve
 * sık kullanılan komut kelimeleri. Böylece "Ayşe'yi ara" gibi isimler doğru yazılır.
 */
object SpeechHints {
    private var cache: List<String> = emptyList()
    private var cachedAt = 0L

    private val COMMON = listOf(
        "Jarvis", "WhatsApp", "Instagram", "YouTube", "Spotify", "Google Haritalar", "Telegram", "Netflix",
        "fener", "alarm", "zamanlayıcı", "sesi fulle", "sesi kıs", "şarkıyı geç", "yol tarifi", "mesaj at",
    )

    fun get(ctx: Context): List<String> {
        if (System.currentTimeMillis() - cachedAt < 30 * 60_000L && cache.isNotEmpty()) return cache
        val out = LinkedHashSet<String>(COMMON)
        runCatching { AppFinder.installed(ctx).forEach { out += it.label } }
        if (ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            runCatching {
                ctx.contentResolver.query(
                    ContactsContract.Contacts.CONTENT_URI,
                    arrayOf(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
                    null, null, ContactsContract.Contacts.TIMES_CONTACTED + " DESC",
                )?.use { c ->
                    while (c.moveToNext() && out.size < 600) {
                        c.getString(0)?.takeIf { it.isNotBlank() && it.length <= 40 }?.let { out += it }
                    }
                }
            }
        }
        cache = out.toList()
        cachedAt = System.currentTimeMillis()
        return cache
    }
}
