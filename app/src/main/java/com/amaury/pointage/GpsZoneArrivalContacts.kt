package com.amaury.pointage

import android.content.Context
import org.json.JSONObject

/**
 * Propriétaire V2 des contacts d'arrivée liés aux zones GPS.
 *
 * Le contact canonique appartient à une zone stable (zoneId). L'ancien
 * arrival_contacts[address] n'est lu/écrit qu'en compatibilité tant qu'aucune
 * zone unique ne peut encore être résolue.
 */
internal object GpsZoneArrivalContacts {
    private const val PREFS = "gps_settings"
    private const val LEGACY_KEY = "arrival_contacts"

    fun get(context: Context, address: String): StoredGpsArrivalContact? =
        get(context, zoneId = null, address = address)

    fun get(context: Context, zoneId: String?, address: String): StoredGpsArrivalContact? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val canonical = resolveGpsZoneArrivalContact(readPersistedGpsZones(prefs), zoneId, address)
        if (canonical != null) return canonical

        val legacy = runCatching {
            JSONObject(prefs.getString(LEGACY_KEY, "{}") ?: "{}")
                .optJSONObject(address)
        }.getOrNull() ?: return null

        return StoredGpsArrivalContact(
            contactName = legacy.optString("contactName").trim().takeIf { it.isNotBlank() },
            phone = legacy.optString("phone").trim().takeIf { it.isNotBlank() },
            enabled = legacy.optBoolean("enabled", false)
        )
    }

    fun put(
        context: Context,
        address: String,
        contactName: String?,
        phone: String?,
        enabled: Boolean
    ) {
        put(
            context = context,
            zoneId = null,
            address = address,
            contactName = contactName,
            phone = phone,
            enabled = enabled
        )
    }

    fun put(
        context: Context,
        zoneId: String?,
        address: String,
        contactName: String?,
        phone: String?,
        enabled: Boolean
    ) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = readPersistedGpsZones(prefs)
        if (stored is GpsZonesReadResult.Corrupt) return

        val normalizedAddress = address.trim()
        val resolvedId = zoneId?.trim()?.takeIf { it.isNotBlank() }
            ?: (stored as? GpsZonesReadResult.Valid)
                ?.zones
                ?.filter { it.address?.trim()?.equals(normalizedAddress, ignoreCase = true) == true }
                ?.singleOrNull()
                ?.id

        val contact = StoredGpsArrivalContact(
            contactName = contactName?.trim()?.takeIf { it.isNotBlank() },
            phone = phone?.trim()?.takeIf { it.isNotBlank() },
            enabled = enabled
        )

        if (!resolvedId.isNullOrBlank()) {
            val zones = stored.toMutableJsonArrayOrNull() ?: return
            if (updateGpsZoneArrivalContactById(zones, resolvedId, contact)) {
                val legacy = legacyObject(context)
                legacy.remove(normalizedAddress)
                prefs.edit()
                    .putString("zones", zones.toString())
                    .putString(LEGACY_KEY, legacy.toString())
                    .apply()
                return
            }
        }

        // Compatibilité transitoire : aucun propriétaire de zone unique n'existe encore.
        val legacy = legacyObject(context)
        if (contact.contactName.isNullOrBlank() && contact.phone.isNullOrBlank() && !enabled) {
            legacy.remove(normalizedAddress)
        } else {
            legacy.put(
                normalizedAddress,
                JSONObject()
                    .put("contactName", contact.contactName.orEmpty())
                    .put("phone", contact.phone.orEmpty())
                    .put("enabled", enabled)
            )
        }
        prefs.edit().putString(LEGACY_KEY, legacy.toString()).apply()
    }

    fun remove(context: Context, zoneId: String?, address: String) {
        put(
            context = context,
            zoneId = zoneId,
            address = address,
            contactName = null,
            phone = null,
            enabled = false
        )
    }

    private fun legacyObject(context: Context): JSONObject {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return runCatching {
            JSONObject(prefs.getString(LEGACY_KEY, "{}") ?: "{}")
        }.getOrElse { JSONObject() }
    }
}
