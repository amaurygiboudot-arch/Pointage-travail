package com.amaury.pointage.core.rights

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RightRuleResolverTest {
    @Test
    fun `la priorite explicite vient avant la valeur`() {
        val legal = candidate("legal", RightSource.LAW, 10, 20)
        val agreement = candidate("accord", RightSource.COMPANY_AGREEMENT, 20, 30)

        val result = RightRuleResolver.resolve(listOf(legal, agreement))

        assertEquals("accord", result.selected?.ruleId)
    }

    @Test
    fun `a priorite egale la valeur minimale la plus favorable gagne`() {
        val first = candidate("branche-a", RightSource.COLLECTIVE_AGREEMENT, 20, 20)
        val second = candidate("branche-b", RightSource.COLLECTIVE_AGREEMENT, 20, 30)

        val result = RightRuleResolver.resolve(listOf(first, second))

        assertEquals("branche-b", result.selected?.ruleId)
    }

    @Test
    fun `aucune regle ne fabrique un droit`() {
        assertNull(RightRuleResolver.resolve(emptyList()).selected)
    }

    private fun candidate(id: String, source: RightSource, priority: Int, minutes: Long) =
        RightRuleCandidate(
            ruleId = id,
            source = source,
            priority = priority,
            minimumValueMs = minutes * 60_000L,
            explanation = "Règle générique française"
        )
}
