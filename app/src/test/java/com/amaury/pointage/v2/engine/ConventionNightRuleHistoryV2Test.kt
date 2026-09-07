package com.amaury.pointage.v2.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class ConventionNightRuleHistoryV2Test {
    @Test
    fun `retrouve uniquement la version applicable a la date`() {
        val first = snapshot(
            versionId = "night-v1",
            from = LocalDate.of(2025, 1, 1),
            to = LocalDate.of(2025, 12, 31),
            multiplier = 1.10
        )
        val second = snapshot(
            versionId = "night-v2",
            from = LocalDate.of(2026, 1, 1),
            to = null,
            multiplier = 1.25
        )
        val history = ConventionNightRuleHistoryV2(listOf(first, second))

        assertEquals(
            "night-v1",
            history.applicable("292", LocalDate.of(2025, 6, 1).toEpochDay())?.versionId
        )
        assertEquals(
            "night-v2",
            history.applicable("0292", LocalDate.of(2026, 9, 30).toEpochDay())?.versionId
        )
        assertNull(history.applicable("0292", LocalDate.of(2024, 12, 31).toEpochDay()))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `refuse deux regles de nuit qui se chevauchent`() {
        ConventionNightRuleHistoryV2(
            listOf(
                snapshot(
                    versionId = "night-v1",
                    from = LocalDate.of(2026, 1, 1),
                    to = LocalDate.of(2026, 12, 31),
                    multiplier = 1.10
                ),
                snapshot(
                    versionId = "night-v2",
                    from = LocalDate.of(2026, 6, 1),
                    to = null,
                    multiplier = 1.25
                )
            )
        )
    }

    private fun snapshot(
        versionId: String,
        from: LocalDate,
        to: LocalDate?,
        multiplier: Double
    ) = ConventionNightRuleSnapshotV2(
        idcc = "0292",
        versionId = versionId,
        sourceId = "legifrance:KALI:$versionId",
        effectiveFromEpochDay = from.toEpochDay(),
        effectiveToEpochDay = to?.toEpochDay(),
        rule = NightPremiumRuleV2(21 * 60, 6 * 60, multiplier),
        checkedAtMs = 1L
    )
}
