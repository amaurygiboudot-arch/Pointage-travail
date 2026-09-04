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
        /**
         * HoraTrack ne possède pas encore la catégorie ANI 2.1/2.2 : un simple
         * statut CADRE/NON_CADRE ne suffit pas à confirmer juridiquement le champ.
         */
        val eligibilityConfirmed: Boolean,
        val minimumGrossRate: Double?,
        val relayAfterEmployerMaintenance: Boolean,
        val earliestContinuousStopDay: Int?,
        val exactBenefitAmountAvailable: Boolean,
        val warnings: List<String>
    )

    fun assess(
        idcc: String?,
        seniorityMonths: Int?,
        professionalStatus: String?
    ): Result {
        val normalized = idcc.orEmpty().filter(Char::isDigit).trimStart('0')
        if (normalized != IDCC) {
            return Result(false, false, false, null, false, null, false, emptyList())
        }
        if (seniorityMonths == null) {
            return Result(
                applicableConvention = true,
                potentiallyCovered = false,
                eligibilityConfirmed = false,
                minimumGrossRate = MIN_GROSS_RATE,
                relayAfterEmployerMaintenance = true,
                earliestContinuousStopDay = null,
                exactBenefitAmountAvailable = false,
                warnings = listOf("Prévoyance Plasturgie : ancienneté manquante, éligibilité impossible à confirmer.")
            )
        }
        if (seniorityMonths < MIN_SENIORITY_MONTHS) {
            return Result(
                applicableConvention = true,
                potentiallyCovered = false,
                eligibilityConfirmed = true,
                minimumGrossRate = null,
                relayAfterEmployerMaintenance = false,
                earliestContinuousStopDay = null,
                exactBenefitAmountAvailable = false,
                warnings = listOf("Prévoyance Plasturgie de branche : ancienneté inférieure à 3 mois, garantie incapacité minimale non ouverte par cet accord.")
            )
        }

        val status = professionalStatus.orEmpty().trim().uppercase()
        val categoryWarning = when (status) {
            "CADRE" -> "Prévoyance Plasturgie : le statut cadre déclaré ne permet pas d'appliquer automatiquement ce régime de branche, réservé aux salariés ne relevant pas des articles 2.1/2.2 de l'ANI prévoyance cadres."
            "NON_CADRE" -> "Prévoyance Plasturgie : statut non-cadre connu, mais l'exclusion des articles 2.1/2.2 de l'ANI prévoyance cadres reste à confirmer avant de conclure à l'éligibilité."
            else -> "Prévoyance Plasturgie : catégorie professionnelle/ANI à confirmer avant de conclure à l'éligibilité."
        }
        val potential = status != "CADRE"
        val relayDay = if (seniorityMonths < 12) UNDER_ONE_YEAR_RELAY_DAY else null

        return Result(
            applicableConvention = true,
            potentiallyCovered = potential,
            eligibilityConfirmed = false,
            minimumGrossRate = if (potential) MIN_GROSS_RATE else null,
            relayAfterEmployerMaintenance = potential,
            earliestContinuousStopDay = if (potential) relayDay else null,
            exactBenefitAmountAvailable = false,
            warnings = buildList {
                add(categoryWarning)
                if (potential) {
                    add("Garantie incapacité Plasturgie : au minimum 60 % du salaire brut, sous déduction des prestations de Sécurité sociale, en relais du maintien employeur.")
                    if (relayDay != null) add("Entre 3 mois et moins d'1 an d'ancienneté : relais à partir du 91e jour d'arrêt continu.")
                    else add("À partir d'1 an d'ancienneté : le relais intervient après les obligations de maintien employeur ; aucun chevauchement n'est supposé automatiquement.")
                    add("Montant exact de prévoyance non calculé sans salaire de référence des 12 mois et données de l'organisme assureur.")
                }
            }
        )
    }
}
