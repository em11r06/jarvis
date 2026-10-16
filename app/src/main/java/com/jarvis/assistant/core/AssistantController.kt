package com.jarvis.assistant.core

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Asistanın o anki durumu; arayüzdeki animasyon buna göre değişir. */
enum class AssistantState { IDLE, LISTENING, THINKING, SPEAKING }

/** Ekranda gösterilen tek bir sohbet balonu. */
data class ChatBubble(val fromUser: Boolean, val text: String)

/**
 * Asistanın merkezi. Ekran ve arka plan servisi aynı nesneyi kullanır.
 */
class AssistantController(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _state = MutableStateFlow(AssistantState.IDLE)
    val state: StateFlow<AssistantState> = _state.asStateFlow()

    private val _bubbles = MutableStateFlow<List<ChatBubble>>(emptyList())
    val bubbles: StateFlow<List<ChatBubble>> = _bubbles.asStateFlow()

    fun sendText(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        _bubbles.update { it + ChatBubble(true, clean) }
        scope.launch {
            _state.value = AssistantState.THINKING
            _bubbles.update { it + ChatBubble(false, "Duydum efendim: $clean") }
            _state.value = AssistantState.IDLE
        }
    }

    fun startVoice() {
        // Aşama 2'de ses tanıma eklenecek.
    }
}
