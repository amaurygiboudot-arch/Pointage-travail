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
    fun `seules les zones travail et candidates sont surveillees automatiquement`() {
        val stored = GpsZonesReadResult.Valid(
            listOf(
                zone("work", "POSTE"),
                zone("parking", "PARKING"),
                zone("pause", "PAUSE"),
                zone("other", "OTHER"),
                zone("candidate", null, smartCandidate = true)
            )
        )

        val plan = planStoredGeofenceRegistrationV2(true, true, true, stored)

        assertTrue(plan is StoredGeofencePlanV2.Register)
        assertEquals(
            listOf("work", "candidate"),
            (plan as StoredGeofencePlanV2.Register).zones.map { it.id }
        )
    }

    @Test
    fun `des zones contextuelles seules ne peuvent pas declencher le pointage automatique`() {
        val stored = GpsZonesReadResult.Valid(
            listOf(zone("parking", "PARKING"), zone("pause", "PAUSE"), zone("other", "OTHER"))
        )

        assertTrue(
            planStoredGeofenceRegistrationV2(true, true, true, stored) is
                StoredGeofencePlanV2.Remove
        )
    }

    @Test
    fun `l empreinte automatique ignore les changements de zones contextuelles`() {
        val work = zone("work", "POSTE")
        val parkingA = zone("parking", "PARKING", latitude = 46.7)
        val parkingB = zone("parking", "PARKING", latitude = 47.2)

        val first = storedGpsAutomaticFingerprintV2(
            true,
            GpsZonesReadResult.Valid(listOf(work, parkingA))
        )
        val second = storedGpsAutomaticFingerprintV2(
            true,
            GpsZonesReadResult.Valid(listOf(work, parkingB))
        )

        assertEquals(first, second)
    }

    @Test
    fun `l empreinte automatique change avec une zone de travail`() {
        val first = storedGpsAutomaticFingerprintV2(
            true,
            GpsZonesReadResult.Valid(listOf(zone("work", "POSTE", latitude = 46.7)))
        )
        val second = storedGpsAutomaticFingerprintV2(
            true,
            GpsZonesReadResult.Valid(listOf(zone("work", "POSTE", latitude = 47.2)))
        )

        assertTrue(first != second)
    }

    @Test
    fun `l empreinte canonique ne confond pas les separateurs saisis par l utilisateur`() {
        val first = storedGpsAutomaticFingerprintV2(
            true,
            GpsZonesReadResult.Valid(
                listOf(zone("work", address = "A|B", companyId = "C"))
            )
        )
        val second = storedGpsAutomaticFingerprintV2(
            true,
            GpsZonesReadResult.Valid(
                listOf(zone("work", address = "A", companyId = "B|C"))
            )
        )

        assertTrue(first != second)
    }

    @Test
    fun `un callback exige une inscription valide pour l empreinte courante`() {
        assertTrue(isCurrentStoredGeofenceRegistrationV2(true, "config-b", "config-b"))
        assertTrue(!isCurrentStoredGeofenceRegistrationV2(false, "config-b", "config-b"))
        assertTrue(!isCurrentStoredGeofenceRegistrationV2(true, null, "config-b"))
        assertTrue(!isCurrentStoredGeofenceRegistrationV2(true, "config-a", "config-b"))
    }

    private fun zone(
        id: String,
        pointType: String? = "POSTE",
        smartCandidate: Boolean = false,
        latitude: Double = 46.7,
        address: String = "Atelier $id",
        companyId: String? = null
    ): StoredGpsZone {
        val source = org.json.JSONObject()
            .put("id", id)
            .put("latitude", latitude)
            .put("longitude", -1.4)
            .put("radius", 150)
        if (pointType != null) source.put("pointType", pointType)
        if (smartCandidate) source.put("smartCandidate", true)
        return StoredGpsZone(
            id = id,
            latitude = latitude,
            longitude = -1.4,
            radius = 150f,
            address = address,
            companyId = companyId,
            companySlot = null,
            pointTypeToken = pointType,
            label = null,
            sourceJson = source.toString()
        )
    }
}
