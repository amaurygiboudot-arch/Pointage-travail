package com.amaury.pointage

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Lecture canonique de la configuration des zones GPS.
 *
 * Une absence de configuration est un état valide et distinct d'une configuration
 * corrompue. Une configuration corrompue n'est jamais transformée en liste vide ou
 * partiellement acceptée : les événements automatiques doivent alors rester fermés.
 */
internal data class StoredGpsZone(
    val id: String,
    val latitude: Double,
    val longitude: Double,
    val radius: Float,
    val address: String?,
    /** Identifiant stable V2 de l'entreprise explicitement associée à la zone. */
    val companyId: String?,
    /** Compatibilité historique uniquement avec les anciennes associations Entreprise 1/2. */
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

/**
 * Copie modifiable réservée aux parcours qui vont explicitement éditer la configuration.
 * Une configuration corrompue ne devient jamais une liste vide susceptible d'écraser
 * silencieusement les zones encore récupérables.
 */
internal fun GpsZonesReadResult.toMutableJsonArrayOrNull(): JSONArray? = when (this) {
    GpsZonesReadResult.Missing -> JSONArray()
    is GpsZonesReadResult.Valid -> JSONArray().apply {
        zones.forEach { put(JSONObject(it.sourceJson)) }
    }
    is GpsZonesReadResult.Corrupt -> null
}

internal fun readPersistedGpsZones(
    prefs: SharedPreferences,
    key: String = "zones"
): GpsZonesReadResult {
    if (!prefs.contains(key)) return GpsZonesReadResult.Missing
    val raw = try {
        prefs.getString(key, null)
    } catch (_: ClassCastException) {
        return GpsZonesReadResult.Corrupt("La clé $key n'est pas une chaîne JSON")
    }
    return parsePersistedGpsZones(raw)
}

internal fun parsePersistedGpsZones(raw: String?): GpsZonesReadResult {
    if (raw == null) return GpsZonesReadResult.Corrupt("Configuration GPS nulle")

    val array = try {
        JSONArray(raw)
    } catch (_: Exception) {
        return GpsZonesReadResult.Corrupt("JSON des zones GPS illisible")
    }

    val seenIds = mutableSetOf<String>()
    val zones = ArrayList<StoredGpsZone>(array.length())

    for (index in 0 until array.length()) {
        val item = array.optJSONObject(index)
            ?: return GpsZonesReadResult.Corrupt("Zone GPS #${index + 1} invalide")

        val id = item.optString("id").trim()
        if (id.isBlank()) {
            return GpsZonesReadResult.Corrupt("Zone GPS #${index + 1} sans identifiant")
        }
        if (!seenIds.add(id)) {
            return GpsZonesReadResult.Corrupt("Identifiant de zone GPS dupliqué : $id")
        }

        val latitude = item.optDouble("latitude", Double.NaN)
        val longitude = item.optDouble("longitude", Double.NaN)
        if (!latitude.isFinite() || latitude !in -90.0..90.0 ||
            !longitude.isFinite() || longitude !in -180.0..180.0
        ) {
            return GpsZonesReadResult.Corrupt("Coordonnées invalides pour la zone GPS $id")
        }

        val radius = if (item.has("radius")) {
            val storedRadius = item.optDouble("radius", Double.NaN)
            if (!storedRadius.isFinite() || storedRadius !in 50.0..1000.0) {
                return GpsZonesReadResult.Corrupt("Rayon invalide pour la zone GPS $id")
            }
            storedRadius.toFloat()
        } else {
            // Compatibilité avec les anciennes zones valides qui ne stockaient pas le rayon.
            150f
        }

        val address = item.optString("address")
            .trim()
            .takeIf { it.isNotBlank() }

        val companyId = if (item.has("companyId") && !item.isNull("companyId")) {
            val rawCompanyId = item.optString("companyId").trim()
            if (rawCompanyId.isBlank()) {
                return GpsZonesReadResult.Corrupt("Identifiant d'entreprise vide pour la zone GPS $id")
            }
            rawCompanyId
        } else null

        val companySlot = if (item.has("companySlot") && !item.isNull("companySlot")) {
            val rawSlot = item.opt("companySlot")
            val slot = when (rawSlot) {
                is Number -> rawSlot.toInt().takeIf { rawSlot.toDouble() == it.toDouble() }
                else -> null
            }
            if (slot !in 1..2) {
                return GpsZonesReadResult.Corrupt("Ancien slot d'entreprise invalide pour la zone GPS $id")
            }
            slot
        } else null

        val pointTypeToken = listOf("pointType", "zoneType", "type")
            .asSequence()
            .map { key -> item.optString(key).trim() }
            .firstOrNull { it.isNotBlank() }
        val label = listOf("name", "label", "placeName", "zoneName")
            .asSequence()
            .map { key -> item.optString(key).trim() }
            .firstOrNull { it.isNotBlank() }

        zones += StoredGpsZone(
            id = id,
            latitude = latitude,
            longitude = longitude,
            radius = radius,
            address = address,
            companyId = companyId,
            companySlot = companySlot,
            pointTypeToken = pointTypeToken,
            label = label,
            sourceJson = item.toString()
        )
    }

    return GpsZonesReadResult.Valid(zones)
}



internal data class GpsLocationEntry(
    val zoneId: String?,
    val address: String
)

internal fun resolveGpsLocationEntries(
    zonesResult: GpsZonesReadResult,
    savedAddresses: List<String>
): List<GpsLocationEntry>? {
    if (zonesResult is GpsZonesReadResult.Corrupt) return null

    val normalizedSaved = savedAddresses
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinctBy { it.lowercase() }

    val entries = mutableListOf<GpsLocationEntry>()
    val coveredAddresses = mutableSetOf<String>()

    if (zonesResult is GpsZonesReadResult.Valid) {
        zonesResult.zones.forEach { zone ->
            val source = runCatching { JSONObject(zone.sourceJson) }.getOrNull()
            if (source?.optBoolean("smartCandidate", false) == true) return@forEach
            val address = zone.address?.trim().orEmpty()
            if (address.isBlank()) return@forEach
            entries += GpsLocationEntry(zone.id, address)
            coveredAddresses += address.lowercase()
        }
    }

    normalizedSaved
        .filterNot { it.lowercase() in coveredAddresses }
        .forEach { entries += GpsLocationEntry(null, it) }

    return entries
}


internal data class GpsPlaceGroup(
    val address: String,
    val companyId: String?,
    val companySlot: Int?,
    val zones: List<StoredGpsZone>,
    val legacyOnly: Boolean
)

internal fun groupGpsZonesByPlace(
    zonesResult: GpsZonesReadResult,
    savedAddresses: List<String>
): List<GpsPlaceGroup>? {
    if (zonesResult is GpsZonesReadResult.Corrupt) return null
    data class GroupBuilder(
        val address: String,
        val companyId: String?,
        val companySlot: Int?,
        val zones: MutableList<StoredGpsZone>
    )
    fun companyKey(zone: StoredGpsZone): String = when {
        !zone.companyId.isNullOrBlank() -> "id:${zone.companyId.trim().lowercase()}"
        zone.companySlot != null -> "slot:${zone.companySlot}"
        else -> "legacy"
    }
    val groups = linkedMapOf<String, GroupBuilder>()
    if (zonesResult is GpsZonesReadResult.Valid) {
        zonesResult.zones.forEach { zone ->
            val source = runCatching { JSONObject(zone.sourceJson) }.getOrNull()
            if (source?.optBoolean("smartCandidate", false) == true) return@forEach
            val address = zone.address?.trim().orEmpty()
            if (address.isBlank()) return@forEach
            val key = "${companyKey(zone)}|${address.lowercase()}"
            val builder = groups.getOrPut(key) {
                GroupBuilder(address, zone.companyId, zone.companySlot, mutableListOf())
            }
            builder.zones += zone
        }
    }
    savedAddresses.map(String::trim).filter(String::isNotBlank).forEach { address ->
        val covered = groups.values.any {
            it.address.equals(address, ignoreCase = true) &&
                it.companyId == null && it.companySlot == null
        }
        if (!covered) {
            groups["legacy|${address.lowercase()}"] =
                GroupBuilder(address, null, null, mutableListOf())
        }
    }
    return groups.values.map { builder ->
        GpsPlaceGroup(
            address = builder.address,
            companyId = builder.companyId,
            companySlot = builder.companySlot,
            zones = builder.zones.toList(),
            legacyOnly = builder.zones.isEmpty()
        )
    }
}



internal data class GpsPlaceTypeSummary(
    val workZones: Int,
    val parkingZones: Int,
    val otherZones: Int
)

internal fun summarizeGpsPlaceTypes(group: GpsPlaceGroup): GpsPlaceTypeSummary {
    var work = 0
    var parking = 0
    var other = 0
    group.zones.forEach { zone ->
        when (zone.pointTypeToken?.trim()?.uppercase()) {
            "PARKING" -> parking++
            "OTHER", "AUTRE" -> other++
            else -> work++
        }
    }
    return GpsPlaceTypeSummary(work, parking, other)
}

internal fun uniqueGpsPlaceLabel(group: GpsPlaceGroup): String? {
    val labels = group.zones.mapNotNull { it.label?.trim()?.takeIf(String::isNotBlank) }.distinct()
    return labels.singleOrNull()
}

internal fun representativeGpsZoneId(group: GpsPlaceGroup): String? =
    group.zones.singleOrNull()?.id

internal sealed class GpsZoneRadiusResolution {
    data class Known(val radiusMeters: Float) : GpsZoneRadiusResolution()
    object Missing : GpsZoneRadiusResolution()
    object Ambiguous : GpsZoneRadiusResolution()
    object Corrupt : GpsZoneRadiusResolution()
}

internal fun resolveGpsZoneRadiusForAddress(
    zonesResult: GpsZonesReadResult,
    address: String
): GpsZoneRadiusResolution = when (zonesResult) {
    GpsZonesReadResult.Missing -> GpsZoneRadiusResolution.Missing
    is GpsZonesReadResult.Corrupt -> GpsZoneRadiusResolution.Corrupt
    is GpsZonesReadResult.Valid -> {
        val normalized = address.trim()
        val matches = zonesResult.zones.filter {
            it.address?.trim()?.equals(normalized, ignoreCase = true) == true
        }
        if (matches.isEmpty()) {
            GpsZoneRadiusResolution.Missing
        } else {
            val radii = matches.map { it.radius }.distinct()
            if (radii.size == 1) {
                GpsZoneRadiusResolution.Known(radii.single())
            } else {
                GpsZoneRadiusResolution.Ambiguous
            }
        }
    }
}


internal fun resolveUniqueGpsZoneIdForAddress(
    zonesResult: GpsZonesReadResult,
    address: String
): String? {
    val valid = zonesResult as? GpsZonesReadResult.Valid ?: return null
    val normalized = address.trim()
    if (normalized.isBlank()) return null
    val matches = valid.zones.filter {
        it.address?.trim()?.equals(normalized, ignoreCase = true) == true
    }
    return matches.singleOrNull()?.id
}


internal fun resolveGpsZoneScopedObject(
    values: JSONObject,
    zonesResult: GpsZonesReadResult,
    zoneId: String?,
    address: String
): JSONObject? {
    val canonicalId = zoneId?.trim().orEmpty()
    if (canonicalId.isNotBlank()) {
        values.optJSONObject(canonicalId)?.let { return it }
    }
    val uniqueOwner = resolveUniqueGpsZoneIdForAddress(zonesResult, address) ?: return null
    if (canonicalId.isNotBlank() && uniqueOwner != canonicalId) return null
    return values.optJSONObject(address)
}

internal fun putGpsZoneScopedObject(
    values: JSONObject,
    zoneId: String?,
    address: String,
    value: JSONObject
): Boolean {
    val canonicalId = zoneId?.trim().orEmpty()
    if (canonicalId.isBlank()) return false
    values.remove(address)
    values.put(canonicalId, JSONObject(value.toString()))
    return true
}

internal fun removeGpsZoneById(zones: JSONArray, zoneId: String): JSONArray? {
    val target = zoneId.trim()
    if (target.isBlank()) return null
    val result = JSONArray()
    var removed = false
    for (index in 0 until zones.length()) {
        val zone = zones.optJSONObject(index) ?: return null
        if (zone.optString("id").trim() == target) {
            if (removed) return null
            removed = true
        } else {
            result.put(JSONObject(zone.toString()))
        }
    }
    return result.takeIf { removed }
}


internal fun removeGpsPlaceZones(
    zones: JSONArray,
    address: String,
    companyId: String? = null,
    companySlot: Int? = null
): JSONArray? {
    val targetAddress = address.trim()
    val targetCompanyId = companyId?.trim()?.takeIf { it.isNotBlank() }
    if (targetAddress.isBlank()) return null
    val result = JSONArray()
    var removed = false
    for (index in 0 until zones.length()) {
        val zone = zones.optJSONObject(index) ?: return null
        val sameAddress = zone.optString("address").trim().equals(targetAddress, ignoreCase = true)
        val storedCompanyId = zone.optString("companyId").trim().takeIf { it.isNotBlank() }
        val storedSlot = if (zone.has("companySlot") && !zone.isNull("companySlot")) zone.optInt("companySlot") else null
        val sameCompany = when {
            targetCompanyId != null -> storedCompanyId == targetCompanyId
            companySlot != null -> storedCompanyId == null && storedSlot == companySlot
            else -> storedCompanyId == null && storedSlot == null
        }
        if (sameAddress && sameCompany) {
            removed = true
        } else {
            result.put(JSONObject(zone.toString()))
        }
    }
    return result.takeIf { removed }
}

internal fun moveGpsPlaceAddress(
    zones: JSONArray,
    oldAddress: String,
    newAddress: String,
    companyId: String? = null,
    companySlot: Int? = null
): Boolean {
    val oldValue = oldAddress.trim()
    val newValue = newAddress.trim()
    val targetCompanyId = companyId?.trim()?.takeIf { it.isNotBlank() }
    if (oldValue.isBlank() || newValue.isBlank()) return false
    var changed = false
    for (index in 0 until zones.length()) {
        val zone = zones.optJSONObject(index) ?: return false
        val sameAddress = zone.optString("address").trim().equals(oldValue, ignoreCase = true)
        val storedCompanyId = zone.optString("companyId").trim().takeIf { it.isNotBlank() }
        val storedSlot = if (zone.has("companySlot") && !zone.isNull("companySlot")) zone.optInt("companySlot") else null
        val sameCompany = when {
            targetCompanyId != null -> storedCompanyId == targetCompanyId
            companySlot != null -> storedCompanyId == null && storedSlot == companySlot
            else -> storedCompanyId == null && storedSlot == null
        }
        if (sameAddress && sameCompany) {
            zone.put("address", newValue)
            changed = true
        }
    }
    return changed
}


internal fun updateGpsZoneTypeById(
    zones: JSONArray,
    zoneId: String,
    pointType: String
): Boolean {
    val targetId = zoneId.trim()
    if (targetId.isBlank()) return false
    var changed = false
    for (index in 0 until zones.length()) {
        val zone = zones.optJSONObject(index) ?: continue
        if (zone.optString("id").trim() != targetId) continue
        zone.put("pointType", pointType)
        changed = true
        break
    }
    return changed
}


internal fun updateGpsZoneLabelById(
    zones: JSONArray,
    zoneId: String,
    label: String?
): Boolean {
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

internal fun resolveGpsZoneLabel(
    zonesResult: GpsZonesReadResult,
    zoneId: String?,
    address: String?
): String? {
    val valid = zonesResult as? GpsZonesReadResult.Valid ?: return null
    val normalizedId = zoneId?.trim().orEmpty()
    if (normalizedId.isNotBlank()) {
        return valid.zones.firstOrNull { it.id == normalizedId }
            ?.label?.trim()?.takeIf { it.isNotBlank() }
    }

    val normalizedAddress = address?.trim().orEmpty()
    if (normalizedAddress.isBlank()) return null
    val matches = valid.zones.filter {
        it.address?.trim()?.equals(normalizedAddress, ignoreCase = true) == true
    }
    if (matches.size != 1) return null
    return matches.single().label?.trim()?.takeIf { it.isNotBlank() }
}

internal fun resolveGpsRadiusForRefresh(existing: JSONObject?, fallbackRadius: Int): Int {
    val existingRadius = existing
        ?.optDouble("radius", Double.NaN)
        ?.takeIf { it.isFinite() && it in 50.0..1000.0 }
        ?.toInt()
    return existingRadius ?: fallbackRadius.coerceIn(50, 1000)
}

/** Met à jour la géométrie sans perdre le type, l'entreprise ou les métadonnées existantes. */
internal fun refreshedGpsZoneJson(
    existing: JSONObject?,
    id: String,
    address: String,
    latitude: Double,
    longitude: Double,
    radius: Int
): JSONObject {
    val zone = existing?.let { JSONObject(it.toString()) } ?: JSONObject()
    zone.put("id", id)
        .put("address", address)
        .put("latitude", latitude)
        .put("longitude", longitude)
        .put("radius", radius)
    if (listOf("pointType", "zoneType", "type").none { zone.optString(it).isNotBlank() }) {
        zone.put("pointType", "POSTE")
    }
    return zone
}
