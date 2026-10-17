package com.jarvis.assistant.ai

import android.util.Log
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch

/**
 * Telefonun içinde çalışan yapay zeka (Gemini Nano, ML Kit GenAI Prompt API).
 * İnternet yokken sohbet için kullanılır. Model telefona bir kez indirilir.
 */
object NanoModel {
    private const val TAG = "JarvisNano"

    private val model: GenerativeModel by lazy { Generation.getClient() }

    /** Ayarlar ekranında gösterilen durum metni. */
    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status

    /** Model kullanıma hazır mı? */
    suspend fun isReady(): Boolean = checkStatus() == FeatureStatus.AVAILABLE

    suspend fun checkStatus(): Int {
        val s = runCatching { model.checkStatus() }
            .onFailure { Log.w(TAG, "Durum alınamadı", it) }
            .getOrDefault(FeatureStatus.UNAVAILABLE)
        _status.value = when (s) {
            FeatureStatus.AVAILABLE -> "Hazır: internetsizken de sohbet edebilirim."
            FeatureStatus.DOWNLOADABLE -> "Telefonunuz destekliyor; modeli indirmeniz gerekiyor."
            FeatureStatus.DOWNLOADING -> "Model indiriliyor..."
            else -> "Bu telefonda telefonda çalışan yapay zeka (Gemini Nano) kullanılamıyor."
        }
        return s
    }

    /** Modeli indirir; ilerlemeyi durum metnine yazar. */
    suspend fun download() {
        _status.value = "İndirme başlıyor..."
        model.download()
            .catch { e ->
                Log.w(TAG, "İndirme hatası", e)
                _status.value = "İndirme hatası: ${e.message}"
            }
            .collect { st ->
                val name = st::class.simpleName.orEmpty()
                _status.value = when {
                    name.contains("Completed", true) -> "İndirme tamamlandı."
                    name.contains("Failed", true) -> "İndirme başarısız. Wi-Fi'ye bağlanıp tekrar deneyin."
                    name.contains("Started", true) -> "İndirme başladı (Wi-Fi önerilir)..."
                    else -> "Model indiriliyor..."
                }
            }
        checkStatus()
    }

    /**
     * İnternetsiz sohbet cevabı.
     * @return cevap; model hazır değilse null
     */
    suspend fun chat(system: String, recent: List<Pair<String, String>>, userText: String): String? {
        if (!isReady()) return null
        val prompt = buildString {
            append(system).append("\n\n")
            recent.takeLast(6).forEach { (who, text) -> append(who).append(": ").append(text.take(400)).append('\n') }
            append("Kullanıcı: ").append(userText).append("\nJarvis:")
        }
        return runCatching {
            model.generateContent(prompt).candidates.firstOrNull()?.text?.trim()
        }.onFailure { Log.w(TAG, "Nano cevap üretemedi", it) }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    const val OFFLINE_SYSTEM =
        "Sen Jarvis'sin, kullanıcının telefonundaki samimi, esprili ve kibar Türkçe sesli asistan. " +
            "Şu an internet yok; internette arama yapamazsın, güncel bilgi gerekiyorsa bunu kısaca söyle. " +
            "Cevapların sesli okunacak: kısa konuş (bir iki cümle), markdown, liste ve emoji kullanma."
}
