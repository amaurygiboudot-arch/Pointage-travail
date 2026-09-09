package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.v2.engine.ConventionMealBasketEvaluatorV2
import com.amaury.pointage.v2.engine.PayrollLegalArbitratorV2
import com.amaury.pointage.v2.engine.VerifiedMealBasketPayrollV2
import com.amaury.pointage.v2.engine.WorkTimePolicyV2
import com.amaury.pointage.v2.model.WorkSessionV2
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/**
 * Pont mensuel Salaire : l'arbitrage juridique est refait pour la date réelle de chaque journée.
 * Cela respecte les avenants/règles qui commencent ou expirent en cours de mois.
 * Les faits locaux sont eux aussi résolus pour chaque session ; un fait inconnu reste null.
 */
object MealBasketSalaryBridgeV2 {
    data class Result(
        val count: Int,
        val totalAmount: Double?,
        val unitAmount: Double?,
        val reliable: Boolean,
        val warnings: List<String>,
        val selectedSources: Set<PayrollLegalArbitratorV2.Source>
    )

    fun calculate(
        context: Context,
        companyId: String,
        expectedIdcc: String,
        companyAddress: String,
        sessions: List<WorkSessionV2>,
        year: Int,
        monthZeroBased: Int,
        acceptedEmployerIds: Set<String>,
        facts: VerifiedMealBasketPayrollV2.FactDefaults = VerifiedMealBasketPayrollV2.FactDefaults(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): Result {
        require(monthZeroBased in 0..11) { "Mois invalide" }
        val targetMonth = YearMonth.of(year, monthZeroBased + 1)
        if (acceptedEmployerIds.isEmpty()) {
            return Result(0, 0.0, null, true, emptyList(), emptySet())
        }

        var malformedCompletedSession = false
        val relevant = sessions.filter { it.employerId in acceptedEmployerIds && it.realExitMs != null }
        val byDay = relevant.mapNotNull { session ->
            val entry = WorkTimePolicyV2.repairKnownCountedEntry(session.realArrivalMs, session.countedEntryMs)
                ?: session.countedEntryMs ?: session.realArrivalMs
            if (entry == null) {
                malformedCompletedSession = true
                null
            } else {
                val day = Instant.ofEpochMilli(entry).atZone(zoneId).toLocalDate()
                if (YearMonth.from(day) == targetMonth) day to session else null
            }
        }.groupBy({ it.first }, { it.second }).toSortedMap()

        if (malformedCompletedSession) {
            return Result(
                count = 0,
                totalAmount = null,
                unitAmount = null,
                reliable = false,
                warnings = listOf("Panier : session clôturée sans date d'entrée exploitable ; mois non certifiable."),
                selectedSources = emptySet()
            )
        }
        if (byDay.isEmpty()) {
            return Result(0, 0.0, null, true, emptyList(), emptySet())
        }

        val territoryCode = FrenchCompanyTerritoryV2.departmentCode(companyAddress)
        var count = 0
        var total = 0.0
        var reliable = true
        var unitStillUnique = true
        val units = linkedSetOf<Double>()
        val warnings = mutableListOf<String>()
        val sources = linkedSetOf<PayrollLegalArbitratorV2.Source>()

        byDay.forEach { (day, daySessions) ->
            val legal = MealBasketLegalProviderV2.load(
                context = context,
                companyId = companyId,
                expectedIdcc = expectedIdcc,
                referenceDate = day,
                territoryCode = territoryCode
            )
            warnings += legal.warnings
            if (!legal.reliable) {
                reliable = false
                return@forEach
            }

            val contexts = legal.resolution.selected.associate { selected ->
                val formula = selected.branchRule?.amountFormula ?: selected.companyRule?.amountFormula
                val minimumGuaranteed = if (formula is com.amaury.pointage.v2.engine.ConventionMealBasketV2.AmountFormula.MinimumGuaranteedMultiple) {
                    MealBasketLegalFactsV2.minimumGuaranteed(day, companyAddress)?.amount
                } else null
                selected.subject to ConventionMealBasketEvaluatorV2.AmountContext(
                    minimumGuaranteed = minimumGuaranteed,
                    externalAgreementAmount = null
                )
            }

            val factsBySessionId = daySessions.associate { session ->
                val local = V2MealBasketFactStore.resolve(
                    context = context,
                    companyId = companyId,
                    day = day,
                    sessionId = session.id
                )
                warnings += local.warnings
                session.id to MealBasketFactJournalV2.overlay(
                    fallback = facts,
                    resolution = local
                )
            }

            val daily = VerifiedMealBasketPayrollV2.calculate(
                sessions = daySessions,
                year = year,
                monthZeroBased = monthZeroBased,
                acceptedEmployerIds = acceptedEmployerIds,
                arbitration = legal.resolution,
                amountContextsBySubject = contexts,
                facts = facts,
                zoneId = zoneId,
                factsBySessionId = factsBySessionId
            )
            warnings += daily.warnings
            sources += daily.selectedSources
            count += daily.count
            if (!daily.reliable || daily.totalAmount == null) {
                reliable = false
            } else {
                total += daily.totalAmount
            }
            if (daily.count > 0) {
                if (daily.unitAmount == null) unitStillUnique = false else units += daily.unitAmount
            }
        }

        return Result(
            count = count,
            totalAmount = total.takeIf { reliable },
            unitAmount = units.singleOrNull().takeIf { reliable && unitStillUnique },
            reliable = reliable,
            warnings = warnings.distinct(),
            selectedSources = sources
        )
    }
}
