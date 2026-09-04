package com.amaury.pointage.v2.engine

/**
 * Garantie minimale incapacité temporaire de l'accord prévoyance Plasturgie.
 *
 * Cette garantie est un RELAIS des obligations de maintien de salaire de
 * l'employeur. Elle ne doit donc jamais être soustraite du complément employeur
 * comme si elle le chevauchait automatiquement.
 */
object PlasturgieProvidentIncapacityV2 {
    const val IDCC = "292"
    const val MIN_GROSS_RATE = 0.60
    const val MIN_SENIORITY_MONTHS = 3
    const val UNDER_ONE_YEAR_RELAY_DAY = 91

    data class Result(
        val applicableConvention: Boolean,
        val potentiallyCovered: Boolean,
        val eligibilityConfirmed: Boolean,
        val protectionCategory: PlasturgieProtectionCategoryV2.Category,
        val minimumGrossRate: Double?,
        val relayAfterEmployerMaintenance: Boolean,
        val earliestContinuousStopDay: Int?,
        val relayReached: Boolean?,
        val exactBenefitAmountAvailable: Boolean,
        val warnings: List<String>
    )

    fun assess(
        idcc: String?,
        seniorityMonths: Int?,
        protectionCategory: PlasturgieProtectionCategoryV2.Result,
        maintenance: PlasturgieSicknessMaintenanceV2.Result?,
        absenceCalendarDays: Int
    ): Result {
        val normalized = idcc.orEmpty().filter(Char::isDigit).trimStart('0')
        if (normalized != IDCC) {
            return Result(false, false, false, PlasturgieProtectionCategoryV2.Category.NOT_APPLICABLE, null, false, null, null, false, emptyList())
        }
        if (seniorityMonths == null) {
            return Result(
                applicableConvention = true,
                potentiallyCovered = false,
                eligibilityConfirmed = false,
                protectionCategory = protectionCategory.category,
                minimumGrossRate = MIN_GROSS_RATE,
                relayAfterEmployerMaintenance = true,
                earliestContinuousStopDay = null,
                relayReached = null,
                exactBenefitAmountAvailable = false,
                warnings = listOf("Prévoyance Plasturgie : ancienneté manquante, éligibilité impossible à confirmer.")
            )
        }
        if (seniorityMonths < MIN_SENIORITY_MONTHS) {
            return Result(
                applicableConvention = true,
                potentiallyCovered = false,
                eligibilityConfirmed = true,
                protectionCategory = protectionCategory.category,
                minimumGrossRate = null,
                relayAfterEmployerMaintenance = false,
                earliestContinuousStopDay = null,
                relayReached = false,
                exactBenefitAmountAvailable = false,
                warnings = listOf("Prévoyance Plasturgie de branche : ancienneté inférieure à 3 mois, garantie incapacité minimale non ouverte par cet accord.")
            )
        }
        if (!protectionCategory.confirmed || protectionCategory.category == PlasturgieProtectionCategoryV2.Category.TO_CONFIRM) {
            return Result(
                applicableConvention = true,
                potentiallyCovered = false,
                eligibilityConfirmed = false,
                protectionCategory = protectionCategory.category,
                minimumGrossRate = MIN_GROSS_RATE,
                relayAfterEmployerMaintenance = true,
                earliestContinuousStopDay = null,
                relayReached = null,
                exactBenefitAmountAvailable = false,
                warnings = protectionCategory.warnings.ifEmpty {
                    listOf("Prévoyance Plasturgie : catégorie ANI 2.1/2.2 impossible à confirmer.")
                }
            )
        }
        if (protectionCategory.category == PlasturgieProtectionCategoryV2.Category.ARTICLE_2_1 ||
            protectionCategory.category == PlasturgieProtectionCategoryV2.Category.ARTICLE_2_2) {
            return Result(
                applicableConvention = true,
                potentiallyCovered = false,
                eligibilityConfirmed = true,
                protectionCategory = protectionCategory.category,
                minimumGrossRate = null,
                relayAfterEmployerMaintenance = false,
                earliestContinuousStopDay = null,
                relayReached = false,
                exactBenefitAmountAvailable = false,
                warnings = listOf("Prévoyance Plasturgie de branche hors ANI 2.1/2.2 non applicable à cette catégorie ; contrôler le régime cadres/assimilés de l'entreprise.")
            )
        }

        val relayDay = if (seniorityMonths < 12) {
            UNDER_ONE_YEAR_RELAY_DAY
        } else {
            val annualLimit = maintenance?.annualLimitDays
            val consumed = maintenance?.alreadyConsumedIndemnifiedDays
            val waiting = maintenance?.employerWaitingDays
            if (maintenance?.applicable == true && maintenance.eligibilityConfirmed &&
                annualLimit != null && consumed != null && waiting != null) {
                val remainingMaintenance = (annualLimit - consumed).coerceAtLeast(0)
                waiting + remainingMaintenance + 1
            } else null
        }
        val reached = relayDay?.let { absenceCalendarDays >= it }

        return Result(
            applicableConvention = true,
            potentiallyCovered = true,
            eligibilityConfirmed = true,
            protectionCategory = protectionCategory.category,
            minimumGrossRate = MIN_GROSS_RATE,
            relayAfterEmployerMaintenance = true,
            earliestContinuousStopDay = relayDay,
            relayReached = reached,
            exactBenefitAmountAvailable = false,
            warnings = buildList {
                addAll(protectionCategory.warnings)
                add("Garantie incapacité Plasturgie : au minimum 60 % du salaire brut, sous déduction des prestations de Sécurité sociale, en relais du maintien employeur.")
                if (seniorityMonths < 12) {
                    add("Entre 3 mois et moins d'1 an d'ancienneté : relais à partir du 91e jour d'arrêt continu.")
                } else if (relayDay != null) {
                    add("Début du relais calculé après le maintien employeur restant : ${relayDay}e jour d'arrêt continu selon les arrêts enregistrés dans HoraTrack.")
                } else {
                    add("Début exact du relais : maintien employeur restant impossible à déterminer avec les données actuelles.")
                }
                if (protectionCategory.category == PlasturgieProtectionCategoryV2.Category.EXTENSION_ELIGIBLE) {
                    add("Coefficient 800 à 820 : extension au régime cadres possible ; un régime d'entreprise plus favorable peut remplacer le minimum de branche à contrôler.")
                }
                add("Montant exact de prévoyance non calculé sans décompte assureur ou données exprimées sur une même base de prestation.")
            }
        )
    }
}
