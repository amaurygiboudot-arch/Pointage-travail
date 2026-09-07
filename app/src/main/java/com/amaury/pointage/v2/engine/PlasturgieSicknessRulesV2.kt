package com.amaury.pointage.v2.engine

import java.time.LocalDate

/** Règles maladie Plasturgie officiellement vérifiées, projetées dans le moteur générique. */
object PlasturgieSicknessRulesV2 {
    const val IDCC = "292"

    private const val NON_CADRE_SOURCE =
        "Légifrance — IDCC 292, avenant du 15 mai 1991, article 13, en vigueur étendu"
    private const val CADRE_SOURCE =
        "Légifrance — IDCC 292, accord cadres du 17 décembre 1992, article 5, en vigueur étendu"

    fun rules(): List<ConventionSicknessMaintenanceV2.Rule> = listOf(
        ConventionSicknessMaintenanceV2.Rule(
            idcc = IDCC,
            ruleId = "builtin_292_sickness_non_cadre",
            effectiveFrom = LocalDate.of(1991, 6, 1),
            professionalStatus = "NON_CADRE",
            minimumSeniorityYears = 1,
            tiers = listOf(
                ConventionSicknessMaintenanceV2.SeniorityTier(
                    minimumYears = 1,
                    bands = listOf(
                        ConventionSicknessMaintenanceV2.Band(45, 1.00, "Maintien conventionnel à 100 % du net de référence"),
                        ConventionSicknessMaintenanceV2.Band(60, 0.75, "Maintien conventionnel à 75 % du net de référence")
                    )
                ),
                ConventionSicknessMaintenanceV2.SeniorityTier(
                    minimumYears = 5,
                    bands = listOf(
                        ConventionSicknessMaintenanceV2.Band(60, 1.00, "Maintien conventionnel à 100 % du net de référence"),
                        ConventionSicknessMaintenanceV2.Band(75, 0.75, "Maintien conventionnel à 75 % du net de référence")
                    )
                )
            ),
            waitingPolicy = ConventionSicknessMaintenanceV2.WaitingPolicy.FIRST_STOP_FREE_THEN_THREE_DAYS_SHORT_FIRST_CARRY,
            socialSecurityCoverageRequiredAfterDays = 3,
            source = NON_CADRE_SOURCE,
            extensionStatus = ConventionMinimumSalaryV2.ExtensionStatus.EXTENDED,
            extensionEffectiveFrom = LocalDate.of(1991, 8, 7)
        ),
        ConventionSicknessMaintenanceV2.Rule(
            idcc = IDCC,
            ruleId = "builtin_292_sickness_cadre",
            effectiveFrom = LocalDate.of(1992, 12, 17),
            professionalStatus = "CADRE",
            minimumSeniorityYears = 1,
            tiers = listOf(
                ConventionSicknessMaintenanceV2.SeniorityTier(
                    minimumYears = 1,
                    bands = listOf(
                        ConventionSicknessMaintenanceV2.Band(45, 1.00, "Maintien cadre à 100 % du net de référence"),
                        ConventionSicknessMaintenanceV2.Band(45, 0.50, "Maintien cadre à 50 % du net de référence")
                    )
                ),
                ConventionSicknessMaintenanceV2.SeniorityTier(
                    minimumYears = 2,
                    bands = listOf(
                        ConventionSicknessMaintenanceV2.Band(60, 1.00, "Maintien cadre à 100 % du net de référence"),
                        ConventionSicknessMaintenanceV2.Band(60, 0.50, "Maintien cadre à 50 % du net de référence")
                    )
                ),
                ConventionSicknessMaintenanceV2.SeniorityTier(
                    minimumYears = 3,
                    bands = listOf(
                        ConventionSicknessMaintenanceV2.Band(90, 1.00, "Maintien cadre à 100 % du net de référence"),
                        ConventionSicknessMaintenanceV2.Band(90, 0.50, "Maintien cadre à 50 % du net de référence")
                    )
                ),
                ConventionSicknessMaintenanceV2.SeniorityTier(
                    minimumYears = 5,
                    bands = listOf(
                        ConventionSicknessMaintenanceV2.Band(120, 1.00, "Maintien cadre à 100 % du net de référence"),
                        ConventionSicknessMaintenanceV2.Band(120, 0.50, "Maintien cadre à 50 % du net de référence")
                    )
                ),
                ConventionSicknessMaintenanceV2.SeniorityTier(
                    minimumYears = 10,
                    bands = listOf(
                        ConventionSicknessMaintenanceV2.Band(150, 1.00, "Maintien cadre à 100 % du net de référence"),
                        ConventionSicknessMaintenanceV2.Band(150, 0.50, "Maintien cadre à 50 % du net de référence")
                    )
                )
            ),
            waitingPolicy = ConventionSicknessMaintenanceV2.WaitingPolicy.NONE,
            socialSecurityCoverageRequiredAfterDays = 2,
            source = CADRE_SOURCE,
            extensionStatus = ConventionMinimumSalaryV2.ExtensionStatus.EXTENDED,
            extensionEffectiveFrom = LocalDate.of(1993, 4, 1)
        )
    )
}
