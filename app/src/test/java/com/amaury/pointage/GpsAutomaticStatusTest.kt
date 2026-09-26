package com.amaury.pointage

import org.junit.Assert.assertEquals
import org.junit.Test

class GpsAutomaticStatusTest {
    @Test
    fun `system location off cannot be reported as automatic gps active`() {
        assertEquals(
            "Localisation de l’appareil désactivée — active-la dans les réglages",
            gpsAutomaticStatus(true, true, true, false, true)
        )
    }

    @Test
    fun `authorization does not imply registered geofences`() {
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
    fun `preference and permission remain distinct from system service`() {
        assertEquals("GPS automatique désactivé", gpsAutomaticStatus(false, true, true, false, true))
        assertEquals("Localisation précise à autoriser", gpsAutomaticStatus(true, false, false, false, false))
        assertEquals("Autorise la localisation tout le temps", gpsAutomaticStatus(true, true, false, false, false))
    }

    @Test
    fun `failed registration remains explicit until another verification`() {
        assertEquals(
            "Service GPS indisponible",
            gpsAutomaticStatus(true, true, true, true, false, "Service GPS indisponible")
        )
    }

    @Test
    fun `system settings action requires the displayed status to be system location off`() {
        assertEquals(false, shouldOpenSystemLocationSettings(true, false, false, false))
        assertEquals(false, shouldOpenSystemLocationSettings(true, true, false, false))
        assertEquals(false, shouldOpenSystemLocationSettings(false, true, true, false))
        assertEquals(true, shouldOpenSystemLocationSettings(true, true, true, false))
    }
}
