package com.amaury.pointage.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V2ConventionRuleStoreFailClosedV2Test {
    private fun snapshot(
        version: String,
        from: Long,
        to: Long? = null,
        multiplier: Double = 1.25
    ): String = """
        {
          "idcc":"0292",
          "versionId":"$version",
          "sourceId":"legifrance:KALI:$version",
          "effectiveFromEpochDay":$from,
          "effectiveToEpochDay":${to ?: "null"},
          "checkedAtMs":1,
          "rules":{
            "weeklyRegularMinutes":2100,
            "overtimeTiers":[
              {"fromMinutes":2100,"toMinutes":2580,"multiplier":$multiplier},
              {"fromMinutes":2580,"toMinutes":null,"multiplier":1.5}
            ]
          }
        }
    """.trimIndent()

    @Test
    fun `liste vide explicite reste fiable`() {
        val result = V2ConventionRuleStore.decodeConfirmed("[]")

        assertTrue(result.reliable)
        assertTrue(result.snapshots.isEmpty())
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun `json illisible devient non fiable`() {
        val result = V2ConventionRuleStore.decodeConfirmed("{cassé")

        assertFalse(result.reliable)
        assertTrue(result.snapshots.isEmpty())
        assertTrue(result.warnings.isNotEmpty())
    }

    @Test
    fun `une entree invalide rend tout historique non fiable`() {
        val result = V2ConventionRuleStore.decodeConfirmed("[${snapshot("v1", 1000)},42]")

        assertFalse(result.reliable)
        assertEquals(1, result.snapshots.size)
    }

    @Test
    fun `versions dupliquees rendent historique non fiable`() {
        val result = V2ConventionRuleStore.decodeConfirmed(
            "[${snapshot("v1", 1000, 1100)},${snapshot("v1", 1101, 1200)}]"
        )

        assertFalse(result.reliable)
    }

    @Test
    fun `periodes qui se chevauchent rendent historique non fiable`() {
        val result = V2ConventionRuleStore.decodeConfirmed(
            "[${snapshot("v1", 1000, 1100)},${snapshot("v2", 1050, 1200)}]"
        )

        assertFalse(result.reliable)
    }

    @Test
    fun `historique non chevauchant reste fiable`() {
        val result = V2ConventionRuleStore.decodeConfirmed(
            "[${snapshot("v1", 1000, 1100)},${snapshot("v2", 1101, 1200)}]"
        )

        assertTrue(result.reliable)
        assertEquals(2, result.snapshots.size)
    }

    @Test
    fun `type de paliers invalide nest jamais transforme en liste vide fiable`() {
        val raw = snapshot("v1", 1000).replace(
            "\"overtimeTiers\":[\n      {\"fromMinutes\":2100,\"toMinutes\":2580,\"multiplier\":1.25},\n      {\"fromMinutes\":2580,\"toMinutes\":null,\"multiplier\":1.5}\n    ]",
            "\"overtimeTiers\":\"invalide\""
        )
        val result = V2ConventionRuleStore.decodeConfirmed("[$raw]")

        assertFalse(result.reliable)
    }

    @Test
    fun `multiplicateur impossible rend historique non fiable`() {
        val result = V2ConventionRuleStore.decodeConfirmed("[${snapshot("v1", 1000, multiplier = 0.5)}]")

        assertFalse(result.reliable)
    }
}
