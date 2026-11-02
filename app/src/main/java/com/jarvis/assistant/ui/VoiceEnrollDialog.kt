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
import com.jarvis.assistant.wake.VoskModels
import com.jarvis.assistant.wake.WakeWordService
import kotlinx.coroutines.delay

private const val SAMPLES = 8

/** Normal konuşma sesini öğrenmek için okunan cümleler. */
private val SENTENCES = listOf(
    "Yarın sabah yedide beni uyandır ve hava durumunu söyle.",
    "Bugün akşam arkadaşlarla buluşacağım, güzel bir mekan önerir misin?",
    "Sevdiğim şarkıları aç ve sesi biraz yükselt.",
    "Annemi ara, eve geç kalacağımı söyleyeceğim.",
)

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
    var phase by remember { mutableIntStateOf(1) }

    LaunchedEffect(Unit) {
        val wasRunning = WakeWordService.isRunning.value
        if (wasRunning) {
            WakeWordService.stop(context)
            delay(600)
        }
        val word = VoskModels.wakeWords(settings).first().replaceFirstChar { it.titlecase(java.util.Locale("tr", "TR")) }
        val samples = mutableListOf<FloatArray>()
        var misses = 0
        try {
            while (samples.size < SAMPLES && misses < 8) {
                message = "\"$word\" deyin (${samples.size + 1}/$SAMPLES)\n" + TIPS[samples.size % TIPS.size]
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
                // 2. aşama: normal konuşma sesi. Uyandıktan sonraki cümlelerin size ait olup olmadığı bununla anlaşılır.
                val recorder = com.jarvis.assistant.speech.CloudListener(context, settings)
                val long = mutableListOf<FloatArray>()
                var sentenceMisses = 0
                var i = 0
                while (i < SENTENCES.size && sentenceMisses < 6) {
                    phase = 2
                    message = "Şimdi bu cümleyi normal sesinizle okuyun (${i + 1}/${SENTENCES.size}):\n\n\"${SENTENCES[i]}\""
                    val pcm = recorder.recordSentence(10_000)
                    val vf = pcm?.let { VoiceId.voiceVector(context, it) }
                    if (vf != null && vf.second >= 120) {
                        long += vf.first
                        count = SAMPLES + long.size
                        message = "Aldım!"
                        delay(500)
                        i++
                    } else {
                        sentenceMisses++
                        message = "Tam duyamadım, cümlenin tamamını okuyun."
                        delay(1200)
                    }
                }
                message = if (long.size >= 3) {
                    VoiceId.saveLongEnrollment(settings, long)
                    "Sesiniz kaydedildi. Artık hem uyandırırken hem konuşurken sadece size tepki vereceğim."
                } else {
                    "Uyandırma sesiniz kaydedildi ama cümleler alınamadı; konuşma kontrolü daha gevşek çalışacak. Sessiz bir yerde tekrar deneyebilirsiniz."
                }
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
                LinearProgressIndicator(progress = { count / (SAMPLES + SENTENCES.size).toFloat() })
                Spacer(Modifier.height(8.dp))
                Text(
                    if (phase == 1) "Telefonu her zamanki gibi tutun; müzik varsa kapatın."
                    else "Acele etmeden, her zamanki konuşma sesinizle okuyun.",
                    fontSize = 12.sp,
                )
            }
        },
        confirmButton = {
            if (finished) TextButton(onClick = { onDone(settings.voicePrint.isNotBlank()) }) { Text("Tamam") }
        },
    )
}
