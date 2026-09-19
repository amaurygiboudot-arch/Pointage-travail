package com.amaury.pointage.v2.engine

import java.time.YearMonth
import kotlin.math.min

/**
 * Contexte légal du coefficient maximal RGDU 2026.
 *
 * D.241-7 CSS rattache Tδ au taux de contribution logement réellement dû
 * (L.813-5 1° ou 2° CCH), pas au seul effectif brut de l'entreprise. Lorsque
 * la somme des taux effectivement à la charge de l'employeur est inférieure
 * au maximum standard, Tδ est réduit pour que le maximum RGDU ne dépasse pas
 * cette somme.
 *
 * Une règle doit donc être datée, sourcée et confirmer à la fois le régime
 * logement applicable et la somme des taux éligibles réellement supportés.
 */
object EmployerGeneralReductionRateContext2026V2 {
    private const val T_MIN = 0.0200
    private const val T_DELTA_L813_5_1 = 0.3781
    private const val T_DELTA_L813_5_2 = 0.3821

    enum class HousingContributionRegime {
        /** CCH L.813-5 1° : contribution logement au taux de 0,1 % sur assiette plafonnée. */
        L813_5_1,

        /** CCH L.813-5 2° : contribution logement au taux de 0,5 % sur la totalité de l'assiette. */
        L813_5_2
    }

    data class Record(
        val id: String,
        val housingContributionRegime: HousingContributionRegime,
        /**
         * Somme décimale des taux de cotisations/contributions éligibles RGDU
         * effectivement à la charge de l'employeur pour le salarié concerné.
         * Exemple : 0,4021 = 40,21 %.
         */
        val eligibleEmployerRateSum: Double,
        val effectiveFrom: YearMonth,
        val effectiveTo: YearMonth? = null,
        val source: String
    )

    data class Snapshot(
        val tDelta: Double?,
        val maximumCoefficient: Double?,
        val source: String?,
        val reliable: Boolean,
        val warnings: List<String>
    )

    fun resolve(records: List<Record>, period: YearMonth): Snapshot {
        if (period.year != 2026) {
            return blocked("RGDU : contexte de coefficient 2026 indisponible pour ${period.year}.")
        }

        val malformed = records.filter { record ->
            record.id.isBlank() ||
                record.source.isBlank() ||
                record.effectiveTo?.let { it < record.effectiveFrom } == true ||
                !validEligibleRateSum(record.eligibleEmployerRateSum)
        }
        if (malformed.isNotEmpty()) {
            return blocked("RGDU 2026 : règle de coefficient incomplète ou incohérente ; aucun Tδ n'est supposé.")
        }

        val active = records.filter { record ->
            period >= record.effectiveFrom && (record.effectiveTo == null || period <= record.effectiveTo)
        }
        if (active.isEmpty()) {
            return blocked("RGDU 2026 : régime de contribution logement et somme des taux éligibles à confirmer pour ${period.monthValue.toString().padStart(2, '0')}/${period.year}.")
        }
        if (active.size > 1) {
            return blocked("RGDU 2026 : plusieurs règles de coefficient se chevauchent ; calcul automatique bloqué.")
        }

        val selected = active.single()
        val standardDelta = when (selected.housingContributionRegime) {
            HousingContributionRegime.L813_5_1 -> T_DELTA_L813_5_1
            HousingContributionRegime.L813_5_2 -> T_DELTA_L813_5_2
        }
        val standardMaximum = T_MIN + standardDelta
        val effectiveMaximum = min(standardMaximum, selected.eligibleEmployerRateSum)
        val effectiveDelta = effectiveMaximum - T_MIN

        if (!effectiveDelta.isFinite() || effectiveDelta < 0.0) {
            return blocked("RGDU 2026 : somme des taux éligibles incompatible avec Tmin ; calcul automatique bloqué.")
        }

        return Snapshot(
            tDelta = effectiveDelta,
            maximumCoefficient = effectiveMaximum,
            source = selected.source.trim(),
            reliable = true,
            warnings = emptyList()
        )
    }

    fun isUsable(snapshot: Snapshot?): Boolean {
        if (snapshot == null || !snapshot.reliable || snapshot.source.isNullOrBlank()) return false
        val delta = snapshot.tDelta ?: return false
        val maximum = snapshot.maximumCoefficient ?: return false
        if (!delta.isFinite() || delta < 0.0 || !maximum.isFinite() || maximum < T_MIN || maximum > 1.0) return false
        return kotlin.math.abs((T_MIN + delta) - maximum) < 0.0000001
    }

    private fun validEligibleRateSum(value: Double): Boolean =
        value.isFinite() && value >= T_MIN && value <= 1.0

    private fun blocked(message: String) = Snapshot(
        tDelta = null,
        maximumCoefficient = null,
        source = null,
        reliable = false,
        warnings = listOf(message)
    )
}
