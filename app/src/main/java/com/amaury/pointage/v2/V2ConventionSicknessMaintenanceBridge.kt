package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.SalaryCompanyStore
import com.amaury.pointage.v2.engine.AbsencePayrollImpactV2
import com.amaury.pointage.v2.engine.ConventionMatterCoverageV2
import com.amaury.pointage.v2.engine.ConventionMinimumSalaryV2
import com.amaury.pointage.v2.engine.ConventionSicknessMaintenanceV2
import com.amaury.pointage.v2.engine.PlasturgieSicknessRulesV2
import com.amaury.pointage.v2.model.AbsenceV2
import java.time.Instant
import java.time.ZoneId

/** Entreprise -> règle de maintien maladie conventionnelle générique. */
object V2ConventionSicknessMaintenanceBridge {
    data class Snapshot(
        val result: ConventionSicknessMaintenanceV2.Result,
        val coverage: ConventionMatterCoverageV2.Snapshot
    )

    fun load(context: Context, companyId: String, absence: AbsenceV2): Snapshot? {
        if (absence.type != AbsencePayrollImpactV2.TYPE_SICKNESS) return null
        val profile = ConventionLegalProfileV2.load(context, companyId)
            ?: return unresolvedProfileSnapshot(
                "Maintien maladie : entreprise ou profil juridique local non fiable ; aucun barème ni aucune absence de droit ne sont déduits automatiquement."
            )
        val normalizedIdcc = ConventionMinimumSalaryV2.normalizeIdcc(profile.idcc)
        if (normalizedIdcc.isBlank()) {
            return unresolvedProfileSnapshot(
                "Maintien maladie : IDCC local non confirmé ; aucun barème ni aucune absence de droit ne sont déduits automatiquement."
            )
        }
        val classification = profile.classification
        val professionalStatus = profile.professionalStatus
        val entryDate = profile.entryDate
        val referenceDate = Instant.ofEpochMilli(absence.startMs).atZone(ZoneId.systemDefault()).toLocalDate()

        val builtIn = PlasturgieSicknessRulesV2.rules().filter {
            ConventionMinimumSalaryV2.normalizeIdcc(it.idcc) == normalizedIdcc
        }
        val dynamicStored = V2ConventionSicknessMaintenanceStore.readConfirmed(context)
        val dynamic = if (dynamicStored.reliable) {
            dynamicStored.rules.filter { ConventionMinimumSalaryV2.normalizeIdcc(it.idcc) == normalizedIdcc }
        } else {
            emptyList()
        }
        val storedCoverage = V2ConventionMatterCoverageStore.resolve(
            context = context,
            idcc = normalizedIdcc,
            matter = ConventionMatterCoverageV2.Matter.SICKNESS_MAINTENANCE,
            date = referenceDate,
            classification = classification,
            professionalStatus = professionalStatus
        )

        if (!dynamicStored.reliable) {
            val warnings = (
                dynamicStored.warnings +
                    storedCoverage.warnings +
                    "Maintien maladie IDCC $normalizedIdcc : historique KALI local non fiable ; aucun barème, y compris historique, ni aucune absence de droit ne sont déduits automatiquement."
                ).distinct()
            return Snapshot(
                result = ConventionSicknessMaintenanceV2.Result(
                    applicable = false,
                    eligibilityConfirmed = false,
                    reliable = false,
                    selectedRule = null,
                    referenceBasis = ConventionSicknessMaintenanceV2.ReferenceBasis.UNKNOWN,
                    employerWaitingDays = null,
                    firstRecordedStopOfYear = null,
                    annualLimitDays = null,
                    alreadyConsumedIndemnifiedDays = null,
                    currentIndemnifiableDays = null,
                    bands = emptyList(),
                    socialSecurityCoverageRequired = false,
                    exactEmployerAmountAvailable = false,
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
        val matching = rules.filter {
            it.structurallyValid() && it.activeOn(referenceDate) &&
                classification.matches(it.classification) && it.statusMatches(professionalStatus)
        }
        val coverage = if (matching.isNotEmpty()) {
            ConventionMatterCoverageV2.Snapshot(
                state = ConventionMatterCoverageV2.State.CONFIRMED_RULES,
                record = storedCoverage.record,
                reliable = true,
                warnings = emptyList()
            )
        } else storedCoverage

        if (matching.isEmpty() && coverage.state == ConventionMatterCoverageV2.State.CONFIRMED_NO_RULE) {
            return Snapshot(noRuleResult(), coverage)
        }

        val calculated = ConventionSicknessMaintenanceV2.calculate(
            rules = rules,
            idcc = normalizedIdcc,
            classification = classification,
            professionalStatus = professionalStatus,
            currentAbsence = absence,
            allAbsences = V2RightsStore.absencesForCompany(context, companyId),
            entryDate = entryDate,
            acceptedEmployerIds = SalaryCompanyStore.acceptedEmployerIds(context, companyId),
            zoneId = ZoneId.systemDefault()
        )
        return Snapshot(
            result = calculated.copy(
                reliable = calculated.reliable && coverage.reliable,
                warnings = (calculated.warnings + coverage.warnings).distinct()
            ),
            coverage = coverage
        )
    }

    internal fun unresolvedProfileSnapshot(warning: String): Snapshot {
        val warnings = listOf(warning)
        return Snapshot(
            result = ConventionSicknessMaintenanceV2.Result(
                applicable = false,
                eligibilityConfirmed = false,
                reliable = false,
                selectedRule = null,
                referenceBasis = ConventionSicknessMaintenanceV2.ReferenceBasis.UNKNOWN,
                employerWaitingDays = null,
                firstRecordedStopOfYear = null,
                annualLimitDays = null,
                alreadyConsumedIndemnifiedDays = null,
                currentIndemnifiableDays = null,
                bands = emptyList(),
                socialSecurityCoverageRequired = false,
                exactEmployerAmountAvailable = false,
                warnings = warnings
            ),
            coverage = ConventionMatterCoverageV2.Snapshot(
                state = ConventionMatterCoverageV2.State.INCOMPLETE,
                record = null,
                reliable = false,
                warnings = warnings
            )
        )
    }

    private fun noRuleResult() = ConventionSicknessMaintenanceV2.Result(
        applicable = false,
        eligibilityConfirmed = true,
        reliable = true,
        selectedRule = null,
        referenceBasis = ConventionSicknessMaintenanceV2.ReferenceBasis.UNKNOWN,
        employerWaitingDays = null,
        firstRecordedStopOfYear = null,
        annualLimitDays = 0,
        alreadyConsumedIndemnifiedDays = 0,
        currentIndemnifiableDays = 0,
        bands = emptyList(),
        socialSecurityCoverageRequired = false,
        exactEmployerAmountAvailable = false,
        warnings = emptyList()
    )
}
