package com.amaury.pointage

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

/**
 * Façade propriétaire V2 des réglages GPS.
 *
 * Le stockage physique reste volontairement `gps_settings` pour préserver les
 * installations existantes. Les consommateurs doivent progressivement passer
 * par cette façade afin que le nom du fichier et les règles de lecture/écriture
 * ne soient plus dupliqués dans l'UI.
 */
internal object GpsSettingsV2 {
    private const val PREFS = "gps_settings"
    private const val KEY_ZONES = "zones"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_RADIUS = "radius"

    fun preferences(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun readZones(context: Context): GpsZonesReadResult =
        readPersistedGpsZones(preferences(context), KEY_ZONES)

    fun mutableZonesOrNull(context: Context): JSONArray? =
        readZones(context).toMutableJsonArrayOrNull()

    fun isAutomaticEnabled(context: Context): Boolean =
        preferences(context).getBoolean(KEY_ENABLED, false)

    fun defaultRadiusMeters(context: Context): Int =
        preferences(context).getInt(KEY_RADIUS, 150).coerceIn(50, 1000)
}
