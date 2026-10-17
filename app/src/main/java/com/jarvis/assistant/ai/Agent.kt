package com.jarvis.assistant.ai

import android.content.Context
import android.util.Log
import com.jarvis.assistant.data.HistoryStore
import com.jarvis.assistant.data.Settings
import com.jarvis.assistant.tools.ToolRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Jarvis'in beyni: kullanıcının sözünü yapay zekaya gönderir,
 * yapay zeka araç isterse aracı çalıştırıp sonucu geri verir,
 * en sonda sesli okunacak cevabı döndürür.
 */
class Agent(
    private val context: Context,
    private val settings: Settings,
    private val history: HistoryStore,
) {
    private val gemini = GeminiProvider({ settings.geminiKey }, { settings.geminiModel })
    private val claude = ClaudeProvider({ settings.claudeKey }, { settings.claudeModel })

    private fun provider(): AiProvider =
        if (settings.provider == Settings.PROVIDER_CLAUDE) claude else gemini

    /**
     * @param onToolUse araç çalışırken ekranda göstermek için (ör. "uygulama_ac")
     * @param onInterimText yapay zeka araç çağırırken bir şey söylerse (ör. "Hemen açıyorum")
     */
    suspend fun ask(
        userText: String,
        onToolUse: (String) -> Unit = {},
        onInterimText: suspend (String) -> Unit = {},
    ): String {
        // Yarım kalmış araç çağrısı varsa (uygulama kapanmış olabilir) kapat; yoksa API hata verir.
        (history.turns.lastOrNull() as? Turn.Assistant)?.takeIf { it.calls.isNotEmpty() }?.let { last ->
            history.turns += Turn.Tools(last.calls.map { ToolResult(it.id, it.name, "İptal edildi.") })
        }
        history.turns += Turn.User(userText)
        history.save()
        // İş bitince kullanıcıyı kaldığı yere döndürebilmek için şu anki ekranı hatırla.
        com.jarvis.assistant.core.ScreenReturn.remember()
        val tools = ToolRegistry.all.map { it.spec() }

        try {
            return runLoop(tools, onToolUse, onInterimText)
        } finally {
            com.jarvis.assistant.accessibility.JarvisAccessibilityService.instance?.hideCurtain()
        }
    }

    private suspend fun runLoop(
        tools: List<ToolSpec>,
        onToolUse: (String) -> Unit,
        onInterimText: suspend (String) -> Unit,
    ): String {
        repeat(MAX_STEPS) {
            val reply = fixFakeCall(provider().complete(systemPrompt(), history.turns.toList(), tools).turn)
            history.turns += reply
            history.save()

            if (reply.calls.isEmpty()) {
                compactIfNeeded()
                return reply.text.ifBlank { "Tamamdır." }
            }
            if (reply.text.isNotBlank()) {
                // Kullanıcıya bir şey söylerken (ör. onay sorusu) perdeyi kaldır.
                com.jarvis.assistant.accessibility.JarvisAccessibilityService.instance?.hideCurtain()
                onInterimText(reply.text)
            }

            // Araçları sırayla çalıştır (ör. önce şarkıyı aç, sonra sesi fulle).
            val results = reply.calls.map { call ->
                onToolUse(call.name)
                // Ekranda iş yapan araçlarda üstü perdeyle ört; kullanıcı arkadaki gidip gelmeyi görmesin.
                if (call.name in CURTAIN_TOOLS && settings.curtainEnabled) {
                    com.jarvis.assistant.accessibility.JarvisAccessibilityService.instance?.showCurtain()
                }
                val tool = ToolRegistry.find(call.name)
                val out = if (tool == null) {
                    "Hata: '${call.name}' adında araç yok."
                } else {
                    try {
                        // Araçlar ana iş parçacığını (arayüzü) yormasın.
                        withContext(Dispatchers.Default) { tool.run(context, call.args) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Araç hatası ${call.name}", e)
                        "Hata: ${e.message ?: e.javaClass.simpleName}"
                    }
                }
                ToolResult(call.id, call.name, out.take(MAX_TOOL_RESULT))
            }
            history.turns += Turn.Tools(results)
            history.save()
        }
        return "Bu iş beklediğimden uzun sürdü efendim, burada durdum."
    }

    /**
     * Bazı modeller aracı çağırmak yerine "[Araç kullanıldı: uygulama_ac({...})]" diye yazıyor.
     * Böyle bir metin gelirse onu gerçek bir araç çağrısına çeviririz.
     */
    private fun fixFakeCall(reply: Turn.Assistant): Turn.Assistant {
        if (reply.calls.isNotEmpty()) return reply
        val matches = FAKE_CALL.findAll(reply.text).toList()
        if (matches.isEmpty()) return reply
        val calls = matches.mapIndexedNotNull { i, m ->
            val name = m.groupValues[1]
            if (ToolRegistry.find(name) == null) return@mapIndexedNotNull null
            val args = runCatching { org.json.JSONObject(m.groupValues[2].ifBlank { "{}" }) }.getOrDefault(org.json.JSONObject())
            ToolCall("local_${System.nanoTime()}_$i", name, args)
        }
        val text = FAKE_CALL.replace(reply.text, "").trim()
        if (calls.isEmpty()) return reply.copy(text = text)
        Log.w(TAG, "Sahte araç çağrısı düzeltildi: ${calls.map { it.name }}")
        return Turn.Assistant(text, calls, null, null)
    }

    /** Geçmiş uzayınca eski kısmı özetleyip kısaltır (maliyet şişmesin). */
    private suspend fun compactIfNeeded() {
        val turns = history.turns
        if (turns.size <= COMPACT_AT) return
        // Kesme noktası bir kullanıcı mesajı olmalı, böylece araç çiftleri bölünmez.
        var cut = turns.size - KEEP_RECENT
        while (cut > 0 && turns[cut] !is Turn.User) cut--
        if (cut <= 0) return

        val transcript = buildString {
            if (history.summary.isNotBlank()) append("Önceki özet: ${history.summary}\n\n")
            turns.subList(0, cut).forEach { t ->
                when (t) {
                    is Turn.User -> append("Kullanıcı: ${t.text}\n")
                    is Turn.Assistant -> if (t.text.isNotBlank()) append("Jarvis: ${t.text}\n")
                    is Turn.Tools -> t.results.forEach { append("(${it.name} sonucu: ${it.content.take(200)})\n") }
                }
            }
        }
        try {
            val summary = provider().complete(
                "Aşağıdaki konuşmayı Türkçe, en fazla 10 cümlede özetle. Kullanıcı hakkında öğrenilen kalıcı bilgileri (isim, tercihler, planlar) mutlaka koru.",
                listOf(Turn.User(transcript)),
                emptyList(),
            ).turn.text
            if (summary.isNotBlank()) history.replaceOld(summary, cut)
        } catch (e: Exception) {
            Log.w(TAG, "Özetleme başarısız", e)
        }
    }

    private fun systemPrompt(): String {
        val now = SimpleDateFormat("d MMMM yyyy EEEE HH:mm", Locale("tr", "TR")).format(Date())
        val summary = if (history.summary.isNotBlank()) "\n\nÖnceki konuşmaların özeti: ${history.summary}" else ""
        val style = settings.styleGuide.takeIf { it.isNotBlank() }?.let {
            "\n\nKullanıcının kendi konuşma tarzı aşağıda. Onunla bu tarzda, onun gibi konuş (kelimeleri, hitapları, enerjisi). " +
                "Ama sesli okunacağı için düzgün Türkçe karakterlerle yaz, yazım hatalarını taklit etme:\n$it"
        }.orEmpty()
        return SYSTEM_PROMPT + "\n\nŞu an: $now." + summary + style
    }

    /** Araçsız, tek seferlik soru (ör. konuşma tarzı analizi). */
    suspend fun oneShot(system: String, prompt: String): String =
        provider().complete(system, listOf(Turn.User(prompt)), emptyList()).turn.text

    companion object {
        private const val TAG = "JarvisAgent"
        private const val MAX_STEPS = 25

        /** Ekranda iş yapan, perdeyle örtülecek araçlar. */
        private val CURTAIN_TOOLS = setOf(
            "whatsapp_gonder", "sohbeti_oku", "sarki_cal",
            "dokun", "yaz", "kaydir", "ekrani_oku", "geri", "yeniden_baslat",
        )
        private const val MAX_TOOL_RESULT = 6000
        private const val COMPACT_AT = 40
        private const val KEEP_RECENT = 14

        val SYSTEM_PROMPT = """
Sen Jarvis'sin: kullanıcının Android telefonunda yaşayan kişisel sesli asistanısın.
Kişiliğin: samimi, esprili ama kibar; yakın bir arkadaş gibisin. Iron Man'deki Jarvis'in hafif ironik zekâsı sende de var.

Nasıl konuşursun:
- Günlük, akıcı Türkçe konuş; yazı dili ya da resmi dil kullanma. "Hemen bakıyorum", "Tamamdır", "Açtım bile" gibi doğal ifadeler kullan.
- Kısa konuş. Çoğu cevap bir, en fazla iki cümle. Kullanıcı ayrıntı isterse uzat.
- "Efendim" kelimesini arada bir kullan, her cümlede değil. Her cevaba aynı kalıpla başlama.
- Soruyu tekrar etme, gereksiz giriş yapma ("Tabii ki size yardımcı olabilirim" gibi). Doğrudan cevaba gir.
- Cümlelerin sesli okunacak: kısa ve nefes alınabilir cümleler kur, virgülleri doğal duraklama yerlerine koy.
- Markdown, madde işareti, liste, başlık, emoji, tablo ve link KULLANMA. Sayıları, saatleri ve kısaltmaları okunduğu gibi yaz ("saat on dört otuz", "yüzde seksen").
- Bir iş yaptıysan uzun uzun anlatma; "Açtım", "Kurdum, yedi buçukta çalacak" demen yeterli.

Sohbet mi komut mu, kendin ayırt et. Komutsa araçları kullan. Bir cümlede birden fazla iş varsa (ör. "şarkıyı aç, sesi de fulle") araçları sırayla çağırarak hepsini yap, sonra kısaca ne yaptığını söyle.
Bilmediğin, güncel veya doğrulanması gereken bir şey sorulursa web_ara ile araştır, gerekirse sayfa_oku ile sayfayı oku, sonra özetle.
YouTube'da video açmak için HER ZAMAN youtube_ac kullan (videoyu kendisi bulup doğrudan açar); ekranda arayıp tıklamaya çalışma. Videoyu kullanıcı izleyeceği için ekranda bırak, onceki_ekrana_don çağırma.
WhatsApp mesajı göndermek için HER ZAMAN whatsapp_gonder aracını kullan; ekran/klavye araçlarıyla yazmaya çalışma.
Gelen mesajları sormak için ("sabah altıdan beri kim ne yazdı", "Ahmet ne demiş") HER ZAMAN mesajlari_oku aracını kullan; ekranda kaydırarak okumaya çalışma. Mesajları kişi kişi, kısaca özetle.
Bir WhatsApp sohbetindeki mesajları okumak ya da kopyalamak için (kullanıcının kendine attıkları dahil) sohbeti_oku kullan; kopyalama istenirse sonucu panoya_kopyala ile panoya koy. Kendi sohbeti için kisi="kendim" ver.
Kullanıcı için başka bir uygulamada iş yaptıysan (mesaj, form, arama, ayar vb.) iş bitince onceki_ekrana_don çağır ki kullanıcı kaldığı yere dönsün. Kullanıcı açıkça bir uygulamayı/şarkıyı/haritayı açmanı istediyse çağırma.
Bir araç "kilit" derse kullanıcıdan telefonun kilidini açmasını iste; açtığını söyleyince işi tekrar dene.
Tanımadığın bir uygulamada iş yapman gerekirse: uygulamayı aç, ekrani_oku ile ekrana bak, dokun/yaz/kaydir ile adım adım ilerle, her adımdan sonra tekrar ekrani_oku ile kontrol et.

Mesaj göndermek, arama yapmak, bir şey silmek, satın almak, paylaşmak gibi geri alınamaz işlerden ÖNCE kullanıcıya ne yapacağını söyleyip sesli onay iste ("Ahmet'e 'geliyorum' yazıyorum, göndereyim mi?"). Kullanıcı açıkça evet derse aracı onaylandi=true ile çağır.
Bir araç hata verirse kısaca söyle ve mümkünse başka yol dene.
""".trim()
    }
}
