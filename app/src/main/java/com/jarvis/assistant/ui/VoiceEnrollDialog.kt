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
import com.jarvis.assistant.wake.SpeakerId
import com.jarvis.assistant.wake.VoiceId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
        val spk = mutableListOf<FloatArray>()
        var misses = 0
        var got = 0
        val recorder = com.jarvis.assistant.speech.CloudListener(context, settings)
        try {
            // 1. aşama: uyandırma kelimesi.
            while (got < SAMPLES && misses < 8) {
                message = "\"$word\" deyin (${got + 1}/$SAMPLES)\n" + TIPS[got % TIPS.size]
                val pcm = recorder.recordSentence(6000)
                val vosk = pcm?.let { VoiceId.voiceVector(context, it) }?.first
                val emb = pcm?.let { withContext(Dispatchers.IO) { SpeakerId.embedBytes(context, it) } }
                if (vosk != null || emb != null) {
                    vosk?.let { samples += it }
                    emb?.let { spk += it }
                    got++
                    count = got
                    message = "Aldım!"
                    delay(400)
                } else {
                    misses++
                    message = "Duyamadım, tekrar deneyin."
                    delay(900)
                }
            }
            if (got >= 4) {
                if (samples.size >= 3) VoiceId.saveEnrollment(settings, samples)
                // 2. aşama: normal konuşma sesi. Uyandıktan sonraki cümlelerin size ait olup olmadığı bununla anlaşılır.
                val long = mutableListOf<FloatArray>()
                var sentenceMisses = 0
                var i = 0
                while (i < SENTENCES.size && sentenceMisses < 6) {
                    phase = 2
                    message = "Şimdi bu cümleyi normal sesinizle okuyun (${i + 1}/${SENTENCES.size}):\n\n\"${SENTENCES[i]}\""
                    val pcm = recorder.recordSentence(10_000)
                    val vf = pcm?.let { VoiceId.voiceVector(context, it) }
                    val emb = pcm?.takeIf { it.size >= 16000 * 2 * 3 / 2 }?.let { withContext(Dispatchers.IO) { SpeakerId.embedBytes(context, it) } }
                    if ((vf != null && vf.second >= 120) || emb != null) {
                        if (vf != null && vf.second >= 120) long += vf.first
                        emb?.let { spk += it }
                        count = SAMPLES + i + 1
                        message = "Aldım!"
                        delay(500)
                        i++
                    } else {
                        sentenceMisses++
                        message = "Tam duyamadım, cümlenin tamamını okuyun."
                        delay(1200)
                    }
                }
                if (long.size >= 3) VoiceId.saveLongEnrollment(settings, long)
                message = when {
                    spk.size >= 6 -> {
                        SpeakerId.saveEnrollment(settings, spk)
                        "Sesiniz kaydedildi. Artık hem uyandırırken hem konuşurken sadece size tepki vereceğim."
                    }
                    long.size >= 3 -> "Sesiniz kaydedildi."
                    else -> "Uyandırma sesiniz kaydedildi ama cümleler alınamadı. Sessiz bir yerde tekrar deneyin."
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
            if (finished) TextButton(onClick = { onDone(settings.voicePrint.isNotBlank() || SpeakerId.enrolled(settings)) }) { Text("Tamam") }
        },
    )
}
