package com.amaury.pointage

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Lecture canonique : une configuration corrompue ne devient jamais une liste vide. */
internal data class StoredGpsZone(
    val id: String,
    val latitude: Double,
    val longitude: Double,
    val radius: Float,
    val address: String?,
    val companyId: String?,
    val companySlot: Int?,
    val pointTypeToken: String?,
    val label: String?,
    val sourceJson: String
) {
    fun asWorkZone(): WorkZone = WorkZone(id, latitude, longitude, radius)
}

internal sealed class GpsZonesReadResult {
    object Missing : GpsZonesReadResult()
    data class Valid(val zones: List<StoredGpsZone>) : GpsZonesReadResult()
    data class Corrupt(val reason: String) : GpsZonesReadResult()
}

internal fun GpsZonesReadResult.toMutableJsonArrayOrNull(): JSONArray? = when (this) {
    GpsZonesReadResult.Missing -> JSONArray()
    is GpsZonesReadResult.Valid -> JSONArray().apply {
        zones.forEach { put(JSONObject(it.sourceJson)) }
    }
    is GpsZonesReadResult.Corrupt -> null
}

internal fun readPersistedGpsZones(prefs: SharedPreferences, key: String = "zones"): GpsZonesReadResult {
    if (!prefs.contains(key)) return GpsZonesReadResult.Missing
    val raw = try { prefs.getString(key, null) } catch (_: ClassCastException) {
        return GpsZonesReadResult.Corrupt("La clé $key n'est pas une chaîne JSON")
    }
    return parsePersistedGpsZones(raw)
}

internal fun parsePersistedGpsZones(raw: String?): GpsZonesReadResult {
    if (raw == null) return GpsZonesReadResult.Corrupt("Configuration GPS nulle")
    val array = try { JSONArray(raw) } catch (_: Exception) {
        return GpsZonesReadResult.Corrupt("JSON des zones GPS illisible")
    }
    val seenIds = mutableSetOf<String>()
    val zones = ArrayList<StoredGpsZone>(array.length())
    for (index in 0 until array.length()) {
        val item = array.optJSONObject(index)
            ?: return GpsZonesReadResult.Corrupt("Zone GPS #${index + 1} invalide")
        val id = item.optString("id").trim()
        if (id.isBlank()) return GpsZonesReadResult.Corrupt("Zone GPS #${index + 1} sans identifiant")
        if (!seenIds.add(id)) return GpsZonesReadResult.Corrupt("Identifiant de zone GPS dupliqué : $id")
        val latitude = item.optDouble("latitude", Double.NaN)
        val longitude = item.optDouble("longitude", Double.NaN)
        if (!latitude.isFinite() || latitude !in -90.0..90.0 ||
            !longitude.isFinite() || longitude !in -180.0..180.0) {
            return GpsZonesReadResult.Corrupt("Coordonnées invalides pour la zone GPS $id")
        }
        val radius = if (item.has("radius")) {
            val storedRadius = item.optDouble("radius", Double.NaN)
            if (!storedRadius.isFinite() || storedRadius !in 50.0..1000.0) {
                return GpsZonesReadResult.Corrupt("Rayon invalide pour la zone GPS $id")
            }
            storedRadius.toFloat()
        } else 150f // Compatibilité avec les anciennes zones sans rayon.
        val address = item.optString("address").trim().takeIf { it.isNotBlank() }
        val companyId = if (item.has("companyId") && !item.isNull("companyId")) {
            val value = item.optString("companyId").trim()
            if (value.isBlank()) return GpsZonesReadResult.Corrupt("Identifiant d'entreprise vide pour la zone GPS $id")
            value
        } else null
        val companySlot = if (item.has("companySlot") && !item.isNull("companySlot")) {
            val rawSlot = item.opt("companySlot")
            val slot = when (rawSlot) {
                is Number -> rawSlot.toInt().takeIf { rawSlot.toDouble() == it.toDouble() }
                else -> null
            }
            if (slot !in 1..2) return GpsZonesReadResult.Corrupt("Ancien slot d'entreprise invalide pour la zone GPS $id")
            slot
        } else null
        val pointTypeToken = listOf("pointType", "zoneType", "type").asSequence()
            .map { item.optString(it).trim() }.firstOrNull { it.isNotBlank() }
        val label = listOf("name", "label", "placeName", "zoneName").asSequence()
            .map { item.optString(it).trim() }.firstOrNull { it.isNotBlank() }
        zones += StoredGpsZone(id, latitude, longitude, radius, address, companyId,
            companySlot, pointTypeToken, label, item.toString())
    }
    return GpsZonesReadResult.Valid(zones)
}

internal data class GpsLocationEntry(val zoneId: String?, val address: String)

internal fun resolveGpsLocationEntries(zonesResult: GpsZonesReadResult, savedAddresses: List<String>): List<GpsLocationEntry>? {
    if (zonesResult is GpsZonesReadResult.Corrupt) return null
    val entries = mutableListOf<GpsLocationEntry>()
    val covered = mutableSetOf<String>()
    if (zonesResult is GpsZonesReadResult.Valid) {
        zonesResult.zones.forEach { zone ->
            if (zone.isGpsCandidate()) return@forEach
            val address = zone.address?.trim().orEmpty()
            if (address.isBlank()) return@forEach
            entries += GpsLocationEntry(zone.id, address)
            covered += address.lowercase(Locale.ROOT)
        }
    }
    savedAddresses.map(String::trim).filter(String::isNotBlank)
        .distinctBy { it.lowercase(Locale.ROOT) }
        .filterNot { it.lowercase(Locale.ROOT) in covered }
        .forEach { entries += GpsLocationEntry(null, it) }
    return entries
}

internal fun StoredGpsZone.isGpsCandidate(): Boolean =
    JSONObject(sourceJson).optBoolean("smartCandidate", false)

internal fun StoredGpsZone.placeScope(): GpsPlaceScopeV2 =
    GpsPlaceScopeV2.of(companyId, companySlot, address.orEmpty())

/** Les anciens points sans type étaient des postes ; tous les nouveaux brouillons ont un rôle explicite. */
internal fun StoredGpsZone.roleForContextV2(): GpsZoneRoleV2 =
    if (pointTypeToken.isNullOrBlank()) GpsZoneRoleV2.WORK else GpsZoneRoleV2.fromToken(pointTypeToken)

internal data class GpsPlaceGroup(
    val address: String,
    val companyId: String?,
    val companySlot: Int?,
    val zones: List<StoredGpsZone>,
    val legacyOnly: Boolean
) {
    fun scope(): GpsPlaceScopeV2 = GpsPlaceScopeV2.of(companyId, companySlot, address)
}

internal fun groupGpsZonesByPlace(zonesResult: GpsZonesReadResult, savedAddresses: List<String>): List<GpsPlaceGroup>? {
    if (zonesResult is GpsZonesReadResult.Corrupt) return null
    val groups = linkedMapOf<GpsPlaceScopeV2, MutableList<StoredGpsZone>>()
    val addresses = linkedMapOf<GpsPlaceScopeV2, String>()
    if (zonesResult is GpsZonesReadResult.Valid) {
        zonesResult.zones.forEach { zone ->
            if (zone.isGpsCandidate() || zone.address.isNullOrBlank()) return@forEach
            val scope = zone.placeScope()
            groups.getOrPut(scope) { mutableListOf() }.add(zone)
            addresses.putIfAbsent(scope, zone.address.trim())
        }
    }
    // La liste d'adresses est une projection de compatibilité, pas une seconde entreprise.
    val covered = groups.keys.map { it.addressKey }.toSet()
    savedAddresses.map(String::trim).filter(String::isNotBlank).forEach { address ->
        val scope = GpsPlaceScopeV2.of(null, null, address)
        if (scope.addressKey !in covered) {
            groups.getOrPut(scope) { mutableListOf() }
            addresses.putIfAbsent(scope, address)
        }
    }
    return groups.map { (scope, zones) ->
        GpsPlaceGroup(addresses.getValue(scope), scope.companyId, scope.companySlot,
            zones.toList(), legacyOnly = zones.isEmpty())
    }
}

internal data class GpsPlaceTypeSummary(val workZones: Int, val parkingZones: Int, val pauseZones: Int, val otherZones: Int)

internal fun summarizeGpsPlaceTypes(group: GpsPlaceGroup): GpsPlaceTypeSummary {
    val roles = group.zones.map { it.roleForContextV2() }
    return GpsPlaceTypeSummary(roles.count { it == GpsZoneRoleV2.WORK },
        roles.count { it == GpsZoneRoleV2.PARKING }, roles.count { it == GpsZoneRoleV2.BREAK },
        roles.count { it == GpsZoneRoleV2.OTHER })
}

internal fun uniqueGpsPlaceLabel(group: GpsPlaceGroup): String? {
    val labels = group.zones.map { it.label?.trim()?.takeIf(String::isNotBlank) }
    return if (labels.isNotEmpty() && labels.none { it == null }) labels.distinct().singleOrNull() else null
}
internal fun representativeGpsZoneId(group: GpsPlaceGroup): String? = group.zones.singleOrNull()?.id

internal sealed class GpsZoneRadiusResolution {
    data class Known(val radiusMeters: Float) : GpsZoneRadiusResolution()
    object Missing : GpsZoneRadiusResolution()
    object Ambiguous : GpsZoneRadiusResolution()
    object Corrupt : GpsZoneRadiusResolution()
}

internal fun resolveGpsZoneRadiusForAddress(zonesResult: GpsZonesReadResult, address: String): GpsZoneRadiusResolution = when (zonesResult) {
    GpsZonesReadResult.Missing -> GpsZoneRadiusResolution.Missing
    is GpsZonesReadResult.Corrupt -> GpsZoneRadiusResolution.Corrupt
    is GpsZonesReadResult.Valid -> {
        val matches = zonesResult.zones.filter { it.address?.trim()?.equals(address.trim(), ignoreCase = true) == true }
        val radii = matches.map { it.radius }.distinct()
        when (radii.size) {
            0 -> GpsZoneRadiusResolution.Missing
            1 -> GpsZoneRadiusResolution.Known(radii.single())
            else -> GpsZoneRadiusResolution.Ambiguous
        }
    }
}

internal fun resolveUniqueGpsZoneIdForAddress(zonesResult: GpsZonesReadResult, address: String): String? {
    val valid = zonesResult as? GpsZonesReadResult.Valid ?: return null
    val normalized = address.trim()
    if (normalized.isBlank()) return null
    return valid.zones.filter { it.address?.trim()?.equals(normalized, ignoreCase = true) == true }.singleOrNull()?.id
}

internal fun resolveGpsZoneScopedObject(values: JSONObject, zonesResult: GpsZonesReadResult, zoneId: String?, address: String): JSONObject? {
    val canonicalId = zoneId?.trim().orEmpty()
    if (canonicalId.isNotBlank()) values.optJSONObject(canonicalId)?.let { return it }
    val uniqueOwner = resolveUniqueGpsZoneIdForAddress(zonesResult, address) ?: return null
    if (canonicalId.isNotBlank() && uniqueOwner != canonicalId) return null
    return values.optJSONObject(address)
}

internal fun putGpsZoneScopedObject(values: JSONObject, zoneId: String?, address: String, value: JSONObject): Boolean {
    val canonicalId = zoneId?.trim().orEmpty()
    if (canonicalId.isBlank()) return false
    values.remove(address)
    values.put(canonicalId, JSONObject(value.toString()))
    return true
}

internal fun removeGpsZoneById(zones: JSONArray, zoneId: String): JSONArray? {
    val valid = parsePersistedGpsZones(zones.toString()) as? GpsZonesReadResult.Valid ?: return null
    val target = zoneId.trim()
    if (valid.zones.none { it.id == target }) return null
    return JSONArray().apply { valid.zones.filterNot { it.id == target }.forEach { put(JSONObject(it.sourceJson)) } }
}

internal fun removeGpsPlaceZones(zones: JSONArray, address: String, companyId: String? = null, companySlot: Int? = null): JSONArray? {
    val valid = parsePersistedGpsZones(zones.toString()) as? GpsZonesReadResult.Valid ?: return null
    val scope = GpsPlaceScopeV2.of(companyId, companySlot, address)
    if (scope.addressKey.isBlank()) return null
    val selected = valid.zones.filter { it.placeScope() == scope && !it.isGpsCandidate() }.map { it.id }.toSet()
    if (selected.isEmpty()) return null
    return JSONArray().apply { valid.zones.filterNot { it.id in selected }.forEach { put(JSONObject(it.sourceJson)) } }
}

internal fun moveGpsPlaceAddress(zones: JSONArray, oldAddress: String, newAddress: String, companyId: String? = null, companySlot: Int? = null): Boolean {
    // Valider l'ensemble AVANT toute écriture : pas de modification partielle en cas d'erreur.
    val valid = parsePersistedGpsZones(zones.toString()) as? GpsZonesReadResult.Valid ?: return false
    val scope = GpsPlaceScopeV2.of(companyId, companySlot, oldAddress)
    val address = newAddress.trim()
    if (scope.addressKey.isBlank() || address.isBlank()) return false
    val selected = valid.zones.filter { it.placeScope() == scope && !it.isGpsCandidate() }.map { it.id }.toSet()
    if (selected.isEmpty()) return false
    for (index in 0 until zones.length()) {
        val zone = zones.getJSONObject(index)
        if (zone.getString("id").trim() in selected) zone.put("address", address)
    }
    return true
}

internal fun updateGpsZoneTypeById(zones: JSONArray, zoneId: String, pointType: String): Boolean {
    val targetId = zoneId.trim()
    if (targetId.isBlank()) return false
    for (index in 0 until zones.length()) {
        val zone = zones.optJSONObject(index) ?: continue
        if (zone.optString("id").trim() != targetId) continue
        zone.put("pointType", pointType)
        return true
    }
    return false
}

internal fun updateGpsZoneLabelById(zones: JSONArray, zoneId: String, label: String?): Boolean {
    val targetId = zoneId.trim()
    if (targetId.isBlank()) return false
    val normalizedLabel = label?.trim().orEmpty()
    for (index in 0 until zones.length()) {
        val zone = zones.optJSONObject(index) ?: continue
        if (zone.optString("id").trim() != targetId) continue
        if (normalizedLabel.isBlank()) {
            listOf("name", "label", "placeName", "zoneName").forEach(zone::remove)
        } else {
            zone.put("label", normalizedLabel)
            listOf("name", "placeName", "zoneName").forEach(zone::remove)
        }
        return true
    }
    return false
}

internal fun resolveGpsZoneLabel(zonesResult: GpsZonesReadResult, zoneId: String?, address: String?): String? {
    val valid = zonesResult as? GpsZonesReadResult.Valid ?: return null
    val normalizedId = zoneId?.trim().orEmpty()
    if (normalizedId.isNotBlank()) return valid.zones.firstOrNull { it.id == normalizedId }?.label?.trim()?.takeIf { it.isNotBlank() }
    val normalizedAddress = address?.trim().orEmpty()
    if (normalizedAddress.isBlank()) return null
    val matches = valid.zones.filter { it.address?.trim()?.equals(normalizedAddress, ignoreCase = true) == true }
    if (matches.size != 1) return null
    return matches.single().label?.trim()?.takeIf { it.isNotBlank() }
}

internal fun resolveGpsRadiusForRefresh(existing: JSONObject?, fallbackRadius: Int): Int {
    val radius = existing?.optDouble("radius", Double.NaN)?.takeIf { it.isFinite() && it in 50.0..1000.0 }?.toInt()
    return radius ?: fallbackRadius.coerceIn(50, 1000)
}

/** Met à jour la géométrie sans perdre le type, l'entreprise ou les métadonnées existantes. */
internal fun refreshedGpsZoneJson(existing: JSONObject?, id: String, address: String, latitude: Double, longitude: Double, radius: Int): JSONObject {
    val zone = existing?.let { JSONObject(it.toString()) } ?: JSONObject()
    zone.put("id", id).put("address", address).put("latitude", latitude).put("longitude", longitude).put("radius", radius)
    if (listOf("pointType", "zoneType", "type").none { zone.optString(it).isNotBlank() }) zone.put("pointType", "POSTE")
    return zone
}
