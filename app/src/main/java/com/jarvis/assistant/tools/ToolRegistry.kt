package com.jarvis.assistant.tools

/**
 * Tüm araçların kayıt listesi. Yeni bir araç yazdığında buraya eklemen yeterli.
 */
object ToolRegistry {
    val all: List<Tool> = listOf(
        // İnternet
        WebAraTool(),
        SayfaOkuTool(),
        // Uygulamalar ve medya
        UygulamaAcTool(),
        SarkiCalTool(),
        MedyaKontrolTool(),
        SesAyarlaTool(),
        HaritaAcTool(),
        // Telefon
        KisiBulTool(),
        AramaYapTool(),
        SmsGonderTool(),
        // Sistem
        AlarmKurTool(),
        ZamanlayiciKurTool(),
        FenerTool(),
        SaatTarihTool(),
        PilDurumuTool(),
    )

    fun find(name: String): Tool? = all.firstOrNull { it.name == name }
}
