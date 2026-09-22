package com.amaury.pointage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GpsStoredGeofencePlanV2Test {
    @Test
    fun `configuration desactivee retire toujours les anciennes zones`() {
        val plan = planStoredGeofenceRegistrationV2(
            enabled = false,
            hasHardware = true,
            hasPermissions = true,
            stored = GpsZonesReadResult.Valid(listOf(zone("work")))
        )

        assertTrue(plan is StoredGeofencePlanV2.Remove)
    }

    @Test
    fun `configuration absente corrompue ou vide retire les anciennes zones`() {
        val states = listOf(
            GpsZonesReadResult.Missing,
            GpsZonesReadResult.Corrupt("test"),
            GpsZonesReadResult.Valid(emptyList())
        )

        states.forEach { stored ->
            val plan = planStoredGeofenceRegistrationV2(
                enabled = true,
                hasHardware = true,
                hasPermissions = true,
                stored = stored
            )
            assertTrue(plan is StoredGeofencePlanV2.Remove)
        }
    }

    @Test
    fun `permission ou materiel absent retire les geofences au lieu de garder une ancienne config`() {
        val stored = GpsZonesReadResult.Valid(listOf(zone("work")))

        assertTrue(
            planStoredGeofenceRegistrationV2(true, false, true, stored) is
                StoredGeofencePlanV2.Remove
        )
        assertTrue(
            planStoredGeofenceRegistrationV2(true, true, false, stored) is
                StoredGeofencePlanV2.Remove
        )
    }

    @Test
    fun `depassement de limite retire les anciennes zones`() {
        val stored = GpsZonesReadResult.Valid((1..11).map { zone("work-$it") })

        assertTrue(
            planStoredGeofenceRegistrationV2(true, true, true, stored) is
                StoredGeofencePlanV2.Remove
        )
    }

    @Test
    fun `configuration valide produit exactement les zones canoniques`() {
        val stored = GpsZonesReadResult.Valid(listOf(zone("work-a"), zone("work-b")))
        val plan = planStoredGeofenceRegistrationV2(true, true, true, stored)

        assertTrue(plan is StoredGeofencePlanV2.Register)
        assertEquals(
            listOf("work-a", "work-b"),
            (plan as StoredGeofencePlanV2.Register).zones.map { it.id }
        )
    }

    @Test
    fun `un callback exige une inscription valide pour l empreinte courante`() {
        assertTrue(isCurrentStoredGeofenceRegistrationV2(true, "config-b", "config-b"))
        assertTrue(!isCurrentStoredGeofenceRegistrationV2(false, "config-b", "config-b"))
        assertTrue(!isCurrentStoredGeofenceRegistrationV2(true, null, "config-b"))
        assertTrue(!isCurrentStoredGeofenceRegistrationV2(true, "config-a", "config-b"))
    }

    private fun zone(id: String) = StoredGpsZone(
        id = id,
        latitude = 46.7,
        longitude = -1.4,
        radius = 150f,
        address = "Atelier $id",
        companyId = null,
        companySlot = null,
        pointTypeToken = "POSTE",
        label = null,
        sourceJson = "{}"
    )
}
