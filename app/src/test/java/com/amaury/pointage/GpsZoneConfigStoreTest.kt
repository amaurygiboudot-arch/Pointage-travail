package com.amaury.pointage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GpsZoneConfigStoreTest {
    @Test
    fun `un tableau vide reste une configuration valide et vide`() {
        val result = parsePersistedGpsZones("[]")

        assertTrue(result is GpsZonesReadResult.Valid)
        assertEquals(0, (result as GpsZonesReadResult.Valid).zones.size)
    }

    @Test
    fun `un json illisible est corrompu et jamais transforme en liste vide`() {
        val result = parsePersistedGpsZones("{pas-un-tableau}")

        assertTrue(result is GpsZonesReadResult.Corrupt)
    }

    @Test
    fun `une seule zone invalide rend toute la configuration corrompue`() {
        val result = parsePersistedGpsZones(
            """[
                {"id":"workplace_1","latitude":46.7,"longitude":-1.4,"radius":150},
                {"id":"workplace_2","latitude":"inconnue","longitude":-1.5,"radius":150}
            ]""".trimIndent()
        )

        assertTrue(result is GpsZonesReadResult.Corrupt)
    }

    @Test
    fun `deux request id identiques sont refuses`() {
        val result = parsePersistedGpsZones(
            """[
                {"id":"workplace_1","latitude":46.7,"longitude":-1.4,"radius":150},
                {"id":"workplace_1","latitude":46.8,"longitude":-1.5,"radius":150}
            ]""".trimIndent()
        )

        assertTrue(result is GpsZonesReadResult.Corrupt)
    }

    @Test
    fun `une zone valide conserve ses metadonnees sans en inventer`() {
        val result = parsePersistedGpsZones(
            """[
                {
                    "id":"workplace_1",
                    "latitude":46.7,
                    "longitude":-1.4,
                    "radius":175,
                    "address":"12 rue des Lilas",
                    "companySlot":2,
                    "pointType":"POSTE",
                    "label":"Atelier"
                }
            ]""".trimIndent()
        )

        assertTrue(result is GpsZonesReadResult.Valid)
        val zone = (result as GpsZonesReadResult.Valid).zones.single()
        assertEquals("workplace_1", zone.id)
        assertEquals(46.7, zone.latitude, 0.0)
        assertEquals(-1.4, zone.longitude, 0.0)
        assertEquals(175f, zone.radius)
        assertEquals("12 rue des Lilas", zone.address)
        assertEquals(2, zone.companySlot)
        assertEquals("POSTE", zone.pointTypeToken)
        assertEquals("Atelier", zone.label)
    }

    @Test
    fun `une ancienne zone sans rayon garde le rayon historique de compatibilite`() {
        val result = parsePersistedGpsZones(
            """[{"id":"workplace_1","latitude":46.7,"longitude":-1.4}]"""
        )

        assertTrue(result is GpsZonesReadResult.Valid)
        assertEquals(150f, (result as GpsZonesReadResult.Valid).zones.single().radius)
    }

    @Test
    fun `des coordonnees hors du globe sont refusees`() {
        val result = parsePersistedGpsZones(
            """[{"id":"workplace_1","latitude":146.7,"longitude":-1.4,"radius":150}]"""
        )

        assertTrue(result is GpsZonesReadResult.Corrupt)
    }
}
