package com.amaury.pointage.v2.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConventionRuleHistoryV2Test {
    private fun snapshot(
        idcc: String,
        version: String,
        from: Long,
        to: Long?,
        regularMinutes: Int
    ) = ConventionRuleSnapshotV2(
        idcc = idcc,
        versionId = version,
        sourceId = "legifrance:$idcc:$version",
        effectiveFromEpochDay = from,
        effectiveToEpochDay = to,
        rules = PayrollRulesV2(weeklyRegularMinutes = regularMinutes),
        checkedAtMs = 1L
    )

    @Test
    fun periodBeforeAndAfterEffectiveDateSelectsCorrectVersion() {
        val history = ConventionRuleHistoryV2(
            listOf(
                snapshot("0292", "v1", 100L, 199L, 35 * 60),
                snapshot("0292", "v2", 200L, null, 36 * 60)
            )
        )

        assertEquals("v1", history.applicable("292", 150L)?.versionId)
        assertEquals("v2", history.applicable("0292", 250L)?.versionId)
    }

    @Test
    fun missingHistoricalRuleReturnsUnknownInsteadOfLatestVersion() {
        val history = ConventionRuleHistoryV2(
            listOf(snapshot("0292", "v2", 200L, null, 36 * 60))
        )

        assertNull(history.applicable("0292", 150L))
    }

    @Test
    fun wrongIdccNeverFallsBackToAnotherConvention() {
        val history = ConventionRuleHistoryV2(
            listOf(snapshot("0292", "plasturgie", 100L, null, 35 * 60))
        )

        assertNull(history.applicable("1979", 150L))
        assertNull(history.applicable(null, 150L))
        assertNull(history.applicable("", 150L))
    }

    @Test(expected = IllegalArgumentException::class)
    fun openEndedVersionCannotBeFollowedByAnotherVersion() {
        ConventionRuleHistoryV2(
            listOf(
                snapshot("0292", "v1", 100L, null, 35 * 60),
                snapshot("0292", "v2", 200L, null, 36 * 60)
            )
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun overlappingHistoricalVersionsAreRejected() {
        ConventionRuleHistoryV2(
            listOf(
                snapshot("0292", "v1", 100L, 220L, 35 * 60),
                snapshot("0292", "v2", 200L, null, 36 * 60)
            )
        )
    }
}
