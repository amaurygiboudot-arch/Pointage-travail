package com.amaury.pointage.v2

import java.util.Locale

/**
 * Expansion KALI exhaustive pour les audits juridiques génériques.
 *
 * Contrairement à l'expandeur historique des heures supplémentaires, aucun filtrage métier
 * n'est appliqué ici : tous les KALIARTI présents dans le KALITEXT consulté sont conservés.
 * La réponse doit contenir exactement le KALITEXT demandé, sinon elle est considérée non fiable.
 */
object OfficialKaliMatterTextExpansionV2 {
    data class Expansion(
        val expectedTextId: String,
        val articleIds: List<String>,
        /** KALITEXT officiel le plus proche de chaque KALIARTI dans l'arbre consulté. */
        val articleTextIds: Map<String, String>,
        val sectionIds: List<String>,
        val reliable: Boolean,
        val warnings: List<String>
    )

    fun parse(data: Any?, expectedTextId: String): Expansion {
        val expected = expectedTextId.trim().uppercase(Locale.ROOT)
        if (!textIdRegex.matches(expected)) {
            return unresolved(expected, "identifiant KALITEXT demandé invalide")
        }

        val foundTextIds = linkedSetOf<String>()
        val articleIds = linkedSetOf<String>()
        val articleTextIds = linkedMapOf<String, String>()
        val sectionIds = linkedSetOf<String>()

        fun directId(map: Map<*, *>): String? = map.entries
            .firstOrNull { (key, _) -> key?.toString()?.lowercase(Locale.ROOT) in setOf("id", "cid") }
            ?.value
            ?.toString()
            ?.uppercase(Locale.ROOT)
            ?.let { idRegex.find(it)?.value }

        fun walk(value: Any?, inheritedTextId: String?, depth: Int = 0) {
            if (depth > 20) return
            when (value) {
                is Map<*, *> -> {
                    val id = directId(value)
                    val currentTextId = when {
                        id?.startsWith("KALITEXT") == true -> id.also(foundTextIds::add)
                        else -> inheritedTextId
                    }
                    when {
                        id?.startsWith("KALISCTA") == true -> sectionIds += id
                        id?.startsWith("KALIARTI") == true -> {
                            articleIds += id
                            currentTextId?.let { articleTextIds.putIfAbsent(id, it) }
                        }
                    }
                    value.values.forEach { child -> walk(child, currentTextId, depth + 1) }
                }
                is List<*> -> value.forEach { child -> walk(child, inheritedTextId, depth + 1) }
            }
        }

        walk(data, null)
        if (expected !in foundTextIds) {
            return unresolved(expected, "la réponse /consult/kaliText ne contient pas le KALITEXT demandé")
        }

        val unmapped = articleIds.filter { it !in articleTextIds }
        if (unmapped.isNotEmpty()) {
            return Expansion(
                expectedTextId = expected,
                articleIds = articleIds.toList(),
                articleTextIds = articleTextIds.toMap(),
                sectionIds = sectionIds.toList(),
                reliable = false,
                warnings = listOf(
                    "KALI : ${unmapped.size} KALIARTI du texte $expected n'ont pas de KALITEXT parent prouvé ; couverture générique incomplète."
                )
            )
        }

        return Expansion(
            expectedTextId = expected,
            articleIds = articleIds.toList(),
            articleTextIds = articleTextIds.toMap(),
            sectionIds = sectionIds.toList(),
            reliable = true,
            warnings = emptyList()
        )
    }

    private fun unresolved(expected: String, reason: String) = Expansion(
        expectedTextId = expected,
        articleIds = emptyList(),
        articleTextIds = emptyMap(),
        sectionIds = emptyList(),
        reliable = false,
        warnings = listOf("KALI : $reason ; aucun développement exhaustif n'est certifié.")
    )

    private val idRegex = Regex("(?:KALIARTI|KALITEXT|KALISCTA)\\d+", RegexOption.IGNORE_CASE)
    private val textIdRegex = Regex("^KALITEXT\\d+$")
}
