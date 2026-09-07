package com.amaury.pointage.v2.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConventionPublicHolidayPremiumV2Test {
    private fun snapshot(
        version: String,
        from: Long,
        to: Long?,
        multiplier: Double
    ) = ConventionPublicHolidayPremiumSnapshotV2(
        idcc = "0292",
        versionId = version,
        sourceId = "legifrance:KALI:$version",
        effectiveFromEpochDay = from,
        effectiveToEpochDay = to,
        rule = PublicHolidayPremiumRuleV2(multiplier),
        checkedAtMs = 1L
    )

    @Test
    fun historySelectsOnlyTheApplicableDatedHolidayRule() {
        val history = ConventionPublicHolidayPremiumHistoryV2(
            listOf(
                snapshot("old", 100L, 199L, 1.25),
                snapshot("current", 200L, null, 1.5)
            )
        )

        assertEquals(1.25, history.applicable("292", 150L)!!.rule.multiplier, 0.0001)
        assertEquals(1.5, history.applicable("0292", 250L)!!.rule.multiplier, 0.0001)
        assertNull(history.applicable("0292", 99L))
    }

    @Test(expected = IllegalArgumentException::class)
    fun overlappingHolidayRulesAreRejected() {
        ConventionPublicHolidayPremiumHistoryV2(
            listOf(
                snapshot("a", 100L, 220L, 1.25),
                snapshot("b", 200L, null, 1.5)
            )
        )
    }
}
