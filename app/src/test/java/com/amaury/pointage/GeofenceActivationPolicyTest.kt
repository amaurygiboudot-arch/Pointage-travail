package com.amaury.pointage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeofenceActivationPolicyTest {
    @Test
    fun `une desactivation explicite interdit toute inscription GPS`() {
        assertFalse(shouldRegisterAutomaticGps(enabled = false))
        assertTrue(shouldRegisterAutomaticGps(enabled = true))
    }

    @Test
    fun `une desactivation explicite interdit toute restauration de zone`() {
        assertFalse(
            shouldRecoverAutomaticGpsZones(
                enabled = false,
                currentZoneCount = 0,
                currentAddresses = setOf("12 rue des lilas"),
                backupAddresses = setOf("12 rue des lilas")
            )
        )
    }

    @Test
    fun `la restauration exige le GPS actif une liste vide et les memes adresses`() {
        val addresses = setOf("12 rue des lilas", "4 avenue du port")

        assertTrue(shouldRecoverAutomaticGpsZones(true, 0, addresses, addresses))
        assertFalse(shouldRecoverAutomaticGpsZones(true, 1, addresses, addresses))
        assertFalse(shouldRecoverAutomaticGpsZones(true, 0, emptySet(), emptySet()))
        assertFalse(shouldRecoverAutomaticGpsZones(true, 0, addresses, setOf("autre adresse")))
    }
}
