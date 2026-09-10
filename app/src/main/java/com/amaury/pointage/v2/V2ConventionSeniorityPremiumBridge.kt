package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.SalaryCompanyStore
import com.amaury.pointage.v2.engine.ConventionClassificationV2
import com.amaury.pointage.v2.engine.ConventionMatterCoverageV2
import com.amaury.pointage.v2.engine.ConventionMinimumSalaryV2
import com.amaury.pointage.v2.engine.ConventionSeniorityPremiumV2
import com.amaury.pointage.v2.engine.PlasturgieSeniorityPremiumV2
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Passerelle entreprise -> prime d'ancienneté conventionnelle générique. */
object V2ConventionSeniorityPremiumBridge {
    data class Snapshot(
        val result: ConventionSeniorityPremiumV2.Result,
        val coverage: ConventionMatterCoverageV2.Snapshot
    )

    fun load(
        context: Context,
        companyId: String,
        idcc: String,
        referenceDate: LocalDate,
        actualMonthlyBaseGross: Double?,
        conventionalMinimumMonthlyGross: Double?
    ): Snapshot {
        val normalizedIdcc = ConventionMinimumSalaryV2.normalizeIdcc(idcc)
        val classification = ConventionClassificationStoreV2.load(context, companyId)
        val prefs = SalaryCompanyStore.prefs(context, companyId)
        val seniorityDate = runCatching {
            prefs.getString("convention_seniority_date", "").orEmpty().trim()
                .takeIf { it.isNotBlank() }
                ?.let { LocalDate.parse(it, DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.FRANCE)) }
        }.getOrNull()
        val supplement = prefs.getString("seniority_rtt_differential_monthly", "").orEmpty()
            .replace(',', '.')
            .toDoubleOrNull()
            ?.takeIf { it.isFinite() && it >= 0.0 }

        val builtIn = PlasturgieSeniorityPremiumV2.genericRules()
            .filter { ConventionMinimumSalaryV2.normalizeIdcc(it.idcc) == normalizedIdcc }
        val dynamicStored = V2ConventionSeniorityPremiumStore.readConfirmed(context)
        val dynamic = if (dynamicStored.reliable) {
            dynamicStored.rules.filter { ConventionMinimumSalaryV2.normalizeIdcc(it.idcc) == normalizedIdcc }
        } else {
            emptyList()
        }
        val storedCoverage = V2ConventionMatterCoverageStore.resolve(
            context = context,
            idcc = normalizedIdcc,
            matter = ConventionMatterCoverageV2.Matter.SENIORITY_PREMIUM,
            date = referenceDate,
            classification = classification
        )

        if (!dynamicStored.reliable) {
            val warnings = (
                dynamicStored.warnings +
                    storedCoverage.warnings +
                    "Prime d'ancienneté IDCC $normalizedIdcc : historique KALI local non fiable ; aucun montant, aucune absence de droit et aucun barème historique ne sont déduits automatiquement."
                ).distinct()
            return Snapshot(
                result = ConventionSeniorityPremiumV2.Result(
                    applicable = false,
                    reliable = false,
                    selectedRule = null,
                    stepYears = null,
                    rate = null,
                    monthlyAmount = null,
                    warnings = warnings
                ),
                coverage = ConventionMatterCoverageV2.Snapshot(
                    state = ConventionMatterCoverageV2.State.INCOMPLETE,
                    record = storedCoverage.record,
                    reliable = false,
                    warnings = warnings
                )
            )
        }

        val rules = builtIn + dynamic
        val matchingRules = rules.filter {
            it.structurallyValid() && it.activeOn(referenceDate) && classification.matches(it.classification)
        }
        val coverage = when {
            matchingRules.isNotEmpty() -> ConventionMatterCoverageV2.Snapshot(
                state = ConventionMatterCoverageV2.State.CONFIRMED_RULES,
                record = storedCoverage.record,
                reliable = true,
                warnings = emptyList()
            )
            builtInConfirmedNoRule(normalizedIdcc, classification) -> ConventionMatterCoverageV2.Snapshot(
                state = ConventionMatterCoverageV2.State.CONFIRMED_NO_RULE,
                record = storedCoverage.record,
                reliable = true,
                warnings = emptyList()
            )
            else -> storedCoverage
        }

        if (matchingRules.isEmpty() && coverage.state == ConventionMatterCoverageV2.State.CONFIRMED_NO_RULE) {
            return Snapshot(
                result = ConventionSeniorityPremiumV2.Result(
                    applicable = false,
                    reliable = true,
                    selectedRule = null,
                    stepYears = null,
                    rate = null,
                    monthlyAmount = 0.0,
                    warnings = emptyList()
                ),
                coverage = coverage
            )
        }

        val calculated = ConventionSeniorityPremiumV2.calculate(
            rules = rules,
            idcc = normalizedIdcc,
            classification = classification,
            referenceDate = referenceDate,
            confirmedSeniorityDate = seniorityDate,
            actualMonthlyBaseGross = actualMonthlyBaseGross,
            conventionalMinimumMonthlyGross = conventionalMinimumMonthlyGross,
            confirmedMonthlySupplement = supplement,
            companyApplicabilityConfirmed = false
        )
        val warnings = (calculated.warnings + coverage.warnings).distinct()
        return Snapshot(
            result = calculated.copy(
                reliable = calculated.reliable && coverage.reliable,
                warnings = warnings
            ),
            coverage = coverage
        )
    }

    internal fun builtInConfirmedNoRule(
        idcc: String,
        classification: ConventionClassificationV2
    ): Boolean {
        val normalizedIdcc = ConventionMinimumSalaryV2.normalizeIdcc(idcc)
        val coefficient = classification.coefficient
        return normalizedIdcc == PlasturgieSeniorityPremiumV2.IDCC && coefficient != null && coefficient in 900..940
    }
}
