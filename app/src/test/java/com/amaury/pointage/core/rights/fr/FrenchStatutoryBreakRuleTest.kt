package com.amaury.pointage.core.rights.fr

import com.amaury.pointage.core.rights.RuleSourceType
import com.amaury.pointage.core.rights.WorkRightsInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrenchStatutoryBreakRuleTest {
    private val hour = 60L * 60L * 1000L

    @Test
    fun `moins de six heures ne cree aucun droit legal de pause`() {
        val rights = FrenchStatutoryBreakRule.evaluate(input(5 * hour + 59 * 60_000L))
        assertTrue(rights.isEmpty())
    }

    @Test
    fun `six heures ouvre le droit a vingt minutes consecutives`() {
        val right = FrenchStatutoryBreakRule.evaluate(input(6 * hour)).single()

        assertEquals(20 * 60_000L, right.amountMs)
        assertEquals(RuleSourceType.LAW, right.source.type)
        assertEquals("code-travail-L3121-16", right.source.id)
    }

    private fun input(workedMs: Long) = WorkRightsInput(
        events = emptyList(),
        firstEntryMs = null,
        lastExitMs = null,
        workedMs = workedMs,
        pausedMs = 0L,
        presenceMs = 0L,
        warnings = emptyList(),
        coherenceMessages = emptyList()
    )
}
