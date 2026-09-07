package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.v2.engine.ConventionClassificationV2
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
        val resolution: ConventionMinimumSalaryV2.Result
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
        val rules = ConventionPayrollReferenceV2.genericMinimumRules() +
            V2ConventionMinimumSalaryStore.rules(context, normalizedIdcc)
        val resolution = ConventionMinimumSalaryV2.resolve(
            rules = rules,
            idcc = normalizedIdcc,
            date = referenceDate,
            classification = classification,
            companyApplicabilityConfirmed = false
        )
        return Snapshot(normalizedIdcc, classification, resolution)
    }
}
