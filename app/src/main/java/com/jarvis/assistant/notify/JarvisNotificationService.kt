package com.jarvis.assistant.notify

import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Parcelable
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.jarvis.assistant.tools.normalizeName
import org.json.JSONObject
import java.io.File

/** Kaydedilen tek bir bildirim mesajı. */
data class StoredMessage(
    val time: Long,
    val pkg: String,
    val app: String,
    /** Sohbet adı (kişi ya da grup). */
    val chat: String,
    /** Gönderen (grupta kişi, bireysel sohbette chat ile aynı). */
    val sender: String,
    val text: String,
) {
    fun toJson(): String = JSONObject()
        .put("t", time).put("p", pkg).put("a", app).put("c", chat).put("s", sender).put("x", text)
        .toString()

    companion object {
        fun fromJson(line: String): StoredMessage? = runCatching {
            val o = JSONObject(line)
            StoredMessage(o.getLong("t"), o.getString("p"), o.optString("a"), o.optString("c"), o.optString("s"), o.optString("x"))
        }.getOrNull()
    }
}

/**
 * Bildirim dinleyicisi. Gelen mesaj bildirimlerini (WhatsApp, SMS, Instagram...) telefonda
 * kaydeder; "sabah altıdan beri gelen mesajlar" sorusu ekranda gezinmeden, eksiksiz cevaplanır.
 * Ayrıca bildirimdeki "Yanıtla" ile uygulamayı açmadan cevap gönderebilir.
 */
class JarvisNotificationService : NotificationListenerService() {

    override fun onListenerConnected() {
        instance = this
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn?.notification ?: return
        if (sbn.packageName == packageName) return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return
        runCatching { MessageLog.add(this, extract(sbn)) }.onFailure { Log.w(TAG, "Bildirim kaydedilemedi", it) }
    }

    private fun extract(sbn: StatusBarNotification): List<StoredMessage> {
        val n = sbn.notification
        val e = n.extras
        val app = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        }.getOrDefault(sbn.packageName)
        val title = e.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val convTitle = e.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
        val chat = (convTitle ?: title).ifBlank { app }

        // Mesajlaşma uygulamaları (WhatsApp vb.) tek bildirimde birden çok mesaj taşır.
        @Suppress("DEPRECATION")
        val msgs: Array<Parcelable>? = e.getParcelableArray(Notification.EXTRA_MESSAGES)
        if (!msgs.isNullOrEmpty()) {
            return msgs.mapNotNull { p ->
                val b = p as? Bundle ?: return@mapNotNull null
                val text = b.getCharSequence("text")?.toString() ?: return@mapNotNull null
                val sender = b.getCharSequence("sender")?.toString()
                    ?: (b.get("sender_person") as? android.app.Person)?.name?.toString()
                    ?: chat
                StoredMessage(b.getLong("time", sbn.postTime), sbn.packageName, app, chat, sender, text)
            }
        }
        val text = (e.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: e.getCharSequence(Notification.EXTRA_TEXT))?.toString()
        if (text.isNullOrBlank()) return emptyList()
        return listOf(StoredMessage(sbn.postTime, sbn.packageName, app, chat, title.ifBlank { chat }, text))
    }

    /**
     * Uygulamayı açmadan, bildirimdeki "Yanıtla" ile cevap gönderir.
     * @return gönderildiyse true; o kişiden yanıtlanabilir bildirim yoksa false
     */
    fun replyTo(pkgs: Set<String>, who: String, message: String): Boolean {
        val target = normalizeName(who)
        if (target.isBlank()) return false
        val sbn = activeNotifications.orEmpty()
            .filter { it.packageName in pkgs }
            .sortedByDescending { it.postTime }
            .firstOrNull { s ->
                val e = s.notification.extras
                val names = listOfNotNull(
                    e.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE),
                    e.getCharSequence(Notification.EXTRA_TITLE),
                ).map { normalizeName(it.toString()) }
                names.any { it.isNotBlank() && (it == target || it.contains(target) || target.contains(it)) } &&
                    replyAction(s.notification) != null
            } ?: return false
        val action = replyAction(sbn.notification) ?: return false
        val inputs = action.remoteInputs
        val intent = Intent()
        val results = Bundle().apply { inputs.forEach { putCharSequence(it.resultKey, message) } }
        RemoteInput.addResultsToIntent(inputs, intent, results)
        return runCatching { action.actionIntent.send(this, 0, intent) }.isSuccess
    }

    private fun replyAction(n: Notification): Notification.Action? =
        n.actions?.firstOrNull { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true }

    companion object {
        private const val TAG = "JarvisNotify"

        @Volatile
        var instance: JarvisNotificationService? = null
            private set
    }
}

/** Bildirim mesajlarının kalıcı kaydı (son 7 gün). */
object MessageLog {
    private const val MAX_AGE_MS = 7L * 24 * 3600 * 1000
    private const val MAX_LINES = 5000
    private val recent = ArrayDeque<String>()

    private fun file(ctx: Context) = File(ctx.filesDir, "messages.jsonl")

    @Synchronized
    fun add(ctx: Context, items: List<StoredMessage>) {
        if (items.isEmpty()) return
        val f = file(ctx)
        // Aynı mesaj güncellenen bildirimde tekrar gelir; son görülenlerle karşılaştır.
        val fresh = items.filter { m ->
            val key = "${m.pkg}|${m.chat}|${m.sender}|${m.text}|${m.time / 1000}"
            if (key in recent) false else {
                recent.addLast(key)
                if (recent.size > 500) recent.removeFirst()
                true
            }
        }
        if (fresh.isEmpty()) return
        f.appendText(fresh.joinToString("") { it.toJson() + "\n" })
        trimIfNeeded(f)
    }

    private fun trimIfNeeded(f: File) {
        if (f.length() < 2_000_000) return
        val cutoff = System.currentTimeMillis() - MAX_AGE_MS
        val keep = f.readLines().mapNotNull { StoredMessage.fromJson(it) }.filter { it.time >= cutoff }.takeLast(MAX_LINES)
        f.writeText(keep.joinToString("") { it.toJson() + "\n" })
    }

    @Synchronized
    fun query(ctx: Context, since: Long, app: String?, who: String?): List<StoredMessage> {
        val f = file(ctx)
        if (!f.exists()) return emptyList()
        val a = app?.let { normalizeName(it) }?.takeIf { it.isNotBlank() }
        val w = who?.let { normalizeName(it) }?.takeIf { it.isNotBlank() }
        return f.readLines().mapNotNull { StoredMessage.fromJson(it) }
            .filter { it.time >= since }
            .filter { a == null || normalizeName(it.app).contains(a) || it.pkg.contains(a) }
            .filter { w == null || normalizeName(it.chat).contains(w) || normalizeName(it.sender).contains(w) }
            .distinctBy { "${it.pkg}|${it.chat}|${it.sender}|${it.text}" }
            .sortedBy { it.time }
    }
}
