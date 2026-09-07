package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.WeekdayPremiumKindV2

/** Recherche KALI ciblée des majorations de samedi/dimanche. */
object OfficialKaliWeekdayPremiumSourceV2 {
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
    )

    fun searchBody(
        idcc: String,
        kind: WeekdayPremiumKindV2,
        pageNumber: Int = 1,
        pageSize: Int = 25
    ): Map<String, Any> {
        val normalized = normalizeIdcc(idcc)
            ?: throw IllegalArgumentException("IDCC KALI invalide")
        require(pageNumber >= 1) { "Page KALI invalide" }
        val keyword = when (kind) {
            WeekdayPremiumKindV2.SATURDAY -> "samedi majoration"
            WeekdayPremiumKindV2.SUNDAY -> "dimanche majoration"
        }

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
                                "valeur" to keyword,
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

    private fun normalizeIdcc(value: String): String? {
        val digits = value.filter(Char::isDigit)
        val number = digits.takeIf { it.length in 1..4 }?.toIntOrNull() ?: return null
        if (number <= 0 || number == 9999) return null
        return number.toString().padStart(4, '0')
    }
}
