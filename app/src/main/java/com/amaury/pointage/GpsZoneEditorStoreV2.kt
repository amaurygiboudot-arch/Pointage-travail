package com.amaury.pointage

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Éditeur du store GPS existant, jamais du journal de pointage ni des données de paie. */
internal object GpsZoneEditorStoreV2 {
    private val objectKeys = setOf("arrival_contacts", "zone_point_overrides", "zone_point_confirmed", "address_names", "address_company_slots")
    private val keys = objectKeys + setOf("zones", "address", "pending_point_address")

    data class Snapshot(val values: Map<String, String?>, val zones: List<StoredGpsZone>) {
        fun addresses(): List<String> = values["address"].orEmpty().lines().map(String::trim).filter(String::isNotBlank)
        fun groups(): List<GpsPlaceGroup> = groupGpsZonesByPlace(GpsZonesReadResult.Valid(zones), addresses()).orEmpty()
        fun json(key: String): JSONObject = JSONObject(values[key] ?: "{}")
        fun contact(zone: StoredGpsZone): JSONObject? = resolveGpsZoneScopedObject(
            json("arrival_contacts"), GpsZonesReadResult.Valid(zones), zone.id, zone.address.orEmpty())
    }
    data class Change(val expected: Snapshot, val values: Map<String, String?>)

    fun read(prefs: SharedPreferences): Snapshot? = decode(prefs.all)

    internal fun decode(all: Map<String, *>): Snapshot? {
        if (keys.any { all.containsKey(it) && all[it] !is String }) return null
        val values = keys.associateWith { all[it] as? String }
        if (objectKeys.any { key -> values[key]?.let { runCatching { JSONObject(it) }.isFailure } == true }) return null
        for (key in objectKeys) {
            val map = JSONObject(values[key] ?: "{}")
            for (entry in map.keys()) {
                val value = map.opt(entry)
                val valid = when (key) {
                    "arrival_contacts" -> value is JSONObject &&
                        listOf("contactName", "phone").all { !value.has(it) || value.opt(it) is String } &&
                        (!value.has("enabled") || value.opt("enabled") is Boolean)
                    "zone_point_overrides" -> value is JSONObject &&
                        value.optDouble("latitude", Double.NaN).let { it.isFinite() && it in -90.0..90.0 } &&
                        value.optDouble("longitude", Double.NaN).let { it.isFinite() && it in -180.0..180.0 }
                    "zone_point_confirmed" -> value is Boolean
                    "address_names" -> value is String
                    "address_company_slots" -> value is Number && value.toDouble() in listOf(1.0, 2.0)
                    else -> false
                }
                if (!valid) return null
            }
        }
        val zones = if (values["zones"] == null) emptyList() else {
            (parsePersistedGpsZones(values["zones"]) as? GpsZonesReadResult.Valid)?.zones ?: return null
        }
        return Snapshot(values, zones)
    }

    /** Refuse une fenêtre périmée, y compris après changement d'entreprise ou de contacts. */
    @Synchronized
    fun commit(prefs: SharedPreferences, change: Change): Boolean {
        val current = read(prefs) ?: return false
        if (change.values.keys != keys) return false
        if (current.values != change.expected.values || decode(change.values.filterValues { it != null }) == null) return false
        val editor = prefs.edit()
        change.values.forEach { (key, value) ->
            if (value != current.values[key]) {
                if (value == null) editor.remove(key) else editor.putString(key, value)
            }
        }
        // Les inscriptions système sont invalidées dans cette même écriture.
        editor.remove("active_zones").remove("entry_resolution_pending")
            .remove("entry_resolution_token").remove("pending_exit_zones")
            .remove("geofence_registration_valid").remove("geofence_registration_fingerprint")
        return editor.commit()
    }

    private fun selected(snapshot: Snapshot, group: GpsPlaceGroup): List<StoredGpsZone>? {
        val actual = snapshot.groups().singleOrNull { it.scope() == group.scope() } ?: return null
        if (actual.zones.map { it.id }.toSet() != group.zones.map { it.id }.toSet()) return null
        return actual.zones
    }

    private fun finish(snapshot: Snapshot, zones: List<JSONObject>, values: MutableMap<String, String?>): Change? {
        values["zones"] = JSONArray().apply { zones.forEach { put(it) } }.toString()
        return if (decode(values.filterValues { it != null }) == null) null else Change(snapshot, values.toMap())
    }

    /** Déplace les anciennes métadonnées d'adresse vers leur unique propriétaire AVANT ajout. */
    private fun preserveAddressMetadata(snapshot: Snapshot, zones: List<JSONObject>, values: MutableMap<String, String?>, selectedIds: Set<String>) {
        snapshot.zones.groupBy { it.address?.trim()?.lowercase(Locale.ROOT) }.values.forEach { oldZones ->
            val old = oldZones.singleOrNull()?.takeIf { it.id in selectedIds } ?: return@forEach
            val address = old.address ?: return@forEach
            val target = zones.singleOrNull { it.optString("id").trim() == old.id } ?: return@forEach
            val names = snapshot.json("address_names")
            if (old.label.isNullOrBlank()) {
                (names.opt(address) as? String)?.trim()?.takeIf(String::isNotBlank)?.let { target.put("label", it) }
            }
            for (key in listOf("arrival_contacts", "zone_point_overrides", "zone_point_confirmed")) {
                val map = JSONObject(values[key] ?: "{}")
                if (map.has(address) && !map.has(old.id)) {
                    map.put(old.id, map.get(address))
                    // Ne pas retirer la clé d'adresse si elle est aussi un identifiant existant.
                    if (snapshot.zones.none { it.id == address }) map.remove(address)
                    values[key] = map.toString()
                }
            }
        }
    }

    /**
     * L'ancien lien adresse -> slot doit rester porté par les anciennes zones, jamais par
     * une nouvelle zone dont l'utilisateur a explicitement choisi l'absence d'association.
     * Une collision de clé avec un ID ou des variantes contradictoires bloque l'opération.
     */
    private fun preserveLegacyCompanyLinksBeforeUnboundAddition(
        snapshot: Snapshot, zones: List<JSONObject>, values: MutableMap<String, String?>,
        address: String
    ): Boolean {
        val map = JSONObject(values["address_company_slots"] ?: "{}")
        val aliases = map.keys().asSequence().filter { it.equals(address.trim(), ignoreCase = true) }.toList()
        if (aliases.isEmpty()) return true
        val slots = aliases.map { map.getInt(it) }.distinct()
        if (slots.size != 1 || aliases.any { key -> snapshot.zones.any { it.id.equals(key, ignoreCase = true) } }) return false
        snapshot.zones.filter {
            it.address?.equals(address.trim(), ignoreCase = true) == true &&
                it.companyId == null && it.companySlot == null
        }.forEach { old ->
            zones.single { it.optString("id").trim() == old.id }.put("companySlot", slots.single())
        }
        aliases.forEach(map::remove)
        values["address_company_slots"] = map.toString()
        return true
    }

    fun saveZone(snapshot: Snapshot, group: GpsPlaceGroup, zoneId: String?, newZoneId: String,
                 draft: GpsZoneDraftV2, maximumZones: Int, pointSource: String = "manual_coordinates"): Change? {
        if (draft.error() != null || pointSource !in setOf("manual_coordinates", "map")) return null
        val members = selected(snapshot, group) ?: return null
        val existing = zoneId?.let { id -> members.singleOrNull { it.id == id } ?: return null }
        val id = existing?.id ?: newZoneId.trim()
        if (id.isBlank()) return null
        if (existing == null && (snapshot.zones.size >= maximumZones || snapshot.zones.any { it.id == id })) return null
        if (existing == null && objectKeys.any { snapshot.json(it).has(id) }) return null
        // Un ancien slot n'est pas une preuve suffisante pour créer de nouvelles associations.
        if (existing == null && group.companyId == null && group.companySlot != null) return null
        val zones = snapshot.zones.map { JSONObject(it.sourceJson) }.toMutableList()
        val values = snapshot.values.toMutableMap()
        preserveAddressMetadata(snapshot, zones, values, members.map { it.id }.toSet())
        if (existing == null && group.companyId == null &&
            !preserveLegacyCompanyLinksBeforeUnboundAddition(snapshot, zones, values, group.address)) return null
        val target = if (existing != null) zones.single { it.getString("id").trim() == id } else {
            JSONObject().put("id", id).put("address", group.address)
                .apply { group.companyId?.let { put("companyId", it) } }
                .also(zones::add)
        }
        target.put("label", draft.label.trim()).put("latitude", draft.latitude).put("longitude", draft.longitude)
            .put("radius", draft.radius).put("pointType", draft.role.token).put("pointSource", pointSource)
        listOf("name", "placeName", "zoneName", "zoneType", "type").forEach(target::remove)
        val contact = existing?.let { snapshot.contact(it) }
        val contacts = JSONObject(values["arrival_contacts"] ?: "{}")
        contacts.put(id, (if (existing == null) JSONObject() else contact?.let { JSONObject(it.toString()) } ?: JSONObject())
            .put("contactName", draft.contactName.trim()).put("phone", draft.phone.trim()).put("enabled", draft.notifyOnArrival))
        values["arrival_contacts"] = contacts.toString()
        val overrides = JSONObject(values["zone_point_overrides"] ?: "{}")
        overrides.put(id, JSONObject().put("latitude", draft.latitude).put("longitude", draft.longitude).put("source", pointSource))
        values["zone_point_overrides"] = overrides.toString()
        values["zone_point_confirmed"] = JSONObject(values["zone_point_confirmed"] ?: "{}").put(id, true).toString()
        return finish(snapshot, zones, values)
    }

    /** Crée le premier point d'un lieu, sans confondre une adresse partagée avec une identité. */
    fun addPlace(snapshot: Snapshot, address: String, companyId: String?, companySlot: Int?,
                 newZoneId: String, draft: GpsZoneDraftV2, maximumZones: Int,
                 pointSource: String): Change? {
        val cleanAddress = address.trim()
        val id = newZoneId.trim()
        if (cleanAddress.isBlank() || id.isBlank() || draft.error() != null ||
            pointSource !in setOf("geocoder", "map", "manual_coordinates")) return null
        if (companyId != null && companyId.trim().isEmpty()) return null
        if (companySlot != null && (companySlot !in 1..2 || companyId != null)) return null
        val scope = GpsPlaceScopeV2.of(companyId, companySlot, cleanAddress)
        if (snapshot.zones.size >= maximumZones || snapshot.zones.any { it.id == id } ||
            objectKeys.any { snapshot.json(it).has(id) }) return null
        val zones = snapshot.zones.map { JSONObject(it.sourceJson) }.toMutableList()
        val values = snapshot.values.toMutableMap()
        // Une ancienne métadonnée d'adresse reste attachée à son premier propriétaire,
        // même quand une autre entreprise ajoute maintenant une zone à cette adresse.
        val previousIds = snapshot.zones.filter {
            it.address?.equals(cleanAddress, ignoreCase = true) == true
        }.map { it.id }.toSet()
        preserveAddressMetadata(snapshot, zones, values, previousIds)
        if (scope.companyId == null && scope.companySlot == null &&
            !preserveLegacyCompanyLinksBeforeUnboundAddition(snapshot, zones, values, cleanAddress)) return null
        val projected = parsePersistedGpsZones(JSONArray().apply { zones.forEach { put(it) } }.toString())
        val existingGroups = groupGpsZonesByPlace(projected, snapshot.addresses()) ?: return null
        if (existingGroups.any { it.scope() == scope && it.zones.isNotEmpty() }) return null
        zones += JSONObject().put("id", id).put("address", cleanAddress)
            .put("label", draft.label.trim()).put("latitude", draft.latitude).put("longitude", draft.longitude)
            .put("radius", draft.radius).put("pointType", draft.role.token).put("pointSource", pointSource)
            .apply {
                scope.companyId?.let { put("companyId", it) }
                scope.companySlot?.let { put("companySlot", it) }
            }
        values["arrival_contacts"] = JSONObject(values["arrival_contacts"] ?: "{}")
            .put(id, JSONObject().put("contactName", draft.contactName.trim()).put("phone", draft.phone.trim())
                .put("enabled", draft.notifyOnArrival)).toString()
        val confirmed = pointSource != "geocoder"
        values["zone_point_confirmed"] = JSONObject(values["zone_point_confirmed"] ?: "{}").put(id, confirmed).toString()
        if (confirmed) values["zone_point_overrides"] = JSONObject(values["zone_point_overrides"] ?: "{}")
            .put(id, JSONObject().put("latitude", draft.latitude).put("longitude", draft.longitude).put("source", pointSource)).toString()
        values["address"] = (snapshot.addresses() + cleanAddress)
            .distinctBy { it.lowercase(Locale.ROOT) }.joinToString("\n")
        return finish(snapshot, zones, values)
    }

    /** Déplace uniquement un point existant ; ne recrée jamais une zone supprimée. */
    fun setPoint(snapshot: Snapshot, zoneId: String, latitude: Double, longitude: Double,
                 source: String): Change? {
        if (!latitude.isFinite() || latitude !in -90.0..90.0 ||
            !longitude.isFinite() || longitude !in -180.0..180.0 ||
            source !in setOf("map", "manual_coordinates")) return null
        val original = snapshot.zones.singleOrNull { it.id == zoneId && !it.isGpsCandidate() } ?: return null
        val zones = snapshot.zones.map { JSONObject(it.sourceJson) }
        val values = snapshot.values.toMutableMap()
        preserveAddressMetadata(snapshot, zones, values, setOf(zoneId))
        val target = zones.single { it.optString("id").trim() == zoneId }
        target.put("latitude", latitude).put("longitude", longitude).put("pointSource", source)
        // Le rayon, le type, le nom, l'entreprise et le contact restent ceux de la zone.
        values["zone_point_overrides"] = JSONObject(values["zone_point_overrides"] ?: "{}")
            .put(zoneId, JSONObject().put("latitude", latitude).put("longitude", longitude).put("source", source)).toString()
        values["zone_point_confirmed"] = JSONObject(values["zone_point_confirmed"] ?: "{}").put(zoneId, true).toString()
        if (values["pending_point_address"]?.equals(original.address, ignoreCase = true) == true) {
            values["pending_point_address"] = null
        }
        return finish(snapshot, zones, values)
    }

    fun setRole(snapshot: Snapshot, zoneId: String, role: GpsZoneRoleV2): Change? {
        val original = snapshot.zones.singleOrNull { it.id == zoneId && !it.isGpsCandidate() } ?: return null
        val zones = snapshot.zones.map { JSONObject(it.sourceJson) }
        val target = zones.single { it.optString("id").trim() == original.id }
        target.put("pointType", role.token)
        listOf("zoneType", "type").forEach(target::remove)
        return finish(snapshot, zones, snapshot.values.toMutableMap())
    }

    fun remove(snapshot: Snapshot, group: GpsPlaceGroup, zoneId: String? = null): Change? {
        val members = selected(snapshot, group) ?: return null
        val ids = if (zoneId == null) members.map { it.id }.toSet() else {
            if (members.none { it.id == zoneId }) return null
            setOf(zoneId)
        }
        if (ids.isEmpty() && !group.legacyOnly) return null
        val zones = snapshot.zones.filterNot { it.id in ids }.map { JSONObject(it.sourceJson) }
        val values = snapshot.values.toMutableMap()
        val stillUsed = zones.any { it.optString("address").trim().equals(group.address.trim(), ignoreCase = true) }
        for (key in objectKeys) {
            val map = snapshot.json(key)
            // address_names et address_company_slots sont des clés d'adresse legacy, pas des ID de zone.
            if (key !in setOf("address_names", "address_company_slots")) ids.forEach(map::remove)
            if (!stillUsed && snapshot.zones.none { it.id == group.address && it.id !in ids }) map.remove(group.address)
            values[key] = map.toString()
        }
        if (!stillUsed || group.legacyOnly) {
            values["address"] = snapshot.addresses().filterNot { it.equals(group.address, ignoreCase = true) }.joinToString("\n")
            if (values["pending_point_address"]?.equals(group.address, ignoreCase = true) == true) values["pending_point_address"] = null
        }
        return finish(snapshot, zones, values)
    }

    fun renameAddress(snapshot: Snapshot, group: GpsPlaceGroup, newAddress: String): Change? {
        val members = selected(snapshot, group) ?: return null
        val address = newAddress.trim()
        if (address.isBlank()) return null
        val nextScope = GpsPlaceScopeV2.of(group.companyId, group.companySlot, address)
        if (snapshot.groups().any { it.scope() == nextScope && it.scope() != group.scope() }) return null
        val ids = members.map { it.id }.toSet()
        val zones = snapshot.zones.map { JSONObject(it.sourceJson) }
        val values = snapshot.values.toMutableMap()
        preserveAddressMetadata(snapshot, zones, values, members.map { it.id }.toSet())
        zones.filter { it.optString("id").trim() in ids }.forEach { it.put("address", address) }
        val oldStillUsed = zones.any { it.optString("address").trim().equals(group.address, ignoreCase = true) }
        for (key in objectKeys) {
            val map = JSONObject(values[key] ?: "{}")
            if (!oldStillUsed && group.address != address && snapshot.zones.none { it.id == group.address }) {
                if (group.legacyOnly && map.has(group.address) && !map.has(address)) map.put(address, map.get(group.address))
                map.remove(group.address)
            }
            values[key] = map.toString()
        }
        val addresses = snapshot.addresses().filterNot { (group.legacyOnly || !oldStillUsed) && it.equals(group.address, ignoreCase = true) }.toMutableList()
        if (addresses.none { it.equals(address, ignoreCase = true) }) addresses += address
        values["address"] = addresses.distinctBy { it.lowercase(Locale.ROOT) }.joinToString("\n")
        if (values["pending_point_address"]?.equals(group.address, ignoreCase = true) == true) values["pending_point_address"] = null
        return finish(snapshot, zones, values)
    }
}
