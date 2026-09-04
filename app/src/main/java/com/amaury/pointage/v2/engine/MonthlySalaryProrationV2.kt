package com.amaury.pointage.v2.engine

import java.time.LocalDate

/**
 * Décide si le brut mensualisé peut être affiché comme fiable pour un mois donné.
 *
 * HoraTrack ne prorate jamais un salaire mensualisé par simple nombre de jours calendaires.
 * Pour un mois d'entrée incomplet, le calcul exact exige le nombre d'heures de travail
 * prévu dans l'entreprise pour le mois et les heures réellement dues sur la période.
 */
object MonthlySalaryProrationV2 {
    enum class State {
        FULL_MONTH,
        ENTRY_DURING_MONTH,
        BEFORE_EMPLOYMENT,
        ENTRY_DATE_UNKNOWN
    }

    data class Assessment(
        val state: State,
        val exactMonthlyGrossAvailable: Boolean,
        val warning: String?
    )

    fun assess(entryDate: LocalDate?, referenceDate: LocalDate): Assessment {
        val monthStart = referenceDate.withDayOfMonth(1)
        val monthEnd = monthStart.withDayOfMonth(monthStart.lengthOfMonth())

        return when {
            entryDate == null -> Assessment(
                state = State.ENTRY_DATE_UNKNOWN,
                exactMonthlyGrossAvailable = false,
                warning = "Brut mensuel : date d'entrée inconnue, HoraTrack ne peut pas confirmer que la période couvre un mois complet."
            )
            entryDate.isAfter(monthEnd) -> Assessment(
                state = State.BEFORE_EMPLOYMENT,
                exactMonthlyGrossAvailable = false,
                warning = "Brut mensuel : le contrat n'avait pas encore commencé pendant cette période."
            )
            entryDate.isAfter(monthStart) -> Assessment(
                state = State.ENTRY_DURING_MONTH,
                exactMonthlyGrossAvailable = false,
                warning = "Mois d'embauche incomplet : le brut exact exige les heures de travail prévues dans l'entreprise pour ce mois. Aucun prorata calendaire n'est inventé."
            )
            else -> Assessment(
                state = State.FULL_MONTH,
                exactMonthlyGrossAvailable = true,
                warning = null
            )
        }
    }
}
