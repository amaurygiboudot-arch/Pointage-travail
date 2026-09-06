package com.amaury.pointage.v2

/**
 * Recherche ciblée des règles d'heures supplémentaires dans le fonds KALI.
 *
 * Cette couche reste volontairement une source de candidats : elle ne transforme jamais un extrait
 * de recherche en règle de paie. Les résultats devront ensuite être consultés, datés, vérifiés et
 * structurés avant tout appel à V2ConventionRuleStore.saveConfirmed().
 */
object OfficialKaliOvertimeSourceV2 {
    data class Candidate(
        val id: String,
        val title: String?,
        val snippet: String?
    )

    data class Page(
        val candidates: List<Candidate>,
        val pageNumber: Int,
        val pageSize: Int,
        val totalResults: Int?
    ) {
        val lastPageConfirmed: Boolean
            get() = totalResults?.let { pageNumber * pageSize >= it } == true
    }

    fun searchBody(idcc: String, pageNumber: Int = 1, pageSize: Int = 25): Map<String, Any> {
        val normalized = normalizeIdcc(idcc)
            ?: throw IllegalArgumentException("IDCC KALI invalide")
        require(pageNumber >= 1) { "Page KALI invalide" }

        return mapOf(
            "fond" to "KALI",
            "recherche" to mapOf(
                "champs" to listOf(
                    mapOf(
                        "typeChamp" to "IDCC",
                        "operateur" to "ET",
                        "criteres" to listOf(
                            mapOf(
                                "valeur" to normalized.toInt().toString(),
                                "typeRecherche" to "TOUS_LES_MOTS_DANS_UN_CHAMP",
                                "operateur" to "ET"
                            )
                        )
                    ),
                    mapOf(
                        "typeChamp" to "ARTICLE",
                        "operateur" to "ET",
                        "criteres" to listOf(
                            mapOf(
                                "valeur" to "heures supplémentaires majoration",
                                "typeRecherche" to "UN_DES_MOTS",
                                "operateur" to "ET"
                            )
                        )
                    )
                ),
                "sort" to "PERTINENCE",
                "fromAdvancedRecherche" to false,
                "pageNumber" to pageNumber,
                "pageSize" to pageSize.coerceIn(1, 25),
                "typePagination" to "ARTICLE",
                "secondSort" to "ID",
                "operateur" to "ET"
            )
        )
    }

    fun parsePage(data: Any?, requestedPage: Int, requestedPageSize: Int): Page {
        require(requestedPage >= 1)
        require(requestedPageSize in 1..25)
        val root = data as? Map<*, *>
        val candidates = if (root == null) emptyList() else parseCandidates(root)
        return Page(
            candidates = candidates,
            pageNumber = requestedPage,
            pageSize = requestedPageSize,
            totalResults = root?.let(::findTotalResults)
        )
    }

    private fun parseCandidates(root: Map<*, *>): List<Candidate> {
        val results = root.entries
            .firstOrNull { it.key?.toString()?.equals("results", ignoreCase = true) == true }
            ?.value as? List<*> ?: emptyList<Any?>()

        val found = buildList {
            results.forEach { rawResult ->
                val result = rawResult as? Map<*, *> ?: return@forEach
                val inheritedTitle = firstString(result, "title", "titre", "libelle")
                collectKaliCandidates(result, inheritedTitle, this)
            }
        }
        return found.distinctBy { it.id }
    }

    private fun collectKaliCandidates(
        value: Any?,
        inheritedTitle: String?,
        output: MutableList<Candidate>,
        depth: Int = 0
    ) {
        if (depth > 6) return
        when (value) {
            is Map<*, *> -> {
                val title = firstString(value, "title", "titre", "libelle") ?: inheritedTitle
                val id = firstString(value, "id", "cid")?.takeIf(::isKaliContentId)
                if (id != null) {
                    val snippet = extractSnippet(value)
                    output += Candidate(id = id, title = title, snippet = snippet)
                }
                value.values.forEach { child -> collectKaliCandidates(child, title, output, depth + 1) }
            }
            is List<*> -> value.forEach { child -> collectKaliCandidates(child, inheritedTitle, output, depth + 1) }
        }
    }

    private fun extractSnippet(map: Map<*, *>): String? {
        val direct = firstString(map, "content", "contenu", "texte", "snippet", "extract")
        if (!direct.isNullOrBlank()) return clean(direct).take(900).takeIf { it.isNotBlank() }

        val values = map.entries
            .firstOrNull { it.key?.toString()?.equals("values", ignoreCase = true) == true }
            ?.value as? List<*>
        return values
            ?.joinToString(" ") { it?.toString().orEmpty() }
            ?.let(::clean)
            ?.take(900)
            ?.takeIf { it.isNotBlank() }
    }

    private fun findTotalResults(root: Map<*, *>): Int? {
        val acceptedKeys = setOf(
            "totalresultnumber",
            "totalresults",
            "totalresult",
            "nbresults",
            "nbresult",
            "nombrederesultats",
            "total"
        )
        fun walk(value: Any?, depth: Int): Int? {
            if (depth > 4) return null
            return when (value) {
                is Map<*, *> -> {
                    value.entries.firstNotNullOfOrNull { (key, raw) ->
                        val normalizedKey = key?.toString()?.lowercase()?.replace(Regex("[^a-z]"), "").orEmpty()
                        if (normalizedKey in acceptedKeys) asNonNegativeInt(raw) else null
                    } ?: value.values.firstNotNullOfOrNull { walk(it, depth + 1) }
                }
                is List<*> -> value.firstNotNullOfOrNull { walk(it, depth + 1) }
                else -> null
            }
        }
        return walk(root, 0)
    }

    private fun asNonNegativeInt(value: Any?): Int? = when (value) {
        is Int -> value.takeIf { it >= 0 }
        is Long -> value.takeIf { it in 0..Int.MAX_VALUE.toLong() }?.toInt()
        is Number -> value.toInt().takeIf { it >= 0 }
        is String -> value.toIntOrNull()?.takeIf { it >= 0 }
        else -> null
    }

    private fun firstString(map: Map<*, *>, vararg keys: String): String? {
        keys.forEach { wanted ->
            map.entries.firstOrNull { it.key?.toString()?.equals(wanted, ignoreCase = true) == true }
                ?.value?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }

    private fun isKaliContentId(value: String): Boolean {
        val prefixes = listOf("KALIARTI", "KALITEXT", "KALISCTA")
        return prefixes.any { prefix ->
            value.startsWith(prefix) && value.drop(prefix.length).isNotBlank() &&
                value.drop(prefix.length).all(Char::isDigit)
        }
    }

    private fun normalizeIdcc(value: String): String? {
        val digits = value.filter(Char::isDigit)
        val number = digits.takeIf { it.length in 1..4 }?.toIntOrNull() ?: return null
        if (number <= 0 || number == 9999) return null
        return number.toString().padStart(4, '0')
    }

    private fun clean(value: String): String = value
        .replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace(Regex("\\s+"), " ")
        .trim()
}
