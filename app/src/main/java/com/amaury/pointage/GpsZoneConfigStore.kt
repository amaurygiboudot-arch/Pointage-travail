package com.amaury.pointage

import android.content.SharedPreferences
import org.json.JSONArray

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
        val companySlot = item.optInt("companySlot", 0)
            .takeIf { it in 1..2 }
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
            companySlot = companySlot,
            pointTypeToken = pointTypeToken,
            label = label,
            sourceJson = item.toString()
        )
    }

    return GpsZonesReadResult.Valid(zones)
}
