package com.amaury.pointage.v2

import java.text.Normalizer
import java.util.Locale

/**
 * Extrait les identifiants officiels KALI contenus dans une réponse /consult/kaliText.
 *
 * Les KALITEXT/KALISCTA sont conservés comme repères de navigation. Pour KALIARTI, on ne remonte
 * que les articles situés dans une section clairement liée aux heures supplémentaires ou dont le
 * propre titre/contenu contient un indice paie pertinent. Cela évite de reconsulter des centaines
 * d'articles sans rapport avec les heures supplémentaires tout en restant non destructif : cette
 * couche ne décide jamais qu'un article est applicable et ne crée aucune règle de paie.
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

        fun directId(map: Map<*, *>): String? = map.entries
            .firstOrNull { (key, _) ->
                val normalized = key?.toString()?.lowercase(Locale.ROOT)
                normalized == "id" || normalized == "cid"
            }
            ?.value
            ?.toString()
            ?.uppercase(Locale.ROOT)
            ?.let { raw -> idRegex.find(raw)?.value }

        fun directSearchableText(map: Map<*, *>): String = buildString {
            val searchableKeys = setOf(
                "title", "titre", "libelle", "label", "num", "numero",
                "content", "contenu", "texte", "textehtml", "description"
            )
            map.forEach { (key, value) ->
                if (key?.toString()?.lowercase(Locale.ROOT) !in searchableKeys) return@forEach
                when (value) {
                    is String, is Number -> append(' ').append(value.toString())
                    is List<*> -> value.filterIsInstance<String>().forEach { append(' ').append(it) }
                }
            }
        }

        fun looksOvertimeRelevant(raw: String): Boolean {
            val text = normalize(raw)
            if (text.isBlank()) return false
            if (text.contains("heure supplementaire") || text.contains("heures supplementaires")) return true
            if (text.contains("repos compensateur") || text.contains("contrepartie obligatoire en repos")) return true
            if (text.contains("contingent") && text.contains("heure")) return true
            if (text.contains("majoration") && (text.contains("heure") || text.contains("duree"))) return true
            if ((text.contains("36e") || text.contains("43e") || text.contains("44e")) && text.contains("heure")) return true
            return false
        }

        fun walk(value: Any?, inheritedRelevant: Boolean = false, depth: Int = 0) {
            if (depth > 14) return
            when (value) {
                is Map<*, *> -> {
                    val id = directId(value)
                    val ownRelevant = looksOvertimeRelevant(directSearchableText(value))
                    val relevantHere = inheritedRelevant || ownRelevant

                    when {
                        id?.startsWith("KALITEXT") == true -> textIds += id
                        id?.startsWith("KALISCTA") == true -> sectionIds += id
                        id?.startsWith("KALIARTI") == true && relevantHere -> articleIds += id
                    }

                    val childRelevant = when {
                        id?.startsWith("KALIARTI") == true -> inheritedRelevant
                        id?.startsWith("KALISCTA") == true -> relevantHere
                        id?.startsWith("KALITEXT") == true -> relevantHere
                        else -> relevantHere
                    }
                    value.values.forEach { child -> walk(child, childRelevant, depth + 1) }
                }
                is List<*> -> value.forEach { child -> walk(child, inheritedRelevant, depth + 1) }
            }
        }

        walk(data)
        return Expansion(articleIds.toList(), textIds.toList(), sectionIds.toList())
    }

    private fun normalize(value: String): String = Normalizer.normalize(
        value.lowercase(Locale.FRANCE),
        Normalizer.Form.NFD
    ).replace(Regex("\\p{M}+"), "")
        .replace('’', '\'')
        .replace(Regex("\\s+"), " ")
        .trim()

    private val idRegex = Regex("(?:KALIARTI|KALITEXT|KALISCTA)\\d+", RegexOption.IGNORE_CASE)
}
