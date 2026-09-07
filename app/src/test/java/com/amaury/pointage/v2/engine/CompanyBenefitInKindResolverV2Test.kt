package com.amaury.pointage.v2.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth

class CompanyBenefitInKindResolverV2Test {
    @Test
    fun `avantage mensuel est applique uniquement dans sa periode`() {
        val record = CompanyBenefitInKindResolverV2.Record(
            id = "vehicle",
            label = "Véhicule de fonction",
            grossValue = 180.0,
            kind = CompanyBenefitInKindResolverV2.Kind.MONTHLY,
            effectiveFrom = YearMonth.of(2026, 4),
            effectiveTo = YearMonth.of(2026, 12)
        )

        assertEquals(0.0, CompanyBenefitInKindResolverV2.resolve(listOf(record), YearMonth.of(2026, 3)).totalGross, 0.001)
        assertEquals(180.0, CompanyBenefitInKindResolverV2.resolve(listOf(record), YearMonth.of(2026, 4)).totalGross, 0.001)
        assertEquals(180.0, CompanyBenefitInKindResolverV2.resolve(listOf(record), YearMonth.of(2026, 12)).totalGross, 0.001)
        assertEquals(0.0, CompanyBenefitInKindResolverV2.resolve(listOf(record), YearMonth.of(2027, 1)).totalGross, 0.001)
    }

    @Test
    fun `avantage ponctuel ne tombe que sur le mois prevu`() {
        val record = CompanyBenefitInKindResolverV2.Record(
            id = "meal",
            label = "Repas fourni",
            grossValue = 55.0,
            kind = CompanyBenefitInKindResolverV2.Kind.ONE_OFF,
            paymentMonth = YearMonth.of(2026, 9)
        )

        assertEquals(0.0, CompanyBenefitInKindResolverV2.resolve(listOf(record), YearMonth.of(2026, 8)).totalGross, 0.001)
        assertEquals(55.0, CompanyBenefitInKindResolverV2.resolve(listOf(record), YearMonth.of(2026, 9)).totalGross, 0.001)
        assertEquals(0.0, CompanyBenefitInKindResolverV2.resolve(listOf(record), YearMonth.of(2026, 10)).totalGross, 0.001)
    }

    @Test
    fun `plusieurs avantages du meme mois sont additionnes`() {
        val records = listOf(
            CompanyBenefitInKindResolverV2.Record(
                id = "vehicle",
                label = "Véhicule",
                grossValue = 180.0,
                kind = CompanyBenefitInKindResolverV2.Kind.MONTHLY,
                effectiveFrom = YearMonth.of(2026, 1)
            ),
            CompanyBenefitInKindResolverV2.Record(
                id = "housing",
                label = "Logement",
                grossValue = 120.0,
                kind = CompanyBenefitInKindResolverV2.Kind.MONTHLY,
                effectiveFrom = YearMonth.of(2026, 1)
            )
        )

        val result = CompanyBenefitInKindResolverV2.resolve(records, YearMonth.of(2026, 9))

        assertEquals(300.0, result.totalGross, 0.001)
        assertEquals(2, result.applied.size)
        assertTrue(result.reliable)
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun `periode incomplete bloque la fiabilite sans inventer de valeur`() {
        val record = CompanyBenefitInKindResolverV2.Record(
            id = "bad",
            label = "Logement",
            grossValue = 120.0,
            kind = CompanyBenefitInKindResolverV2.Kind.MONTHLY
        )

        val result = CompanyBenefitInKindResolverV2.resolve(listOf(record), YearMonth.of(2026, 9))

        assertEquals(0.0, result.totalGross, 0.001)
        assertFalse(result.reliable)
        assertTrue(result.warnings.any { it.contains("mois de début manquant") })
    }

    @Test
    fun `valeur nulle est refusee`() {
        val record = CompanyBenefitInKindResolverV2.Record(
            id = "zero",
            label = "Véhicule",
            grossValue = 0.0,
            kind = CompanyBenefitInKindResolverV2.Kind.ONE_OFF,
            paymentMonth = YearMonth.of(2026, 9)
        )

        val result = CompanyBenefitInKindResolverV2.resolve(listOf(record), YearMonth.of(2026, 9))

        assertFalse(result.reliable)
        assertEquals(0.0, result.totalGross, 0.001)
    }
}
