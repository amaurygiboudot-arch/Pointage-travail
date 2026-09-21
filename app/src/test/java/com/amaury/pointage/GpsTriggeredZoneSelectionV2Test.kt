package com.amaury.pointage

import com.amaury.pointage.v2.engine.GpsPointTypeV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GpsTriggeredZoneSelectionV2Test {
    @Test
    fun `une seule zone est selectionnee`() {
        val result = GpsTriggeredZoneSelectionV2.select(
            listOf(candidate("zone-a"))
        )

        assertEquals(
            GpsTriggeredZoneSelectionV2.Result.Selected("zone-a"),
            result
        )
    }

    @Test
    fun `zones equivalentes utilisent un choix deterministe`() {
        val result = GpsTriggeredZoneSelectionV2.select(
            listOf(
                candidate("zone-z"),
                candidate("zone-a")
            )
        )

        assertEquals(
            GpsTriggeredZoneSelectionV2.Result.Selected("zone-a"),
            result
        )
    }

    @Test
    fun `employeurs differents bloquent le pointage automatique`() {
        val result = GpsTriggeredZoneSelectionV2.select(
            listOf(
                candidate("zone-a", employerKey = "company:a"),
                candidate("zone-b", employerKey = "company:b")
            )
        )

        assertTrue(result is GpsTriggeredZoneSelectionV2.Result.Blocked)
    }

    @Test
    fun `types de point differents bloquent le pointage automatique`() {
        val result = GpsTriggeredZoneSelectionV2.select(
            listOf(
                candidate("zone-a", pointType = GpsPointTypeV2.POSTE),
                candidate("zone-b", pointType = GpsPointTypeV2.PARKING)
            )
        )

        assertTrue(result is GpsTriggeredZoneSelectionV2.Result.Blocked)
    }

    @Test
    fun `lieux differents bloquent le pointage automatique`() {
        val result = GpsTriggeredZoneSelectionV2.select(
            listOf(
                candidate("zone-a", placeKey = "address:a"),
                candidate("zone-b", placeKey = "address:b")
            )
        )

        assertTrue(result is GpsTriggeredZoneSelectionV2.Result.Blocked)
    }

    @Test
    fun `doublon du meme request id ne cree pas de fausse ambiguite`() {
        val result = GpsTriggeredZoneSelectionV2.select(
            listOf(
                candidate("zone-a"),
                candidate("zone-a")
            )
        )

        assertEquals(
            GpsTriggeredZoneSelectionV2.Result.Selected("zone-a"),
            result
        )
    }

    private fun candidate(
        id: String,
        employerKey: String = "company:a",
        pointType: GpsPointTypeV2 = GpsPointTypeV2.POSTE,
        placeKey: String = "address:site-a"
    ) = GpsTriggeredZoneSelectionV2.Candidate(
        zoneId = id,
        employerKey = employerKey,
        pointType = pointType,
        placeKey = placeKey
    )
}
