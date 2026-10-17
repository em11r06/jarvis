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
import androidx.compose.runtime.LaunchedEffect
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
    var wakeSound by remember { mutableStateOf(s.wakeSound) }
    var wakeWords by remember { mutableStateOf(s.wakeWords) }
    var bargeIn by remember { mutableStateOf(s.bargeIn) }
    var cloudStt by remember { mutableStateOf(s.cloudStt) }
    var headsetMic by remember { mutableStateOf(s.headsetMic) }
    var wordsChanged by remember { mutableStateOf(false) }
    var testingWake by remember { mutableStateOf(false) }
    var hasTr by remember { mutableStateOf(com.jarvis.assistant.wake.VoskModels.hasTurkish(context)) }
    var trProgress by remember { mutableStateOf(-1) }
    var wakePhrases by remember { mutableStateOf(s.wakePhrases) }
    var wakeVolume by remember { mutableFloatStateOf(s.wakeVolume) }
    var hasWakeFile by remember { mutableStateOf(WakeWordService.wakeFile(context).exists()) }
    var hasPrint by remember { mutableStateOf(s.voicePrint.isNotBlank()) }
    var ownNumber by remember { mutableStateOf(s.ownNumber) }
    var curtain by remember { mutableStateOf(s.curtainEnabled) }
    val scope = rememberCoroutineScope()
    var senders by remember { mutableStateOf(StyleLearner.senders(context)) }
    var styleOwner by remember { mutableStateOf(s.styleOwner) }
    var styleGuide by remember { mutableStateOf(s.styleGuide) }
    var styleStatus by remember { mutableStateOf("") }
    var styleKb by remember { mutableStateOf(StyleLearner.storageKb(context)) }
    val pickChats = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            StyleLearner.lastFollowingCount = 0
            val n = StyleLearner.import(context, uris)
            val f = StyleLearner.lastFollowingCount
            senders = StyleLearner.senders(context)
            styleKb = StyleLearner.storageKb(context)
            if (styleOwner.isBlank()) styleOwner = senders.firstOrNull()?.first.orEmpty()
            val t = StyleLearner.lastTagged
            val src = StyleLearner.lastSources.takeIf { it.isNotBlank() }?.let { " Kaynak: $it." }.orEmpty()
            styleStatus = when {
                f > 0 -> "$n mesaj ve $f takip edilen hesap yüklendi.$src"
                n == 0 && t > 0 -> "Bu dosya zaten yüklüydü; $t mesajın hangi hesaptan geldiği kaydedildi.$src"
                n == 0 -> "Yeni mesaj bulunamadı. Dosya zaten yüklenmiş olabilir; değilse WhatsApp dışa aktarma (.txt/.zip) ya da Instagram bilgi indirme ZIP'i olmalı."
                else -> "$n mesaj yüklendi.$src"
            }
        }
    }
    // Uyanma sesi dosyası uygulamanın içine kopyalanır (asıl dosya silinse de çalışsın); en fazla 3 MB.
    val pickWakeFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                    require(bytes.size <= 3_000_000) { "dosya çok büyük" }
                    WakeWordService.wakeFile(context).writeBytes(bytes)
                }.isSuccess
            }
            hasWakeFile = ok && WakeWordService.wakeFile(context).exists()
            if (!ok) android.widget.Toast.makeText(context, "Ses dosyası okunamadı.", android.widget.Toast.LENGTH_SHORT).show()
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
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Gelişmiş ses tanıma", color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        "Söylediklerinizi Gemini yazıya döker: isimleri, argoyu ve hızlı konuşmayı çok daha doğru anlar. " +
                            "Konuşurken yazı canlı görünmez, bitince çıkar. İnternet yoksa telefonun tanıyıcısı kullanılır.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = cloudStt, onCheckedChange = { cloudStt = it; s.cloudStt = it })
            }

            SectionTitle("Uyandırma kelimesi")
            OutlinedTextField(
                wakeWords, { wakeWords = it; s.wakeWords = it; wordsChanged = true },
                label = { Text("Kelimeler (virgülle, ör. jarvis, can, hey x)") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "İngilizce kelimeler (jarvis) hazır modelle çalışır. Türkçe kelimeler (can, emir...) için Türkçe modeli bir kez indirin (~35 MB). " +
                    "Kısa ve günlük konuşmada sık geçen kelimeler (\"can\" gibi) yanlışlıkla uyandırabilir; ses kaydınız bunu azaltır.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                OutlinedButton(onClick = {
                    if (Permissions.has(context, Manifest.permission.RECORD_AUDIO)) testingWake = true
                    else message = "Önce mikrofon izni verin (Kurulum sihirbazı)."
                }) { Text("Dene") }
                if (wordsChanged && wakeRunning) Button(onClick = {
                    wordsChanged = false
                    scope.launch {
                        WakeWordService.stop(context)
                        kotlinx.coroutines.delay(700)
                        WakeWordService.start(context)
                    }
                }) { Text("Uygula") }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                when {
                    trProgress in 0..99 -> Text("Türkçe model indiriliyor... %$trProgress", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                    hasTr -> {
                        Text("Türkçe model yüklü ✓  ", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                        TextButton(onClick = { com.jarvis.assistant.wake.VoskModels.deleteTurkish(context); hasTr = false }) { Text("Sil") }
                    }
                    else -> OutlinedButton(onClick = {
                        trProgress = 0
                        scope.launch {
                            val ok = com.jarvis.assistant.wake.VoskModels.downloadTurkish(context) { p -> trProgress = p }
                            trProgress = -1
                            hasTr = ok
                            message = if (ok) "" else "Türkçe model indirilemedi; internet bağlantısını kontrol edip tekrar deneyin."
                            if (ok && wakeRunning) { WakeWordService.stop(context); kotlinx.coroutines.delay(700); WakeWordService.start(context) }
                        }
                    }) { Text("Türkçe modeli indir") }
                }
            }
            Text(
                "Anahtar gerekmez: Jarvis ücretsiz ve çevrimdışı Vosk motoruyla dinler. Picovoice anahtarınız varsa girin; daha az pil harcar (sadece \"jarvis\" kelimesiyle).",
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
            Text("Uyanınca", color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 10.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                listOf(
                    Settings.WAKE_SPEAK to "Söz söylesin", Settings.WAKE_BEEP to "Bip", Settings.WAKE_FILE to "Kendi sesim", Settings.WAKE_SILENT to "Sessiz",
                ).forEach { (mode, label) ->
                    FilterChip(wakeSound == mode, { wakeSound = mode; s.wakeSound = mode }, { Text(label) })
                }
            }
            when (wakeSound) {
                Settings.WAKE_SPEAK -> OutlinedTextField(
                    wakePhrases, { wakePhrases = it; s.wakePhrases = it },
                    label = { Text("Söylenecekler (her satıra bir tane, rastgele seçilir)") },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    minLines = 2,
                )
                Settings.WAKE_FILE -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                    Button(onClick = { pickWakeFile.launch(arrayOf("audio/*")) }) { Text(if (hasWakeFile) "Sesi değiştir" else "Ses dosyası seç") }
                    Text(
                        if (hasWakeFile) "  Seçildi (ilk 5 saniye çalar)" else "  Kısa bir mp3/ogg/wav seçin",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (wakeSound == Settings.WAKE_FILE || wakeSound == Settings.WAKE_BEEP) {
                Text("Ses düzeyi: %${(wakeVolume * 100).roundToInt()}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                Slider(value = wakeVolume, onValueChange = { wakeVolume = it }, valueRange = 0.1f..1f, onValueChangeFinished = { s.wakeVolume = wakeVolume })
            }
            if (wakeSound != Settings.WAKE_SILENT) {
                OutlinedButton(
                    onClick = { scope.launch { WakeWordService.playWakeSound(context) } },
                    modifier = Modifier.padding(top = 4.dp),
                ) { Text("Dene") }
            }

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Konuşurken sözünü kesebileyim", color = MaterialTheme.colorScheme.onSurface)
                    Text("Jarvis konuşurken uyandırma kelimesini söyleyin; susar ve sizi dinler.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = bargeIn, onCheckedChange = { bargeIn = it; s.bargeIn = it })
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Bluetooth kulaklığın mikrofonunu kullan", color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        "Kulaklıkla sizi duymuyorsa açın. Telefon cepteyken bile duyar ama kulaklık görüşme moduna geçer: müzik kalitesi düşer, pil biraz daha gider.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = headsetMic, onCheckedChange = {
                    headsetMic = it
                    s.headsetMic = it
                    if (wakeRunning) scope.launch { WakeWordService.stop(context); kotlinx.coroutines.delay(700); WakeWordService.start(context) }
                })
            }

            SectionTitle("Sesimi tanı")
            Text(
                if (hasPrint) "Sesiniz kayıtlı: Jarvis sadece size tepki verir (eşik ${"%.2f".format(threshold)}). İlk seferde tanımazsa hemen bir daha \"Jarvis\" deyin; kabul eder ve sesinizi öğrenir. Eski sürümde kaydettiyseniz bir kez \"Yeniden kaydet\" yapın."
                else "Kayıt yok: \"Jarvis\" diyen herkese (ve bazen şarkılara) tepki verebilir. 8 kez \"Jarvis\" diyerek sesinizi tanıtın.",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                Button(onClick = {
                    if (Permissions.has(context, Manifest.permission.RECORD_AUDIO)) enrolling = true
                    else message = "Önce mikrofon izni verin (Kurulum sihirbazı)."
                }) { Text(if (hasPrint) "Yeniden kaydet" else "Sesimi tanıt") }
                if (hasPrint) OutlinedButton(onClick = { s.voicePrint = ""; s.voiceSamples = ""; hasPrint = false }) { Text("Sil") }
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

            SectionTitle("İnternetsiz sohbet")
            val nanoStatus by com.jarvis.assistant.ai.NanoModel.status.collectAsStateWithLifecycle()
            LaunchedEffect(Unit) { com.jarvis.assistant.ai.NanoModel.checkStatus() }
            Text(
                "İnternet yokken telefon komutları zaten çalışır. Sohbet için telefonun içindeki yapay zeka (Gemini Nano) kullanılır; " +
                    "model bir kez indirilir (Wi-Fi önerilir).",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (nanoStatus.isNotBlank()) Text(nanoStatus, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 4.dp))
            OutlinedButton(
                onClick = { scope.launch { com.jarvis.assistant.ai.NanoModel.download() } },
                modifier = Modifier.padding(top = 6.dp),
            ) { Text("Modeli indir / yeniden kontrol et") }
            if (nanoStatus.startsWith("Gemini Nano şu an")) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                    listOf("AICore" to com.jarvis.assistant.ai.NanoModel.AICORE, "Private Compute" to com.jarvis.assistant.ai.NanoModel.PCS).forEach { (label, pkg) ->
                        TextButton(onClick = {
                            runCatching {
                                context.startActivity(
                                    android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=$pkg"))
                                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                        }) { Text("$label güncelle") }
                    }
                }
            }

            SectionTitle("Konuşma tarzım")
            Text(
                "WhatsApp: sohbette ⋮ > Diğer > Sohbeti dışa aktar > Medya olmadan. Instagram: Hesap Merkezi > Bilgilerin ve izinlerin > " +
                    "Bilgilerini indir (Mesajlar, tercihen JSON) ile gelen ZIP. Dosyayı buraya yükleyin ya da paylaş menüsünden Jarvis'i seçin. " +
                    "Birden fazla hesap yükleyebilirsiniz (ör. 1 WhatsApp + 2 Instagram); hepsi harmanlanır. " +
                    "Yapay zekaya SADECE sizin mesajlarınız (her hesaptan adil pay, toplam 200 bin karaktere kadar), paylaştığınız " +
                    "reels açıklamaları ve takip ettiğiniz hesaplar gider. Arkadaşlarınızın mesajları telefonda kalır.",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                Button(onClick = { pickChats.launch(arrayOf("text/plain", "application/zip", "*/*")) }) { Text("Sohbet yükle") }
                if (senders.isNotEmpty()) OutlinedButton(onClick = {
                    StyleLearner.clear(context)
                    senders = emptyList()
                    styleKb = 0
                    styleStatus = "Yüklenen sohbetler silindi."
                }) { Text("Sohbetleri sil") }
            }
            if (senders.isNotEmpty()) {
                Text("Hangileri sizsiniz? (birden fazla seçebilirsiniz)", fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.onSurface)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                ) {
                    senders.take(25).forEach { (name, count) ->
                        // Birden fazla ad seçilebilir (WhatsApp ve Instagram'daki adlarınız farklıysa).
                        val selected = name in ownerSet(styleOwner)
                        FilterChip(selected, {
                            val set = ownerSet(styleOwner).toMutableSet()
                            if (selected) set.remove(name) else set.add(name)
                            styleOwner = set.joinToString(",")
                        }, { Text("$name ($count)") })
                    }
                }
                Button(
                    onClick = {
                        styleStatus = "Analiz ediliyor..."
                        scope.launch {
                            styleStatus = try {
                                val g = StyleLearner.analyze(context, styleOwner)
                                styleGuide = s.styleGuide
                                if (g == styleGuide) "Konuşma tarzınız öğrenildi (${StyleLearner.lastUsage})." else g
                            } catch (e: Exception) {
                                "Hata: ${e.message}"
                            }
                        }
                    },
                    enabled = styleOwner.isNotBlank(),
                    modifier = Modifier.padding(top = 6.dp),
                ) { Text("Tarzımı analiz et") }
                Text("Kayıt boyutu: $styleKb KB (sıkıştırılmış)", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val n = StyleLearner.pruneOthers(context, styleOwner)
                            senders = StyleLearner.senders(context)
                            styleKb = StyleLearner.storageKb(context)
                            styleStatus = "Başkalarına ait $n mesaj telefondan silindi; sadece sizinkiler kaldı."
                        }
                    },
                    enabled = styleOwner.isNotBlank(),
                ) { Text("Başkalarının mesajlarını sil") }
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

    if (testingWake) WakeTestDialog(onDone = { testingWake = false; wordsChanged = false })

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

/** Seçili adlar ("Ali,ali_insta") kümesi. */
private fun ownerSet(s: String): Set<String> = s.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
