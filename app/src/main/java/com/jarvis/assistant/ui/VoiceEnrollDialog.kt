package com.jarvis.assistant.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.wake.VoiceId
import com.jarvis.assistant.wake.WakeWordService
import kotlinx.coroutines.delay

private const val SAMPLES = 8

/** Farklı koşullarda örnek alınsın diye her kayıtta değişen ipuçları. */
private val TIPS = listOf(
    "Normal sesinizle", "Biraz uzaktan, telefon masada", "Hızlıca, günlük konuşur gibi", "Biraz yüksek sesle",
    "Alçak sesle", "Telefon cebinizdeymiş gibi uzaktan", "Yorgun/rahat bir tonla", "Normal sesinizle, tekrar",
)

/**
 * "Sesimi tanıt": kullanıcıdan 5 kez "Jarvis" demesini ister, ses izini kaydeder.
 * Kayıt sırasında uyandırma servisi mikrofonu bırakması için durdurulur, sonra tekrar başlar.
 */
@Composable
fun VoiceEnrollDialog(onDone: (Boolean) -> Unit) {
    val context = LocalContext.current
    val settings = JarvisApp.instance.settings
    var count by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf("Hazırlanıyor...") }
    var finished by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val wasRunning = WakeWordService.isRunning.value
        if (wasRunning) {
            WakeWordService.stop(context)
            delay(600)
        }
        val samples = mutableListOf<FloatArray>()
        var misses = 0
        try {
            while (samples.size < SAMPLES && misses < 8) {
                message = "\"Jarvis\" deyin (${samples.size + 1}/$SAMPLES)\n" + TIPS[samples.size % TIPS.size]
                val v = VoiceId.captureSample(context)
                if (v != null) {
                    samples += v
                    count = samples.size
                    message = "Aldım!"
                    delay(500)
                } else {
                    misses++
                    message = "Duyamadım, tekrar deneyin."
                    delay(900)
                }
            }
            if (samples.size >= 4) {
                VoiceId.saveEnrollment(settings, samples)
                message = "Sesiniz kaydedildi. Artık sadece size tepki vereceğim."
            } else {
                message = "Yeterli kayıt alınamadı. Sessiz bir yerde tekrar deneyin."
            }
        } catch (e: Exception) {
            message = "Hata: ${e.message}"
        } finally {
            finished = true
            if (wasRunning) WakeWordService.start(context)
        }
    }

    AlertDialog(
        onDismissRequest = {},
        title = { Text("Sesimi tanıt") },
        text = {
            Column {
                Text(message, fontSize = 16.sp)
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(progress = { count / SAMPLES.toFloat() })
                Spacer(Modifier.height(8.dp))
                Text("Telefonu her zamanki gibi tutun; müzik varsa kapatın.", fontSize = 12.sp)
            }
        },
        confirmButton = {
            if (finished) TextButton(onClick = { onDone(settings.voicePrint.isNotBlank()) }) { Text("Tamam") }
        },
    )
}
