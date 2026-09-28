package com.amaury.pointage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ObjectiveDeliveryGameEngineTest {
    private fun play(
        type: ObjectiveCompanyType,
        seed: Long,
        vararg decisions: ObjectiveDecision
    ): ObjectiveDeliveryState {
        var state = ObjectiveDeliveryGameEngine.newCampaign(type, "test-campaign", seed)
        decisions.forEach { state = ObjectiveDeliveryGameEngine.apply(state, it) }
        return state
    }

    @Test
    fun `complete discovery and balanced offer can unlock chapter two`() {
        val result = play(
            ObjectiveCompanyType.WORKSHOP,
            42L,
            ObjectiveDecision.ANSWER_NOW,
            ObjectiveDecision.FULL_DISCOVERY,
            ObjectiveDecision.VALUE_OFFER
        )

        assertEquals(ObjectiveOutcome.WON, result.outcome)
        assertEquals(2, result.unlockedChapter)
        assertTrue(result.marginAmount > 0)
    }

    @Test
    fun `poor contact and incomplete discovery cannot unlock chapter two`() {
        val result = play(
            ObjectiveCompanyType.RETAIL,
            42L,
            ObjectiveDecision.LEAVE_WAITING,
            ObjectiveDecision.STANDARD_SOLUTION,
            ObjectiveDecision.FAST_PREMIUM
        )

        assertEquals(ObjectiveOutcome.LOST, result.outcome)
        assertEquals(1, result.unlockedChapter)
    }

    @Test
    fun `same seed and same decisions produce same result`() {
        val first = play(
            ObjectiveCompanyType.SERVICES,
            9001L,
            ObjectiveDecision.ASK_INFORMATION,
            ObjectiveDecision.SITE_VISIT,
            ObjectiveDecision.DISCOUNT_OFFER
        )
        val second = play(
            ObjectiveCompanyType.SERVICES,
            9001L,
            ObjectiveDecision.ASK_INFORMATION,
            ObjectiveDecision.SITE_VISIT,
            ObjectiveDecision.DISCOUNT_OFFER
        )

        assertEquals(first, second)
    }

    @Test
    fun `three company models use different scenarios`() {
        val workshop = ObjectiveDeliveryGameEngine.scenario(ObjectiveCompanyType.WORKSHOP)
        val retail = ObjectiveDeliveryGameEngine.scenario(ObjectiveCompanyType.RETAIL)
        val services = ObjectiveDeliveryGameEngine.scenario(ObjectiveCompanyType.SERVICES)

        assertNotEquals(workshop.referencePrice, retail.referencePrice)
        assertNotEquals(retail.desiredDays, services.desiredDays)
        assertNotEquals(workshop.clientTitle, services.clientTitle)
    }

    @Test
    fun `risk summary exposes incomplete need before quote`() {
        val state = ObjectiveDeliveryGameEngine.newCampaign(
            ObjectiveCompanyType.WORKSHOP,
            "risk-test",
            1L
        )

        assertTrue(
            ObjectiveDeliveryGameEngine.riskSummary(state)
                .any { it.contains("incomplet", ignoreCase = true) }
        )
    }
    @Test
    fun `lost campaign can retry and still unlock chapter two`() {
        val lost = play(
            ObjectiveCompanyType.RETAIL,
            42L,
            ObjectiveDecision.LEAVE_WAITING,
            ObjectiveDecision.STANDARD_SOLUTION,
            ObjectiveDecision.FAST_PREMIUM
        )

        var retry = ObjectiveDeliveryGameEngine.retryChapterOne(lost)
        retry = ObjectiveDeliveryGameEngine.apply(retry, ObjectiveDecision.ANSWER_NOW)
        retry = ObjectiveDeliveryGameEngine.apply(retry, ObjectiveDecision.FULL_DISCOVERY)
        retry = ObjectiveDeliveryGameEngine.apply(retry, ObjectiveDecision.VALUE_OFFER)

        assertEquals(ObjectiveOutcome.WON, retry.outcome)
        assertEquals(2, retry.unlockedChapter)
        assertTrue(retry.revision > lost.revision)
    }

}
