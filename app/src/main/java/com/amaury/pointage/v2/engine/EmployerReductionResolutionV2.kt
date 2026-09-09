package com.amaury.pointage.v2.engine

import java.time.YearMonth

/**
 * Arbitre pur entre le total mensuel de réductions confirmé manuellement et la RGDU calculée.
 *
 * Règle de sécurité :
 * - un total mensuel confirmé (DSN/bulletin/calcul employeur validé) reste prioritaire ;
 * - une RGDU automatique ne devient le total appliqué que si l'absence d'autre réduction ou
 *   exonération à agréger est explicitement confirmée ;
 * - une incohérence dans les données manuelles bloque le repli automatique au lieu de la masquer.
 */
object EmployerReductionResolutionV2 {
    enum class Mode {
        MANUAL_CONFIRMED_TOTAL,
        AUTOMATIC_RGDU_ONLY,
        BLOCKED
    }

    data class Result(
        /** Total mensuel pouvant être soustrait du sous-total patronal connu. */
        val totalReductionAmount: Double?,
        /** RGDU calculée, conservée séparément pour diagnostic même si elle ne peut pas être le total. */
        val automaticRgduAmount: Double?,
        val mode: Mode,
        val source: String?,
        val reliable: Boolean,
        val warnings: List<String>
    )

    fun resolve(
        month: YearMonth,
        manualRecords: List<EmployerReductionAdjustmentV2.Record>,
        automaticRgdu: EmployerGeneralReduction2026V2.Result,
        context: EmployerGeneralReductionContextV2.Snapshot
    ): Result {
        val malformedManual = manualRecords.any {
            !it.totalReductionAmount.isFinite() || it.totalReductionAmount < 0.0 || it.source.isBlank()
        }
        val activeManual = manualRecords.filter { it.month == month }
        val manual = EmployerReductionAdjustmentV2.resolve(manualRecords, month)

        if (malformedManual || activeManual.size > 1) {
            return blocked(
                automaticRgduAmount = automaticRgdu.amount?.takeIf { automaticRgdu.reliable },
                warnings = manual.warnings
            )
        }

        if (activeManual.size == 1 && manual.reliable && manual.amount != null) {
            return Result(
                totalReductionAmount = manual.amount,
                automaticRgduAmount = automaticRgdu.amount?.takeIf { automaticRgdu.reliable },
                mode = Mode.MANUAL_CONFIRMED_TOTAL,
                source = manual.source,
                reliable = true,
                warnings = emptyList()
            )
        }

        val candidateAmount = automaticRgdu.amount?.takeIf { automaticRgdu.reliable }
        val blockers = buildList {
            if (!context.reliable) addAll(context.warnings)
            if (context.reliable && context.fullMonthPresent != true) {
                add("RGDU automatique : mois complet non confirmé ; le total des réductions reste bloqué.")
            }
            if (context.reliable && context.standardCommonLawCaseConfirmed != true) {
                add("RGDU automatique : cas de droit commun non confirmé ; le total des réductions reste bloqué.")
            }
            if (context.reliable && context.noOtherEmployerReductionConfirmed != true) {
                add("RGDU automatique : l'absence d'autre réduction/exonération patronale n'est pas confirmée ; la RGDU seule ne peut pas être assimilée au total mensuel.")
            }
            if (!automaticRgdu.reliable || automaticRgdu.amount == null) addAll(automaticRgdu.warnings)
        }.distinct()

        if (blockers.isNotEmpty()) {
            return blocked(candidateAmount, blockers)
        }

        val contextSource = context.source?.trim().orEmpty()
        if (contextSource.isBlank()) {
            return blocked(
                candidateAmount,
                listOf("RGDU automatique : source du contexte mensuel absente ; total des réductions bloqué.")
            )
        }

        return Result(
            totalReductionAmount = automaticRgdu.amount,
            automaticRgduAmount = automaticRgdu.amount,
            mode = Mode.AUTOMATIC_RGDU_ONLY,
            source = "RGDU 2026 automatique — contexte : $contextSource",
            reliable = true,
            warnings = emptyList()
        )
    }

    private fun blocked(automaticRgduAmount: Double?, warnings: List<String>) = Result(
        totalReductionAmount = null,
        automaticRgduAmount = automaticRgduAmount,
        mode = Mode.BLOCKED,
        source = null,
        reliable = false,
        warnings = warnings.ifEmpty {
            listOf("Réductions/exonérations patronales : total mensuel non démontré ; aucun ajustement automatique n'est appliqué.")
        }.distinct()
    )
}
