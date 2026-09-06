package com.amaury.pointage.v2

/**
 * Extrait uniquement les identifiants officiels KALI contenus dans une réponse /consult/kaliText.
 * Cette couche ne décide jamais qu'un article est applicable et ne crée aucune règle de paie.
 */
object OfficialKaliTextExpansionV2 {
    data class Expansion(
        val articleIds: List<String>,
        val textIds: List<String>,
        val sectionIds: List<String>
    )

    fun parse(data: Any?): Expansion {
        val articleIds = linkedSetOf<String>()
        val textIds = linkedSetOf<String>()
        val sectionIds = linkedSetOf<String>()

        fun collectId(raw: String?) {
            val value = raw.orEmpty().uppercase()
            idRegex.findAll(value).forEach { match ->
                val id = match.value
                when {
                    id.startsWith("KALIARTI") -> articleIds += id
                    id.startsWith("KALITEXT") -> textIds += id
                    id.startsWith("KALISCTA") -> sectionIds += id
                }
            }
        }

        fun walk(value: Any?, depth: Int = 0) {
            if (depth > 12) return
            when (value) {
                is Map<*, *> -> value.forEach { (key, child) ->
                    collectId(key?.toString())
                    if (child is String || child is Number) collectId(child.toString())
                    walk(child, depth + 1)
                }
                is List<*> -> value.forEach { child -> walk(child, depth + 1) }
                is String -> collectId(value)
            }
        }

        walk(data)
        return Expansion(articleIds.toList(), textIds.toList(), sectionIds.toList())
    }

    private val idRegex = Regex("(?:KALIARTI|KALITEXT|KALISCTA)\\d+", RegexOption.IGNORE_CASE)
}
