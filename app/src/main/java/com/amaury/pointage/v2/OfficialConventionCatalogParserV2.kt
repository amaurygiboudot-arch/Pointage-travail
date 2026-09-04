package com.amaury.pointage.v2

/** Convertit la réponse officielle /list/conventions sans y associer de règle de paie. */
object OfficialConventionCatalogParserV2 {
    data class Item(
        val idcc: String,
        val title: String,
        val textId: String?
    )

    data class Page(
        val items: List<Item>,
        val rawResultCount: Int,
        val totalResultNumber: Int?
    )

    fun parse(data: Any?): Page {
        val root = data as? Map<*, *> ?: return Page(emptyList(), 0, null)
        val results = root["results"] as? List<*> ?: emptyList<Any?>()
        val items = results.mapNotNull { raw ->
            val item = raw as? Map<*, *> ?: return@mapNotNull null
            val idcc = normalizeIdcc(firstString(item, "idcc", "num")) ?: return@mapNotNull null
            val title = firstString(item, "titre", "title")?.trim().orEmpty()
            if (title.isBlank()) return@mapNotNull null
            val textId = firstString(item, "id", "cid")
                ?.takeIf { it.startsWith("KALITEXT") }
            Item(idcc, title, textId)
        }.distinctBy { it.idcc }
        val total = when (val value = root["totalResultNumber"]) {
            is Number -> value.toInt()
            is String -> value.toIntOrNull()
            else -> null
        }?.takeIf { it >= 0 }
        return Page(items, results.size, total)
    }

    fun normalizeIdcc(value: String?): String? {
        val digits = value.orEmpty().filter(Char::isDigit)
        val number = digits.takeIf { it.length in 1..4 }?.toIntOrNull() ?: return null
        if (number <= 0) return null
        return number.toString().padStart(4, '0')
    }

    private fun firstString(map: Map<*, *>, vararg keys: String): String? {
        keys.forEach { wanted ->
            map.entries.firstOrNull { it.key?.toString()?.equals(wanted, ignoreCase = true) == true }
                ?.value?.toString()?.let { return it }
        }
        return null
    }
}
