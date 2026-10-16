package com.jarvis.assistant.ui

import android.content.Intent
import android.speech.tts.TextToSpeech
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.data.Settings
import com.jarvis.assistant.speech.ElevenVoice
import com.jarvis.assistant.speech.VoiceCatalog
import com.jarvis.assistant.speech.VoiceItem
import com.jarvis.assistant.ui.theme.JarvisCyan
import com.jarvis.assistant.ui.theme.JarvisSurface
import kotlinx.coroutines.launch

/**
 * Ses seçme ekranı: telefondaki tüm TTS sesleri ve ElevenLabs sesleri.
 * Seçimler anında kaydedilir.
 */
@Composable
fun VoiceSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = JarvisApp.instance
    val settings = app.settings
    val speaker = app.assistant.speaker
    val scope = rememberCoroutineScope()

    var mode by remember { mutableStateOf(settings.ttsMode) }
    var selEngine by remember { mutableStateOf(settings.ttsEngine) }
    var selVoice by remember { mutableStateOf(settings.ttsVoice) }
    var rate by remember { mutableFloatStateOf(settings.ttsRate) }
    var pitch by remember { mutableFloatStateOf(settings.ttsPitch) }
    var voices by remember { mutableStateOf<List<VoiceItem>?>(null) }
    var onlyTurkish by remember { mutableStateOf(false) }

    var elevenKey by remember { mutableStateOf(settings.elevenKey) }
    var elevenId by remember { mutableStateOf(settings.elevenVoiceId) }
    var elevenVoices by remember { mutableStateOf<List<ElevenVoice>>(emptyList()) }
    var elevenStatus by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { voices = VoiceCatalog.androidVoices(context) }

    fun previewSelected() = scope.launch {
        speaker.preview(VoiceCatalog.SAMPLE, selEngine, selVoice, rate, pitch)
    }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .statusBarsPadding().navigationBarsPadding().imePadding(),
    ) {
        JarvisTopBar("Ses", onBack)
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = mode == Settings.TTS_ANDROID,
                        onClick = { mode = Settings.TTS_ANDROID; settings.ttsMode = mode },
                        label = { Text("Telefon sesleri") },
                    )
                    FilterChip(
                        selected = mode == Settings.TTS_ELEVEN,
                        onClick = { mode = Settings.TTS_ELEVEN; settings.ttsMode = mode },
                        label = { Text("ElevenLabs") },
                    )
                }
                Text(
                    if (mode == Settings.TTS_ELEVEN) "ElevenLabs çalışmazsa aşağıda seçili telefon sesi kullanılır."
                    else "Aşağıdan bir ses seçin.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                )

                SectionTitle("Hız: ${"%.2f".format(rate)}")
                Slider(
                    value = rate, onValueChange = { rate = it }, valueRange = 0.5f..2.0f,
                    onValueChangeFinished = { settings.ttsRate = rate; previewSelected() },
                )
                SectionTitle("Ton: ${"%.2f".format(pitch)}")
                Slider(
                    value = pitch, onValueChange = { pitch = it }, valueRange = 0.5f..2.0f,
                    onValueChangeFinished = { settings.ttsPitch = pitch; previewSelected() },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        rate = 1f; pitch = 1f
                        settings.ttsRate = 1f; settings.ttsPitch = 1f
                    }) { Text("Sıfırla") }
                    OutlinedButton(onClick = {
                        val intents = listOf(
                            Intent("com.android.settings.TTS_SETTINGS"),
                            Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA),
                        )
                        for (i in intents) {
                            val ok = runCatching { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
                            if (ok) break
                        }
                    }) { Text("Daha fazla ses indir") }
                }
            }

            // ---------------- ElevenLabs ----------------
            if (mode == Settings.TTS_ELEVEN) {
                item {
                    SectionTitle("ElevenLabs")
                    SecretField("ElevenLabs API anahtarı", elevenKey, {
                        elevenKey = it
                        settings.elevenKey = it
                    })
                    Spacer(Modifier.padding(4.dp))
                    OutlinedTextField(
                        value = elevenId,
                        onValueChange = { elevenId = it; settings.elevenVoiceId = it },
                        label = { Text("Ses ID (elle de girebilirsiniz)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        Button(onClick = {
                            elevenStatus = "Yükleniyor..."
                            scope.launch {
                                elevenStatus = try {
                                    elevenVoices = VoiceCatalog.elevenVoices(elevenKey)
                                    "${elevenVoices.size} ses bulundu."
                                } catch (e: Exception) {
                                    "Hata: ${e.message}"
                                }
                            }
                        }, enabled = elevenKey.isNotBlank()) { Text("Seslerimi getir") }
                        OutlinedButton(onClick = {
                            scope.launch {
                                val ok = speaker.previewEleven(VoiceCatalog.SAMPLE, elevenId)
                                if (!ok) elevenStatus = "Okunamadı. Anahtarı, ses ID'sini ve interneti kontrol edin."
                            }
                        }, enabled = elevenKey.isNotBlank() && elevenId.isNotBlank()) { Text("Dinle") }
                    }
                    if (elevenStatus.isNotBlank()) Text(elevenStatus, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        "Model: ${settings.elevenModel} (Türkçe destekli çok dilli model)",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(elevenVoices, key = { "e_" + it.id }) { v ->
                    VoiceRow(
                        title = v.name,
                        subtitle = v.info.ifBlank { v.id },
                        selected = v.id == elevenId,
                        onSelect = {
                            elevenId = v.id
                            settings.elevenVoiceId = v.id
                            settings.elevenVoiceName = v.name
                        },
                        onListen = { scope.launch { speaker.previewEleven(VoiceCatalog.SAMPLE, v.id) } },
                    )
                }
            }

            // ---------------- Telefon sesleri ----------------
            item {
                SectionTitle("Telefon sesleri")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(selected = onlyTurkish, onClick = { onlyTurkish = !onlyTurkish }, label = { Text("Sadece Türkçe") })
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = {
                        selEngine = ""; selVoice = ""
                        settings.ttsEngine = ""; settings.ttsVoice = ""
                    }) { Text("Varsayılana dön") }
                }
                if (voices == null) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(16.dp)) {
                        CircularProgressIndicator(Modifier.width(24.dp))
                        Spacer(Modifier.width(12.dp))
                        Text("Sesler yükleniyor...")
                    }
                } else if (voices!!.isEmpty()) {
                    Text("Hiç ses bulunamadı. \"Daha fazla ses indir\" ile ses yükleyin.")
                }
            }
            val shown = voices.orEmpty().filter { !onlyTurkish || it.isTurkish }
            items(shown, key = { it.engine + "/" + it.name }) { v ->
                VoiceRow(
                    title = v.name,
                    subtitle = "${v.engineLabel} · ${v.localeName} · ${if (v.needsNetwork) "çevrimiçi" else "yerel"}",
                    selected = v.engine == selEngine && v.name == selVoice,
                    onSelect = {
                        selEngine = v.engine; selVoice = v.name
                        settings.ttsEngine = v.engine; settings.ttsVoice = v.name
                    },
                    onListen = { scope.launch { speaker.preview(VoiceCatalog.SAMPLE, v.engine, v.name, rate, pitch) } },
                )
            }
            item { Spacer(Modifier.padding(24.dp)) }
        }
    }
}

@Composable
private fun VoiceRow(title: String, subtitle: String, selected: Boolean, onSelect: () -> Unit, onListen: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            .background(JarvisSurface, shape)
            .border(1.dp, if (selected) JarvisCyan else JarvisCyan.copy(alpha = 0.15f), shape)
            .clickable(onClick = onSelect)
            .padding(8.dp),
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = onListen) { Text("Dinle") }
    }
}
