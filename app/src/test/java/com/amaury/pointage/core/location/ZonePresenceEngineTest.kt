package com.amaury.pointage.core.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ZonePresenceEngineTest {
    private val minute = 60_000L

    @Test
    fun `entree puis sortie stables produisent un fait ferme`() {
        val zone = circle("paris-site", 48.8566, 2.3522, 120.0)
        val result = ZonePresenceEngine.evaluate(
            zones = listOf(zone),
            observations = listOf(
                observation(8 * 60 * minute, 48.8580, 2.3522),
                observation(8 * 60 * minute + 5 * minute, 48.8566, 2.3522),
                observation(8 * 60 * minute + 6 * minute, 48.8566, 2.3522),
                observation(16 * 60 * minute, 48.8566, 2.3522),
                observation(16 * 60 * minute + 5 * minute, 48.8580, 2.3522),
                observation(16 * 60 * minute + 6 * minute, 48.8580, 2.3522)
            )
        )

        assertEquals(1, result.size)
        assertEquals(8 * 60 * minute + 5 * minute, result.single().enteredAtMs)
        assertEquals(16 * 60 * minute + 5 * minute, result.single().exitedAtMs)
    }

    @Test
    fun `un rebond unique pres de la frontiere ne ferme pas la presence`() {
        val zone = circle("paris-site", 48.8566, 2.3522, 120.0)
        val result = ZonePresenceEngine.evaluate(
            zones = listOf(zone),
            observations = listOf(
                observation(8 * 60 * minute, 48.8566, 2.3522),
                observation(12 * 60 * minute, 48.8580, 2.3522),
                observation(12 * 60 * minute + minute, 48.8566, 2.3522),
                observation(16 * 60 * minute, 48.8566, 2.3522)
            )
        )

        assertEquals(1, result.size)
        assertEquals(8 * 60 * minute, result.single().enteredAtMs)
        assertNull(result.single().exitedAtMs)
    }

    @Test
    fun `une transition confirmee conserve la premiere heure observee`() {
        val zone = circle("paris-bureau", 48.8566, 2.3522, 80.0)
        val result = ZonePresenceEngine.evaluate(
            zones = listOf(zone),
            observations = listOf(
                observation(8 * 60 * minute, 48.8580, 2.3522),
                observation(9 * 60 * minute, 48.8566, 2.3522),
                observation(9 * 60 * minute + minute, 48.8566, 2.3522)
            )
        )

        assertEquals(1, result.size)
        assertEquals(9 * 60 * minute, result.single().enteredAtMs)
        assertNull(result.single().exitedAtMs)
    }

    @Test
    fun `une presence encore active reste ouverte`() {
        val zone = circle("paris-bureau", 48.8566, 2.3522, 120.0)
        val result = ZonePresenceEngine.evaluate(
            zones = listOf(zone),
            observations = listOf(
                observation(9 * 60 * minute, 48.8566, 2.3522),
                observation(12 * 60 * minute, 48.8566, 2.3522)
            )
        )

        assertEquals(1, result.size)
        assertNull(result.single().exitedAtMs)
    }

    @Test
    fun `les zones imbriquees restent des faits distincts`() {
        val site = circle("paris-site", 48.8566, 2.3522, 300.0)
        val office = circle("paris-bureau", 48.8566, 2.3522, 50.0)
        val result = ZonePresenceEngine.evaluate(
            zones = listOf(site, office),
            observations = listOf(
                observation(8 * 60 * minute, 48.8566, 2.3522),
                observation(10 * 60 * minute, 48.8584, 2.3522),
                observation(10 * 60 * minute + minute, 48.8584, 2.3522),
                observation(12 * 60 * minute, 48.8610, 2.3522),
                observation(12 * 60 * minute + minute, 48.8610, 2.3522)
            )
        )

        val officeFact = result.single { it.zoneId == "paris-bureau" }
        val siteFact = result.single { it.zoneId == "paris-site" }
        assertEquals(8 * 60 * minute, officeFact.enteredAtMs)
        assertEquals(10 * 60 * minute, officeFact.exitedAtMs)
        assertEquals(8 * 60 * minute, siteFact.enteredAtMs)
        assertEquals(12 * 60 * minute, siteFact.exitedAtMs)
    }

    @Test
    fun `la presence gps ne cree aucun evenement de travail`() {
        val zone = circle("paris-site", 48.8566, 2.3522, 120.0)
        val result = ZonePresenceEngine.evaluate(
            zones = listOf(zone),
            observations = listOf(observation(8 * 60 * minute, 48.8566, 2.3522))
        )

        assertEquals(1, result.size)
        assertEquals("paris-site", result.single().zoneId)
    }

    @Test
    fun `la politique peut exiger trois confirmations`() {
        val zone = circle("paris-site", 48.8566, 2.3522, 120.0)
        val result = ZonePresenceEngine.evaluate(
            zones = listOf(zone),
            observations = listOf(
                observation(8 * 60 * minute, 48.8580, 2.3522),
                observation(9 * 60 * minute, 48.8566, 2.3522),
                observation(9 * 60 * minute + minute, 48.8566, 2.3522),
                observation(9 * 60 * minute + 2 * minute, 48.8566, 2.3522)
            ),
            policy = ZonePresencePolicy(confirmationSamples = 3)
        )

        assertEquals(1, result.size)
        assertEquals(9 * 60 * minute, result.single().enteredAtMs)
    }

    private fun circle(id: String, lat: Double, lon: Double, radius: Double) = WorkplaceZone(
        id = id,
        name = id,
        kind = WorkplaceZoneKind.SITE,
        geometry = WorkplaceGeometry.Circle(GeoPoint(lat, lon), radius)
    )

    private fun observation(at: Long, lat: Double, lon: Double) =
        LocationObservation(at, GeoPoint(lat, lon), accuracyMeters = 10f)
}
