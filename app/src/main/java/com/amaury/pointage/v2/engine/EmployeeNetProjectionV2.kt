package com.amaury.pointage.v2.engine

import java.util.Locale

/**
 * Projection de confiance pour les montants salarié calculés par [NetSalaryEngineV2].
 *
 * Le moteur historique conserve ses sous-totaux numériques pour compatibilité interne, mais cette
 * couche interdit de les exposer comme un net fiable lorsqu'une donnée qui change réellement les
 * retenues salariales est inconnue. Une absence de donnée n'est jamais assimilée à zéro.
 */
object EmployeeNetProjectionV2 {
    data class Result(
        val payroll: NetSalaryEngineV2.Result,
        val netBeforeIncomeTax: Double?,
        val netTaxable: Double?,
        val incomeTax: Double?,
        val netAfterIncomeTax: Double?,
        val netBeforeIncomeTaxComplete: Boolean,
        val warnings: List<String>
    )

    fun calculate(
        gross: Double,
        year: Int,
        company: CompanyPayrollOverridesV2.Snapshot,
        complementaryMinutes: Int? = null,
        upstreamGrossReliable: Boolean = true
    ): Result = project(
        payroll = NetSalaryEngineV2.calculate(
            gross = gross,
            year = year,
            company = company,
            complementaryMinutes = complementaryMinutes
        ),
        year = year,
        company = company,
        upstreamGrossReliable = upstreamGrossReliable
    )

    internal fun project(
        payroll: NetSalaryEngineV2.Result,
        year: Int,
        company: CompanyPayrollOverridesV2.Snapshot,
        upstreamGrossReliable: Boolean = true
    ): Result {
        val professionalStatus = company.professionalStatus?.trim()?.uppercase(Locale.ROOT)
        val supportedNationalTables = SocialContributionCatalogV2.employeeRules(year).isNotEmpty() &&
            SocialSecurityCeilingV2.fullMonthly(year) != null
        val directEmployeeDeductionsComplete = company.mutualEmployeeAmount != null &&
            company.providentEmployeeAmount != null &&
            company.transportEmployeeAmount != null
        val statutoryInputsComplete = company.alsaceMoselleLocalRegime != null &&
            company.employerProtectionCsgCrdsBaseAmount != null
        val retirementInputsComplete = professionalStatus == "CADRE" || professionalStatus == "NON_CADRE"
        val aniInputComplete = !company.verifiedProtectionCategory.conventionControlsAni ||
            company.verifiedProtectionCategory.confirmed

        val blockers = buildList {
            if (!supportedNationalTables) add("barèmes nationaux non intégrés pour $year")
            if (!upstreamGrossReliable) add("brut issu du calcul temps/primes incomplet")
            if (!payroll.grossReliable) add("brut social incomplet ou avantages en nature non confirmés")
            if (!payroll.socialSecurityCeilingComplete) add("plafond de Sécurité sociale incomplet")
            if (company.alsaceMoselleLocalRegime == null) add("affiliation Alsace-Moselle à confirmer")
            if (company.employerProtectionCsgCrdsBaseAmount == null) {
                add("part employeur de protection complémentaire soumise à CSG/CRDS à confirmer")
            }
            if (company.mutualEmployeeAmount == null) add("mutuelle salariale à confirmer")
            if (company.providentEmployeeAmount == null) add("prévoyance salariale réellement prélevée à confirmer")
            if (company.transportEmployeeAmount == null) add("retenue transport à confirmer")
            if (!retirementInputsComplete) add("statut professionnel cadre/non-cadre à confirmer")
            if (!aniInputComplete) add("catégorie ANI conventionnelle à confirmer")
        }

        val netBeforeIncomeTaxComplete = supportedNationalTables &&
            upstreamGrossReliable &&
            payroll.grossReliable &&
            payroll.socialSecurityCeilingComplete &&
            directEmployeeDeductionsComplete &&
            statutoryInputsComplete &&
            retirementInputsComplete &&
            aniInputComplete

        val failClosedWarnings = if (netBeforeIncomeTaxComplete) {
            emptyList()
        } else {
            listOf(
                "Net avant impôt incomplet : ${blockers.joinToString(" ; ")}. Les sous-totaux connus restent calculables mais aucun net salarié final n'est affiché."
            )
        }

        return Result(
            payroll = payroll,
            netBeforeIncomeTax = payroll.netBeforeIncomeTax.takeIf { netBeforeIncomeTaxComplete },
            netTaxable = payroll.netTaxable.takeIf { netBeforeIncomeTaxComplete },
            incomeTax = payroll.incomeTax.takeIf { netBeforeIncomeTaxComplete },
            netAfterIncomeTax = payroll.netAfterIncomeTax.takeIf { netBeforeIncomeTaxComplete },
            netBeforeIncomeTaxComplete = netBeforeIncomeTaxComplete,
            warnings = (payroll.warnings + failClosedWarnings).distinct()
        )
    }
}
