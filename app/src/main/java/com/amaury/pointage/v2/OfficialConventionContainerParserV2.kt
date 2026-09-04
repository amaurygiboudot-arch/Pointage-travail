package com.amaury.pointage.v2

/** Vérifie qu'un conteneur KALI correspond exactement à l'IDCC demandé. */
object OfficialConventionContainerParserV2 {
    data class VerifiedConvention(
        val idcc: String,
        val containerId: String,
        val title: String,
        val baseTextIds: List<String>,
        val checkedAtMs: Long
    )

    fun parseVerified(
        data: Any?,
        expectedIdcc: String,
        checkedAtMs: Long = System.currentTimeMillis()
    ): VerifiedConvention? {
        val expected = OfficialConventionCatalogParserV2.normalizeIdcc(expectedIdcc) ?: return null
        val container = findContainer(data) ?: return null
        val actual = OfficialConventionCatalogParserV2.normalizeIdcc(
            firstString(container, "num", "idcc", "numeroTexte")
        ) ?: return null
        if (actual != expected) return null

        val containerId = firstString(container, "id", "cid")
            ?.takeIf { it.startsWith("KALICONT") } ?: return null
        val title = firstString(container, "titre", "title")?.trim().orEmpty()
        if (title.isBlank()) return null
        val baseTextValue = container.entries.firstOrNull {
            val key = it.key?.toString()
            key.equals("texteBaseId", ignoreCase = true) ||
                key.equals("idTexteBase", ignoreCase = true)
        }?.value
        val baseTextIds = when (baseTextValue) {
            is List<*> -> baseTextValue.mapNotNull(::safeBaseTextId)
            else -> listOfNotNull(safeBaseTextId(baseTextValue))
        }.distinct()

        return VerifiedConvention(actual, containerId, title, baseTextIds, checkedAtMs)
    }

    fun apiId(idcc: String): String? = OfficialConventionCatalogParserV2.normalizeIdcc(idcc)
        ?.trimStart('0')
        ?.takeIf { it.isNotBlank() }

    fun publicUrl(containerId: String): String? = containerId
        .takeIf { it.startsWith("KALICONT") && it.drop(8).all(Char::isDigit) }
        ?.let { "https://www.legifrance.gouv.fr/conv_coll/id/$it" }

    private fun safeBaseTextId(value: Any?): String? = value
        ?.toString()
        ?.takeIf { it.startsWith("KALITEXT") && it.drop(8).all(Char::isDigit) }

    private fun findContainer(value: Any?, depth: Int = 0): Map<*, *>? {
        if (depth > 4) return null
        return when (value) {
            is Map<*, *> -> {
                val id = firstString(value, "id", "cid")
                if (id?.startsWith("KALICONT") == true) value
                else value.values.firstNotNullOfOrNull { findContainer(it, depth + 1) }
            }
            is List<*> -> value.firstNotNullOfOrNull { findContainer(it, depth + 1) }
            else -> null
        }
    }

    private fun firstString(map: Map<*, *>, vararg keys: String): String? {
        keys.forEach { wanted ->
            map.entries.firstOrNull { it.key?.toString()?.equals(wanted, ignoreCase = true) == true }
                ?.value?.toString()?.let { return it }
        }
        return null
    }
}
