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
