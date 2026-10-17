package com.jarvis.assistant.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.jarvis.assistant.ai.Agent
import com.jarvis.assistant.ai.Turn
import com.jarvis.assistant.data.HistoryStore
import com.jarvis.assistant.data.Settings
import com.jarvis.assistant.speech.SpeechListener
import com.jarvis.assistant.speech.Speaker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Asistanın o anki durumu; arayüzdeki animasyon buna göre değişir. */
enum class AssistantState { IDLE, LISTENING, THINKING, SPEAKING }

/** Ekranda gösterilen tek bir sohbet balonu. */
data class ChatBubble(val fromUser: Boolean, val text: String, val images: List<String> = emptyList())

/**
 * Asistanın merkezi. Ekran ve arka plandaki uyandırma servisi aynı nesneyi kullanır.
 * Akış: dinle -> yapay zekaya sor -> sesli cevap ver -> birkaç saniye takip sorusu bekle.
 */
class AssistantController(
    private val context: Context,
    val settings: Settings,
    val history: HistoryStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val agent = Agent(context, settings, history)
    private val offline = OfflineBrain(context)
    val speaker = Speaker(context, settings)
    private val listener = SpeechListener(context)
    private val cloudListener = com.jarvis.assistant.speech.CloudListener(context, settings)
    private val busy = Mutex()
    /** Sesli oturum sürüyor mu (araya girme sadece o sırada dinlenir). */
    @Volatile private var inVoiceSession = false
    /** Son konuşma kullanıcı tarafından kesildi mi? */
    @Volatile private var barged = false
    private var sessionJob: Job? = null

    /** Uyandırma servisi bunları ayarlar: dinlemeden önce mikrofonu bırakır, sonra geri alır. */
    var beforeListen: (() -> Unit)? = null
    var afterSession: (() -> Unit)? = null

    private val _state = MutableStateFlow(AssistantState.IDLE)
    val state: StateFlow<AssistantState> = _state.asStateFlow()

    private val _bubbles = MutableStateFlow(bubblesFromHistory())
    val bubbles: StateFlow<List<ChatBubble>> = _bubbles.asStateFlow()

    /** Konuşurken ekranda canlı gösterilen ara metin. */
    private val _partial = MutableStateFlow("")
    val partial: StateFlow<String> = _partial.asStateFlow()

    /** Gönderilmeyi bekleyen, sohbete eklenmiş görseller. */
    private val _pendingImages = MutableStateFlow<List<String>>(emptyList())
    val pendingImages: StateFlow<List<String>> = _pendingImages.asStateFlow()

    /** Seçilen/paylaşılan görselleri küçültüp bekleyenlere ekler. */
    fun attachImages(uris: List<android.net.Uri>) {
        scope.launch {
            val paths = kotlinx.coroutines.withContext(Dispatchers.IO) {
                uris.take(4).mapNotNull { com.jarvis.assistant.ai.Images.importUri(context, it) }
            }
            _pendingImages.update { (it + paths).takeLast(4) }
        }
    }

    fun removePendingImage(path: String) = _pendingImages.update { it - path }

    /** Bekleyen görsellerle birlikte gönderir. */
    fun sendWithPending(text: String) {
        val imgs = _pendingImages.value
        _pendingImages.value = emptyList()
        sendText(text, imgs)
    }

    private fun bubblesFromHistory(): List<ChatBubble> = history.turns.mapNotNull {
        when (it) {
            is Turn.User -> ChatBubble(true, it.text, it.images)
            is Turn.Assistant -> it.text.takeIf { t -> t.isNotBlank() }?.let { t -> ChatBubble(false, t) }
            else -> null
        }
    }.takeLast(100)

    private fun addBubble(b: ChatBubble) = _bubbles.update { (it + b).takeLast(100) }

    /** Yazıyla gönderilen mesaj. Cevap yine sesli okunur. */
    fun sendText(text: String, images: List<String> = emptyList()) {
        val clean = text.trim().ifEmpty { if (images.isNotEmpty()) "Bu görsele bak ve ne olduğunu anlat." else "" }
        if (clean.isEmpty()) return
        speaker.stop()
        sessionJob?.cancel()
        sessionJob = scope.launch {
            busy.withLock { handle(clean, images = images) }
            _state.value = AssistantState.IDLE
        }
    }

    /** Mikrofon/reaktör düğmesi: dinlemeyi başlatır ya da durdurur. */
    fun startVoice() {
        if (_state.value != AssistantState.IDLE) {
            stopAll()
            return
        }
        sessionJob = scope.launch { voiceSession() }
    }

    fun stopAll() {
        sessionJob?.cancel()
        listener.stop()
        speaker.stop()
        _partial.value = ""
        _state.value = AssistantState.IDLE
    }

    /**
     * Bir sesli oturum: dinle, cevapla, takip için dinlemeye devam et.
     * Uyandırma servisi de bunu çağırır ve bitmesini bekler.
     */
    suspend fun voiceSession() {
        if (!hasMic()) {
            addBubble(ChatBubble(false, "Mikrofon izni yok efendim. Kurulum ekranından izin verin."))
            return
        }
        if (busy.isLocked) return
        beforeListen?.invoke()
        busy.withLock {
            // Dinlerken müziği/videoyu kıs ki kullanıcıyı net duyalım.
            speaker.acquireFocus()
            inVoiceSession = true
            try {
                var timeout = FIRST_LISTEN_MS
                while (true) {
                    _state.value = AssistantState.LISTENING
                    val hints = kotlinx.coroutines.withContext(Dispatchers.IO) { SpeechHints.get(context) }
                    listener.hints = hints
                    val heardFull = listenOnce(timeout, hints, followUp = timeout != FIRST_LISTEN_MS)
                    val heard = heardFull?.text
                    _partial.value = ""
                    if (heard.isNullOrBlank()) break
                    if (isStopWord(heard)) {
                        addBubble(ChatBubble(true, heard))
                        break
                    }
                    barged = false
                    handle(heard, withAlternatives(heardFull!!))
                    // Sözü kesildiyse hemen dinlemeye geç.
                    if (barged) { timeout = FIRST_LISTEN_MS; continue }
                    val follow = settings.followUpSeconds
                    if (follow <= 0) break
                    timeout = follow * 1000L
                }
            } finally {
                inVoiceSession = false
                _partial.value = ""
                _state.value = AssistantState.IDLE
                listener.release()
                speaker.releaseFocus()
                afterSession?.invoke()
            }
        }
    }

    /**
     * Bir kez dinler. İnternet ve Gemini anahtarı varsa gelişmiş (Gemini) tanıma, yoksa ya da
     * hata olursa telefonun tanıyıcısı kullanılır.
     */
    private suspend fun listenOnce(timeout: Long, hints: List<String>, followUp: Boolean): com.jarvis.assistant.speech.Heard? {
        val cloud = settings.cloudStt && settings.geminiKey.isNotBlank() && Network.isOnline(context) &&
            !com.jarvis.assistant.ai.GeminiQuota.billingBlocked()
        if (cloud) {
            cloudListener.hints = hints
            // Sadece Jarvis'in son sözü verilir; kullanıcının eski cümleleri verilirse sessizlikte onları "duymuş" gibi yazabiliyor.
            cloudListener.context_ = history.turns.lastOrNull { it is Turn.Assistant && it.text.isNotBlank() }
                ?.let { (it as Turn.Assistant).text.take(300) }.orEmpty()
            for (attempt in 0..1) {
                when (val r = cloudListener.listen(timeout) { _partial.value = it }) {
                    is com.jarvis.assistant.speech.CloudListener.Result.Text -> return r.heard
                    is com.jarvis.assistant.speech.CloudListener.Result.NoSpeech -> return null
                    is com.jarvis.assistant.speech.CloudListener.Result.NotUser -> {
                        // Takip dinlemesinde ortam sesi olabilir: sessizce bitir. İlk dinlemede kullanıcıya söyle.
                        _partial.value = ""
                        if (!followUp) speaker.speakShort("Sesinizi tanıyamadım efendim.")
                        return null
                    }
                    is com.jarvis.assistant.speech.CloudListener.Result.Unclear -> {
                        _partial.value = ""
                        if (attempt == 1 || followUp) return null
                        speaker.speakShort("Anlayamadım, tekrar söyler misiniz?")
                    }
                    is com.jarvis.assistant.speech.CloudListener.Result.Failed -> {
                        // Gemini'ye ulaşılamadı: bu sefer telefonun tanıyıcısıyla tekrar dinle.
                        speaker.speakShort("Tekrar söyler misiniz?")
                        break
                    }
                }
            }
        }
        return listener.listen(timeout) { _partial.value = it }
    }

    /** Tek bir isteği işler: yapay zekaya sorar ve cevabı okur. */
    /**
     * Tanıyıcı emin değilse diğer tahminleri yapay zekaya not olarak ekler;
     * yapay zeka bağlama göre doğru olanı seçer. Ekranda sadece en olası metin görünür.
     */
    private fun withAlternatives(h: com.jarvis.assistant.speech.Heard): String {
        val alts = h.alternatives.filter { !it.equals(h.text, ignoreCase = true) }.take(3)
        val unsure = h.confidence == null || h.confidence < 0.85f
        if (alts.isEmpty() || !unsure) return h.text
        return h.text + "\n[Ses tanıma emin değil; olası alternatifler: " + alts.joinToString(" | ") + ". Bağlama göre doğrusunu seç.]"
    }

    private suspend fun handle(text: String, forAi: String = text, images: List<String> = emptyList()) {
        addBubble(ChatBubble(true, text, images))
        _state.value = AssistantState.THINKING
        val answer = try {
            // İnternet yoksa telefon komutlarını çevrimdışı beyinle yap.
            if (!Network.isOnline(context)) {
                val reply = offline.handle(text) ?: offlineChat(text) ?: OfflineBrain.NO_INTERNET
                history.turns += Turn.User(text)
                history.turns += Turn.Assistant(reply, emptyList())
                history.save()
                return finish(reply)
            }
            agent.ask(
                forAi,
                images,
                onToolUse = { name -> _partial.value = "⚙ $name" },
                onInterimText = { interim ->
                    addBubble(ChatBubble(false, interim))
                    _state.value = AssistantState.SPEAKING
                    speakVoice(interim)
                    _state.value = AssistantState.THINKING
                },
            )
        } catch (e: Exception) {
            Log.w("Jarvis", "Agent hatası", e)
            if (e is kotlinx.coroutines.CancellationException) throw e
            if (Network.isNetworkError(e) || (e as? com.jarvis.assistant.ai.AiException)?.outOfCredit == true) {
                // Bağlantı koptu ya da yapay zekanın kotası/bakiyesi bitti: komutsa yine de yap.
                offline.handle(text) ?: if (Network.isNetworkError(e)) (offlineChat(text) ?: OfflineBrain.NO_INTERNET) else (e.message ?: "")
            } else {
                "Bir sorun çıktı efendim: ${e.message ?: "bilinmeyen hata"}"
            }
        }
        finish(answer)
    }

    /** İnternetsiz sohbet: telefondaki Gemini Nano (varsa). */
    private suspend fun offlineChat(text: String): String? {
        val recent = history.turns.takeLast(8).mapNotNull {
            when (it) {
                is Turn.User -> "Kullanıcı" to it.text
                is Turn.Assistant -> it.text.takeIf { t -> t.isNotBlank() }?.let { t -> "Jarvis" to t }
                else -> null
            }
        }
        val style = settings.styleGuide.takeIf { it.isNotBlank() }?.let { "\nKullanıcının konuşma tarzı (onun gibi konuş):\n$it" }.orEmpty()
        return com.jarvis.assistant.ai.NanoModel.chat(com.jarvis.assistant.ai.NanoModel.OFFLINE_SYSTEM + style, recent, text)
    }

    private suspend fun finish(answer: String) {
        _partial.value = ""
        addBubble(ChatBubble(false, answer))
        _state.value = AssistantState.SPEAKING
        speakVoice(answer)
    }

    /**
     * Sesli okur. Sesli oturumdaysa okurken uyandırma kelimesini de dinler;
     * kullanıcı araya girerse konuşmayı keser (barged = true).
     */
    private suspend fun speakVoice(text: String) {
        // Kullanıcı bu istekte zaten araya girdiyse kalan konuşmaları okuma.
        if (barged) return
        if (!inVoiceSession || !settings.bargeIn) {
            speaker.speak(text)
            return
        }
        kotlinx.coroutines.coroutineScope {
            val speaking = launch { speaker.speak(text) }
            val barge = com.jarvis.assistant.wake.BargeIn.start(context, settings, text) {
                barged = true
                speaking.cancel()
                speaker.stop()
            }
            try {
                speaking.join()
            } finally {
                barge?.stop()
            }
        }
    }

    fun clearHistory() {
        history.clear()
        _bubbles.value = emptyList()
    }

    private fun hasMic() = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun isStopWord(t: String): Boolean {
        val s = t.lowercase().trim().trimEnd('.', '!')
        return s in setOf("teşekkürler", "teşekkür ederim", "sağ ol", "sağolun", "sağol", "yeter", "bu kadar", "görüşürüz")
    }

    private companion object {
        const val FIRST_LISTEN_MS = 8000L
    }
}
