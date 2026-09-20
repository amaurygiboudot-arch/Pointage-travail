package com.amaury.pointage.v2.engine

import java.time.YearMonth
import kotlin.math.min

/**
 * Cotisations patronales liées à l'effectif en 2026.
 *
 * La tranche d'effectif ne suffit pas à elle seule à déterminer le FNAL pour tous les employeurs :
 * certains employeurs agricoles/cooperatifs relèvent du taux plafonné de 0,10 % même avec un
 * effectif d'au moins 50 salariés. L'applicabilité de la contribution formation peut elle aussi
 * dépendre de la situation du salarié (ex. exonération confirmée).
 *
 * HoraTrack exige donc ces faits explicitement au lieu de les déduire silencieusement.
 */
object EmployerWorkforceContributionsV2 {
    enum class Band { UNDER_11, FROM_11_TO_49, AT_LEAST_50 }

    enum class FnalTreatment {
        /** 0,10 % dans la limite du plafond de sécurité sociale. */
        CAPPED_0_1_PERCENT,
        /** 0,50 % sur la totalité de l'assiette. */
        UNCAPPED_0_5_PERCENT
    }

    enum class TrainingTreatment {
        /** Contribution légale calculée selon la tranche <11 / >=11. */
        STANDARD,
        /** Exonération/applicabilité nulle confirmée pour la rémunération considérée. */
        EXEMPT_CONFIRMED
    }

    data class Record(
        val id: String,
        val band: Band,
        val effectiveFrom: YearMonth,
        val effectiveTo: YearMonth? = null,
        val source: String,
        /** Régime FNAL confirmé ; null reste inconnu pour une migration fail-closed. */
        val fnalTreatment: FnalTreatment? = null,
        /** Applicabilité formation confirmée ; null reste inconnu pour une migration fail-closed. */
        val trainingTreatment: TrainingTreatment? = null
    )

    data class Snapshot(
        val band: Band?,
        val source: String?,
        val reliable: Boolean,
        val warnings: List<String>,
        val fnalTreatment: FnalTreatment? = null,
        val trainingTreatment: TrainingTreatment? = null
    )

    data class Result(
        val fnalAmount: Double?,
        val trainingAmount: Double?,
        val totalEmployerAmount: Double?,
        val complete: Boolean,
        val warnings: List<String>
    )

    fun resolve(records: List<Record>, period: YearMonth): Snapshot {
        val malformed = records.filter {
            it.source.isBlank() || it.effectiveTo?.let { end -> end < it.effectiveFrom } == true
        }
        if (malformed.isNotEmpty()) {
            return Snapshot(
                band = null,
                source = null,
                reliable = false,
                warnings = listOf("Effectif employeur : une règle enregistrée est incomplète ou incohérente ; FNAL/formation non calculés."),
                fnalTreatment = null,
                trainingTreatment = null
            )
        }
        val active = records.filter {
            period >= it.effectiveFrom && (it.effectiveTo == null || period <= it.effectiveTo)
        }
        if (active.isEmpty()) {
            return Snapshot(
                band = null,
                source = null,
                reliable = false,
                warnings = listOf("Effectif employeur : tranche <11 / 11–49 / >=50, régime FNAL et applicabilité formation à confirmer pour ${period.monthValue.toString().padStart(2, '0')}/${period.year} ; cotisations incomplètes."),
                fnalTreatment = null,
                trainingTreatment = null
            )
        }
        if (active.size > 1) {
            return Snapshot(
                band = null,
                source = null,
                reliable = false,
                warnings = listOf("Effectif employeur : plusieurs règles se chevauchent sur la période ; FNAL/formation bloqués."),
                fnalTreatment = null,
                trainingTreatment = null
            )
        }
        val selected = active.single()
        val warnings = buildList {
            if (selected.fnalTreatment == null) {
                add("FNAL : régime 0,10 % plafonné / 0,50 % déplafonné à confirmer ; la tranche d'effectif seule ne suffit pas pour tous les employeurs.")
            }
            if (selected.trainingTreatment == null) {
                add("Formation professionnelle : applicabilité/exonération à confirmer pour la rémunération considérée.")
            }
        }
        return Snapshot(
            band = selected.band,
            source = selected.source,
            reliable = selected.fnalTreatment != null && selected.trainingTreatment != null,
            warnings = warnings,
            fnalTreatment = selected.fnalTreatment,
            trainingTreatment = selected.trainingTreatment
        )
    }

    fun calculate(
        grossSocial: Double,
        applicableMonthlyCeiling: Double?,
        year: Int,
        band: Band?,
        fnalTreatment: FnalTreatment? = null,
        trainingTreatment: TrainingTreatment? = null
    ): Result {
        if (year != 2026) {
            return Result(null, null, null, false, listOf("FNAL/formation : barème non intégré pour $year."))
        }
        if (!grossSocial.isFinite() || grossSocial < 0.0) {
            return Result(null, null, null, false, listOf("FNAL/formation : assiette brute sociale invalide ; aucun montant patronal n'est calculé."))
        }

        val warnings = mutableListOf<String>()

        val fnal = when (fnalTreatment) {
            null -> {
                warnings += "FNAL : régime 0,10 % plafonné / 0,50 % déplafonné à confirmer ; aucun taux n'est déduit du seul effectif."
                null
            }
            FnalTreatment.CAPPED_0_1_PERCENT -> {
                val ceiling = applicableMonthlyCeiling
                if (ceiling == null || !ceiling.isFinite() || ceiling < 0.0) {
                    warnings += "FNAL : plafond social applicable indisponible pour le régime plafonné."
                    null
                } else {
                    min(grossSocial, ceiling) * 0.0010
                }
            }
            FnalTreatment.UNCAPPED_0_5_PERCENT -> grossSocial * 0.0050
        }

        val training = when (trainingTreatment) {
            null -> {
                warnings += "Formation professionnelle : applicabilité/exonération à confirmer pour la rémunération considérée."
                null
            }
            TrainingTreatment.EXEMPT_CONFIRMED -> 0.0
            TrainingTreatment.STANDARD -> {
                when (band) {
                    null -> {
                        warnings += "Formation professionnelle : tranche d'effectif <11 / >=11 à confirmer."
                        null
                    }
                    Band.UNDER_11 -> grossSocial * 0.0055
                    Band.FROM_11_TO_49, Band.AT_LEAST_50 -> grossSocial * 0.0100
                }
            }
        }

        val complete = fnal != null && training != null
        return Result(
            fnalAmount = fnal,
            trainingAmount = training,
            totalEmployerAmount = if (complete) fnal!! + training!! else null,
            complete = complete,
            warnings = warnings.distinct()
        )
    }
}
