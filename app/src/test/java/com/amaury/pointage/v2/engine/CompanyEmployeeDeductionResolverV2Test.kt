package com.amaury.pointage.v2.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth

class CompanyEmployeeDeductionResolverV2Test {
    private val kind = CompanyEmployeeDeductionResolverV2.Kind.MUTUAL_EMPLOYEE

    @Test
    fun `une retenue datee ne vaut que dans sa periode`() {
        val record = CompanyEmployeeDeductionResolverV2.Record(
            id = "mutuelle_2026",
            kind = kind,
            amount = 28.40,
            effectiveFrom = YearMonth.of(2026, 3),
            effectiveTo = YearMonth.of(2026, 8),
            source = "Bulletin mars 2026"
        )

        val august = CompanyEmployeeDeductionResolverV2.resolve(
            listOf(record),
            YearMonth.of(2026, 8)
        )[kind]
        assertEquals(28.40, august.amount!!, 0.001)
        assertTrue(august.reliable)
        assertFalse(august.legacyUsed)

        val september = CompanyEmployeeDeductionResolverV2.resolve(
            listOf(record),
            YearMonth.of(2026, 9)
        )[kind]
        assertNull(september.amount)
        assertFalse(september.reliable)
        assertTrue(september.hasDatedRecords)
    }

    @Test
    fun `zero doit etre explicitement confirme et reste valide`() {
        val value = CompanyEmployeeDeductionResolverV2.resolve(
            listOf(
                CompanyEmployeeDeductionResolverV2.Record(
                    id = "transport_zero",
                    kind = CompanyEmployeeDeductionResolverV2.Kind.TRANSPORT_EMPLOYEE,
                    amount = 0.0,
                    effectiveFrom = YearMonth.of(2026, 1)
                )
            ),
            YearMonth.of(2026, 9)
        )[CompanyEmployeeDeductionResolverV2.Kind.TRANSPORT_EMPLOYEE]

        assertEquals(0.0, value.amount!!, 0.001)
        assertTrue(value.reliable)
    }

    @Test
    fun `deux periodes qui se chevauchent bloquent le type`() {
        val records = listOf(
            CompanyEmployeeDeductionResolverV2.Record(
                id = "a",
                kind = kind,
                amount = 20.0,
                effectiveFrom = YearMonth.of(2026, 1),
                effectiveTo = YearMonth.of(2026, 12)
            ),
            CompanyEmployeeDeductionResolverV2.Record(
                id = "b",
                kind = kind,
                amount = 30.0,
                effectiveFrom = YearMonth.of(2026, 6)
            )
        )

        val value = CompanyEmployeeDeductionResolverV2.resolve(
            records,
            YearMonth.of(2026, 9)
        )[kind]

        assertNull(value.amount)
        assertFalse(value.reliable)
        assertTrue(value.warnings.any { it.contains("chevauchent") })
    }

    @Test
    fun `une regle datee invalide bloque sans retomber sur lancien montant`() {
        val dated = CompanyEmployeeDeductionResolverV2.resolve(
            listOf(
                CompanyEmployeeDeductionResolverV2.Record(
                    id = "bad",
                    kind = kind,
                    amount = 25.0,
                    effectiveFrom = YearMonth.of(2026, 10),
                    effectiveTo = YearMonth.of(2026, 9)
                )
            ),
            YearMonth.of(2026, 9)
        )
        val effective = CompanyEmployeeDeductionResolverV2.withLegacyFallback(
            dated,
            mapOf(kind to 99.0)
        )[kind]

        assertNull(effective.amount)
        assertFalse(effective.reliable)
        assertFalse(effective.legacyUsed)
        assertTrue(effective.hasDatedRecords)
    }

    @Test
    fun `ancien montant reste estimation tant quaucune regle datee nexiste`() {
        val empty = CompanyEmployeeDeductionResolverV2.resolve(
            emptyList(),
            YearMonth.of(2026, 9)
        )
        val effective = CompanyEmployeeDeductionResolverV2.withLegacyFallback(
            empty,
            mapOf(kind to 28.40)
        )[kind]

        assertEquals(28.40, effective.amount!!, 0.001)
        assertFalse(effective.reliable)
        assertTrue(effective.legacyUsed)
        assertTrue(effective.warnings.any { it.contains("non datée") })
    }
}
