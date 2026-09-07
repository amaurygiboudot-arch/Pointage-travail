package com.amaury.pointage.v2

/** Recherche KALI générique par IDCC + expression métier, sans interprétation juridique. */
object OfficialKaliMatterSourceV2 {
    data class Candidate(val id: String, val title: String?, val snippet: String?)
    data class Page(
        val candidates: List<Candidate>,
        val pageNumber: Int,
        val pageSize: Int,
        val totalResults: Int?
    )

    fun searchBody(idcc: String, expression: String, pageNumber: Int = 1, pageSize: Int = 25): Map<String, Any> {
        val digits = idcc.filter(Char::isDigit)
        val normalized = digits.takeIf { it.length in 1..4 }?.toIntOrNull()?.takeIf { it > 0 && it != 9999 }
            ?: throw IllegalArgumentException("IDCC KALI invalide")
        val query = expression.trim().replace(Regex("\\s+"), " ").takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Expression KALI vide")
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
                                "valeur" to normalized.toString(),
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
                                "valeur" to query,
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

    fun parsePage(data: Any?, requestedPage: Int, requestedPageSize: Int): Page {
        val page = OfficialKaliOvertimeSourceV2.parsePage(data, requestedPage, requestedPageSize)
        return Page(
            candidates = page.candidates.map { Candidate(it.id, it.title, it.snippet) },
            pageNumber = page.pageNumber,
            pageSize = page.pageSize,
            totalResults = page.totalResults
        )
    }
}
