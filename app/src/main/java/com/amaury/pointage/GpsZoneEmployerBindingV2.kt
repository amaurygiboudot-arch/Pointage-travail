package com.amaury.pointage

import android.content.SharedPreferences
import org.json.JSONObject
import java.util.Locale

/** Shared compatibility binding for arrivals and departures. -1 means explicit but unprovable. */
internal fun resolveLegacyGpsAddressCompanySlotV2(prefs: SharedPreferences, zone: StoredGpsZone): Int? {
    if (!prefs.contains("address_company_slots")) return null
    val raw = runCatching { prefs.getString("address_company_slots", null) }.getOrNull() ?: return -1
    val map = runCatching { JSONObject(raw) }.getOrNull() ?: return -1
    val candidates = listOfNotNull(zone.address, zone.id).map { it.trim().lowercase(Locale.ROOT) }
        .filter { it.isNotEmpty() }.toSet()
    val matches = map.keys().asSequence().filter { it.trim().lowercase(Locale.ROOT) in candidates }.toList()
    if (matches.isEmpty()) return null
    val slots = matches.map { key ->
        when (val value = map.opt(key)) {
            is Int -> value
            is Long -> value.toInt().takeIf { it.toLong() == value }
            is String -> value.trim().toIntOrNull()
            else -> null
        }?.takeIf { it in 1..2 } ?: return -1
    }.distinct()
    return slots.singleOrNull() ?: -1
}
