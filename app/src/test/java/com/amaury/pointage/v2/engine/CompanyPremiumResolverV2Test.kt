package com.amaury.pointage.v2.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth

class CompanyPremiumResolverV2Test {
    @Test
    fun `prime mensuelle est appliquee uniquement dans sa periode`() {
        val record = CompanyPremiumResolverV2.Record(
            id = "monthly",
            label = "Prime qualite",
            grossAmount = 80.0,
            kind = CompanyPremiumResolverV2.Kind.MONTHLY,
            effectiveFrom = YearMonth.of(2026, 3),
            effectiveTo = YearMonth.of(2026, 9)
        )

        assertEquals(0.0, CompanyPremiumResolverV2.resolve(listOf(record), YearMonth.of(2026, 2)).totalGross, 0.001)
        assertEquals(80.0, CompanyPremiumResolverV2.resolve(listOf(record), YearMonth.of(2026, 3)).totalGross, 0.001)
        assertEquals(80.0, CompanyPremiumResolverV2.resolve(listOf(record), YearMonth.of(2026, 9)).totalGross, 0.001)
        assertEquals(0.0, CompanyPremiumResolverV2.resolve(listOf(record), YearMonth.of(2026, 10)).totalGross, 0.001)
    }

    @Test
    fun `prime ponctuelle ne tombe que sur son mois de versement`() {
        val record = CompanyPremiumResolverV2.Record(
            id = "one_off",
            label = "Prime exceptionnelle",
            grossAmount = 300.0,
            kind = CompanyPremiumResolverV2.Kind.ONE_OFF,
            paymentMonth = YearMonth.of(2026, 9)
        )

        assertEquals(0.0, CompanyPremiumResolverV2.resolve(listOf(record), YearMonth.of(2026, 8)).totalGross, 0.001)
        assertEquals(300.0, CompanyPremiumResolverV2.resolve(listOf(record), YearMonth.of(2026, 9)).totalGross, 0.001)
        assertEquals(0.0, CompanyPremiumResolverV2.resolve(listOf(record), YearMonth.of(2026, 10)).totalGross, 0.001)
    }

    @Test
    fun `plusieurs primes dues le meme mois sont additionnees une seule fois`() {
        val records = listOf(
            CompanyPremiumResolverV2.Record(
                id = "monthly",
                label = "Prime equipe",
                grossAmount = 75.0,
                kind = CompanyPremiumResolverV2.Kind.MONTHLY,
                effectiveFrom = YearMonth.of(2026, 1)
            ),
            CompanyPremiumResolverV2.Record(
                id = "one_off",
                label = "Prime exceptionnelle",
                grossAmount = 250.0,
                kind = CompanyPremiumResolverV2.Kind.ONE_OFF,
                paymentMonth = YearMonth.of(2026, 9)
            )
        )

        val result = CompanyPremiumResolverV2.resolve(records, YearMonth.of(2026, 9))

        assertEquals(325.0, result.totalGross, 0.001)
        assertEquals(2, result.applied.size)
        assertTrue(result.reliable)
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun `prime mensuelle incomplete bloque la fiabilite sans inventer de montant`() {
        val record = CompanyPremiumResolverV2.Record(
            id = "bad",
            label = "Prime sans date",
            grossAmount = 100.0,
            kind = CompanyPremiumResolverV2.Kind.MONTHLY
        )

        val result = CompanyPremiumResolverV2.resolve(listOf(record), YearMonth.of(2026, 9))

        assertEquals(0.0, result.totalGross, 0.001)
        assertFalse(result.reliable)
        assertTrue(result.warnings.any { it.contains("mois de début manquant") })
    }

    @Test
    fun `periode mensuelle inversee est bloquee`() {
        val record = CompanyPremiumResolverV2.Record(
            id = "bad_period",
            label = "Prime période invalide",
            grossAmount = 100.0,
            kind = CompanyPremiumResolverV2.Kind.MONTHLY,
            effectiveFrom = YearMonth.of(2026, 10),
            effectiveTo = YearMonth.of(2026, 9)
        )

        val result = CompanyPremiumResolverV2.resolve(listOf(record), YearMonth.of(2026, 9))

        assertEquals(0.0, result.totalGross, 0.001)
        assertFalse(result.reliable)
        assertTrue(result.warnings.any { it.contains("période invalide") })
    }
}
