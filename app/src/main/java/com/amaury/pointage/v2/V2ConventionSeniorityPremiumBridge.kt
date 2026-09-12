package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.SalaryCompanyStore
import com.amaury.pointage.v2.engine.ConventionClassificationV2
import com.amaury.pointage.v2.engine.ConventionMatterCoverageV2
import com.amaury.pointage.v2.engine.ConventionMinimumSalaryV2
import com.amaury.pointage.v2.engine.ConventionSeniorityPremiumV2
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Passerelle entreprise -> prime d'ancienneté conventionnelle générique. */
object V2ConventionSeniorityPremiumBridge {
    data class Snapshot(
        val result: ConventionSeniorityPremiumV2.Result,
        val coverage: ConventionMatterCoverageV2.Snapshot
    )

    internal data class RuntimeSourceSelection(
        val rules: List<ConventionSeniorityPremiumV2.Rule>,
        val confirmedNoRule: Boolean,
        val reliable: Boolean,
        val warnings: List<String>
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
        val legalProfile = ConventionLegalProfileV2.load(context, companyId)
        val classification = legalProfile?.classification ?: ConventionClassificationStoreV2.load(context, companyId)
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

        val dynamicStored = V2ConventionSeniorityPremiumStore.readConfirmed(context)
        val storedCoverage = V2ConventionMatterCoverageStore.resolve(
            context = context,
            idcc = normalizedIdcc,
            matter = ConventionMatterCoverageV2.Matter.SENIORITY_PREMIUM,
            date = referenceDate,
            classification = classification,
            professionalStatus = legalProfile?.professionalStatus
        )
        val runtimeSource = selectOfficialRuntimeSource(
            stored = dynamicStored,
            coverage = storedCoverage,
            idcc = normalizedIdcc,
            classification = classification,
            referenceDate = referenceDate
        )

        if (!runtimeSource.reliable) {
            val warnings = (
                runtimeSource.warnings +
                    dynamicStored.warnings +
                    storedCoverage.warnings +
                    "Prime d'ancienneté IDCC $normalizedIdcc : aucune règle monétaire n'est appliquée sans preuve KALI officielle confirmée pour ce profil et cette période."
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
                coverage = storedCoverage.copy(
                    reliable = false,
                    warnings = warnings
                )
            )
        }

        if (runtimeSource.confirmedNoRule) {
            return Snapshot(
                result = ConventionSeniorityPremiumV2.Result(
                    applicable = false,
                    reliable = true,
                    selectedRule = null,
                    stepYears = null,
                    rate = null,
                    monthlyAmount = 0.0,
                    warnings = runtimeSource.warnings
                ),
                coverage = storedCoverage
            )
        }

        val calculated = ConventionSeniorityPremiumV2.calculate(
            rules = runtimeSource.rules,
            idcc = normalizedIdcc,
            classification = classification,
            referenceDate = referenceDate,
            confirmedSeniorityDate = seniorityDate,
            actualMonthlyBaseGross = actualMonthlyBaseGross,
            conventionalMinimumMonthlyGross = conventionalMinimumMonthlyGross,
            confirmedMonthlySupplement = supplement,
            companyApplicabilityConfirmed = false
        )
        val warnings = (calculated.warnings + runtimeSource.warnings + storedCoverage.warnings).distinct()
        return Snapshot(
            result = calculated.copy(
                reliable = calculated.reliable && storedCoverage.reliable,
                warnings = warnings
            ),
            coverage = storedCoverage
        )
    }

    internal fun selectOfficialRuntimeSource(
        stored: V2ConventionSeniorityPremiumStore.ReadResult,
        coverage: ConventionMatterCoverageV2.Snapshot,
        idcc: String,
        classification: ConventionClassificationV2,
        referenceDate: LocalDate
    ): RuntimeSourceSelection {
        val normalizedIdcc = ConventionMinimumSalaryV2.normalizeIdcc(idcc)
        if (!stored.reliable) {
            return RuntimeSourceSelection(
                rules = emptyList(),
                confirmedNoRule = false,
                reliable = false,
                warnings = stored.warnings
            )
        }

        val record = coverage.record
        val kaliConfirmed = coverage.reliable &&
            record != null &&
            ConventionMatterCoverageV2.Authority.KALI in record.authorities
        if (!kaliConfirmed) {
            return RuntimeSourceSelection(
                rules = emptyList(),
                confirmedNoRule = false,
                reliable = false,
                warnings = listOf(
                    "Prime d'ancienneté IDCC $normalizedIdcc : couverture KALI officielle absente ou non confirmée pour ce profil et cette période."
                )
            )
        }

        if (coverage.state == ConventionMatterCoverageV2.State.CONFIRMED_NO_RULE) {
            return RuntimeSourceSelection(
                rules = emptyList(),
                confirmedNoRule = true,
                reliable = true,
                warnings = emptyList()
            )
        }
        if (coverage.state != ConventionMatterCoverageV2.State.CONFIRMED_RULES) {
            return RuntimeSourceSelection(
                rules = emptyList(),
                confirmedNoRule = false,
                reliable = false,
                warnings = listOf(
                    "Prime d'ancienneté IDCC $normalizedIdcc : l'audit KALI n'a pas confirmé de règle exploitable."
                )
            )
        }

        val matchingRules = stored.rules.filter {
            it.structurallyValid() &&
                ConventionMinimumSalaryV2.normalizeIdcc(it.idcc) == normalizedIdcc &&
                it.activeOn(referenceDate) &&
                classification.matches(it.classification)
        }
        if (matchingRules.isEmpty()) {
            return RuntimeSourceSelection(
                rules = emptyList(),
                confirmedNoRule = false,
                reliable = false,
                warnings = listOf(
                    "Prime d'ancienneté IDCC $normalizedIdcc : la couverture KALI annonce des règles confirmées mais aucune règle officielle stockée ne correspond au profil et à la période."
                )
            )
        }

        return RuntimeSourceSelection(
            rules = matchingRules,
            confirmedNoRule = false,
            reliable = true,
            warnings = emptyList()
        )
    }
}
