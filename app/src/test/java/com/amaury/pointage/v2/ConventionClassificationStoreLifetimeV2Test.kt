package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.ConventionClassificationV2
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConventionClassificationStoreLifetimeV2Test {
    @Test
    fun `classification sans entreprise reste vide`() {
        val classification = ConventionClassificationStoreV2.unavailableClassification()
        assertTrue(classification.isEmpty())
    }

    @Test
    fun `classification sans entreprise ne matche pas un coefficient precis`() {
        val classification = ConventionClassificationStoreV2.unavailableClassification()
        assertFalse(classification.matches(ConventionClassificationV2(coefficient = 700)))
    }
}
