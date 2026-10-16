package com.jarvis.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jarvis.assistant.ui.ChatScreen
import com.jarvis.assistant.ui.VoiceSettingsScreen
import com.jarvis.assistant.ui.theme.JarvisTheme
import com.jarvis.assistant.wake.WakeWordService

class MainActivity : ComponentActivity() {

    private val assistant get() = (application as JarvisApp).assistant

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) assistant.startVoice()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent {
            JarvisTheme {
                // Basit ekran geçişi: hangi ekranın açık olduğunu tutar.
                var screen by rememberSaveable { mutableStateOf(SCREEN_CHAT) }
                BackHandler(enabled = screen != SCREEN_CHAT) { screen = SCREEN_CHAT }
                when (screen) {
                    SCREEN_VOICE -> VoiceSettingsScreen(onBack = { screen = SCREEN_CHAT })
                    else -> {
                        val bubbles by assistant.bubbles.collectAsStateWithLifecycle()
                        val state by assistant.state.collectAsStateWithLifecycle()
                        val partial by assistant.partial.collectAsStateWithLifecycle()
                        ChatScreen(
                            bubbles = bubbles,
                            state = state,
                            partial = partial,
                            onSend = assistant::sendText,
                            onMic = ::onMic,
                            onSettings = { screen = SCREEN_VOICE },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** Bildirimden "başlatmak için dokun" ile gelindiyse uyandırma servisini başlat. */
    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(WakeWordService.EXTRA_START_WAKE, false) == true) {
            WakeWordService.start(this)
        }
    }

    private fun onMic() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            assistant.startVoice()
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private companion object {
        const val SCREEN_CHAT = "chat"
        const val SCREEN_VOICE = "voice"
    }
}
