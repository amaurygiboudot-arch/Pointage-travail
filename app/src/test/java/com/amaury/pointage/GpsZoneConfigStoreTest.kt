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

    @Test
    fun `le rayon existant reste prioritaire sur l ancien defaut global`() {
        val existing = org.json.JSONObject()
            .put("id", "zone-a")
            .put("address", "1 rue A")
            .put("latitude", 46.7)
            .put("longitude", -1.4)
            .put("radius", 320)

        assertEquals(320, resolveGpsRadiusForRefresh(existing, 150))
    }

    @Test
    fun `une nouvelle zone utilise seulement le rayon par defaut interne`() {
        assertEquals(150, resolveGpsRadiusForRefresh(null, 150))
    }


    @Test
    fun `le type gps est modifie uniquement pour la zone cible meme a adresse identique`() {
        val zones = org.json.JSONArray(
            """[
                {"id":"portail","latitude":46.7,"longitude":-1.4,"radius":150,"address":"1 rue A","pointType":"POSTE"},
                {"id":"parking","latitude":46.7005,"longitude":-1.4005,"radius":180,"address":"1 rue A","pointType":"POSTE"}
            ]""".trimIndent()
        )

        assertTrue(updateGpsZoneTypeById(zones, "parking", "PARKING"))
        assertEquals("POSTE", zones.getJSONObject(0).getString("pointType"))
        assertEquals("PARKING", zones.getJSONObject(1).getString("pointType"))
    }

    @Test
    fun `un id de zone gps inconnu ne modifie rien`() {
        val zones = org.json.JSONArray(
            """[{"id":"portail","latitude":46.7,"longitude":-1.4,"radius":150,"address":"1 rue A","pointType":"POSTE"}]"""
        )

        assertTrue(!updateGpsZoneTypeById(zones, "absente", "PARKING"))
        assertEquals("POSTE", zones.getJSONObject(0).getString("pointType"))
    }


    @Test
    fun `le nom gps est modifie uniquement pour la zone cible meme a adresse identique`() {
        val zones = org.json.JSONArray(
            """[
                {"id":"portail","latitude":46.7,"longitude":-1.4,"radius":150,"address":"1 rue A","label":"Atelier"},
                {"id":"parking","latitude":46.7005,"longitude":-1.4005,"radius":180,"address":"1 rue A","label":"Parking ancien"}
            ]""".trimIndent()
        )

        assertTrue(updateGpsZoneLabelById(zones, "parking", "Parking visiteurs"))
        assertEquals("Atelier", zones.getJSONObject(0).getString("label"))
        assertEquals("Parking visiteurs", zones.getJSONObject(1).getString("label"))
    }

    @Test
    fun `supprimer un nom gps ne supprime pas la zone cible`() {
        val zones = org.json.JSONArray(
            """[{"id":"portail","latitude":46.7,"longitude":-1.4,"radius":150,"address":"1 rue A","label":"Atelier"}]"""
        )

        assertTrue(updateGpsZoneLabelById(zones, "portail", "   "))
        assertTrue(!zones.getJSONObject(0).has("label"))
        assertEquals("portail", zones.getJSONObject(0).getString("id"))
        assertEquals("1 rue A", zones.getJSONObject(0).getString("address"))
    }

    @Test
    fun `la resolution du nom par id reste non ambigue a adresse identique`() {
        val result = parsePersistedGpsZones(
            """[
                {"id":"portail","latitude":46.7,"longitude":-1.4,"radius":150,"address":"1 rue A","label":"Atelier"},
                {"id":"parking","latitude":46.7005,"longitude":-1.4005,"radius":180,"address":"1 rue A","label":"Parking"}
            ]""".trimIndent()
        )

        assertEquals("Atelier", resolveGpsZoneLabel(result, "portail", "1 rue A"))
        assertEquals("Parking", resolveGpsZoneLabel(result, "parking", "1 rue A"))
        assertNull(resolveGpsZoneLabel(result, null, "1 rue A"))
    }


    @Test
    fun `une zone conserve son contact d arrivee canonique`() {
        val result = parsePersistedGpsZones(
            """[{
                "id":"portail",
                "latitude":46.7,
                "longitude":-1.4,
                "radius":150,
                "address":"1 rue A",
                "arrivalContact":{"contactName":"Accueil","phone":"0601020304","enabled":true}
            }]""".trimIndent()
        )

        assertTrue(result is GpsZonesReadResult.Valid)
        val contact = (result as GpsZonesReadResult.Valid).zones.single().arrivalContact
        assertEquals("Accueil", contact?.contactName)
        assertEquals("0601020304", contact?.phone)
        assertTrue(contact?.enabled == true)
    }

    @Test
    fun `un contact d arrivee invalide rend la configuration gps corrompue`() {
        val result = parsePersistedGpsZones(
            """[{
                "id":"portail",
                "latitude":46.7,
                "longitude":-1.4,
                "radius":150,
                "arrivalContact":{"enabled":"oui"}
            }]""".trimIndent()
        )

        assertTrue(result is GpsZonesReadResult.Corrupt)
    }

    @Test
    fun `le contact d arrivee est modifie uniquement pour la zone cible a adresse identique`() {
        val zones = org.json.JSONArray(
            """[
                {"id":"portail","latitude":46.7,"longitude":-1.4,"radius":150,"address":"1 rue A",
                 "arrivalContact":{"contactName":"Accueil","phone":"0101","enabled":true}},
                {"id":"parking","latitude":46.7005,"longitude":-1.4005,"radius":180,"address":"1 rue A",
                 "arrivalContact":{"contactName":"Gardien","phone":"0202","enabled":false}}
            ]""".trimIndent()
        )

        assertTrue(
            updateGpsZoneArrivalContactById(
                zones,
                "parking",
                StoredGpsArrivalContact("Parking visiteurs", "0303", true)
            )
        )

        assertEquals("Accueil", zones.getJSONObject(0).getJSONObject("arrivalContact").getString("contactName"))
        val target = zones.getJSONObject(1).getJSONObject("arrivalContact")
        assertEquals("Parking visiteurs", target.getString("contactName"))
        assertEquals("0303", target.getString("phone"))
        assertTrue(target.getBoolean("enabled"))
    }

    @Test
    fun `supprimer le contact d arrivee conserve la zone gps`() {
        val zones = org.json.JSONArray(
            """[{
                "id":"portail",
                "latitude":46.7,
                "longitude":-1.4,
                "radius":150,
                "address":"1 rue A",
                "arrivalContact":{"contactName":"Accueil","phone":"0101","enabled":true}
            }]""".trimIndent()
        )

        assertTrue(updateGpsZoneArrivalContactById(zones, "portail", null))
        assertTrue(!zones.getJSONObject(0).has("arrivalContact"))
        assertEquals("portail", zones.getJSONObject(0).getString("id"))
    }

    @Test
    fun `la resolution du contact par zone reste non ambigue a adresse identique`() {
        val result = parsePersistedGpsZones(
            """[
                {"id":"portail","latitude":46.7,"longitude":-1.4,"radius":150,"address":"1 rue A",
                 "arrivalContact":{"contactName":"Accueil","phone":"0101","enabled":true}},
                {"id":"parking","latitude":46.7005,"longitude":-1.4005,"radius":180,"address":"1 rue A",
                 "arrivalContact":{"contactName":"Gardien","phone":"0202","enabled":false}}
            ]""".trimIndent()
        )

        assertEquals("Accueil", resolveGpsZoneArrivalContact(result, "portail", "1 rue A")?.contactName)
        assertEquals("Gardien", resolveGpsZoneArrivalContact(result, "parking", "1 rue A")?.contactName)
        assertNull(resolveGpsZoneArrivalContact(result, null, "1 rue A"))
    }


}
