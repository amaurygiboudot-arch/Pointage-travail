package com.amaury.pointage.v2.engine

/**
 * Compatibilité historique de la prévoyance conventionnelle.
 *
 * Ce catalogue ne doit plus produire de cotisation : le calcul fiable passe désormais par les
 * règles KALI vérifiées puis l'arbitrage ACCO/KALI. Les anciens paramètres restent acceptés afin
 * de ne pas casser brutalement les appels historiques pendant leur migration.
 */
object ConventionProvidentCatalogV2 {
    data class Line(
        val id: String,
        val label: String,
        val baseAmount: Double,
        val employeeRate: Double,
        val employerRate: Double,
        val employeeAmount: Double,
        val employerAmount: Double,
        val source: String
    )

    data class Estimate(
        val lines: List<Line>,
        val employeeDeductions: Double,
        val employerContributions: Double,
        val warnings: List<String>
    )

    /**
     * Ancien régime statique Plasturgie conservé uniquement comme garde de migration.
     *
     * Dès qu'un profil aurait auparavant déclenché le barème codé en dur, le moteur refuse
     * désormais de calculer et exige les preuves KALI/ACCO actuelles. Un montant réel renseigné
     * par l'entreprise reste géré séparément par NetSalaryEngineV2 et conserve sa priorité.
     */
    fun estimate(
        gross: Double,
        year: Int,
        idcc: String?,
        protectionCategory: PlasturgieProtectionCategoryV2.Result,
        seniorityMonths: Int?,
        ceiling: SocialSecurityCeilingV2.Snapshot? = null
    ): Estimate {
        val g = gross.coerceAtLeast(0.0)
        val convention = idcc?.trim()

        if (convention != "292") return emptyEstimate()
        SocialSecurityCeilingV2.fullMonthly(year)
            ?: return Estimate(
                emptyList(),
                0.0,
                0.0,
                listOf("Prévoyance Plasturgie : règle non validée dans HoraTrack pour $year.")
            )
        if (!protectionCategory.confirmed || protectionCategory.category == PlasturgieProtectionCategoryV2.Category.TO_CONFIRM) {
            return Estimate(
                emptyList(),
                0.0,
                0.0,
                protectionCategory.warnings.ifEmpty {
                    listOf("Prévoyance Plasturgie : catégorie ANI 2.1/2.2 à confirmer avant calcul.")
                }
            )
        }
        if (protectionCategory.category == PlasturgieProtectionCategoryV2.Category.ARTICLE_2_1 ||
            protectionCategory.category == PlasturgieProtectionCategoryV2.Category.ARTICLE_2_2) {
            return emptyEstimate()
        }
        if (seniorityMonths == null) {
            return Estimate(
                emptyList(),
                0.0,
                0.0,
                listOf("Prévoyance Plasturgie hors ANI 2.1/2.2 : ancienneté à confirmer avant calcul.")
            )
        }
        if (seniorityMonths < 3 || g <= 0.0) return Estimate(
            emptyList(),
            0.0,
            0.0,
            protectionCategory.warnings + ceiling?.warnings.orEmpty()
        )

        return Estimate(
            lines = emptyList(),
            employeeDeductions = 0.0,
            employerContributions = 0.0,
            warnings = (
                ceiling?.warnings.orEmpty() +
                    protectionCategory.warnings +
                    "Prévoyance conventionnelle : ancien barème Plasturgie désactivé ; aucune cotisation n'est calculée sans source KALI/ACCO vérifiée et aucun ancien barème n'est réutilisé."
                ).distinct()
        )
    }

    private fun emptyEstimate() = Estimate(emptyList(), 0.0, 0.0, emptyList())
}
