package com.amaury.pointage

object ConventionCatalog {

    data class Convention(
        val idcc: String,
        val shortName: String,
        val fullName: String,
        val rulesIntegrated: Boolean = false
    ) {
        val displayName: String
            get() = if (idcc.isBlank()) shortName else "$shortName — IDCC $idcc"

        fun matches(query: String): Boolean {
            val q = query.trim().lowercase()
            if (q.isBlank()) return true
            return idcc.lowercase().contains(q) ||
                shortName.lowercase().contains(q) ||
                fullName.lowercase().contains(q)
        }
    }

    val conventions = listOf(
        Convention(
            idcc = "0292",
            shortName = "Plasturgie",
            fullName = "Transformation des matières plastiques",
            rulesIntegrated = true
        ),
        Convention(
            idcc = "3248",
            shortName = "Métallurgie",
            fullName = "Convention collective nationale de la métallurgie"
        ),
        Convention(
            idcc = "0016",
            shortName = "Transports routiers",
            fullName = "Transports routiers et activités auxiliaires du transport"
        ),
        Convention(
            idcc = "1486",
            shortName = "Syntec",
            fullName = "Bureaux d'études techniques, cabinets d'ingénieurs-conseils et sociétés de conseils"
        ),
        Convention(
            idcc = "1979",
            shortName = "Hôtels, cafés, restaurants (HCR)",
            fullName = "Convention collective nationale des hôtels, cafés restaurants"
        ),
        Convention(
            idcc = "2216",
            shortName = "Commerce alimentaire",
            fullName = "Commerce de détail et de gros à prédominance alimentaire"
        ),
        Convention(
            idcc = "1596",
            shortName = "Bâtiment — jusqu'à 10 salariés",
            fullName = "Ouvriers des entreprises du bâtiment occupant jusqu'à 10 salariés"
        ),
        Convention(
            idcc = "1597",
            shortName = "Bâtiment — plus de 10 salariés",
            fullName = "Ouvriers des entreprises du bâtiment occupant plus de 10 salariés"
        ),
        Convention(
            idcc = "",
            shortName = "Régime légal / autre convention",
            fullName = "Régime légal sans règle conventionnelle intégrée",
            rulesIntegrated = true
        )
    )

    fun findByIdcc(idcc: String?): Convention? {
        if (idcc == null) return null
        return conventions.firstOrNull { it.idcc == idcc }
    }
}
