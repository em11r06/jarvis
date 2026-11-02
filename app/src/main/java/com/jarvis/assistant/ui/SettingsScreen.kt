package com.jarvis.assistant.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.runtime.rememberCoroutineScope
import com.jarvis.assistant.style.StyleLearner
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.runtime.saveable.rememberSaveable
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
    var micSens by remember { mutableStateOf(s.micSensitivity) }
    var pauseMedia by remember { mutableStateOf(s.pauseMedia) }
    var cloudStt by remember { mutableStateOf(s.cloudStt) }
    var headsetMode by remember { mutableStateOf(s.headsetMicMode) }
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

    // Ayarlar kategorilere ayrıldı: ana ekranda kartlar, dokununca o kategorinin sayfası.
    var section by rememberSaveable { mutableStateOf<String?>(null) }
    androidx.activity.compose.BackHandler(enabled = section != null) { section = null; message = "" }
    val scroll = rememberScrollState()
    LaunchedEffect(section) { scroll.scrollTo(0) }

    // Ayar yedeği: dosyaya kaydet / dosyadan geri yükle.
    val exportSettings = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) runCatching {
            context.contentResolver.openOutputStream(uri)!!.use { it.write(s.exportJson().toByteArray()) }
        }.onSuccess { android.widget.Toast.makeText(context, "Ayarlar yedeklendi.", android.widget.Toast.LENGTH_SHORT).show() }
            .onFailure { message = "Yedek kaydedilemedi: ${it.message}" }
    }
    val importSettings = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching {
            val n = s.importJson(context.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) })
            android.widget.Toast.makeText(context, "$n ayar geri yüklendi. Jarvis yeniden başlıyor.", android.widget.Toast.LENGTH_LONG).show()
            // Yeni ayarların her yerde geçerli olması için uygulamayı yeniden başlat.
            val i = context.packageManager.getLaunchIntentForPackage(context.packageName)!!.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
            Runtime.getRuntime().exit(0)
        }.onFailure { message = "Yedek okunamadı: ${it.message}" }
    }

    fun sectionStatus(id: String): String = when (id) {
        "ai" -> (if (provider == Settings.PROVIDER_CLAUDE) "Claude" else "Gemini") +
            if ((provider == Settings.PROVIDER_CLAUDE && claudeKey.isBlank()) || (provider != Settings.PROVIDER_CLAUDE && geminiKey.isBlank())) " · anahtar girilmemiş" else " · hazır"
        "voice" -> if (s.ttsMode == Settings.TTS_ELEVEN) "ElevenLabs sesi" else "Telefonun sesi"
        "wake" -> (if (wakeRunning) "Dinliyor" else "Kapalı") + " · " + com.jarvis.assistant.wake.VoskModels.wakeWords(s).joinToString(", ")
        "voiceid" -> when {
            !hasPrint -> "Kayıt yok"
            com.jarvis.assistant.wake.VoiceId.hasLongEnrollment(s) -> "Kayıtlı ✓"
            else -> "Cümle kaydı eksik"
        }
        "style" -> if (styleGuide.isNotBlank()) "Öğrenildi ✓" else "Henüz yok"
        "phone" -> if (Permissions.notificationAccess(context)) "Bildirim erişimi açık" else "Bildirim erişimi kapalı"
        "system" -> "Sürüm ${Updater.currentVersion}"
        else -> ""
    }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .statusBarsPadding().navigationBarsPadding().imePadding(),
    ) {
        JarvisTopBar(SECTIONS.firstOrNull { it.id == section }?.title ?: "Ayarlar") { if (section != null) section = null else onBack() }
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 16.dp)) {
            if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            when (section) {
                null -> SECTIONS.forEach { sec ->
                    SettingsCard(sec, subtitle = sectionStatus(sec.id)) { message = ""; section = sec.id }
                }
                "ai" -> {
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

                }
                "voice" -> {
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

                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Dinlerken müziği durdur", color = MaterialTheme.colorScheme.onSurface)
                        Text("Siz konuşurken telefonda çalan müzik/video duraklar, bitince devam eder; telefonun kendi sesi karışmaz.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = pauseMedia, onCheckedChange = { pauseMedia = it; s.pauseMedia = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Konuşurken sözünü kesebileyim", color = MaterialTheme.colorScheme.onSurface)
                        Text("Jarvis konuşurken uyandırma kelimesini söyleyin; susar ve sizi dinler.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = bargeIn, onCheckedChange = { bargeIn = it; s.bargeIn = it })
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

                }
                "wake" -> {
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

                Text("Mikrofon hassasiyeti", color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 10.dp))
                Text(
                    "Otomatik: ortam gürültüsünü ölçüp sessiz yerde daha hassas, gürültülü yerde daha seçici olur. " +
                        "Yine de zor duyuyorsa Yüksek'i, gürültüde yanlışlıkla dinliyorsa Düşük'ü seçin.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(Settings.SENS_AUTO to "Otomatik", 0 to "Düşük", 1 to "Normal", 2 to "Yüksek").forEach { (v, label) ->
                        FilterChip(micSens == v, { micSens = v; s.micSensitivity = v }, { Text(label) })
                    }
                }

                Text("Bluetooth kulaklığın mikrofonu", color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 10.dp))
                Text(
                    "Otomatik: kulaklık (saat değil) bağlanınca komutlarınızı kulaklıktan dinler; uyandırma kelimesini müzik çalmıyorsa kulaklıktan, " +
                        "çalıyorsa (müzik kalitesi bozulmasın diye) telefondan dinler. Hep açık: her zaman kulaklıktan (müzik kalitesi düşer).",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(Settings.HEADSET_AUTO to "Otomatik", Settings.HEADSET_ALWAYS to "Hep açık", Settings.HEADSET_OFF to "Kapalı").forEach { (v, label) ->
                        FilterChip(headsetMode == v, {
                            headsetMode = v
                            s.headsetMicMode = v
                            if (wakeRunning) scope.launch { WakeWordService.stop(context); kotlinx.coroutines.delay(700); WakeWordService.start(context) }
                        }, { Text(label) })
                    }
                }
                }
                "voiceid" -> {
                SectionTitle("Sesimi tanı")
                Text(
                    if (hasPrint) "Sesiniz kayıtlı: Jarvis sadece size tepki verir (eşik ${"%.2f".format(threshold)}). Sizi zor tanıyorsa seçiciliği biraz düşürün ya da sessiz bir yerde yeniden kaydedin." +
                        (if (!com.jarvis.assistant.wake.VoiceId.hasLongEnrollment(s)) "\n\nÖnemli: Konuşma sesiniz henüz kayıtlı değil. \"Yeniden kaydet\" ile birkaç cümle okuyun; böylece uyandıktan sonra sadece sizin sözlerinizi dinlerim." else "")
                    else "Kayıt yok: \"Jarvis\" diyen herkese (ve bazen şarkılara) tepki verebilir. Uyandırma kelimesini 8 kez söyleyip 4 cümle okuyarak sesinizi tanıtın.",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                    Button(onClick = {
                        if (Permissions.has(context, Manifest.permission.RECORD_AUDIO)) enrolling = true
                        else message = "Önce mikrofon izni verin (Kurulum sihirbazı)."
                    }) { Text(if (hasPrint) "Yeniden kaydet" else "Sesimi tanıt") }
                    if (hasPrint) OutlinedButton(onClick = { s.voicePrint = ""; s.voiceSamples = ""; s.voiceSamplesLong = ""; s.voiceThresholdLong = 0f; hasPrint = false }) { Text("Sil") }
                }
                if (hasPrint) {
                    Text("Seçicilik: sizi tanımazsa düşürün, başkalarına tepki verirse artırın.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                    Slider(
                        value = threshold, onValueChange = { threshold = it }, valueRange = 0.2f..0.9f,
                        onValueChangeFinished = { s.voiceThreshold = threshold },
                    )
                }

                }
                "phone" -> {
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

                }
                "style" -> {
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

                }
                "offline" -> {
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

                }
                "system" -> {
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
                        Button(onClick = {
                            val msg = Updater.install(context)
                            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
                        }) { Text("Şimdi güncelle") }
                    } else {
                        OutlinedButton(onClick = { Updater.checkInBackground(context, force = true) }) { Text("Güncellemeleri denetle") }
                    }
                }

                SectionTitle("Yedekle / geri yükle")
                Text(
                    "Tüm ayarlarınızı (API anahtarları, ses kaydınız, konuşma tarzınız dahil) bir dosyaya kaydedin. " +
                        "Uygulamayı silip yeniden kurmanız gerekirse bu dosyayla her şeyi geri getirirsiniz. Dosyayı kimseyle paylaşmayın.",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                    Button(onClick = { exportSettings.launch("jarvis-yedek.json") }) { Text("Yedekle") }
                    OutlinedButton(onClick = { importSettings.launch(arrayOf("application/json", "*/*")) }) { Text("Geri yükle") }
                }

                SectionTitle("Diğer")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onSetup) { Text("Kurulum sihirbazı") }
                    Button(onClick = { confirmClear = true }) { Text("Geçmişi temizle") }
                }
                }
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

/** Ayarlar ana ekranındaki bir kategori. */
private data class SettingsSection(val id: String, val title: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val SECTIONS = listOf(
    SettingsSection("ai", "Yapay zeka", Icons.Default.Psychology),
    SettingsSection("voice", "Ses ve konuşma", Icons.Default.RecordVoiceOver),
    SettingsSection("wake", "Uyandırma", Icons.Default.Hearing),
    SettingsSection("voiceid", "Sesimi tanı", Icons.Default.Fingerprint),
    SettingsSection("style", "Konuşma tarzım", Icons.Default.Forum),
    SettingsSection("phone", "Telefon ve mesajlar", Icons.Default.PhoneAndroid),
    SettingsSection("offline", "İnternetsiz", Icons.Default.CloudOff),
    SettingsSection("system", "Güncelleme ve yedek", Icons.Default.SystemUpdate),
)

/** Kategori kartı: simge, başlık, kısa durum. */
@Composable
private fun SettingsCard(sec: SettingsSection, subtitle: String, onClick: () -> Unit) {
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .background(com.jarvis.assistant.ui.theme.JarvisSurface, shape)
            .border(1.dp, com.jarvis.assistant.ui.theme.JarvisCyan.copy(alpha = 0.35f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        androidx.compose.material3.Icon(sec.icon, contentDescription = null, tint = com.jarvis.assistant.ui.theme.JarvisCyan)
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(sec.title, color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp)
            if (subtitle.isNotBlank()) Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 1)
        }
        androidx.compose.material3.Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null, tint = com.jarvis.assistant.ui.theme.JarvisCyan.copy(alpha = 0.7f),
        )
    }
}

/** Seçili adlar ("Ali,ali_insta") kümesi. */
private fun ownerSet(s: String): Set<String> = s.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
