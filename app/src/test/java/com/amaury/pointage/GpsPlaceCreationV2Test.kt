package com.amaury.pointage

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GpsPlaceCreationV2Test {
    private fun zone(id: String, company: String? = "Company-A", address: String = "Site partagé", radius: Int = 120) = JSONObject()
        .put("id", id).put("companyId", company ?: JSONObject.NULL).put("address", address)
        .put("latitude", 46.7).put("longitude", -1.4).put("radius", radius)
        .put("pointType", "POSTE").put("label", "Zone $id").put("customMetadata", "conserver")
    private fun snapshot(vararg zones: JSONObject, extra: Map<String, Any> = emptyMap()): GpsZoneEditorStoreV2.Snapshot =
        GpsZoneEditorStoreV2.decode(extra + mapOf("zones" to JSONArray().apply { zones.forEach { put(it) } }.toString(), "address" to "Site partagé"))!!
    private val draft = GpsZoneDraftV2("Atelier modifié", 47.0, -1.5, 330.0, GpsZoneRoleV2.PARKING)
    private fun after(change: GpsZoneEditorStoreV2.Change) = GpsZoneEditorStoreV2.decode(change.values.filterValues { it != null })!!

    @Test fun `deux entreprises peuvent creer leur lieu a la meme adresse sans partager les contacts`() {
        val before = snapshot(zone("a"), extra = mapOf(
            "arrival_contacts" to """{"Site partagé":{"phone":"111","contactName":"A","enabled":true}}""",
            "zone_point_overrides" to """{"Site partagé":{"latitude":46.7,"longitude":-1.4}}""",
            "zone_point_confirmed" to """{"Site partagé":true}"""))
        val change = GpsZoneEditorStoreV2.addPlace(before, "Site partagé", "Company-B", null, "b",
            draft.copy(phone = "222", contactName = "B"), 10, "map")!!
        val next = after(change)
        assertEquals(2, next.groups().size)
        assertEquals("Company-B", next.zones.last().companyId)
        assertEquals("111", next.json("arrival_contacts").getJSONObject("a").getString("phone"))
        assertEquals("222", next.json("arrival_contacts").getJSONObject("b").getString("phone"))
        assertEquals(46.7, next.json("zone_point_overrides").getJSONObject("a").getDouble("latitude"), 0.0)
        assertEquals(47.0, next.json("zone_point_overrides").getJSONObject("b").getDouble("latitude"), 0.0)
        assertEquals(before.zones.first().sourceJson, next.zones.first().sourceJson)
        assertEquals(listOf("Site partagé"), next.addresses())
    }
    @Test fun `la premiere zone dun lieu possede son propre rayon role et entreprise`() {
        val before = GpsZoneEditorStoreV2.decode(emptyMap<String, Any>())!!
        val next = after(GpsZoneEditorStoreV2.addPlace(before, "Nouveau site", "Third-Company", null,
            "first", draft.copy(role = GpsZoneRoleV2.BREAK), 10, "manual_coordinates")!!)
        val zone = next.zones.single()
        assertEquals("Third-Company", zone.companyId)
        assertNull(zone.companySlot)
        assertEquals(330f, zone.radius, 0f)
        assertEquals("PAUSE", zone.pointTypeToken)
        assertEquals("Nouveau site", zone.address)
        assertTrue(next.json("zone_point_confirmed").getBoolean("first"))
    }
    @Test fun `adresse dupliquee pour la meme entreprise exige le parcours ajout de zone`() {
        val before = snapshot(zone("a"))
        assertNull(GpsZoneEditorStoreV2.addPlace(before, " SITE PARTAGÉ ", "Company-A", null, "new", draft, 10, "map"))
        // L'identifiant d'entreprise n'est jamais normalisé par changement de casse.
        assertNotNull(GpsZoneEditorStoreV2.addPlace(before, "Site partagé", "company-a", null, "new", draft, 10, "map"))
    }
    @Test fun `geocodage nest pas une confirmation manuelle du point`() {
        val before = GpsZoneEditorStoreV2.decode(emptyMap<String, Any>())!!
        val next = after(GpsZoneEditorStoreV2.addPlace(before, "Adresse trouvée", "Company-A", null, "geo", draft, 10, "geocoder")!!)
        assertFalse(next.json("zone_point_confirmed").getBoolean("geo"))
        assertFalse(next.json("zone_point_overrides").has("geo"))
        assertEquals("geocoder", JSONObject(next.zones.single().sourceJson).getString("pointSource"))
        val confirmed = after(GpsZoneEditorStoreV2.setPoint(next, "geo", 47.2, -1.6, "map")!!)
        assertTrue(confirmed.json("zone_point_confirmed").getBoolean("geo"))
        assertEquals("Company-A", confirmed.zones.single().companyId)
    }
    @Test fun `creation sans coordonnees reelles necrit jamais de faux point`() {
        val before = GpsZoneEditorStoreV2.decode(emptyMap<String, Any>())!!
        assertNull(GpsZoneEditorStoreV2.addPlace(before, "Inconnu", "Company-A", null, "geo",
            draft.copy(latitude = Double.NaN, longitude = Double.NaN), 10, "geocoder"))
        assertTrue(before.zones.isEmpty())
        assertNull(before.values["zones"])
    }
    @Test fun `la creation refuse les associations et identifiants incoherents`() {
        val before = snapshot(zone("a"))
        assertNull(GpsZoneEditorStoreV2.addPlace(before, "Autre", " ", null, "new", draft, 10, "map"))
        assertNull(GpsZoneEditorStoreV2.addPlace(before, "Autre", "Company-B", 1, "new", draft, 10, "map"))
        assertNull(GpsZoneEditorStoreV2.addPlace(before, "Autre", null, 3, "new", draft, 10, "map"))
        assertNull(GpsZoneEditorStoreV2.addPlace(before, "Autre", "Company-B", null, "a", draft, 10, "map"))
        assertNull(GpsZoneEditorStoreV2.addPlace(before, "Autre", "Company-B", null, "new", draft, 1, "map"))
        assertNull(GpsZoneEditorStoreV2.addPlace(before, "Autre", "Company-B", null, "new", draft, 10, "inconnue"))
        val legacy = after(GpsZoneEditorStoreV2.addPlace(before, "Autre", null, 2, "new", draft, 10, "map")!!)
        assertEquals(2, legacy.zones.last().companySlot)
    }
    @Test fun `une reponse de geocodage tardive necrase pas une edition deja enregistree`() {
        val before = snapshot(zone("a"))
        val pendingAdd = GpsZoneEditorStoreV2.addPlace(before, "Autre", "Company-A", null, "new", draft, 10, "geocoder")!!
        val prefs = MemoryPrefs(before.values.filterValues { it != null })
        assertTrue(GpsZoneEditorStoreV2.commit(prefs, GpsZoneEditorStoreV2.setPoint(before, "a", 48.0, -2.0, "map")!!))
        assertFalse(GpsZoneEditorStoreV2.commit(prefs, pendingAdd))
        val actual = GpsZoneEditorStoreV2.read(prefs)!!
        assertEquals(1, actual.zones.size)
        assertEquals(48.0, actual.zones.single().latitude, 0.0)
    }
    @Test fun `sans association automatique ne recupere pas un ancien lien dadresse`() {
        val before = snapshot(zone("old", null), extra = mapOf("address_company_slots" to """{"SITE PARTAGÉ":1}"""))
        val next = after(GpsZoneEditorStoreV2.addPlace(before, "Site partagé", null, null,
            "new", draft, 10, "manual_coordinates")!!)
        val original = next.zones.first()
        val added = next.zones.last()
        assertEquals(1, original.companySlot)
        assertNull(added.companyId)
        assertNull(added.companySlot)
        assertEquals(0, next.json("address_company_slots").length())
        assertEquals(GpsZoneEmployerResolutionV2.KeepCurrent,
            resolveGpsZoneEmployerV2(added.companyId, added.companySlot, true, listOf("Company-A")))
        assertEquals(GpsZoneEmployerResolutionV2.UseCompany("Company-A"),
            resolveGpsZoneEmployerV2(original.companyId, original.companySlot, true, listOf("Company-A")))
    }
    @Test fun `anciens liens dadresse contradictoires ne sont pas arbitres silencieusement`() {
        val before = snapshot(zone("old", null), extra = mapOf("address_company_slots" to """{"Site partagé":1,"SITE PARTAGÉ":2}"""))
        assertNull(GpsZoneEditorStoreV2.addPlace(before, "Site partagé", null, null, "new", draft, 10, "map"))
        assertNull(before.zones.single().companySlot)
        assertEquals(2, before.json("address_company_slots").length())
    }
    @Test fun `ajouter un point a un lieu sans association preserve le lien historique des anciens points`() {
        val before = snapshot(zone("old", null), extra = mapOf("address_company_slots" to """{"Site partagé":2}"""))
        val next = after(GpsZoneEditorStoreV2.saveZone(before, before.groups().single(), null, "new", draft, 10)!!)
        assertEquals(2, next.zones.first().companySlot)
        assertNull(next.zones.last().companySlot)
        assertEquals(0, next.json("address_company_slots").length())
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
