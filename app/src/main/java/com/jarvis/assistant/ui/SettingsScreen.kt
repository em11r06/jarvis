package com.jarvis.assistant.ui

import android.Manifest
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.core.Permissions
import com.jarvis.assistant.data.Settings
import com.jarvis.assistant.wake.WakeWordService
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(onBack: () -> Unit, onVoice: () -> Unit, onSetup: () -> Unit) {
    val context = LocalContext.current
    val app = JarvisApp.instance
    val s = app.settings

    var provider by remember { mutableStateOf(s.provider) }
    var geminiKey by remember { mutableStateOf(s.geminiKey) }
    var geminiModel by remember { mutableStateOf(s.geminiModel) }
    var claudeKey by remember { mutableStateOf(s.claudeKey) }
    var claudeModel by remember { mutableStateOf(s.claudeModel) }
    var picoKey by remember { mutableStateOf(s.picovoiceKey) }
    var elevenKey by remember { mutableStateOf(s.elevenKey) }
    var wake by remember { mutableStateOf(s.wakeEnabled) }
    var follow by remember { mutableFloatStateOf(s.followUpSeconds.toFloat()) }
    var confirmClear by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val wakeRunning by WakeWordService.isRunning.collectAsStateWithLifecycle()

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .statusBarsPadding().navigationBarsPadding().imePadding(),
    ) {
        JarvisTopBar("Ayarlar", onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {

            SectionTitle("Yapay zeka sağlayıcısı")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(provider == Settings.PROVIDER_GEMINI, { provider = Settings.PROVIDER_GEMINI; s.provider = provider }, { Text("Gemini") })
                FilterChip(provider == Settings.PROVIDER_CLAUDE, { provider = Settings.PROVIDER_CLAUDE; s.provider = provider }, { Text("Claude") })
            }

            SectionTitle("Gemini")
            SecretField("Gemini API anahtarı", geminiKey, { geminiKey = it; s.geminiKey = it })
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                geminiModel, { geminiModel = it; s.geminiModel = it },
                label = { Text("Model adı") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )

            SectionTitle("Claude")
            SecretField("Claude API anahtarı", claudeKey, { claudeKey = it; s.claudeKey = it })
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                claudeModel, { claudeModel = it; s.claudeModel = it },
                label = { Text("Model adı") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )

            SectionTitle("Ses")
            OutlinedButton(onClick = onVoice) { Text("Ses seç, hız ve ton ayarla") }
            Spacer(Modifier.height(6.dp))
            SecretField("ElevenLabs API anahtarı (isteğe bağlı)", elevenKey, { elevenKey = it; s.elevenKey = it })

            SectionTitle("Uyandırma kelimesi (\"Jarvis\")")
            SecretField("Picovoice AccessKey", picoKey, { picoKey = it; s.picovoiceKey = it })
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Arka planda dinle", color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        if (wakeRunning) "Çalışıyor" else "Kapalı",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = wake, onCheckedChange = { on ->
                    message = ""
                    if (on) {
                        when {
                            picoKey.isBlank() -> message = "Önce Picovoice anahtarını girin."
                            !Permissions.has(context, Manifest.permission.RECORD_AUDIO) -> message = "Önce mikrofon izni verin (Kurulum sihirbazı)."
                            else -> {
                                wake = true
                                s.wakeEnabled = true
                                WakeWordService.stop(context)
                                WakeWordService.start(context)
                            }
                        }
                    } else {
                        wake = false
                        s.wakeEnabled = false
                        WakeWordService.stop(context)
                    }
                })
            }
            if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)

            SectionTitle("Takip dinleme süresi: ${follow.roundToInt()} sn")
            Text(
                "Cevaptan sonra yeniden \"Jarvis\" demeden konuşabileceğiniz süre. 0: kapalı.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = follow, onValueChange = { follow = it }, valueRange = 0f..15f, steps = 14,
                onValueChangeFinished = { s.followUpSeconds = follow.roundToInt() },
            )

            SectionTitle("Varsayılan dijital asistan")
            Text(
                "Jarvis'i varsayılan asistan yaparsanız yan tuşa uzun basınca açılır. Açılan ekranda \"Dijital asistan uygulaması\" > Jarvis seçin. " +
                    "Sonra Ayarlar > Gelişmiş özellikler > Yan tuş > Basılı tut > Dijital asistan seçin.",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = { Permissions.open(context, *Permissions.defaultAssistantIntents().toTypedArray()) },
                modifier = Modifier.padding(top = 6.dp),
            ) { Text("Varsayılan asistan ayarını aç") }

            SectionTitle("Diğer")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onSetup) { Text("Kurulum sihirbazı") }
                Button(onClick = { confirmClear = true }) { Text("Geçmişi temizle") }
            }
            Spacer(Modifier.height(40.dp))
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Geçmiş silinsin mi?") },
            text = { Text("Jarvis konuştuklarınızı ve özetini unutacak.") },
            confirmButton = {
                TextButton(onClick = {
                    app.assistant.clearHistory()
                    confirmClear = false
                }) { Text("Sil") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Vazgeç") } },
        )
    }
}
