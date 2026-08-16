package com.amaury.pointage

object ConventionCatalog {

    data class OvertimeTier(
        val fromHour: Double,
        val toHour: Double?,
        val multiplier: Double
    )

    data class Convention(
        val idcc: String,
        val shortName: String,
        val fullName: String,
        val rulesIntegrated: Boolean = false,
        val overtimeTiers: List<OvertimeTier> = legalOvertimeTiers(),
        val nightMultiplier: Double? = null,
        val sundayHolidayMultiplier: Double? = null,
        val advantages: List<String> = emptyList(),
        val cautions: List<String> = emptyList()
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

    private fun legalOvertimeTiers() = listOf(
        OvertimeTier(35.0, 43.0, 1.25),
        OvertimeTier(43.0, null, 1.50)
    )

    val conventions = listOf(
        Convention(
            idcc = "0292",
            shortName = "Plasturgie",
            fullName = "Transformation des matières plastiques",
            rulesIntegrated = true,
            overtimeTiers = legalOvertimeTiers(),
            nightMultiplier = 1.12,
            sundayHolidayMultiplier = 2.00,
            advantages = listOf(
                "Heures supplémentaires majorées à 25 % puis 50 %.",
                "Majoration conventionnelle de nuit de 12 % lorsque les conditions sont réunies.",
                "Travail exceptionnel le dimanche ou un jour férié : majoration conventionnelle de 100 %.",
                "Repos compensateur prévu pour les travailleurs de nuit."
            ),
            cautions = listOf(
                "Un accord d'entreprise peut prévoir des règles différentes ou plus favorables.",
                "Certaines majorations ne se cumulent pas entre elles.",
                "La prime d'ancienneté dépend de la situation du salarié et n'est pas encore calculée automatiquement."
            )
        ),
        Convention(
            idcc = "1979",
            shortName = "Hôtels, cafés, restaurants (HCR)",
            fullName = "Convention collective nationale des hôtels, cafés restaurants",
            rulesIntegrated = true,
            overtimeTiers = listOf(
                OvertimeTier(35.0, 39.0, 1.10),
                OvertimeTier(39.0, 42.0, 1.20),
                OvertimeTier(42.0, 43.0, 1.25),
                OvertimeTier(43.0, null, 1.50)
            ),
            advantages = listOf(
                "Barème conventionnel spécifique des heures supplémentaires dans les dispositifs HCR concernés.",
                "Repos compensateur possible en remplacement du paiement des majorations.",
                "Règles spécifiques pour le travail de nuit et l'aménagement du temps de travail."
            ),
            cautions = listOf(
                "La modulation ou l'annualisation peut modifier le déclenchement des heures supplémentaires.",
                "Le calcul reste indicatif si l'entreprise utilise un dispositif spécifique d'aménagement du temps de travail."
            )
        ),
        Convention(
            idcc = "3248",
            shortName = "Métallurgie",
            fullName = "Convention collective nationale de la métallurgie",
            advantages = listOf("Convention nationale unique avec de nombreuses garanties de branche."),
            cautions = listOf("Règles détaillées de paie non encore intégrées : régime légal appliqué provisoirement.")
        ),
        Convention(
            idcc = "0016",
            shortName = "Transports routiers",
            fullName = "Transports routiers et activités auxiliaires du transport",
            advantages = listOf("Règles spécifiques de branche pour le travail de nuit, le dimanche et certains frais."),
            cautions = listOf("Les règles varient selon l'emploi ; régime légal appliqué provisoirement tant que le profil précis n'est pas choisi.")
        ),
        Convention(idcc = "1486", shortName = "Syntec", fullName = "Bureaux d'études techniques, cabinets d'ingénieurs-conseils et sociétés de conseils", cautions = listOf("Règles détaillées non encore intégrées : régime légal appliqué provisoirement.")),
        Convention(idcc = "2216", shortName = "Commerce alimentaire", fullName = "Commerce de détail et de gros à prédominance alimentaire", cautions = listOf("Règles détaillées non encore intégrées : régime légal appliqué provisoirement.")),
        Convention(idcc = "1596", shortName = "Bâtiment — jusqu'à 10 salariés", fullName = "Ouvriers des entreprises du bâtiment occupant jusqu'à 10 salariés", cautions = listOf("Règles détaillées non encore intégrées : régime légal appliqué provisoirement.")),
        Convention(idcc = "1597", shortName = "Bâtiment — plus de 10 salariés", fullName = "Ouvriers des entreprises du bâtiment occupant plus de 10 salariés", cautions = listOf("Règles détaillées non encore intégrées : régime légal appliqué provisoirement.")),
        Convention(
            idcc = "",
            shortName = "Régime légal / autre convention",
            fullName = "Régime légal sans règle conventionnelle intégrée",
            rulesIntegrated = true,
            overtimeTiers = legalOvertimeTiers(),
            advantages = listOf("Calcul basé sur les majorations légales de référence."),
            cautions = listOf("Ne tient pas compte d'une convention collective ou d'un accord d'entreprise plus favorable.")
        )
    )

    fun findByIdcc(idcc: String?): Convention? {
        if (idcc == null) return null
        return conventions.firstOrNull { it.idcc == idcc }
    }
}
