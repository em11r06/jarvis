package com.jarvis.assistant.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.wake.MicLoop
import com.jarvis.assistant.wake.VoiceId
import com.jarvis.assistant.wake.VoskModels
import com.jarvis.assistant.wake.WakeWordService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Uyandırma kelimesini dener: 8 saniye dinler; kelimeyi hangi modelin yakaladığını ve
 * serbest tanımada ne duyulduğunu gösterir (tanınmıyorsa başka yazılış denemek için).
 */
@Composable
fun WakeTestDialog(onDone: () -> Unit) {
    val context = LocalContext.current
    val settings = JarvisApp.instance.settings
    var message by remember { mutableStateOf("Hazırlanıyor...") }
    var heard by remember { mutableStateOf("") }
    var finished by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val wasRunning = WakeWordService.isRunning.value
        if (wasRunning) {
            WakeWordService.stop(context)
            delay(600)
        }
        val words = VoskModels.wakeWords(settings)
        val loops = mutableListOf<MicLoop>()
        val recs = mutableListOf<org.vosk.Recognizer>()
        try {
            val (en, spk) = VoskModels.load(context)
            val tr = VoskModels.loadTurkish(context)
            // Uyandırma tanıyıcıları (İngilizce + varsa Türkçe) ve en sonda serbest tanıma.
            recs += VoskModels.recognizer(en, spk, words, english = true)
            if (tr != null) recs += VoskModels.recognizer(tr, spk, words, english = false)
            val free = VoskModels.freeRecognizer(tr ?: en)
            var found: String? = null
            val heardList = mutableListOf<String>()
            message = "Şimdi uyandırma kelimenizi söyleyin: ${words.joinToString(" / ")}"
            val loop = MicLoop(
                recs + free,
                onResult = { json ->
                    val text = runCatching { JSONObject(json).optString("text") }.getOrDefault("")
                    val hit = VoiceId.parse(json, words)
                    if (hit != null && found == null) {
                        found = "Yakalandı ✓ (güven %${(hit.confidence * 100).toInt()})"
                    } else if (hit == null && text.isNotBlank() && text != "[unk]" && !text.contains("[unk]")) {
                        heardList += text
                    }
                },
                onError = { message = "Mikrofon hatası: $it" },
            )
            loops += loop
            withContext(Dispatchers.Main) { loop.start() }
            var waited = 0
            while (waited < 8000 && found == null) { delay(200); waited += 200 }
            delay(600) // serbest tanımanın sonucu da gelsin
            heard = heardList.distinct().joinToString(", ")
            message = found ?: buildString {
                append("Kelime tanınmadı.")
                if (tr == null) append(" Türkçe bir kelimeyse önce Türkçe modeli indirin.")
                append(" Kelimeyi okunduğu gibi yazmayı deneyin (ör. X için \"iks\"), ya da iki heceli/ayırt edici bir kelime seçin.")
            }
        } catch (e: Exception) {
            message = "Hata: ${e.message}"
        } finally {
            withContext(Dispatchers.IO) { loops.forEach { it.stop() } }
            recs.forEach { runCatching { it.close() } }
            finished = true
            if (wasRunning) WakeWordService.start(context)
        }
    }

    AlertDialog(
        onDismissRequest = {},
        title = { Text("Uyandırma kelimesini dene") },
        text = {
            Column {
                Text(message, fontSize = 16.sp)
                if (heard.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text("Serbest duyulan: $heard", fontSize = 13.sp)
                }
            }
        },
        confirmButton = { if (finished) TextButton(onClick = onDone) { Text("Tamam") } },
    )
}
