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
        WhatsappGonderTool(),
        MesajlariOkuTool(),
        SohbetiOkuTool(),
        PanoyaKopyalaTool(),
        // Sistem
        AlarmKurTool(),
        ZamanlayiciKurTool(),
        FenerTool(),
        SaatTarihTool(),
        PilDurumuTool(),
        GuncelleTool(),
        // Ekran kontrolü (erişilebilirlik servisi)
        EkraniOkuTool(),
        DokunTool(),
        YazTool(),
        KaydirTool(),
        GeriTool(),
        AnaEkranTool(),
        BildirimlerTool(),
        EkranGoruntusuTool(),
        EkraniKilitleTool(),
        YenidenBaslatTool(),
        OncekiEkranaDonTool(),
    )

    fun find(name: String): Tool? = all.firstOrNull { it.name == name }
}
