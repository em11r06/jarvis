package com.jarvis.assistant.tools

/**
 * Tüm araçların kayıt listesi. Yeni bir araç yazdığında buraya eklemen yeterli.
 */
object ToolRegistry {
    val all: List<Tool> = listOf(
        SaatTarihTool(),
    )

    fun find(name: String): Tool? = all.firstOrNull { it.name == name }
}
