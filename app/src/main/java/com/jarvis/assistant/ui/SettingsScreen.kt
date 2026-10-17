package com.jarvis.assistant.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.runtime.rememberCoroutineScope
import com.jarvis.assistant.style.StyleLearner
import kotlinx.coroutines.launch
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
import com.jarvis.assistant.ai.GeminiQuota
import com.jarvis.assistant.core.Permissions
import com.jarvis.assistant.data.Settings
import com.jarvis.assistant.update.Updater
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
    var greeting by remember { mutableStateOf(s.wakeGreeting) }
    var hasPrint by remember { mutableStateOf(s.voicePrint.isNotBlank()) }
    var ownNumber by remember { mutableStateOf(s.ownNumber) }
    var curtain by remember { mutableStateOf(s.curtainEnabled) }
    val scope = rememberCoroutineScope()
    var senders by remember { mutableStateOf(StyleLearner.senders(context)) }
    var styleOwner by remember { mutableStateOf(s.styleOwner) }
    var styleGuide by remember { mutableStateOf(s.styleGuide) }
    var styleStatus by remember { mutableStateOf("") }
    val pickChats = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            val n = StyleLearner.import(context, uris)
            senders = StyleLearner.senders(context)
            if (styleOwner.isBlank()) styleOwner = senders.firstOrNull()?.first.orEmpty()
            styleStatus = if (n == 0) "Dosyada mesaj bulunamadı. WhatsApp dışa aktarma (.txt/.zip) ya da Instagram bilgi indirme ZIP'i olmalı." else "$n mesaj yüklendi."
        }
    }
    var threshold by remember { mutableFloatStateOf(s.voiceThreshold) }
    var enrolling by remember { mutableStateOf(false) }
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
            val active by GeminiQuota.activeModel.collectAsStateWithLifecycle()
            val resetAt = GeminiQuota.blockedUntil(geminiModel)
            val quotaText = buildString {
                if (resetAt > System.currentTimeMillis()) {
                    val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(resetAt))
                    append("Ana modelin kotası doldu; saat $time'de kendiliğinden ona dönülecek. ")
                }
                if (active.isNotBlank()) append("Son cevabı veren model: $active")
            }
            if (quotaText.isNotBlank()) Text(quotaText, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))

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
            Text(
                "Anahtar gerekmez: Jarvis ücretsiz ve çevrimdışı Vosk motoruyla dinler. Picovoice anahtarınız varsa girin; daha az pil harcar.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SecretField("Picovoice AccessKey (isteğe bağlı)", picoKey, { picoKey = it; s.picovoiceKey = it })
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
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Uyanınca sesli cevap ver", color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        if (greeting) "\"Buyrun efendim\" gibi kısa bir cevap" else "Kısa bir bip sesi",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = greeting, onCheckedChange = { greeting = it; s.wakeGreeting = it })
            }

            SectionTitle("Sesimi tanı")
            Text(
                if (hasPrint) "Sesiniz kayıtlı: Jarvis sadece size tepki verir (eşik ${"%.2f".format(threshold)})."
                else "Kayıt yok: \"Jarvis\" diyen herkese (ve bazen şarkılara) tepki verebilir. 5 kez \"Jarvis\" diyerek sesinizi tanıtın.",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                Button(onClick = {
                    if (Permissions.has(context, Manifest.permission.RECORD_AUDIO)) enrolling = true
                    else message = "Önce mikrofon izni verin (Kurulum sihirbazı)."
                }) { Text(if (hasPrint) "Yeniden kaydet" else "Sesimi tanıt") }
                if (hasPrint) OutlinedButton(onClick = { s.voicePrint = ""; hasPrint = false }) { Text("Sil") }
            }
            if (hasPrint) {
                Text("Seçicilik: sizi tanımazsa düşürün, başkalarına tepki verirse artırın.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                Slider(
                    value = threshold, onValueChange = { threshold = it }, valueRange = 0.2f..0.9f,
                    onValueChangeFinished = { s.voiceThreshold = threshold },
                )
            }

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

            SectionTitle("Güncelleme")
            val update by Updater.state.collectAsStateWithLifecycle()
            Text(
                "Kurulu sürüm: ${Updater.currentVersion}. " + when (val u = update) {
                    is Updater.State.Checking -> "Kontrol ediliyor..."
                    is Updater.State.UpToDate -> "En güncel sürüm."
                    is Updater.State.Downloading -> "Sürüm ${u.version} indiriliyor: %${u.percent}"
                    is Updater.State.Ready -> "Sürüm ${u.version} indirildi, kurulmaya hazır."
                    is Updater.State.Error -> "Hata: ${u.message}"
                    else -> "Jarvis açılınca yeni sürümü kendisi kontrol eder."
                },
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                if (update is Updater.State.Ready) {
                    Button(onClick = { Updater.install(context) }) { Text("Şimdi güncelle") }
                } else {
                    OutlinedButton(onClick = { Updater.checkInBackground(context, force = true) }) { Text("Güncellemeleri denetle") }
                }
            }

            SectionTitle("Mesajlar")
            Text(
                if (Permissions.notificationAccess(context))
                    "Bildirim erişimi açık: gelen mesajlar kaydediliyor, WhatsApp mesajları arka planda gönderilebiliyor."
                else
                    "Bildirim erişimi kapalı. Açarsanız Jarvis gelen mesajları eksiksiz okuyabilir ve WhatsApp'tan arka planda cevap gönderebilir.",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = { Permissions.open(context, Permissions.notificationAccessIntent()) },
                modifier = Modifier.padding(top = 6.dp),
            ) { Text("Bildirim erişimi ayarı") }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("İşleri perde arkasında yap", color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        "Jarvis başka uygulamada iş yaparken ekranı \"Jarvis çalışıyor\" paneliyle örter, bitince kaldığınız yere döner.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = curtain, onCheckedChange = { curtain = it; s.curtainEnabled = it })
            }
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                ownNumber, { ownNumber = it; s.ownNumber = it },
                label = { Text("Kendi numaranız (kendinize attığınız WhatsApp mesajları için)") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )

            SectionTitle("Konuşma tarzım")
            Text(
                "WhatsApp: sohbette ⋮ > Diğer > Sohbeti dışa aktar > Medya olmadan. Instagram: Hesap Merkezi > Bilgilerin ve izinlerin > " +
                    "Bilgilerini indir (Mesajlar, tercihen JSON) ile gelen ZIP. Dosyayı buraya yükleyin ya da paylaş menüsünden Jarvis'i seçin. " +
                    "Jarvis sadece SİZİN mesajlarınızı analiz edip sizin gibi konuşur.",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                Button(onClick = { pickChats.launch(arrayOf("text/plain", "application/zip", "*/*")) }) { Text("Sohbet yükle") }
                if (senders.isNotEmpty()) OutlinedButton(onClick = {
                    StyleLearner.clear(context)
                    senders = emptyList()
                    styleStatus = "Yüklenen sohbetler silindi."
                }) { Text("Sohbetleri sil") }
            }
            if (senders.isNotEmpty()) {
                Text("Hangisi sizsiniz?", fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.onSurface)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                ) {
                    senders.take(8).forEach { (name, count) ->
                        FilterChip(styleOwner == name, { styleOwner = name }, { Text("$name ($count)") })
                    }
                }
                Button(
                    onClick = {
                        styleStatus = "Analiz ediliyor..."
                        scope.launch {
                            styleStatus = try {
                                val g = StyleLearner.analyze(context, styleOwner)
                                styleGuide = s.styleGuide
                                if (g == styleGuide) "Konuşma tarzınız öğrenildi." else g
                            } catch (e: Exception) {
                                "Hata: ${e.message}"
                            }
                        }
                    },
                    enabled = styleOwner.isNotBlank(),
                    modifier = Modifier.padding(top = 6.dp),
                ) { Text("Tarzımı analiz et") }
            }
            if (styleStatus.isNotBlank()) Text(styleStatus, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (styleGuide.isNotBlank()) {
                OutlinedTextField(
                    styleGuide, { styleGuide = it; s.styleGuide = it },
                    label = { Text("Konuşma tarzı rehberi (düzenleyebilirsiniz)") },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
                TextButton(onClick = { styleGuide = ""; s.styleGuide = "" }) { Text("Tarzı unut (normal Jarvis)") }
            }

            SectionTitle("Diğer")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onSetup) { Text("Kurulum sihirbazı") }
                Button(onClick = { confirmClear = true }) { Text("Geçmişi temizle") }
            }
            Spacer(Modifier.height(40.dp))
        }
    }

    if (enrolling) {
        VoiceEnrollDialog(onDone = { ok ->
            enrolling = false
            hasPrint = ok
            threshold = s.voiceThreshold
        })
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
