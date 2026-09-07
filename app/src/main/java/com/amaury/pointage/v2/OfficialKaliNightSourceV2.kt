package com.amaury.pointage.v2

/**
 * Recherche ciblée des dispositions KALI concernant les majorations de nuit.
 *
 * Comme pour les heures supplémentaires, cette source ne fait que remonter des candidats officiels.
 * Aucun extrait de recherche n'est traité comme une règle de paie sans consultation de l'article,
 * contrôle de la date d'application et structuration explicite.
 */
object OfficialKaliNightSourceV2 {
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
                                // On exige les deux notions pour éviter une recherche trop large sur le seul mot « nuit ».
                                "valeur" to "nuit majoration",
                                "typeRecherche" to "TOUS_LES_MOTS_DANS_UN_CHAMP",
                                "operateur" to "ET"
                            )
                        )
                    )
                ),
                "sort" to "PERTINENCE",
                "fromAdvancedRecherche" to false,
                "pageNumber" to pageNumber,
                "pageSize" to pageSize.coerceIn(1, 25),
                "typePagination" to "DEFAUT",
                "secondSort" to "PERTINENCE",
                "operateur" to "ET"
            )
        )
    }

    /**
     * Le parseur de page des heures supplémentaires ne dépend en réalité d'aucun mot-clé métier :
     * il ne fait que collecter les identifiants KALI présents dans une réponse /search. On le réutilise
     * ici pour garder exactement le même filtrage KALIARTI/KALITEXT/KALISCTA sans dupliquer ce code.
     */
    fun parsePage(data: Any?, requestedPage: Int, requestedPageSize: Int): Page {
        val page = OfficialKaliOvertimeSourceV2.parsePage(data, requestedPage, requestedPageSize)
        return Page(
            candidates = page.candidates.map { Candidate(it.id, it.title, it.snippet) },
            pageNumber = page.pageNumber,
            pageSize = page.pageSize,
            totalResults = page.totalResults
        )
    }

    private fun normalizeIdcc(value: String): String? {
        val digits = value.filter(Char::isDigit)
        val number = digits.takeIf { it.length in 1..4 }?.toIntOrNull() ?: return null
        if (number <= 0 || number == 9999) return null
        return number.toString().padStart(4, '0')
    }
}
