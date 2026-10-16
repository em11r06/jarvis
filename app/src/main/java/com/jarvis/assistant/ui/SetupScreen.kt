package com.jarvis.assistant.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.core.Permissions
import com.jarvis.assistant.ui.theme.JarvisCyan

/** Sihirbazın bir adımı. */
private class SetupStep(
    val title: String,
    val why: String,
    /** Adım tamamlandı mı (izin verildi mi)? null: kontrol edilemiyor. */
    val done: (Context) -> Boolean?,
    val buttonText: String,
    /** Runtime izni ise izin adı, değilse null. */
    val permission: String? = null,
    val action: ((Context) -> Unit)? = null,
)

/**
 * İlk açılış sihirbazı: izinleri sırayla ister ve her birinin neden gerektiğini anlatır.
 */
@Composable
fun SetupScreen(onFinish: () -> Unit) {
    val context = LocalContext.current
    val settings = JarvisApp.instance.settings
    var index by rememberSaveable { mutableIntStateOf(0) }
    // Ayarlardan geri dönünce durumları yenilemek için.
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }

    val steps = remember {
        listOf(
            SetupStep(
                "Hoş geldiniz efendim",
                "Ben Jarvis. Size sesli yardım edebilmem için birkaç izne ihtiyacım var. Her adımda neden istediğimi anlatacağım. İstemediğiniz adımı atlayabilirsiniz; o özellik çalışmaz, o kadar.",
                { null }, "",
            ),
            SetupStep(
                "Mikrofon",
                "Sesinizi duyabilmem ve \"Jarvis\" diye seslendiğinizde uyanabilmem için gerekli. Ses kaydı saklanmaz.",
                { Permissions.has(it, Manifest.permission.RECORD_AUDIO) }, "İzin ver",
                permission = Manifest.permission.RECORD_AUDIO,
            ),
            SetupStep(
                "Bildirimler",
                "Arka planda dinlerken \"Jarvis dinliyor\" bildirimini göstermek için gerekli. Android bu bildirim olmadan arka planda mikrofon kullanmaya izin vermez.",
                { Permissions.has(it, Manifest.permission.POST_NOTIFICATIONS) }, "İzin ver",
                permission = Manifest.permission.POST_NOTIFICATIONS,
            ),
            SetupStep(
                "Rehber",
                "\"Annemi ara\" ya da \"Ahmet'e mesaj at\" dediğinizde kişiyi rehberde bulabilmem için.",
                { Permissions.has(it, Manifest.permission.READ_CONTACTS) }, "İzin ver",
                permission = Manifest.permission.READ_CONTACTS,
            ),
            SetupStep(
                "Telefon",
                "Sizin onayınızla doğrudan arama başlatabilmem için. Her aramadan önce sesli onay isterim.",
                { Permissions.has(it, Manifest.permission.CALL_PHONE) }, "İzin ver",
                permission = Manifest.permission.CALL_PHONE,
            ),
            SetupStep(
                "SMS",
                "Sizin onayınızla SMS gönderebilmem için. Göndermeden önce mesajı size okur, onay isterim.",
                { Permissions.has(it, Manifest.permission.SEND_SMS) }, "İzin ver",
                permission = Manifest.permission.SEND_SMS,
            ),
            SetupStep(
                "Diğer uygulamaların üzerinde göster",
                "Arka plandayken uygulama açabilmem (Spotify, harita, alarm...) için Android bu izni istiyor. Açılan listede Jarvis'i bulup izni açın.",
                { Permissions.canOverlay(it) }, "Ayarı aç",
                action = { Permissions.open(it, Permissions.overlayIntent(it)) },
            ),
            SetupStep(
                "Pil optimizasyonu",
                "Samsung, arka plandaki uygulamaları pil tasarrufu için kapatabiliyor. Jarvis'i muaf tutarsanız uyandırma kelimesi kesintisiz çalışır. Porcupine çok az pil harcar.",
                { Permissions.ignoresBattery(it) }, "Muaf tut",
                action = { Permissions.open(it, Permissions.batteryIntent(it), Permissions.appInfoIntent(it)) },
            ),
            SetupStep(
                "Kısıtlı ayarlara izin ver",
                "Jarvis'i Play Store dışından kurduğunuz için Android 16, erişilebilirlik iznini önce kilitliyor. Kilidi açmak için:\n\n" +
                    "1. Aşağıdaki düğmeye basın, Jarvis'in \"Uygulama bilgisi\" ekranı açılır.\n" +
                    "2. Sağ üstteki üç noktaya (⋮) dokunun.\n" +
                    "3. \"Kısıtlı ayarlara izin ver\" seçeneğine dokunun ve parmak izi/PIN ile onaylayın.\n" +
                    "4. Geri gelip sonraki adıma geçin.\n\n" +
                    "Üç nokta menüsünde bu seçenek yoksa önce bir sonraki adımda Jarvis'i açmayı deneyin; Android \"kısıtlı ayar\" uyarısı verdikten sonra seçenek görünür.",
                { if (Permissions.accessibilityEnabled(it)) true else null }, "Uygulama bilgisini aç",
                action = { Permissions.open(it, Permissions.appInfoIntent(it)) },
            ),
            SetupStep(
                "Erişilebilirlik servisi",
                "Bu izinle ekranı okuyup sizin için dokunabilirim: Instagram'da gezinmek, WhatsApp'tan mesaj yazmak, telefonu yeniden başlatmak gibi. Ayrıca telefon açılınca dinlemeyi başlatmak için kullanırım.\n\n" +
                    "Açılan ekranda: Yüklü uygulamalar > Jarvis > Aç.",
                { Permissions.accessibilityEnabled(it) }, "Erişilebilirlik ayarlarını aç",
                action = { Permissions.open(it, Permissions.accessibilityIntent()) },
            ),
            SetupStep(
                "API anahtarları",
                "Konuşabilmem için bir yapay zeka anahtarı gerekli. Gemini ücretsiz.\n\n" +
                    "Gemini: aistudio.google.com > Get API key\n" +
                    "Picovoice (\"Jarvis\" diye uyandırma için): console.picovoice.ai > AccessKey\n\n" +
                    "Anahtarları sonraki ekranda, Ayarlar'a yapıştıracaksınız.",
                { settings.geminiKey.isNotBlank() || settings.claudeKey.isNotBlank() }, "Gemini anahtarı al",
                action = { Permissions.open(it, Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey"))) },
            ),
        )
    }

    val step = steps[index]
    val status = remember(tick, index) { step.done(context) }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .statusBarsPadding().navigationBarsPadding().imePadding().padding(20.dp),
    ) {
        Text("KURULUM ${index + 1}/${steps.size}", color = JarvisCyan, fontSize = 13.sp, letterSpacing = 2.sp)
        LinearProgressIndicator(
            progress = { (index + 1f) / steps.size },
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        )
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            if (index == 0) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    ArcReactor(state = com.jarvis.assistant.core.AssistantState.IDLE, onClick = {})
                }
            }
            Text(step.title, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.height(12.dp))
            Text(step.why, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 22.sp)
            Spacer(Modifier.height(20.dp))
            when (status) {
                true -> Text("✓ Tamam", color = JarvisCyan, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                false -> Text("Henüz verilmedi", color = MaterialTheme.colorScheme.error, fontSize = 15.sp)
                null -> {}
            }
            Spacer(Modifier.height(12.dp))
            if (step.buttonText.isNotEmpty() && status != true) {
                Button(onClick = {
                    if (step.permission != null) permLauncher.launch(step.permission) else step.action?.invoke(context)
                }) { Text(step.buttonText) }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (index > 0) TextButton(onClick = { index-- }) { Text("Geri") }
            Spacer(Modifier.weight(1f))
            if (index < steps.lastIndex) {
                if (status == false) OutlinedButton(onClick = { index++ }) { Text("Atla") }
                Spacer(Modifier.padding(4.dp))
                Button(onClick = { index++ }, enabled = status != false) { Text("İleri") }
            } else {
                Button(onClick = {
                    settings.setupDone = true
                    onFinish()
                }) { Text("Bitir") }
            }
        }
    }
}
