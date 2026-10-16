package com.jarvis.assistant.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.jarvis.assistant.core.Launcher
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale

/** Türkçe karakterleri sadeleştirip küçük harfe çevirir: "Ayarlar" ~ "ayarlar". */
fun normalizeName(s: String): String {
    val lower = s.lowercase(Locale("tr", "TR"))
        .replace('ı', 'i').replace('ğ', 'g').replace('ü', 'u').replace('ş', 's').replace('ö', 'o').replace('ç', 'c')
    return Normalizer.normalize(lower, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").replace(Regex("[^a-z0-9 ]"), "").trim()
}

private fun levenshtein(a: String, b: String): Int {
    val d = IntArray(b.length + 1) { it }
    for (i in 1..a.length) {
        var prev = d[0]
        d[0] = i
        for (j in 1..b.length) {
            val tmp = d[j]
            d[j] = minOf(d[j] + 1, d[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev = tmp
        }
    }
    return d[b.length]
}

data class InstalledApp(val label: String, val pkg: String)

object AppFinder {
    fun installed(ctx: Context): List<InstalledApp> {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0).map {
            InstalledApp(it.loadLabel(pm).toString(), it.activityInfo.packageName)
        }.distinctBy { it.pkg }
    }

    fun find(ctx: Context, query: String): InstalledApp? {
        val q = normalizeName(query)
        if (q.isEmpty()) return null
        val apps = installed(ctx)
        val scored = apps.map { app ->
            val l = normalizeName(app.label)
            val score = when {
                l == q -> 0
                l.startsWith(q) -> 1
                l.contains(q) -> 2
                q.contains(l) && l.length >= 3 -> 3
                app.pkg.lowercase().contains(q.replace(" ", "")) -> 4
                else -> 10 + levenshtein(l, q)
            }
            app to score
        }.sortedBy { it.second }
        val best = scored.firstOrNull() ?: return null
        // Çok farklıysa eşleşme sayma.
        return if (best.second < 10 || best.second - 10 <= maxOf(2, q.length / 4)) best.first else null
    }
}

class UygulamaAcTool : Tool {
    override val name = "uygulama_ac"
    override val description = "Telefonda yüklü bir uygulamayı adına göre açar (ör. WhatsApp, Instagram, Ayarlar, Kamera)."
    override val parameters = Schema.obj("ad" to Schema.str("Uygulamanın adı"), required = listOf("ad"))

    override suspend fun run(ctx: Context, args: JSONObject): String {
        val name = args.optString("ad")
        val app = AppFinder.find(ctx, name)
            ?: return "'$name' adında uygulama bulunamadı. Yüklü bazı uygulamalar: " +
                AppFinder.installed(ctx).map { it.label }.sorted().take(60).joinToString()
        val intent = ctx.packageManager.getLaunchIntentForPackage(app.pkg) ?: return "${app.label} açılamıyor."
        return if (Launcher.start(ctx, intent)) "${app.label} açıldı." else "${app.label} açılamadı."
    }
}

class HaritaAcTool : Tool {
    override val name = "harita_ac"
    override val description = "Haritada bir yeri gösterir ya da oraya yol tarifi başlatır."
    override val parameters = Schema.obj(
        "hedef" to Schema.str("Yer adı veya adres"),
        "mod" to Schema.str("goster: sadece haritada göster; araba, yuruyus, bisiklet, toplu: yol tarifi", listOf("goster", "araba", "yuruyus", "bisiklet", "toplu")),
        required = listOf("hedef"),
    )

    override suspend fun run(ctx: Context, args: JSONObject): String {
        val target = args.optString("hedef")
        val mode = args.optString("mod", "goster")
        val enc = Uri.encode(target)
        val intent = if (mode == "goster" || mode.isBlank()) {
            Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$enc"))
        } else {
            val m = when (mode) { "yuruyus" -> "w"; "bisiklet" -> "b"; "toplu" -> "r"; else -> "d" }
            Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$enc&mode=$m")).setPackage("com.google.android.apps.maps")
        }
        if (Launcher.start(ctx, intent)) return if (mode == "goster") "$target haritada gösteriliyor." else "$target için yol tarifi başladı."
        // Google Haritalar yoksa genel bağlantı dene.
        val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$enc"))
        return if (Launcher.start(ctx, web)) "Harita açıldı." else "Harita açılamadı."
    }
}
