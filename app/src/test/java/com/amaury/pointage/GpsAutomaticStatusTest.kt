package com.amaury.pointage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GpsAutomaticStatusTest {
    @Test
    fun `system location off cannot be reported as automatic gps active`() {
        assertEquals(
            "Localisation de l’appareil désactivée — active-la dans les réglages",
            gpsAutomaticStatus(
                enabled = true,
                precisePermission = true,
                backgroundPermission = true,
                systemLocationEnabled = false,
                registrationCurrent = true
            )
        )
    }

    @Test
    fun `permissions do not imply registered geofences`() {
        assertEquals(
            "Vérification des zones GPS en cours…",
            gpsAutomaticStatus(true, true, true, true, false)
        )
        assertEquals(
            "Pointage GPS automatique actif",
            gpsAutomaticStatus(true, true, true, true, true)
        )
    }

    @Test
    fun `registration error stays explicit`() {
        assertEquals(
            "Service GPS indisponible",
            gpsAutomaticStatus(true, true, true, true, false, "Service GPS indisponible")
        )
    }

    @Test
    fun `system settings shortcut only appears for master location switch`() {
        assertTrue(shouldOpenSystemLocationSettings(true, true, true, false))
        assertFalse(shouldOpenSystemLocationSettings(false, true, true, false))
        assertFalse(shouldOpenSystemLocationSettings(true, false, true, false))
        assertFalse(shouldOpenSystemLocationSettings(true, true, false, false))
        assertFalse(shouldOpenSystemLocationSettings(true, true, true, true))
    }
}
