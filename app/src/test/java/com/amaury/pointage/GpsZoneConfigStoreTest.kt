package com.amaury.pointage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
                    "companyId":"company-stable-3",
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
        assertEquals(175f, zone.radius, 0f)
        assertEquals("12 rue des Lilas", zone.address)
        assertEquals("company-stable-3", zone.companyId)
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
        assertEquals(150f, (result as GpsZonesReadResult.Valid).zones.single().radius, 0f)
    }

    @Test
    fun `des coordonnees hors du globe sont refusees`() {
        val result = parsePersistedGpsZones(
            """[{"id":"workplace_1","latitude":146.7,"longitude":-1.4,"radius":150}]"""
        )

        assertTrue(result is GpsZonesReadResult.Corrupt)
    }

    @Test
    fun `un company id explicitement vide rend la configuration corrompue`() {
        val result = parsePersistedGpsZones(
            """[{"id":"workplace_1","latitude":46.7,"longitude":-1.4,"radius":150,"companyId":"   "}]"""
        )

        assertTrue(result is GpsZonesReadResult.Corrupt)
    }

    @Test
    fun `un ancien slot hors de 1 et 2 rend la configuration corrompue`() {
        val result = parsePersistedGpsZones(
            """[{"id":"workplace_1","latitude":46.7,"longitude":-1.4,"radius":150,"companySlot":3}]"""
        )

        assertTrue(result is GpsZonesReadResult.Corrupt)
    }

    @Test
    fun `un ancien slot non entier rend la configuration corrompue`() {
        val result = parsePersistedGpsZones(
            """[{"id":"workplace_1","latitude":46.7,"longitude":-1.4,"radius":150,"companySlot":1.5}]"""
        )

        assertTrue(result is GpsZonesReadResult.Corrupt)
    }

    @Test
    fun `une copie editable conserve les metadonnees de la zone`() {
        val result = parsePersistedGpsZones(
            """[{
                "id":"candidate_1",
                "latitude":46.7,
                "longitude":-1.4,
                "radius":150,
                "companyId":"company-stable-3",
                "smartCandidate":true
            }]""".trimIndent()
        )

        val editable = result.toMutableJsonArrayOrNull()

        assertEquals(1, editable?.length())
        assertEquals("company-stable-3", editable?.getJSONObject(0)?.optString("companyId"))
        assertTrue(editable?.getJSONObject(0)?.optBoolean("smartCandidate") == true)
    }

    @Test
    fun `un regeocodage conserve le type et les metadonnees existantes`() {
        val refreshed = refreshedGpsZoneJson(
            existing = org.json.JSONObject()
                .put("id", "parking-a")
                .put("pointType", "PARKING")
                .put("companyId", "company-a")
                .put("label", "Parking nord"),
            id = "parking-a",
            address = "2 rue Nouvelle",
            latitude = 46.8,
            longitude = -1.5,
            radius = 220
        )

        assertEquals("PARKING", refreshed.getString("pointType"))
        assertEquals("company-a", refreshed.getString("companyId"))
        assertEquals("Parking nord", refreshed.getString("label"))
        assertEquals("2 rue Nouvelle", refreshed.getString("address"))
        assertEquals(220, refreshed.getInt("radius"))
    }

    @Test
    fun `une nouvelle zone de travail recoit un type poste explicite`() {
        val refreshed = refreshedGpsZoneJson(
            existing = null,
            id = "new-zone",
            address = "1 rue Neuve",
            latitude = 46.8,
            longitude = -1.5,
            radius = 150
        )

        assertEquals("POSTE", refreshed.getString("pointType"))
    }

    @Test
    fun `une configuration corrompue ne fournit jamais une liste editable vide`() {
        val result = parsePersistedGpsZones("{invalide}")

        assertNull(result.toMutableJsonArrayOrNull())
    }

    @Test
    fun `le rayon affiche provient de la zone correspondant a l adresse`() {
        val result = parsePersistedGpsZones(
            """[
                {"id":"a","latitude":46.7,"longitude":-1.4,"radius":180,"address":"1 rue A"},
                {"id":"b","latitude":46.8,"longitude":-1.5,"radius":320,"address":"2 rue B"}
            ]""".trimIndent()
        )

        val resolution = resolveGpsZoneRadiusForAddress(result, "2 rue B")

        assertTrue(resolution is GpsZoneRadiusResolution.Known)
        assertEquals(320f, (resolution as GpsZoneRadiusResolution.Known).radiusMeters, 0f)
    }

    @Test
    fun `une adresse sans zone reste a confirmer`() {
        val result = parsePersistedGpsZones(
            """[{"id":"a","latitude":46.7,"longitude":-1.4,"radius":180,"address":"1 rue A"}]"""
        )

        assertTrue(resolveGpsZoneRadiusForAddress(result, "adresse absente") is GpsZoneRadiusResolution.Missing)
    }

    @Test
    fun `deux zones de meme adresse avec rayons differents restent ambigues`() {
        val result = parsePersistedGpsZones(
            """[
                {"id":"a","latitude":46.7,"longitude":-1.4,"radius":180,"address":"1 rue A"},
                {"id":"b","latitude":46.7001,"longitude":-1.4001,"radius":240,"address":"1 rue A"}
            ]""".trimIndent()
        )

        assertTrue(resolveGpsZoneRadiusForAddress(result, "1 RUE A") is GpsZoneRadiusResolution.Ambiguous)
    }

    @Test
    fun `une configuration gps corrompue ne produit aucun faux rayon`() {
        val result = parsePersistedGpsZones("{invalide}")

        assertTrue(resolveGpsZoneRadiusForAddress(result, "1 rue A") is GpsZoneRadiusResolution.Corrupt)
    }

}
