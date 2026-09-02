package com.amaury.pointage.core.rights

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkRightsEngineTest {
    @Test
    fun `sans regle aucun droit nest invente`() {
        val input = WorkRightsInput(
            events = emptyList(),
            firstEntryMs = null,
            lastExitMs = null,
            workedMs = 0L,
            pausedMs = 0L,
            presenceMs = 0L,
            warnings = emptyList(),
            coherenceMessages = emptyList()
        )

        assertTrue(WorkRightsEngine.evaluate(input, emptyList()).isEmpty())
    }

    @Test
    fun `chaque droit conserve sa source et son explication`() {
        val source = RuleSource(
            id = "regle-test-nationale",
            type = RuleSourceType.LAW,
            label = "Règle nationale de test"
        )
        val rule = WorkRightRule {
            listOf(
                DerivedWorkRight(
                    type = WorkRightType.BREAK,
                    amountMs = 20 * 60_000L,
                    source = source,
                    explanation = "Condition de test satisfaite"
                )
            )
        }
        val input = WorkRightsInput(
            events = emptyList(),
            firstEntryMs = null,
            lastExitMs = null,
            workedMs = 6 * 60 * 60_000L,
            pausedMs = 0L,
            presenceMs = 0L,
            warnings = emptyList(),
            coherenceMessages = emptyList()
        )

        val right = WorkRightsEngine.evaluate(input, listOf(rule)).single()
        assertEquals(source, right.source)
        assertEquals("Condition de test satisfaite", right.explanation)
    }
}
