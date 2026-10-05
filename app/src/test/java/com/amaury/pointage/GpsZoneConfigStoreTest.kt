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
    fun `une adresse avec une seule zone resout son proprietaire canonique`() {
        val result = parsePersistedGpsZones(
            """[
                {"id":"atelier","latitude":46.7,"longitude":-1.4,"radius":150,"address":"1 rue A","label":"Atelier"},
                {"id":"depot","latitude":46.8,"longitude":-1.5,"radius":180,"address":"2 rue B","label":"Dépôt"}
            ]""".trimIndent()
        )

        assertEquals("atelier", resolveUniqueGpsZoneIdForAddress(result, "1 RUE A"))
    }

    @Test
    fun `deux zones a la meme adresse ne choisissent jamais un proprietaire arbitraire`() {
        val result = parsePersistedGpsZones(
            """[
                {"id":"portail","latitude":46.7,"longitude":-1.4,"radius":150,"address":"1 rue A","label":"Atelier"},
                {"id":"parking","latitude":46.7005,"longitude":-1.4005,"radius":180,"address":"1 rue A","label":"Parking"}
            ]""".trimIndent()
        )

        assertNull(resolveUniqueGpsZoneIdForAddress(result, "1 rue A"))
    }


    @Test
    fun `un objet de zone est lu par id avant le fallback adresse`() {
        val zones = parsePersistedGpsZones(
            """[
                {"id":"atelier","latitude":46.7,"longitude":-1.4,"radius":150,"address":"1 rue A"}
            ]""".trimIndent()
        )
        val values = org.json.JSONObject()
            .put("atelier", org.json.JSONObject().put("phone", "111"))
            .put("1 rue A", org.json.JSONObject().put("phone", "222"))

        assertEquals("111", resolveGpsZoneScopedObject(values, zones, "atelier", "1 rue A")?.getString("phone"))
    }

    @Test
    fun `le fallback adresse est refuse si plusieurs zones partagent l adresse`() {
        val zones = parsePersistedGpsZones(
            """[
                {"id":"atelier","latitude":46.7,"longitude":-1.4,"radius":150,"address":"1 rue A"},
                {"id":"parking","latitude":46.7005,"longitude":-1.4005,"radius":180,"address":"1 rue A"}
            ]""".trimIndent()
        )
        val values = org.json.JSONObject()
            .put("1 rue A", org.json.JSONObject().put("phone", "222"))

        assertNull(resolveGpsZoneScopedObject(values, zones, "atelier", "1 rue A"))
    }

    @Test
    fun `ecrire un objet de zone migre la cle adresse vers l id canonique`() {
        val values = org.json.JSONObject()
            .put("1 rue A", org.json.JSONObject().put("phone", "ancien"))

        assertTrue(
            putGpsZoneScopedObject(
                values,
                "atelier",
                "1 rue A",
                org.json.JSONObject().put("phone", "nouveau")
            )
        )
        assertNull(values.optJSONObject("1 rue A"))
        assertEquals("nouveau", values.getJSONObject("atelier").getString("phone"))
    }


    @Test
    fun `les fiches de lieux gardent deux zones distinctes a la meme adresse`() {
        val zones = parsePersistedGpsZones(
            """[
                {"id":"atelier","latitude":46.7,"longitude":-1.4,"radius":150,"address":"1 rue A"},
                {"id":"parking","latitude":46.7005,"longitude":-1.4005,"radius":180,"address":"1 rue A"}
            ]""".trimIndent()
        )

        val entries = resolveGpsLocationEntries(zones, listOf("1 rue A"))

        assertEquals(2, entries?.size)
        assertEquals(listOf("atelier", "parking"), entries?.mapNotNull { it.zoneId })
    }

    @Test
    fun `une ancienne adresse sans zone reste visible pour migration`() {
        val zones = parsePersistedGpsZones("[]")

        val entries = resolveGpsLocationEntries(zones, listOf("Ancien site"))

        assertEquals(1, entries?.size)
        assertNull(entries?.single()?.zoneId)
        assertEquals("Ancien site", entries?.single()?.address)
    }

    @Test
    fun `une configuration gps corrompue ne fabrique aucune fiche de lieu`() {
        val zones = parsePersistedGpsZones("{invalide}")

        assertNull(resolveGpsLocationEntries(zones, listOf("1 rue A")))
    }


    @Test
    fun `une zone candidate apprise ne devient pas une fiche utilisateur`() {
        val zones = parsePersistedGpsZones(
            """[
                {
                    "id":"candidate",
                    "latitude":46.7,
                    "longitude":-1.4,
                    "radius":150,
                    "address":"1 rue A",
                    "smartCandidate":true
                }
            ]""".trimIndent()
        )

        val entries = resolveGpsLocationEntries(zones, emptyList())

        assertTrue(entries?.isEmpty() == true)
    }


    @Test
    fun `le multipoint regroupe plusieurs zones sous un seul lieu`() {
        val zones = parsePersistedGpsZones(
            """[
                {"id":"portail","latitude":46.7,"longitude":-1.4,"radius":120,"address":"1 rue A","pointType":"POSTE"},
                {"id":"atelier","latitude":46.7002,"longitude":-1.4002,"radius":160,"address":"1 rue A","pointType":"POSTE"},
                {"id":"pause","latitude":46.7004,"longitude":-1.4004,"radius":90,"address":"1 rue A","pointType":"PAUSE"}
            ]""".trimIndent()
        )

        val groups = groupGpsZonesByPlace(zones, listOf("1 rue A"))

        assertEquals(1, groups?.size)
        assertEquals(listOf("portail", "atelier", "pause"), groups?.single()?.zones?.map { it.id })
        assertTrue(groups?.single()?.legacyOnly == false)
    }

    @Test
    fun `deux lieux restent distincts avec plusieurs zones chacun`() {
        val zones = parsePersistedGpsZones(
            """[
                {"id":"a1","latitude":46.7,"longitude":-1.4,"radius":120,"address":"Site A"},
                {"id":"a2","latitude":46.7002,"longitude":-1.4002,"radius":160,"address":"Site A"},
                {"id":"b1","latitude":46.8,"longitude":-1.5,"radius":140,"address":"Site B"}
            ]""".trimIndent()
        )

        val groups = groupGpsZonesByPlace(zones, listOf("Site A", "Site B"))

        assertEquals(2, groups?.size)
        assertEquals(2, groups?.first { it.address == "Site A" }?.zones?.size)
        assertEquals(1, groups?.first { it.address == "Site B" }?.zones?.size)
    }

    @Test
    fun `une adresse legacy reste un lieu sans inventer de zone`() {
        val groups = groupGpsZonesByPlace(parsePersistedGpsZones("[]"), listOf("Ancien dépôt"))

        assertEquals(1, groups?.size)
        assertTrue(groups?.single()?.legacyOnly == true)
        assertTrue(groups?.single()?.zones?.isEmpty() == true)
    }

    @Test
    fun `une configuration corrompue ne produit aucun groupe multizone`() {
        assertNull(groupGpsZonesByPlace(parsePersistedGpsZones("{invalide}"), listOf("Site A")))
    }



    @Test
    fun `supprimer une zone par id conserve les autres zones du meme lieu`() {
        val zones = org.json.JSONArray(
            """[
                {"id":"portail","latitude":46.7,"longitude":-1.4,"radius":120,"address":"Site A"},
                {"id":"pause","latitude":46.7002,"longitude":-1.4002,"radius":90,"address":"Site A"}
            ]""".trimIndent()
        )

        val remaining = removeGpsZoneById(zones, "pause")

        assertEquals(1, remaining?.length())
        assertEquals("portail", remaining?.getJSONObject(0)?.getString("id"))
    }

    @Test
    fun `un id absent ne produit jamais une fausse suppression`() {
        val zones = org.json.JSONArray(
            """[{"id":"portail","latitude":46.7,"longitude":-1.4,"radius":120,"address":"Site A"}]"""
        )

        assertNull(removeGpsZoneById(zones, "inconnue"))
        assertEquals(1, zones.length())
    }

    @Test
    fun `renommer un lieu deplace toutes ses zones sans toucher aux autres lieux`() {
        val zones = org.json.JSONArray(
            """[
                {"id":"a1","latitude":46.7,"longitude":-1.4,"radius":120,"address":"Site A"},
                {"id":"a2","latitude":46.7002,"longitude":-1.4002,"radius":90,"address":"site a"},
                {"id":"b1","latitude":46.8,"longitude":-1.5,"radius":150,"address":"Site B"}
            ]""".trimIndent()
        )

        assertTrue(moveGpsPlaceAddress(zones, "SITE A", "Nouveau site"))
        assertEquals("Nouveau site", zones.getJSONObject(0).getString("address"))
        assertEquals("Nouveau site", zones.getJSONObject(1).getString("address"))
        assertEquals("Site B", zones.getJSONObject(2).getString("address"))
    }


    @Test
    fun `un lieu multizone ne choisit jamais un id representatif arbitraire`() {
        val zones = parsePersistedGpsZones(
            """[
                {"id":"a1","latitude":46.7,"longitude":-1.4,"radius":120,"address":"Site A","label":"Atelier"},
                {"id":"a2","latitude":46.7002,"longitude":-1.4002,"radius":90,"address":"Site A","label":"Atelier"}
            ]""".trimIndent()
        )
        val group = groupGpsZonesByPlace(zones, emptyList())!!.single()

        assertNull(representativeGpsZoneId(group))
        assertEquals("Atelier", uniqueGpsPlaceLabel(group))
    }

    @Test
    fun `des noms de zones differents ne deviennent pas un faux nom de lieu`() {
        val zones = parsePersistedGpsZones(
            """[
                {"id":"portail","latitude":46.7,"longitude":-1.4,"radius":120,"address":"Site A","label":"Portail"},
                {"id":"parking","latitude":46.7002,"longitude":-1.4002,"radius":90,"address":"Site A","label":"Parking"}
            ]""".trimIndent()
        )
        val group = groupGpsZonesByPlace(zones, emptyList())!!.single()

        assertNull(uniqueGpsPlaceLabel(group))
    }


    @Test
    fun `un lieu expose la repartition de ses zones sans requalifier le temps`() {
        val zones = parsePersistedGpsZones(
            """[
                {"id":"poste1","latitude":46.7,"longitude":-1.4,"radius":120,"address":"Site A","pointType":"POSTE"},
                {"id":"poste2","latitude":46.7001,"longitude":-1.4001,"radius":120,"address":"Site A","pointType":"POSTE"},
                {"id":"parking","latitude":46.7002,"longitude":-1.4002,"radius":90,"address":"Site A","pointType":"PARKING"},
                {"id":"autre","latitude":46.7003,"longitude":-1.4003,"radius":90,"address":"Site A","pointType":"OTHER"}
            ]""".trimIndent()
        )
        val summary = summarizeGpsPlaceTypes(groupGpsZonesByPlace(zones, emptyList())!!.single())

        assertEquals(2, summary.workZones)
        assertEquals(1, summary.parkingZones)
        assertEquals(1, summary.otherZones)
    }

}
