package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.v2.engine.ConventionClassificationV2
import com.amaury.pointage.v2.engine.ConventionMatterCoverageV2
import com.amaury.pointage.v2.engine.ConventionMinimumSalaryV2
import com.amaury.pointage.v2.engine.ConventionPayrollReferenceV2
import java.time.LocalDate

/**
 * Passerelle entre la fiche entreprise et le moteur générique de minima.
 *
 * Les minima historiques déjà audités (actuellement Plasturgie) et les règles
 * KALI confirmées dynamiquement passent par le même résolveur.
 */
object V2ConventionMinimumSalaryBridge {
    data class Snapshot(
        val idcc: String,
        val classification: ConventionClassificationV2,
        val resolution: ConventionMinimumSalaryV2.Result,
        val coverage: ConventionMatterCoverageV2.Snapshot
    ) {
        val selectedMonthlyGross: Double?
            get() = resolution.selected
                ?.takeIf { it.periodicity == ConventionMinimumSalaryV2.Periodicity.MONTHLY }
                ?.amount
    }

    fun load(
        context: Context,
        companyId: String,
        idcc: String,
        referenceDate: LocalDate
    ): Snapshot {
        val normalizedIdcc = ConventionMinimumSalaryV2.normalizeIdcc(idcc)
        val classification = ConventionClassificationStoreV2.load(context, companyId)
        val builtIn = ConventionPayrollReferenceV2.genericMinimumRules()
            .filter { ConventionMinimumSalaryV2.normalizeIdcc(it.idcc) == normalizedIdcc }
        val dynamicStored = V2ConventionMinimumSalaryStore.readConfirmed(context)
        val dynamic = if (dynamicStored.reliable) {
            dynamicStored.rules.filter { ConventionMinimumSalaryV2.normalizeIdcc(it.idcc) == normalizedIdcc }
        } else {
            emptyList()
        }
        val storedCoverage = V2ConventionMatterCoverageStore.resolve(
            context,
            normalizedIdcc,
            ConventionMatterCoverageV2.Matter.MINIMUM_SALARY,
            referenceDate,
            classification
        )

        if (!dynamicStored.reliable) {
            val warnings = (
                dynamicStored.warnings +
                    storedCoverage.warnings +
                    "Minimum conventionnel IDCC $normalizedIdcc : historique KALI local non fiable ; aucun barème, y compris historique, n'est appliqué automatiquement tant que le stockage n'est pas réparé."
                ).distinct()
            return Snapshot(
                idcc = normalizedIdcc,
                classification = classification,
                resolution = ConventionMinimumSalaryV2.Result(
                    selected = null,
                    latestKnown = null,
                    reliable = false,
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
        val coverage = if (matchingRules.isNotEmpty()) {
            ConventionMatterCoverageV2.Snapshot(
                state = ConventionMatterCoverageV2.State.CONFIRMED_RULES,
                record = storedCoverage.record,
                reliable = true,
                warnings = emptyList()
            )
        } else storedCoverage

        val resolution = when {
            matchingRules.isEmpty() && coverage.state == ConventionMatterCoverageV2.State.CONFIRMED_NO_RULE ->
                ConventionMinimumSalaryV2.Result(
                    selected = null,
                    latestKnown = null,
                    reliable = true,
                    warnings = emptyList()
                )
            else -> {
                val resolved = ConventionMinimumSalaryV2.resolve(
                    rules = rules,
                    idcc = normalizedIdcc,
                    date = referenceDate,
                    classification = classification,
                    companyApplicabilityConfirmed = false
                )
                resolved.copy(warnings = (resolved.warnings + coverage.warnings).distinct())
            }
        }
        return Snapshot(normalizedIdcc, classification, resolution, coverage)
    }
}
