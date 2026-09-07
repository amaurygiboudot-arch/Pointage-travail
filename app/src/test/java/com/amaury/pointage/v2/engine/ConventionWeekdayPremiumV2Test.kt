package com.amaury.pointage.v2.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class ConventionWeekdayPremiumV2Test {
    private fun snapshot(
        kind: WeekdayPremiumKindV2,
        version: String,
        from: LocalDate,
        to: LocalDate?,
        multiplier: Double
    ) = ConventionWeekdayPremiumSnapshotV2(
        idcc = "0292",
        versionId = version,
        sourceId = "legifrance:KALI:$version",
        effectiveFromEpochDay = from.toEpochDay(),
        effectiveToEpochDay = to?.toEpochDay(),
        rule = WeekdayPremiumRuleV2(kind, multiplier),
        checkedAtMs = 1L
    )

    @Test
    fun `samedi et dimanche restent independants`() {
        val history = ConventionWeekdayPremiumHistoryV2(
            listOf(
                snapshot(WeekdayPremiumKindV2.SATURDAY, "sat", LocalDate.of(2026, 1, 1), null, 1.25),
                snapshot(WeekdayPremiumKindV2.SUNDAY, "sun", LocalDate.of(2026, 1, 1), null, 2.0)
            )
        )
        val date = LocalDate.of(2026, 9, 30).toEpochDay()
        assertEquals(1.25, history.applicable("292", WeekdayPremiumKindV2.SATURDAY, date)!!.rule.multiplier, 0.0001)
        assertEquals(2.0, history.applicable("0292", WeekdayPremiumKindV2.SUNDAY, date)!!.rule.multiplier, 0.0001)
    }

    @Test
    fun `historique choisit la version couvrant la date`() {
        val history = ConventionWeekdayPremiumHistoryV2(
            listOf(
                snapshot(
                    WeekdayPremiumKindV2.SUNDAY,
                    "old",
                    LocalDate.of(2025, 1, 1),
                    LocalDate.of(2025, 12, 31),
                    1.5
                ),
                snapshot(
                    WeekdayPremiumKindV2.SUNDAY,
                    "new",
                    LocalDate.of(2026, 1, 1),
                    null,
                    2.0
                )
            )
        )
        assertEquals(
            "old",
            history.applicable("0292", WeekdayPremiumKindV2.SUNDAY, LocalDate.of(2025, 6, 1).toEpochDay())!!.versionId
        )
        assertEquals(
            "new",
            history.applicable("0292", WeekdayPremiumKindV2.SUNDAY, LocalDate.of(2026, 9, 30).toEpochDay())!!.versionId
        )
        assertNull(history.applicable("0292", WeekdayPremiumKindV2.SATURDAY, LocalDate.of(2026, 9, 30).toEpochDay()))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `deux versions du meme jour ne peuvent pas se chevaucher`() {
        ConventionWeekdayPremiumHistoryV2(
            listOf(
                snapshot(
                    WeekdayPremiumKindV2.SATURDAY,
                    "a",
                    LocalDate.of(2026, 1, 1),
                    LocalDate.of(2026, 6, 30),
                    1.25
                ),
                snapshot(
                    WeekdayPremiumKindV2.SATURDAY,
                    "b",
                    LocalDate.of(2026, 6, 1),
                    null,
                    1.5
                )
            )
        )
    }
}
