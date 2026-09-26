package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.CompanyPremiumResolverV2
import com.amaury.pointage.v2.engine.ConventionSeniorityPremiumV2
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfirmedCashGrossComponentsBridgeV2Test {
    private val period = YearMonth.of(2026, 9)

    @Test
    fun confirmedZeroSourcesProduceReliableZero() {
        val result = ConfirmedCashGrossComponentsBridgeV2.assemble(
            period = period,
            seniority = seniority(amount = 0.0),
            companyPremiums = premiums(emptyList(), reliable = true),
            coverage = coverage(confirmed = true, source = "user-confirmed")
        )

        assertTrue(result.exhaustive)
        assertTrue(result.sourceId.isNotBlank())
        assertEquals(1, result.components.size)
        assertEquals(0.0, result.components.single().amount, 0.0)
    }
    @Test
    fun confirmedCompanyPremiumsKeepStableIdsAndAmounts() {
        val result = ConfirmedCashGrossComponentsBridgeV2.assemble(
            period = period,
            seniority = seniority(amount = 24.0),
            companyPremiums = premiums(
                listOf(
                    CompanyPremiumResolverV2.Applied("team", "Équipe", 100.0),
                    CompanyPremiumResolverV2.Applied("tool", "Outillage", 15.0)
                ),
                reliable = true
            ),
            coverage = coverage(confirmed = true, source = "bulletin-confirmed")
        )

        assertTrue(result.exhaustive)
        assertEquals(
            listOf("seniority-premium", "company-premium:team", "company-premium:tool"),
            result.components.map { it.id }
        )
        assertEquals(139.0, result.components.sumOf { it.amount }, 0.0001)
    }

    @Test
    fun missingPremiumCoverageNeverBecomesImplicitZero() {
        val result = ConfirmedCashGrossComponentsBridgeV2.assemble(
            period = period,
            seniority = seniority(amount = 0.0),
            companyPremiums = premiums(emptyList(), reliable = false),
            coverage = coverage(confirmed = false, source = null)
        )

        assertFalse(result.exhaustive)
        assertTrue(result.sourceId.isBlank())
        assertEquals(listOf("seniority-premium"), result.components.map { it.id })
    }

    @Test
    fun unknownSeniorityBlocksWholeFixedPackage() {
        val result = ConfirmedCashGrossComponentsBridgeV2.assemble(
            period = period,
            seniority = seniority(amount = null, reliable = false),
            companyPremiums = premiums(emptyList(), reliable = true),
            coverage = coverage(confirmed = true, source = "user-confirmed")
        )

        assertFalse(result.exhaustive)
        assertTrue(result.sourceId.isBlank())
        assertTrue(result.components.isEmpty())
    }

    private fun seniority(
        amount: Double?,
        reliable: Boolean = true
    ) = ConventionSeniorityPremiumV2.Result(
        applicable = amount != null,
        reliable = reliable,
        selectedRule = null,
        stepYears = null,
        rate = if (amount != null) 0.024 else null,
        monthlyAmount = amount,
        warnings = if (reliable) emptyList() else listOf("seniority-unconfirmed")
    )

    private fun premiums(
        applied: List<CompanyPremiumResolverV2.Applied>,
        reliable: Boolean
    ) = CompanyPremiumResolverV2.Snapshot(
        applied = applied,
        totalGross = applied.sumOf { it.grossAmount },
        reliable = reliable,
        warnings = if (reliable) emptyList() else listOf("premiums-unconfirmed")
    )

    private fun coverage(
        confirmed: Boolean,
        source: String?
    ) = CompanyPremiumStoreV2.MonthCoverageSnapshot(
        confirmed = confirmed,
        source = source,
        storageReliable = true,
        warnings = if (confirmed) emptyList() else listOf("coverage-unconfirmed")
    )
}
