package com.amaury.pointage.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V2ConventionWeekdayPremiumStoreFailClosedV2Test {
    private fun snapshot(
        version: String,
        from: Long,
        to: Long? = null,
        kind: String = "SATURDAY",
        multiplier: Double = 1.25
    ): String = """
        {
          "idcc":"0292",
          "versionId":"$version",
          "sourceId":"legifrance:KALI:$version",
          "effectiveFromEpochDay":$from,
          "effectiveToEpochDay":${to ?: "null"},
          "checkedAtMs":1,
          "kind":"$kind",
          "multiplier":$multiplier
        }
    """.trimIndent()

    @Test
    fun `liste vide explicite reste fiable`() {
        val result = V2ConventionWeekdayPremiumStore.decodeConfirmed("[]")

        assertTrue(result.reliable)
        assertTrue(result.snapshots.isEmpty())
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun `json illisible devient non fiable`() {
        val result = V2ConventionWeekdayPremiumStore.decodeConfirmed("{cassé")

        assertFalse(result.reliable)
        assertTrue(result.snapshots.isEmpty())
        assertTrue(result.warnings.isNotEmpty())
    }

    @Test
    fun `une entree invalide rend tout historique non fiable`() {
        val result = V2ConventionWeekdayPremiumStore.decodeConfirmed("[${snapshot("v1", 1000)},42]")

        assertFalse(result.reliable)
        assertEquals(1, result.snapshots.size)
    }

    @Test
    fun `versions dupliquees du meme jour rendent historique non fiable`() {
        val result = V2ConventionWeekdayPremiumStore.decodeConfirmed(
            "[${snapshot("v1", 1000, 1100)},${snapshot("v1", 1101, 1200)}]"
        )

        assertFalse(result.reliable)
    }

    @Test
    fun `periodes qui se chevauchent pour le meme jour rendent historique non fiable`() {
        val result = V2ConventionWeekdayPremiumStore.decodeConfirmed(
            "[${snapshot("v1", 1000, 1100)},${snapshot("v2", 1050, 1200)}]"
        )

        assertFalse(result.reliable)
    }

    @Test
    fun `samedi et dimanche peuvent avoir des periodes qui se recouvrent`() {
        val result = V2ConventionWeekdayPremiumStore.decodeConfirmed(
            "[${snapshot("sat", 1000, 1200)},${snapshot("sun", 1000, 1200, kind = "SUNDAY", multiplier = 1.5)}]"
        )

        assertTrue(result.reliable)
        assertEquals(2, result.snapshots.size)
    }

    @Test
    fun `historique non chevauchant du meme jour reste fiable`() {
        val result = V2ConventionWeekdayPremiumStore.decodeConfirmed(
            "[${snapshot("v1", 1000, 1100)},${snapshot("v2", 1101, 1200)}]"
        )

        assertTrue(result.reliable)
        assertEquals(2, result.snapshots.size)
    }

    @Test
    fun `jour inconnu rend historique non fiable`() {
        val result = V2ConventionWeekdayPremiumStore.decodeConfirmed(
            "[${snapshot("v1", 1000, kind = "MONDAY")}]"
        )

        assertFalse(result.reliable)
    }

    @Test
    fun `multiplicateur impossible rend historique non fiable`() {
        val result = V2ConventionWeekdayPremiumStore.decodeConfirmed(
            "[${snapshot("v1", 1000, multiplier = 0.5)}]"
        )

        assertFalse(result.reliable)
    }
}
