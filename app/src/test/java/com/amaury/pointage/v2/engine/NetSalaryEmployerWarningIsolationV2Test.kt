package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.ContractTypeV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class NetSalaryEmployerWarningIsolationV2Test {
    private val date = LocalDate.of(2026, 1, 31)

    private fun company(
        professionalStatus: String? = "NON_CADRE",
        atMpEmployerRate: Double? = null,
        employerMobilityRate: Double? = null,
        warnings: List<String> = emptyList()
    ) = CompanyPayrollOverridesV2.Snapshot(
        companyId = "company",
        idcc = null,
        referenceDate = date,
        entryDate = LocalDate.of(2020, 1, 1),
        seniorityMonths = 72,
        contractType = ContractTypeV2.FULL_TIME,
        contractualWeeklyMinutes = 35 * 60,
        forfaitAnnualDays = null,
        unpaidAbsenceDays = 0,
        hasUnpaidAbsence = false,
        mutualEmployeeAmount = 0.0,
        providentEmployeeAmount = 0.0,
        transportEmployeeAmount = 0.0,
        employerProtectionTaxableAmount = 0.0,
        employeeProvidentNonDeductibleAmount = 0.0,
        incomeTaxRate = 0.05,
        professionalStatus = professionalStatus,
        protectionCategory = PlasturgieProtectionCategoryV2.classify(null, date, null),
        warnings = warnings,
        alsaceMoselleLocalRegime = false,
        atMpEmployerRate = atMpEmployerRate,
        employerMobilityRate = employerMobilityRate,
        verifiedProtectionCategory = ProtectionCategoryV2.noConventionOverride()
    )

    @Test
    fun `absence de taux patronaux ne degrade pas davantage la fiabilite du net salarie`() {
        val confirmedZero = NetSalaryEngineV2.calculate(
            gross = 2500.0,
            year = 2026,
            company = company(atMpEmployerRate = 0.0, employerMobilityRate = 0.0)
        )
        val unknownEmployerOnly = NetSalaryEngineV2.calculate(
            gross = 2500.0,
            year = 2026,
            company = company(atMpEmployerRate = null, employerMobilityRate = null)
        )

        assertEquals(confirmedZero.warnings, unknownEmployerOnly.warnings)
        assertEquals(confirmedZero.complete, unknownEmployerOnly.complete)
        assertEquals(confirmedZero.netBeforeIncomeTax, unknownEmployerOnly.netBeforeIncomeTax, 0.001)
        assertEquals(confirmedZero.netTaxable!!, unknownEmployerOnly.netTaxable!!, 0.001)
        assertEquals(confirmedZero.netAfterIncomeTax!!, unknownEmployerOnly.netAfterIncomeTax!!, 0.001)
        assertTrue(unknownEmployerOnly.employerCostWarnings.any { it.contains("AT/MP") })
        assertTrue(unknownEmployerOnly.employerCostWarnings.any { it.contains("mobilité", ignoreCase = true) })
    }

    @Test
    fun `avertissements patronaux du snapshot restent hors canal salarie`() {
        val result = NetSalaryEngineV2.calculate(
            gross = 2500.0,
            year = 2026,
            company = company(
                atMpEmployerRate = null,
                employerMobilityRate = null,
                warnings = listOf(
                    "AT/MP employeur : taux de l'établissement non renseigné ; coût employeur incomplet",
                    "Versement mobilité employeur : applicabilité et taux à confirmer pour 01/2026."
                )
            )
        )

        assertFalse(result.warnings.any { it.startsWith("AT/MP employeur") })
        assertFalse(result.warnings.any { it.startsWith("Versement mobilité employeur") })
        assertTrue(result.employerCostWarnings.any { it.startsWith("AT/MP employeur") })
        assertTrue(result.employerCostWarnings.any { it.startsWith("Versement mobilité employeur") })
    }

    @Test
    fun `minimum patronal lie au statut reste dans le cout employeur`() {
        val result = NetSalaryEngineV2.calculate(
            gross = 2500.0,
            year = 2026,
            company = company(
                professionalStatus = null,
                atMpEmployerRate = 0.0,
                employerMobilityRate = 0.0
            )
        )

        assertFalse(result.warnings.any { it.contains("prévoyance cadre minimale", ignoreCase = true) })
        assertTrue(result.employerCostWarnings.any { it.contains("prévoyance cadre minimale", ignoreCase = true) })
        assertTrue(result.warnings.any { it.contains("APEC", ignoreCase = true) })
    }
}
