package com.amaury.pointage.v2

import com.amaury.pointage.V2SalaryAdapter
import com.amaury.pointage.V2SalaryNetBridgeV2
import com.amaury.pointage.v2.engine.NetSalaryEngineV2
import com.amaury.pointage.v2.engine.PayslipDocumentParserV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class V2PayslipComparisonNetBridgeV2Test {
    private fun salary(
        grossReliable: Boolean = true,
        paidTimeReliable: Boolean = true,
        completedSessions: Int = 10,
        warnings: List<String> = emptyList()
    ) = V2SalaryAdapter.Result(
        regularMs = 0L,
        overtimeTiers = emptyList(),
        totalWorkedMs = 0L,
        regularGross = 2_300.0,
        overtimeGross = 120.0,
        premiumsGross = 80.0,
        monthlyEstimatedGross = 2_500.0,
        monthlyGrossReliable = grossReliable,
        nightMs = 0L,
        saturdayMs = 0L,
        sundayMs = 0L,
        complementaryMinutes = 0,
        completedSessions = completedSessions,
        warnings = warnings,
        mealBasketCount = 10,
        mealBasketAmount = 5.38,
        mealBasketTotal = 53.80,
        paidTimeReliable = paidTimeReliable
    )

    private fun payroll(
        grossReliable: Boolean = true,
        conventionProvident: Double = 0.0
    ) = NetSalaryEngineV2.Result(
        gross = 2_500.0,
        socialSecurityCeiling = 4_000.0,
        socialSecurityCeilingComplete = true,
        statutory = 300.0,
        complementaryRetirement = 100.0,
        conventionProvidentEmployee = conventionProvident,
        conventionProvidentEmployer = 0.0,
        companyEmployeeDeductions = 100.0,
        employerStatusContributions = 0.0,
        employerAtMpContribution = null,
        netBeforeIncomeTax = 2_000.0,
        netTaxable = 2_050.0,
        incomeTax = 100.0,
        netAfterIncomeTax = 1_900.0,
        complete = true,
        warnings = emptyList(),
        grossReliable = grossReliable
    )

    private fun bridge(
        grossReliable: Boolean = true,
        paidTimeReliable: Boolean = true,
        payrollGrossReliable: Boolean = true,
        netComplete: Boolean = true,
        taxable: Double? = 2_050.0,
        incomeTax: Double? = 100.0,
        afterTax: Double? = 1_900.0,
        mutual: Double? = 60.0,
        provident: Double? = 40.0,
        conventionProvident: Double = 0.0,
        completedSessions: Int = 10,
        salaryWarnings: List<String> = emptyList()
    ) = V2SalaryNetBridgeV2.Result(
        salary = salary(grossReliable, paidTimeReliable, completedSessions, salaryWarnings),
        payroll = payroll(payrollGrossReliable, conventionProvident),
        netBeforeIncomeTax = 2_000.0.takeIf { netComplete },
        netTaxable = taxable.takeIf { netComplete },
        incomeTax = incomeTax.takeIf { netComplete && taxable != null },
        netAfterIncomeTax = afterTax.takeIf { netComplete && taxable != null },
        netBeforeIncomeTaxComplete = netComplete,
        warnings = emptyList(),
        mutualEmployeeAmount = mutual,
        providentEmployeeAmount = provident
    )

    @Test
    fun `projection complete fournit uniquement les valeurs attendues sures`() {
        val values = V2PayslipStore.expectedCompanyComparisonValues(bridge())!!

        assertEquals(120.0, values[PayslipDocumentParserV2.KEY_OVERTIME_GROSS]!!, 0.0)
        assertEquals(80.0, values[PayslipDocumentParserV2.KEY_PREMIUMS_GROSS]!!, 0.0)
        assertEquals(53.80, values[PayslipDocumentParserV2.KEY_MEAL_BASKETS]!!, 0.0)
        assertEquals(2_500.0, values[PayslipDocumentParserV2.KEY_GROSS]!!, 0.0)
        assertEquals(2_000.0, values[PayslipDocumentParserV2.KEY_NET_BEFORE_TAX]!!, 0.0)
        assertEquals(2_050.0, values[PayslipDocumentParserV2.KEY_NET_TAXABLE]!!, 0.0)
        assertEquals(60.0, values[PayslipDocumentParserV2.KEY_MUTUAL_EMPLOYEE]!!, 0.0)
        assertEquals(40.0, values[PayslipDocumentParserV2.KEY_PROVIDENT_EMPLOYEE]!!, 0.0)
    }

    @Test
    fun `temps ou brut non fiable bloque toute comparaison calculee`() {
        assertNull(V2PayslipStore.expectedCompanyComparisonValues(bridge(paidTimeReliable = false)))
        assertNull(V2PayslipStore.expectedCompanyComparisonValues(bridge(grossReliable = false)))
    }

    @Test
    fun `zero session avec avertissement bloque les fausses anomalies`() {
        assertNull(
            V2PayslipStore.expectedCompanyComparisonValues(
                bridge(
                    completedSessions = 0,
                    salaryWarnings = listOf("Aucune session exploitable")
                )
            )
        )
    }

    @Test
    fun `brut social non fiable masque brut net et repli prevoyance conventionnel`() {
        val values = V2PayslipStore.expectedCompanyComparisonValues(
            bridge(
                payrollGrossReliable = false,
                netComplete = true,
                provident = null,
                conventionProvident = 35.0
            )
        )!!

        assertFalse(PayslipDocumentParserV2.KEY_GROSS in values)
        assertFalse(PayslipDocumentParserV2.KEY_NET_BEFORE_TAX in values)
        assertFalse(PayslipDocumentParserV2.KEY_NET_TAXABLE in values)
        assertFalse(PayslipDocumentParserV2.KEY_PROVIDENT_EMPLOYEE in values)
        assertEquals(60.0, values[PayslipDocumentParserV2.KEY_MUTUAL_EMPLOYEE]!!, 0.0)
    }

    @Test
    fun `pas absent conserve les references avant impot et imposable`() {
        val values = V2PayslipStore.expectedCompanyComparisonValues(
            bridge(incomeTax = null, afterTax = null)
        )!!

        assertEquals(2_000.0, values[PayslipDocumentParserV2.KEY_NET_BEFORE_TAX]!!, 0.0)
        assertEquals(2_050.0, values[PayslipDocumentParserV2.KEY_NET_TAXABLE]!!, 0.0)
    }

    @Test
    fun `donnee fiscale absente conserve le net avant impot seulement`() {
        val values = V2PayslipStore.expectedCompanyComparisonValues(bridge(taxable = null))!!

        assertEquals(2_000.0, values[PayslipDocumentParserV2.KEY_NET_BEFORE_TAX]!!, 0.0)
        assertFalse(PayslipDocumentParserV2.KEY_NET_TAXABLE in values)
    }

    @Test
    fun `retenue salariee inconnue ne publie aucun net technique`() {
        val values = V2PayslipStore.expectedCompanyComparisonValues(bridge(netComplete = false))!!

        assertFalse(PayslipDocumentParserV2.KEY_NET_BEFORE_TAX in values)
        assertFalse(PayslipDocumentParserV2.KEY_NET_TAXABLE in values)
        assertTrue(PayslipDocumentParserV2.KEY_GROSS in values)
    }

    @Test
    fun `intersection compare seulement les cles observees et calculables`() {
        val expected = mapOf(
            PayslipDocumentParserV2.KEY_GROSS to 2_500.0,
            PayslipDocumentParserV2.KEY_NET_BEFORE_TAX to 2_000.0
        )
        val observed = mapOf(
            PayslipDocumentParserV2.KEY_GROSS to 2_500.0,
            PayslipDocumentParserV2.KEY_MUTUAL_EMPLOYEE to 999.0
        )

        val result = V2PayslipStore.compareKnownPayslipValues(expected, observed)

        assertTrue(result!!.conforming)
        assertTrue(result.discrepancies.isEmpty())
        assertNull(
            V2PayslipStore.compareKnownPayslipValues(
                expected,
                mapOf(PayslipDocumentParserV2.KEY_MUTUAL_EMPLOYEE to 999.0)
            )
        )
    }

    @Test
    fun `le bulletin canonique stocke gagne sur un objet appelant divergent`() {
        val canonical = record(id = "same", year = 2026)
        val stored = V2PayslipStore.ReadResult(listOf(canonical), true, emptyList())

        assertSame(canonical, V2PayslipStore.canonicalRecord(stored, "same"))
        assertNull(V2PayslipStore.canonicalRecord(stored.copy(reliable = false), "same"))
        assertNull(V2PayslipStore.canonicalRecord(stored, "missing"))
    }

    @Test
    fun `stockage observe corrompu bloque et brut non confirme nest pas injecte`() {
        val confirmed = record(id = "confirmed", year = 2026)
        val unconfirmed = confirmed.copy(id = "unconfirmed", confirmedByUser = false)
        val emptyReliable = PayslipObservedValuesStoreV2.ReadResult(emptyMap(), true, emptyList())
        val corrupt = emptyReliable.copy(reliable = false)

        assertEquals(
            2_500.0,
            V2PayslipStore.observedComparisonValues(emptyReliable, confirmed)
                ?.get(PayslipDocumentParserV2.KEY_GROSS) ?: -1.0,
            0.0
        )
        assertTrue(V2PayslipStore.observedComparisonValues(emptyReliable, unconfirmed)!!.isEmpty())
        assertNull(V2PayslipStore.observedComparisonValues(corrupt, confirmed))
    }

    private fun record(id: String, year: Int) = V2PayslipStore.Record(
        id = id,
        year = year,
        month = 0,
        sourceUri = "content://payslip/$id",
        sourceMime = "application/pdf",
        gross = 2_500.0,
        net = 2_000.0,
        extractionConfidence = 1.0,
        confirmedByUser = true,
        importedAtMs = 1L,
        companyId = "company"
    )
}
