package com.amaury.pointage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GpsOverlappingWorkZoneContinuityV2Test {
    private fun zone(
        id: String,
        latitude: Double = 46.70000,
        longitude: Double = -1.40000,
        radius: Float = 120f,
        company: String? = "company-a",
        address: String = "Site A",
        pointType: String = "POSTE"
    ) = StoredGpsZone(
        id = id,
        latitude = latitude,
        longitude = longitude,
        radius = radius,
        address = address,
        companyId = company,
        companySlot = null,
        pointTypeToken = pointType,
        label = id,
        sourceJson = "{}"
    )

    @Test fun overlappingWorkZonesSameSiteAndEmployerCanCancelPendingExit() {
        val first = zone("atelier")
        val second = zone("portail", latitude = 46.70035)
        assertTrue(GpsOverlappingWorkZoneContinuityV2.isProvenSameWorksite(
            first, second, exitAtMs = 100_000L, entryAtMs = 160_000L
        ))
        assertTrue(GpsOverlappingWorkZoneContinuityV2.isProvenSameWorksite(
            second, first, exitAtMs = 100_000L, entryAtMs = 160_000L
        ))
    }

    @Test fun employerParkingOrOtherPlaceNeverImplyContinuity() {
        val old = zone("atelier")
        assertFalse(GpsOverlappingWorkZoneContinuityV2.isProvenSameWorksite(
            old, zone("portail", company = "company-b"), 100_000, 150_000
        ))
        assertFalse(GpsOverlappingWorkZoneContinuityV2.isProvenSameWorksite(
            old, zone("pause", pointType = "PAUSE"), 100_000, 150_000
        ))
        assertFalse(GpsOverlappingWorkZoneContinuityV2.isProvenSameWorksite(
            old, zone("autre", address = "Site B"), 100_000, 150_000
        ))
        assertFalse(GpsOverlappingWorkZoneContinuityV2.isProvenSameWorksite(
            old, zone("sans-lien", company = null), 100_000, 150_000
        ))
    }

    @Test fun distantOrLateReturnsCannotProveContinuity() {
        val old = zone("atelier")
        assertFalse(GpsOverlappingWorkZoneContinuityV2.isProvenSameWorksite(
            old, zone("depot-lointain", latitude = 46.72), 100_000, 150_000
        ))
        assertFalse(GpsOverlappingWorkZoneContinuityV2.isProvenSameWorksite(
            old, zone("portail"), 100_000, 220_001
        ))
        assertFalse(GpsOverlappingWorkZoneContinuityV2.isProvenSameWorksite(
            old, zone("portail"), 150_000, 100_000
        ))
        assertFalse(GpsOverlappingWorkZoneContinuityV2.isProvenSameWorksite(
            old, old, 100_000, 150_000
        ))
    }
}
