package com.amaury.pointage.v2

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProvidentLegalReanalysisGateV2Test {
    @Test
    fun `categorie et cotisations ne suffisent pas sans garanties`() {
        val outcome = ProvidentLegalReanalysisGateV2.resolve(
            category = ProvidentLegalReanalysisGateV2.Component(completed = true, saved = true),
            contribution = ProvidentLegalReanalysisGateV2.Component(completed = true, saved = true),
            benefits = ProvidentLegalReanalysisGateV2.Component(completed = false, saved = false)
        )

        assertFalse(outcome.completed)
        assertTrue(outcome.saved)
    }

    @Test
    fun `job prevoyance devient complet seulement quand les trois sous matieres le sont`() {
        val outcome = ProvidentLegalReanalysisGateV2.resolve(
            category = ProvidentLegalReanalysisGateV2.Component(completed = true, saved = true),
            contribution = ProvidentLegalReanalysisGateV2.Component(completed = true, saved = true),
            benefits = ProvidentLegalReanalysisGateV2.Component(completed = true, saved = false)
        )

        assertTrue(outcome.completed)
        assertTrue(outcome.saved)
    }
}
