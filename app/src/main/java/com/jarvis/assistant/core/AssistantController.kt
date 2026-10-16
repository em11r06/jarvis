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
data class ChatBubble(val fromUser: Boolean, val text: String)

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
    private val agent = Agent(context, settings, history)
    val speaker = Speaker(context, settings)
    private val listener = SpeechListener(context)
    private val busy = Mutex()
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

    private fun bubblesFromHistory(): List<ChatBubble> = history.turns.mapNotNull {
        when (it) {
            is Turn.User -> ChatBubble(true, it.text)
            is Turn.Assistant -> it.text.takeIf { t -> t.isNotBlank() }?.let { t -> ChatBubble(false, t) }
            else -> null
        }
    }.takeLast(100)

    private fun addBubble(b: ChatBubble) = _bubbles.update { (it + b).takeLast(100) }

    /** Yazıyla gönderilen mesaj. Cevap yine sesli okunur. */
    fun sendText(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        speaker.stop()
        sessionJob?.cancel()
        sessionJob = scope.launch {
            busy.withLock { handle(clean) }
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
            try {
                var timeout = FIRST_LISTEN_MS
                while (true) {
                    _state.value = AssistantState.LISTENING
                    val heard = listener.listen(timeout) { _partial.value = it }
                    _partial.value = ""
                    if (heard.isNullOrBlank()) break
                    if (isStopWord(heard)) {
                        addBubble(ChatBubble(true, heard))
                        break
                    }
                    handle(heard)
                    val follow = settings.followUpSeconds
                    if (follow <= 0) break
                    timeout = follow * 1000L
                }
            } finally {
                _partial.value = ""
                _state.value = AssistantState.IDLE
                listener.release()
                afterSession?.invoke()
            }
        }
    }

    /** Tek bir isteği işler: yapay zekaya sorar ve cevabı okur. */
    private suspend fun handle(text: String) {
        addBubble(ChatBubble(true, text))
        _state.value = AssistantState.THINKING
        val answer = try {
            agent.ask(
                text,
                onToolUse = { name -> _partial.value = "⚙ $name" },
                onInterimText = { interim ->
                    addBubble(ChatBubble(false, interim))
                    _state.value = AssistantState.SPEAKING
                    speaker.speak(interim)
                    _state.value = AssistantState.THINKING
                },
            )
        } catch (e: Exception) {
            Log.w("Jarvis", "Agent hatası", e)
            if (e is kotlinx.coroutines.CancellationException) throw e
            "Bir sorun çıktı efendim: ${e.message ?: "bilinmeyen hata"}"
        }
        _partial.value = ""
        addBubble(ChatBubble(false, answer))
        _state.value = AssistantState.SPEAKING
        speaker.speak(answer)
    }

    fun clearHistory() {
        history.clear()
        _bubbles.value = emptyList()
    }

    private fun hasMic() = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun isStopWord(t: String): Boolean {
        val s = t.lowercase().trim().trimEnd('.', '!')
        return s in setOf("tamam", "teşekkürler", "teşekkür ederim", "sağ ol", "sağol", "kapat", "dur", "yeter", "görüşürüz")
    }

    private companion object {
        const val FIRST_LISTEN_MS = 8000L
    }
}
