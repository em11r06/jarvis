package com.jarvis.assistant.tools

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.telephony.SmsManager
import com.jarvis.assistant.core.Launcher
import org.json.JSONObject

data class Contact(val name: String, val number: String)

object Contacts {
    fun search(ctx: Context, query: String, limit: Int = 5): List<Contact> {
        if (ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return emptyList()
        val out = mutableListOf<Contact>()
        val nq = normalizeName(query)
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0) ?: continue
                val num = c.getString(1) ?: continue
                if (normalizeName(name).contains(nq)) out += Contact(name, num)
            }
        }
        // Tam eşleşenler önce.
        return out.distinctBy { it.name + it.number.filter(Char::isDigit) }
            .sortedBy { if (normalizeName(it.name) == nq) 0 else if (normalizeName(it.name).startsWith(nq)) 1 else 2 }
            .take(limit)
    }

    /** Bir kişi adı veya numarası verildiğinde numarayı bulur. */
    fun resolve(ctx: Context, who: String): Pair<Contact?, List<Contact>> {
        val digits = who.filter { it.isDigit() || it == '+' }
        if (digits.length >= 5 && digits.length >= who.count { !it.isWhitespace() } - 2) return Contact(who, digits) to emptyList()
        val found = search(ctx, who)
        val exact = found.filter { normalizeName(it.name) == normalizeName(who) }
        return when {
            exact.size == 1 -> exact[0] to found
            found.size == 1 -> found[0] to found
            else -> null to found
        }
    }
}

class KisiBulTool : Tool {
    override val name = "kisi_bul"
    override val description = "Rehberde isimle kişi arar, isim ve telefon numaralarını döndürür."
    override val parameters = Schema.obj("ad" to Schema.str("Kişinin adı"), required = listOf("ad"))

    override suspend fun run(ctx: Context, args: JSONObject): String {
        if (ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return "Rehber izni verilmemiş."
        val list = Contacts.search(ctx, args.optString("ad"))
        if (list.isEmpty()) return "Rehberde bulunamadı."
        return list.joinToString("\n") { "${it.name}: ${it.number}" }
    }
}

private const val CONFIRM_MSG = "ONAY GEREKLİ: Kullanıcıya ne yapacağını söyle ve sesli onay iste. Evet derse aynı aracı onaylandi=true ile tekrar çağır."

class AramaYapTool : Tool {
    override val name = "arama_yap"
    override val description = "Bir kişiyi veya numarayı telefonla arar. Geri alınamaz: önce kullanıcıdan sesli onay al, sonra onaylandi=true ile çağır."
    override val parameters = Schema.obj(
        "kisi" to Schema.str("Rehberdeki kişi adı veya telefon numarası"),
        "onaylandi" to Schema.bool("Kullanıcı aramayı sesli olarak onayladı mı"),
        required = listOf("kisi", "onaylandi"),
    )

    override suspend fun run(ctx: Context, args: JSONObject): String {
        val (contact, options) = Contacts.resolve(ctx, args.optString("kisi"))
        if (contact == null) {
            return if (options.isEmpty()) "Kişi bulunamadı." else "Birden fazla eşleşme var, hangisi? " + options.joinToString { "${it.name} (${it.number})" }
        }
        if (!args.optBoolean("onaylandi", false)) return "$CONFIRM_MSG Aranacak: ${contact.name} ${contact.number}"
        val granted = ctx.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        val intent = Intent(if (granted) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(contact.number)))
        return if (Launcher.start(ctx, intent)) {
            if (granted) "${contact.name} aranıyor." else "Arama izni yok, numara çevirici ekranında açıldı."
        } else "Arama başlatılamadı."
    }
}

class SmsGonderTool : Tool {
    override val name = "sms_gonder"
    override val description = "SMS gönderir. Geri alınamaz: önce mesajı ve alıcıyı kullanıcıya okuyup sesli onay al, sonra onaylandi=true ile çağır."
    override val parameters = Schema.obj(
        "kisi" to Schema.str("Rehberdeki kişi adı veya numara"),
        "mesaj" to Schema.str("Gönderilecek metin"),
        "onaylandi" to Schema.bool("Kullanıcı gönderimi sesli olarak onayladı mı"),
        required = listOf("kisi", "mesaj", "onaylandi"),
    )

    override suspend fun run(ctx: Context, args: JSONObject): String {
        val text = args.optString("mesaj")
        if (text.isBlank()) return "Hata: mesaj boş."
        val (contact, options) = Contacts.resolve(ctx, args.optString("kisi"))
        if (contact == null) {
            return if (options.isEmpty()) "Kişi bulunamadı." else "Birden fazla eşleşme var, hangisi? " + options.joinToString { "${it.name} (${it.number})" }
        }
        if (!args.optBoolean("onaylandi", false)) return "$CONFIRM_MSG Alıcı: ${contact.name}, mesaj: '$text'"
        if (ctx.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) return "SMS izni verilmemiş."
        @Suppress("DEPRECATION")
        val sms = if (android.os.Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java) else SmsManager.getDefault()
        val parts = sms.divideMessage(text)
        sms.sendMultipartTextMessage(contact.number, null, parts, null, null)
        return "${contact.name} kişisine SMS gönderildi."
    }
}
