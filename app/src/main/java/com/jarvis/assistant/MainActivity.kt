package com.jarvis.assistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jarvis.assistant.ui.ChatScreen
import com.jarvis.assistant.ui.theme.JarvisTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val assistant = (application as JarvisApp).assistant
        setContent {
            JarvisTheme {
                val bubbles by assistant.bubbles.collectAsStateWithLifecycle()
                val state by assistant.state.collectAsStateWithLifecycle()
                ChatScreen(
                    bubbles = bubbles,
                    state = state,
                    onSend = assistant::sendText,
                    onMic = assistant::startVoice,
                    onSettings = {},
                )
            }
        }
    }
}
