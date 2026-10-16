package com.jarvis.assistant

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jarvis.assistant.ui.ChatScreen
import com.jarvis.assistant.ui.theme.JarvisTheme

class MainActivity : ComponentActivity() {

    private val assistant get() = (application as JarvisApp).assistant

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) assistant.startVoice()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            JarvisTheme {
                val bubbles by assistant.bubbles.collectAsStateWithLifecycle()
                val state by assistant.state.collectAsStateWithLifecycle()
                val partial by assistant.partial.collectAsStateWithLifecycle()
                ChatScreen(
                    bubbles = bubbles,
                    state = state,
                    partial = partial,
                    onSend = assistant::sendText,
                    onMic = ::onMic,
                    onSettings = {},
                )
            }
        }
    }

    private fun onMic() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            assistant.startVoice()
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}
