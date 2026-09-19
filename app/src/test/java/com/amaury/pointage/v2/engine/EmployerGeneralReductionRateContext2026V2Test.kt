package com.amaury.pointage.v2.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth

class EmployerGeneralReductionRateContext2026V2Test {
    private val month = YearMonth.of(2026, 9)

    private fun record(
        regime: EmployerGeneralReductionRateContext2026V2.HousingContributionRegime =
            EmployerGeneralReductionRateContext2026V2.HousingContributionRegime.L813_5_2,
        rateSum: Double = 0.4021,
        from: YearMonth = YearMonth.of(2026, 1),
        to: YearMonth? = null,
        source: String = "DSN / paramétrage paie 2026",
        id: String = "rgdu-rate"
    ) = EmployerGeneralReductionRateContext2026V2.Record(
        id = id,
        housingContributionRegime = regime,
        eligibleEmployerRateSum = rateSum,
        effectiveFrom = from,
        effectiveTo = to,
        source = source
    )

    @Test
    fun `L813 5 first rate resolves the lower standard coefficient`() {
        val result = EmployerGeneralReductionRateContext2026V2.resolve(
            listOf(
                record(
                    regime = EmployerGeneralReductionRateContext2026V2.HousingContributionRegime.L813_5_1,
                    rateSum = 0.3981
                )
            ),
            month
        )

        assertTrue(result.reliable)
        assertEquals(0.3781, result.tDelta!!, 0.0000001)
        assertEquals(0.3981, result.maximumCoefficient!!, 0.0000001)
        assertTrue(EmployerGeneralReductionRateContext2026V2.isUsable(result))
    }

    @Test
    fun `L813 5 second rate resolves the higher standard coefficient`() {
        val result = EmployerGeneralReductionRateContext2026V2.resolve(listOf(record()), month)

        assertTrue(result.reliable)
        assertEquals(0.3821, result.tDelta!!, 0.0000001)
        assertEquals(0.4021, result.maximumCoefficient!!, 0.0000001)
    }

    @Test
    fun `actual eligible rates lower than standard reduce T delta`() {
        val result = EmployerGeneralReductionRateContext2026V2.resolve(
            listOf(record(rateSum = 0.3500)),
            month
        )

        assertTrue(result.reliable)
        assertEquals(0.3300, result.tDelta!!, 0.0000001)
        assertEquals(0.3500, result.maximumCoefficient!!, 0.0000001)
    }

    @Test
    fun `overlapping coefficient records fail closed`() {
        val result = EmployerGeneralReductionRateContext2026V2.resolve(
            listOf(record(id = "a"), record(id = "b", from = YearMonth.of(2026, 6))),
            month
        )

        assertFalse(result.reliable)
        assertNull(result.tDelta)
        assertTrue(result.warnings.any { it.contains("chevauchent", ignoreCase = true) })
    }

    @Test
    fun `missing period rule never falls back to workforce`() {
        val result = EmployerGeneralReductionRateContext2026V2.resolve(
            listOf(record(from = YearMonth.of(2026, 10))),
            month
        )

        assertFalse(result.reliable)
        assertNull(result.maximumCoefficient)
        assertTrue(result.warnings.any { it.contains("régime de contribution logement", ignoreCase = true) })
    }

    @Test
    fun `invalid eligible rate sums are rejected`() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0, 0.0199, 1.1).forEach { rate ->
            val result = EmployerGeneralReductionRateContext2026V2.resolve(
                listOf(record(rateSum = rate)),
                month
            )

            assertFalse(result.reliable)
            assertNull(result.tDelta)
        }
    }

    @Test
    fun `wrong year never reuses the 2026 coefficient`() {
        val result = EmployerGeneralReductionRateContext2026V2.resolve(
            listOf(record()),
            YearMonth.of(2027, 1)
        )

        assertFalse(result.reliable)
        assertNull(result.maximumCoefficient)
        assertTrue(result.warnings.any { it.contains("2027") })
    }
}
