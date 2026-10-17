package com.jarvis.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jarvis.assistant.ui.ChatScreen
import com.jarvis.assistant.ui.SettingsScreen
import com.jarvis.assistant.ui.SetupScreen
import com.jarvis.assistant.ui.VoiceSettingsScreen
import com.jarvis.assistant.ui.theme.JarvisTheme
import com.jarvis.assistant.update.Updater
import com.jarvis.assistant.wake.WakeWordService

class MainActivity : ComponentActivity() {

    private val app get() = application as JarvisApp
    private val assistant get() = app.assistant

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) assistant.startVoice()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)

        // Uyandırma açıksa ve servis çalışmıyorsa (ör. yeniden başlatmadan sonra) şimdi başlat.
        if (app.settings.wakeEnabled && !WakeWordService.isRunning.value) WakeWordService.start(this)

        // GitHub'da yeni sürüm var mı? Varsa arka planda indirir.
        Updater.checkInBackground(this)

        setContent {
            JarvisTheme {
                // Basit ekran geçişi: hangi ekranın açık olduğunu tutar.
                var screen by rememberSaveable {
                    mutableStateOf(if (app.settings.setupDone) SCREEN_CHAT else SCREEN_SETUP)
                }
                BackHandler(enabled = screen == SCREEN_SETTINGS || screen == SCREEN_VOICE) {
                    screen = if (screen == SCREEN_VOICE) SCREEN_SETTINGS else SCREEN_CHAT
                }
                // Güncelleme indirildiyse bir kez sor.
                val update by Updater.state.collectAsStateWithLifecycle()
                var dismissedVersion by rememberSaveable { mutableStateOf(0) }
                (update as? Updater.State.Ready)?.takeIf { it.version != dismissedVersion }?.let { ready ->
                    AlertDialog(
                        onDismissRequest = { dismissedVersion = ready.version },
                        title = { Text("Yeni sürüm hazır (${ready.version})") },
                        text = { Text(ready.notes.ifBlank { "Jarvis'in yeni sürümü indirildi." } + "\n\nKurulum ekranında Güncelle'ye dokunmanız yeterli.") },
                        confirmButton = {
                            TextButton(onClick = {
                                dismissedVersion = ready.version
                                Updater.install(this@MainActivity)
                            }) { Text("Güncelle") }
                        },
                        dismissButton = { TextButton(onClick = { dismissedVersion = ready.version }) { Text("Sonra") } },
                    )
                }
                when (screen) {
                    SCREEN_SETUP -> SetupScreen(onFinish = { screen = SCREEN_SETTINGS })
                    SCREEN_SETTINGS -> SettingsScreen(
                        onBack = { screen = SCREEN_CHAT },
                        onVoice = { screen = SCREEN_VOICE },
                        onSetup = { screen = SCREEN_SETUP },
                    )
                    SCREEN_VOICE -> VoiceSettingsScreen(onBack = { screen = SCREEN_SETTINGS })
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
                            onSettings = { screen = SCREEN_SETTINGS },
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

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(Updater.EXTRA_INSTALL, false) == true) Updater.install(this)
        // Bildirimden "başlatmak için dokun" ile gelindiyse uyandırma servisini başlat.
        if (intent?.getBooleanExtra(WakeWordService.EXTRA_START_WAKE, false) == true) {
            WakeWordService.start(this)
        }
        // Yan tuşa uzun basma (varsayılan asistan) ile açıldıysa hemen dinlemeye başla.
        if (intent?.action == Intent.ACTION_ASSIST || intent?.action == Intent.ACTION_VOICE_COMMAND) {
            window.decorView.post { if (assistant.state.value == com.jarvis.assistant.core.AssistantState.IDLE) onMic() }
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
        const val SCREEN_SETUP = "setup"
        const val SCREEN_CHAT = "chat"
        const val SCREEN_SETTINGS = "settings"
        const val SCREEN_VOICE = "voice"
    }
}
