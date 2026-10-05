package com.amaury.pointage

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GpsZoneEditorV2Test {
    private fun zone(id: String, company: String? = "Company-A", address: String = "Site partagé", radius: Int = 120) = JSONObject()
        .put("id", id).put("companyId", company ?: JSONObject.NULL).put("address", address)
        .put("latitude", 46.7).put("longitude", -1.4).put("radius", radius)
        .put("pointType", "POSTE").put("label", "Zone $id").put("customMetadata", "conserver")
    private fun snapshot(vararg zones: JSONObject, extra: Map<String, Any> = emptyMap()): GpsZoneEditorStoreV2.Snapshot =
        GpsZoneEditorStoreV2.decode(extra + mapOf("zones" to JSONArray().apply { zones.forEach { put(it) } }.toString(), "address" to "Site partagé"))!!
    private val draft = GpsZoneDraftV2("Atelier modifié", 47.0, -1.5, 330.0, GpsZoneRoleV2.PARKING)
    private fun after(change: GpsZoneEditorStoreV2.Change) = GpsZoneEditorStoreV2.decode(change.values.filterValues { it != null })!!

    @Test fun `ids entreprise opaques et slots legacy restent distincts`() {
        val a = GpsPlaceScopeV2.of("Company-A", 1, " SITE ")
        assertEquals(a, GpsPlaceScopeV2.of("Company-A", 2, "site"))
        assertNotEquals(a, GpsPlaceScopeV2.of("company-a", 1, "site"))
        assertNotEquals(a, GpsPlaceScopeV2.of(null, 1, "site"))
        assertNotEquals(GpsPlaceScopeV2.of("a|b", null, "c"), GpsPlaceScopeV2.of("a", null, "b|c"))
    }
    @Test fun `projection adresse ne cree pas de troisieme lieu fantome`() {
        val before = snapshot(zone("a"), zone("b", "company-a"))
        assertEquals(2, before.groups().size)
        assertTrue(before.groups().none { it.legacyOnly })
    }
    @Test fun `une nouvelle zone exige nom et geometrie valides`() {
        assertNull(draft.error())
        assertNotNull(draft.copy(label = " ").error())
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, -91.0, 91.0)) assertNotNull(draft.copy(latitude = value).error())
        for (value in listOf(Double.NaN, -181.0, 181.0)) assertNotNull(draft.copy(longitude = value).error())
        for (value in listOf(Double.NaN, 49.0, 1001.0)) assertNotNull(draft.copy(radius = value).error())
        assertNull(draft.copy(latitude = -90.0, longitude = 180.0, radius = 50.0).error())
    }
    @Test fun `pause et role absent restent distincts du travail`() {
        assertEquals(GpsZoneRoleV2.BREAK, GpsZoneRoleV2.fromToken("pause"))
        for (value in listOf(null, "", "NOT_WORK", "TYPE_FUTUR")) assertEquals(GpsZoneRoleV2.OTHER, GpsZoneRoleV2.fromToken(value))
    }
    @Test fun `modifier une zone conserve son id entreprise et metadonnees`() {
        val before = snapshot(zone("a"), zone("b", "Company-B", radius = 280))
        val change = GpsZoneEditorStoreV2.saveZone(before, before.groups().first(), "a", "unused", draft, 10)!!
        val next = after(change)
        val edited = next.zones.first { it.id == "a" }
        assertEquals("Company-A", edited.companyId)
        assertEquals(330f, edited.radius, 0f)
        assertEquals("conserver", JSONObject(edited.sourceJson).getString("customMetadata"))
        assertEquals(before.zones.last().sourceJson, next.zones.last().sourceJson)
        assertEquals(120f, before.zones.first().radius, 0f)
    }
    @Test fun `ajouter une zone ne copie jamais le contact du voisin`() {
        val before = snapshot(zone("a"), extra = mapOf("arrival_contacts" to """{"Site partagé":{"phone":"0102030405","contactName":"Ancien","enabled":true}}""",
            "zone_point_confirmed" to """{"Site partagé":true}"""))
        val next = after(GpsZoneEditorStoreV2.saveZone(before, before.groups().single(), null, "new", draft, 10)!!)
        assertEquals(2, next.zones.size)
        assertEquals("0102030405", next.json("arrival_contacts").getJSONObject("a").getString("phone"))
        assertEquals("", next.json("arrival_contacts").getJSONObject("new").getString("phone"))
        assertFalse(next.json("arrival_contacts").getJSONObject("new").getBoolean("enabled"))
        assertEquals("Company-A", next.zones.last().companyId)
        assertTrue(next.json("zone_point_confirmed").getBoolean("a"))
    }
    @Test fun `rayon global ne remplace jamais celui dune zone`() {
        val before = snapshot(zone("a"), zone("b", radius = 280), extra = mapOf("radius" to 999))
        val next = after(GpsZoneEditorStoreV2.saveZone(before, before.groups().single(), "a", "unused", draft, 10)!!)
        assertEquals(280f, next.zones.last().radius, 0f)
        assertEquals(330f, next.zones.first().radius, 0f)
    }
    @Test fun `suppression ciblee conserve voisin entreprise et historique`() {
        val rawHistory = "journal privé inchangé"
        val before = snapshot(zone("a1"), zone("a2"), zone("b", "Company-B"), extra = mapOf(
            "arrival_contacts" to """{"a1":{"phone":"1"},"a2":{"phone":"2"},"b":{"phone":"3"}}"""))
        val change = GpsZoneEditorStoreV2.remove(before, before.groups().first(), "a1")!!
        val next = after(change)
        assertEquals(listOf("a2", "b"), next.zones.map { it.id })
        assertFalse(next.json("arrival_contacts").has("a1"))
        assertEquals("2", next.json("arrival_contacts").getJSONObject("a2").getString("phone"))
        val prefs = MemoryPrefs(before.values.filterValues { it != null } + mapOf("runtime_history" to rawHistory))
        assertTrue(GpsZoneEditorStoreV2.commit(prefs, change))
        assertEquals(rawHistory, prefs.data["runtime_history"])
    }
    @Test fun `suppression du lieu retire toutes ses metadonnees par id`() {
        val before = snapshot(zone("a1"), zone("a2"), zone("b", "Company-B"), extra = mapOf(
            "arrival_contacts" to """{"a1":{},"a2":{},"b":{"phone":"3"}}""",
            "zone_point_confirmed" to """{"a1":true,"a2":true,"b":false}"""))
        val next = after(GpsZoneEditorStoreV2.remove(before, before.groups().first())!!)
        assertEquals(listOf("b"), next.zones.map { it.id })
        assertEquals(setOf("b"), next.json("arrival_contacts").keys().asSequence().toSet())
        assertEquals(setOf("b"), next.json("zone_point_confirmed").keys().asSequence().toSet())
        assertEquals("Site partagé", next.values["address"])
    }
    @Test fun `suppression de la derniere zone nettoie projection et point pending`() {
        val before = snapshot(zone("a"), extra = mapOf("pending_point_address" to "Site partagé"))
        val next = after(GpsZoneEditorStoreV2.remove(before, before.groups().single(), "a")!!)
        assertTrue(next.zones.isEmpty())
        assertTrue(next.addresses().isEmpty())
        assertNull(next.values["pending_point_address"])
    }
    @Test fun `fenetre perimee ne recree pas une zone supprimee`() {
        val before = snapshot(zone("a"))
        val change = GpsZoneEditorStoreV2.saveZone(before, before.groups().single(), "a", "unused", draft, 10)!!
        val prefs = MemoryPrefs(before.values.filterValues { it != null })
        prefs.data["zones"] = "[]"
        assertFalse(GpsZoneEditorStoreV2.commit(prefs, change))
        assertEquals("[]", prefs.data["zones"])
        assertEquals(0, prefs.commits)
    }
    @Test fun `changement concurrent de contact interdit lecrasement`() {
        val before = snapshot(zone("a"))
        val change = GpsZoneEditorStoreV2.remove(before, before.groups().single())!!
        val prefs = MemoryPrefs(before.values.filterValues { it != null })
        prefs.data["arrival_contacts"] = """{"a":{"phone":"nouveau"}}"""
        assertFalse(GpsZoneEditorStoreV2.commit(prefs, change))
        assertEquals(0, prefs.commits)
    }
    @Test fun `stockage invalide ne devient pas une configuration vide`() {
        assertNull(GpsZoneEditorStoreV2.decode(mapOf("zones" to "{invalide}")))
        assertNull(GpsZoneEditorStoreV2.decode(mapOf("zones" to true)))
        assertNull(GpsZoneEditorStoreV2.decode(mapOf("arrival_contacts" to """{"a":"corrompu"}""")))
        assertNull(GpsZoneEditorStoreV2.decode(mapOf("zone_point_confirmed" to """{"a":"oui"}""")))
    }
    @Test fun `renommer un lieu laisse lautre entreprise et les centres inchanges`() {
        val before = snapshot(zone("a"), zone("b", "Company-B"))
        val next = after(GpsZoneEditorStoreV2.renameAddress(before, before.groups().first(), "Nouvelle adresse")!!)
        assertEquals("Nouvelle adresse", next.zones.first().address)
        assertEquals(46.7, next.zones.first().latitude, 0.0)
        assertEquals(before.zones.last().sourceJson, next.zones.last().sourceJson)
    }
    @Test fun `absence cible doublon et limite sont refuses sans perte`() {
        val before = snapshot(zone("a"))
        val group = before.groups().single()
        assertNull(GpsZoneEditorStoreV2.saveZone(before, group, "deleted", "unused", draft, 10))
        assertNull(GpsZoneEditorStoreV2.saveZone(before, group, null, "a", draft, 10))
        assertNull(GpsZoneEditorStoreV2.saveZone(before, group, null, "new", draft, 1))
        assertNull(GpsZoneEditorStoreV2.remove(before, group, "deleted"))
        assertEquals(1, before.zones.size)
    }
    @Test fun `ancienne adresse sans zone peut recevoir sa premiere zone sans employeur invente`() {
        val before = GpsZoneEditorStoreV2.decode(mapOf("address" to "Ancien dépôt"))!!
        val next = after(GpsZoneEditorStoreV2.saveZone(before, before.groups().single(), null, "first", draft, 10)!!)
        assertEquals(1, next.zones.size)
        assertNull(next.zones.single().companyId)
        assertEquals("Ancien dépôt", next.zones.single().address)
    }
    @Test fun `deplacement invalide en milieu de tableau necrit rien`() {
        val array = JSONArray().put(zone("a", null)).put(JSONObject())
        val raw = array.toString()
        assertFalse(moveGpsPlaceAddress(array, "Site partagé", "Nouveau"))
        assertEquals(raw, array.toString())
    }
    @Test fun `une erreur de persistence nest pas annoncee comme succes`() {
        val before = snapshot(zone("a"))
        val prefs = MemoryPrefs(before.values.filterValues { it != null }).apply { failCommit = true }
        val change = GpsZoneEditorStoreV2.remove(before, before.groups().single())!!
        assertFalse(GpsZoneEditorStoreV2.commit(prefs, change))
    }

    @Test fun `edition du role conserve centres rayons contacts et journal`() {
        val before = snapshot(zone("a"), extra = mapOf("arrival_contacts" to """{"a":{"phone":"0102030405"}}"""))
        val next = after(GpsZoneEditorStoreV2.setRole(before, "a", GpsZoneRoleV2.BREAK)!!)
        assertEquals("PAUSE", next.zones.single().pointTypeToken)
        assertEquals(120f, next.zones.single().radius, 0f)
        assertEquals(46.7, next.zones.single().latitude, 0.0)
        assertEquals(before.values["arrival_contacts"], next.values["arrival_contacts"])
        assertNull(GpsZoneEditorStoreV2.setRole(before, "deleted", GpsZoneRoleV2.WORK))
    }

    @Test fun `deplacement par carte conserve rayon role employeur et contact`() {
        val source = zone("a", radius = 330).put("pointType", "PAUSE")
        val before = snapshot(source, zone("b", "Company-B", radius = 270), extra = mapOf(
            "radius" to 999, "arrival_contacts" to """{"a":{"phone":"123","enabled":true}}""",
            "pending_point_address" to "Site partagé"))
        val next = after(GpsZoneEditorStoreV2.setPoint(before, "a", 47.5, -2.0, "map")!!)
        val changed = next.zones.first()
        assertEquals(330f, changed.radius, 0f)
        assertEquals("PAUSE", changed.pointTypeToken)
        assertEquals("Company-A", changed.companyId)
        assertEquals("Zone a", changed.label)
        assertEquals(47.5, changed.latitude, 0.0)
        assertEquals(-2.0, changed.longitude, 0.0)
        assertEquals("123", next.json("arrival_contacts").getJSONObject("a").getString("phone"))
        assertEquals(before.zones.last().sourceJson, next.zones.last().sourceJson)
        assertEquals("map", next.json("zone_point_overrides").getJSONObject("a").getString("source"))
        assertNull(next.values["pending_point_address"])
    }
    @Test fun `carte perimee ne ressuscite jamais une zone supprimee`() {
        val before = snapshot(zone("a"))
        val change = GpsZoneEditorStoreV2.setPoint(before, "a", 47.5, -2.0, "map")!!
        val prefs = MemoryPrefs(before.values.filterValues { it != null })
        prefs.data["zones"] = "[]"
        assertFalse(GpsZoneEditorStoreV2.commit(prefs, change))
        assertEquals(0, prefs.commits)
        assertEquals("[]", prefs.data["zones"])
        val current = GpsZoneEditorStoreV2.read(prefs)!!
        assertNull(GpsZoneEditorStoreV2.setPoint(current, "a", 47.5, -2.0, "map"))
    }
    @Test fun `carte refuse coordonnees invalides sans invalider un autre pending`() {
        val before = snapshot(zone("a"), extra = mapOf("pending_point_address" to "Autre site"))
        for ((lat, lon) in listOf(Double.NaN to 0.0, 91.0 to 0.0, 0.0 to 181.0, 0.0 to Double.NEGATIVE_INFINITY)) {
            assertNull(GpsZoneEditorStoreV2.setPoint(before, "a", lat, lon, "map"))
        }
        assertNull(GpsZoneEditorStoreV2.setPoint(before, "a", 47.0, -1.5, "source_inventee"))
        val next = after(GpsZoneEditorStoreV2.setPoint(before, "a", 47.0, -1.5, "map")!!)
        assertEquals("Autre site", next.values["pending_point_address"])
    }
    @Test fun `editeur de zones ne peut jamais ecrire une cle du journal`() {
        val before = snapshot(zone("a"))
        val valid = GpsZoneEditorStoreV2.setPoint(before, "a", 47.0, -1.5, "map")!!
        val invalid = valid.copy(values = valid.values + ("runtime_history" to "interdit"))
        val prefs = MemoryPrefs(before.values.filterValues { it != null } + ("runtime_history" to "conserver"))
        assertFalse(GpsZoneEditorStoreV2.commit(prefs, invalid))
        assertEquals(0, prefs.commits)
        assertEquals("conserver", prefs.data["runtime_history"])
    }
    @Test fun `modifier une zone contextuelle preserve letat de presence automatique`() {
        val parking = zone("parking").put("pointType", "PARKING")
        val before = snapshot(parking)
        val prefs = MemoryPrefs(
            before.values.filterValues { it != null } + mapOf(
                "enabled" to true,
                "active_zones" to setOf("work-active"),
                "entry_resolution_pending" to true,
                "entry_resolution_token" to "token",
                "pending_exit_zones" to setOf("work-active"),
                "geofence_registration_valid" to true,
                "geofence_registration_fingerprint" to "stable"
            )
        )
        val change = GpsZoneEditorStoreV2.setPoint(before, "parking", 47.2, -1.9, "map")!!

        assertTrue(GpsZoneEditorStoreV2.commit(prefs, change))
        assertEquals(setOf("work-active"), prefs.data["active_zones"])
        assertEquals(true, prefs.data["entry_resolution_pending"])
        assertEquals("token", prefs.data["entry_resolution_token"])
        assertEquals(setOf("work-active"), prefs.data["pending_exit_zones"])
        assertEquals(true, prefs.data["geofence_registration_valid"])
        assertEquals("stable", prefs.data["geofence_registration_fingerprint"])
    }

    @Test fun `modifier une zone travail invalide letat de presence automatique`() {
        val before = snapshot(zone("work").put("pointType", "POSTE"))
        val prefs = MemoryPrefs(
            before.values.filterValues { it != null } + mapOf(
                "enabled" to true,
                "active_zones" to setOf("work"),
                "entry_resolution_pending" to true,
                "entry_resolution_token" to "token",
                "pending_exit_zones" to setOf("work"),
                "geofence_registration_valid" to true,
                "geofence_registration_fingerprint" to "old"
            )
        )
        val change = GpsZoneEditorStoreV2.setPoint(before, "work", 47.2, -1.9, "map")!!

        assertTrue(GpsZoneEditorStoreV2.commit(prefs, change))
        assertFalse(prefs.data.containsKey("active_zones"))
        assertFalse(prefs.data.containsKey("entry_resolution_pending"))
        assertFalse(prefs.data.containsKey("entry_resolution_token"))
        assertFalse(prefs.data.containsKey("pending_exit_zones"))
        assertFalse(prefs.data.containsKey("geofence_registration_valid"))
        assertFalse(prefs.data.containsKey("geofence_registration_fingerprint"))
    }

    @Test fun `premier point confirme sur carte conserve sa provenance`() {
        val before = GpsZoneEditorStoreV2.decode(mapOf("address" to "Ancien dépôt"))!!
        val change = GpsZoneEditorStoreV2.saveZone(before, before.groups().single(), null, "first",
            draft.copy(role = GpsZoneRoleV2.OTHER), 10, "map")!!
        val next = after(change)
        assertEquals("map", JSONObject(next.zones.single().sourceJson).getString("pointSource"))
        assertEquals("map", next.json("zone_point_overrides").getJSONObject("first").getString("source"))
        assertEquals("OTHER", next.zones.single().pointTypeToken)
    }

    private class MemoryPrefs(initial: Map<String, Any?>) : SharedPreferences {
        val data = initial.toMutableMap()
        var commits = 0
        var failCommit = false
        override fun getAll(): MutableMap<String, *> = data.toMutableMap()
        override fun contains(key: String?) = data.containsKey(key)
        override fun getString(key: String?, defValue: String?) = data[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?) = defValues
        override fun getInt(key: String?, defValue: Int) = data[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long) = data[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float) = data[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean) = data[key] as? Boolean ?: defValue
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val next = data.toMutableMap()
            override fun putString(key: String?, value: String?) = apply { next[key!!] = value }
            override fun putStringSet(key: String?, values: MutableSet<String>?) = apply { next[key!!] = values }
            override fun putInt(key: String?, value: Int) = apply { next[key!!] = value }
            override fun putLong(key: String?, value: Long) = apply { next[key!!] = value }
            override fun putFloat(key: String?, value: Float) = apply { next[key!!] = value }
            override fun putBoolean(key: String?, value: Boolean) = apply { next[key!!] = value }
            override fun remove(key: String?) = apply { next.remove(key) }
            override fun clear() = apply { next.clear() }
            override fun commit(): Boolean { commits++; if (failCommit) return false; data.clear(); data.putAll(next); return true }
            override fun apply() { commit() }
        }
    }
}
