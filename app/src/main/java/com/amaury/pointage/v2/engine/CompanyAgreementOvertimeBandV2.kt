package com.amaury.pointage.v2.engine

/**
 * Déduit une tranche hebdomadaire d'heures supplémentaires uniquement lorsque le texte
 * l'exprime de façon suffisamment explicite. Une formulation ambiguë retourne null.
 */
object CompanyAgreementOvertimeBandV2 {
    data class Band(
        val fromHourInclusive: Int,
        val toHourInclusive: Int?
    )

    fun parse(text: String): Band? {
        val normalized = text.lowercase()
            .replace('ᵉ', 'e')
            .replace('è', 'e')
            .replace('é', 'e')

        val boundedPatterns = listOf(
            Regex("""(?:de|a partir de)\s+la\s+(\d{1,3})e?\s+heure\s+(?:a|jusqu'a)\s+la\s+(\d{1,3})e?\s+heure"""),
            Regex("""entre\s+la\s+(\d{1,3})e?\s+et\s+la\s+(\d{1,3})e?\s+heure"""),
            Regex("""(\d{1,3})e?\s+a\s+(\d{1,3})e?\s+heure""")
        )
        val boundedMatches = boundedPatterns.flatMap { it.findAll(normalized).toList() }
        if (boundedMatches.size == 1) {
            val from = boundedMatches.single().groupValues[1].toIntOrNull() ?: return null
            val to = boundedMatches.single().groupValues[2].toIntOrNull() ?: return null
            if (from !in 36..100 || to !in from..100) return null
            return Band(from, to)
        }
        if (boundedMatches.size > 1) return null

        val openPatterns = listOf(
            Regex("""(?:au-dela de|au dela de)\s+(\d{1,3})\s*heures?"""),
            Regex("""(?:a partir de)\s+la\s+(\d{1,3})e?\s+heure""")
        )
        val openMatches = openPatterns.flatMap { it.findAll(normalized).toList() }
        if (openMatches.size != 1) return null
        val stated = openMatches.single().groupValues[1].toIntOrNull() ?: return null
        val from = if (openMatches.single().value.startsWith("au-dela") || openMatches.single().value.startsWith("au dela")) stated + 1 else stated
        if (from !in 36..100) return null
        return Band(from, null)
    }
}
