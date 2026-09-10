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
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Entreprise -> règle de maintien maladie conventionnelle générique. */
object V2ConventionSicknessMaintenanceBridge {
    data class Snapshot(
        val result: ConventionSicknessMaintenanceV2.Result,
        val coverage: ConventionMatterCoverageV2.Snapshot
    )

    fun load(context: Context, companyId: String, absence: AbsenceV2): Snapshot? {
        if (absence.type != AbsencePayrollImpactV2.TYPE_SICKNESS) return null
        val company = SalaryCompanyStore.list(context).firstOrNull { it.id == companyId }
        val prefs = SalaryCompanyStore.prefs(context, companyId)
        val idcc = company?.idcc?.ifBlank { prefs.getString("company_idcc", "").orEmpty() }
            ?: prefs.getString("company_idcc", "").orEmpty()
        val normalizedIdcc = ConventionMinimumSalaryV2.normalizeIdcc(idcc)
        val classification = ConventionClassificationStoreV2.load(context, companyId)
        val professionalStatus = prefs.getString("professional_status", "").orEmpty().trim().uppercase(Locale.ROOT).takeIf {
            it == "CADRE" || it == "NON_CADRE"
        }
        val entryDate = runCatching {
            prefs.getString("entry_date", "").orEmpty().trim().takeIf { it.isNotBlank() }?.let {
                LocalDate.parse(it, DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.FRANCE))
            }
        }.getOrNull()
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
