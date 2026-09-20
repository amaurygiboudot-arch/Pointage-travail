package com.amaury.pointage.v2.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth

class CompanyEmployeeDeductionPayrollBridgeV2Test {
    private val period = YearMonth.of(2026, 9)

    @Test
    fun `seules les retenues salariales cash alimentent PayrollEngineV2`() {
        val snapshot = CompanyEmployeeDeductionResolverV2.resolve(
            listOf(
                record("mutuelle", CompanyEmployeeDeductionResolverV2.Kind.MUTUAL_EMPLOYEE, 30.0),
                record("prevoyance", CompanyEmployeeDeductionResolverV2.Kind.PROVIDENT_EMPLOYEE, 12.5),
                record("transport", CompanyEmployeeDeductionResolverV2.Kind.TRANSPORT_EMPLOYEE, 0.0),
                record("prev_non_deductible", CompanyEmployeeDeductionResolverV2.Kind.EMPLOYEE_PROVIDENT_NON_DEDUCTIBLE, 4.0),
                record("taxable", CompanyEmployeeDeductionResolverV2.Kind.EMPLOYER_PROTECTION_TAXABLE, 55.0),
                record("csg_base", CompanyEmployeeDeductionResolverV2.Kind.EMPLOYER_PROTECTION_CSG_CRDS_BASE, 70.0)
            ),
            period
        )

        val result = CompanyEmployeeDeductionPayrollBridgeV2.resolve(snapshot, period)

        assertTrue(result.confirmedEmployeeDeductionsComplete)
        assertEquals(2, result.deductions.size)
        assertEquals(42.5, result.deductions.sumOf { it.amount }, 0.001)
        assertTrue(result.deductions.none { it.id.contains("employee_provident_non_deductible") })
        assertTrue(result.deductions.none { it.label.contains("employeur", ignoreCase = true) })
        assertEquals(3, result.traces.size)
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun `absence de valeur datee ne devient jamais zero silencieusement`() {
        val snapshot = CompanyEmployeeDeductionResolverV2.resolve(
            listOf(
                record("mutuelle", CompanyEmployeeDeductionResolverV2.Kind.MUTUAL_EMPLOYEE, 30.0)
            ),
            period
        )

        val result = CompanyEmployeeDeductionPayrollBridgeV2.resolve(snapshot, period)

        assertFalse(result.confirmedEmployeeDeductionsComplete)
        assertEquals(1, result.deductions.size)
        assertTrue(result.warnings.any { it.contains("aucun montant daté confirmé", ignoreCase = true) })
    }

    @Test
    fun `zero explicite confirme labsence de toute retenue cash`() {
        val records = listOf(
            record("mutuelle", CompanyEmployeeDeductionResolverV2.Kind.MUTUAL_EMPLOYEE, 0.0),
            record("prevoyance", CompanyEmployeeDeductionResolverV2.Kind.PROVIDENT_EMPLOYEE, 0.0),
            record("transport", CompanyEmployeeDeductionResolverV2.Kind.TRANSPORT_EMPLOYEE, 0.0)
        )
        val result = CompanyEmployeeDeductionPayrollBridgeV2.resolve(
            CompanyEmployeeDeductionResolverV2.resolve(records, period),
            period
        )

        assertTrue(result.confirmedEmployeeDeductionsComplete)
        assertTrue(result.deductions.isEmpty())
        assertEquals(3, result.traces.size)
    }

    @Test
    fun `detail fiscal de prevoyance manquant ne bloque pas le net cash`() {
        val records = listOf(
            record("mutuelle", CompanyEmployeeDeductionResolverV2.Kind.MUTUAL_EMPLOYEE, 30.0),
            record("prevoyance", CompanyEmployeeDeductionResolverV2.Kind.PROVIDENT_EMPLOYEE, 12.5),
            record("transport", CompanyEmployeeDeductionResolverV2.Kind.TRANSPORT_EMPLOYEE, 0.0)
        )

        val result = CompanyEmployeeDeductionPayrollBridgeV2.resolve(
            CompanyEmployeeDeductionResolverV2.resolve(records, period),
            period
        )

        assertTrue(result.confirmedEmployeeDeductionsComplete)
        assertEquals(42.5, result.deductions.sumOf { it.amount }, 0.001)
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun `periode manquante bloque le type cash sans montant de secours`() {
        val records = listOf(
            CompanyEmployeeDeductionResolverV2.Record(
                id = "mutuelle_old",
                kind = CompanyEmployeeDeductionResolverV2.Kind.MUTUAL_EMPLOYEE,
                amount = 30.0,
                effectiveFrom = YearMonth.of(2026, 1),
                effectiveTo = YearMonth.of(2026, 8),
                source = "Bulletin janvier 2026"
            ),
            record("prevoyance", CompanyEmployeeDeductionResolverV2.Kind.PROVIDENT_EMPLOYEE, 0.0),
            record("transport", CompanyEmployeeDeductionResolverV2.Kind.TRANSPORT_EMPLOYEE, 0.0)
        )

        val result = CompanyEmployeeDeductionPayrollBridgeV2.resolve(
            CompanyEmployeeDeductionResolverV2.resolve(records, period),
            period
        )

        assertFalse(result.confirmedEmployeeDeductionsComplete)
        assertTrue(result.deductions.isEmpty())
        assertTrue(result.warnings.any { it.contains("aucune période confirmée", ignoreCase = true) })
    }

    private fun record(
        id: String,
        kind: CompanyEmployeeDeductionResolverV2.Kind,
        amount: Double
    ) = CompanyEmployeeDeductionResolverV2.Record(
        id = id,
        kind = kind,
        amount = amount,
        effectiveFrom = period,
        source = "Bulletin septembre 2026"
    )
}
