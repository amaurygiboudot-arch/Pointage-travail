package com.amaury.pointage

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.UUID

/** Outil privé de diagnostic : simule 3 jours consécutifs de présence >= 7 h sans dépendre d'un SIRET. */
object SmartWorkplaceTestHarness {
    private const val SMART_PREFS = "smart_setup"
    private const val GPS_PREFS = "gps_settings"

    fun simulateThreeQualifiedDays(context: Context): String {
        val gps = context.getSharedPreferences(GPS_PREFS, Context.MODE_PRIVATE)
        val zones = runCatching { JSONArray(gps.getString("zones", "[]") ?: "[]") }.getOrElse { JSONArray() }

        // Réutilise d'abord une vraie zone candidate si HoraTrack en a déjà appris une.
        var zoneId = ""
        var address = ""
        var companySlot = 1
        for (i in 0 until zones.length()) {
            val zone = zones.optJSONObject(i) ?: continue
            if (!zone.optBoolean("smartCandidate", false)) continue
            zoneId = zone.optString("id")
            address = zone.optString("address")
            companySlot = zone.optInt("companySlot", 1).coerceIn(1, 2)
            if (zoneId.isNotBlank()) break
        }

        // Aucun SIRET/aucune zone : le test crée une candidate temporaire autour de la dernière position connue.
        if (zoneId.isBlank()) {
            if (!hasLocationPermission(context)) {
                return "Autorisation de localisation requise pour cette simulation."
            }

            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            val providers = runCatching { locationManager?.getProviders(true).orEmpty() }.getOrDefault(emptyList())
            val location = providers.asSequence()
                .mapNotNull { provider ->
                    try {
                        locationManager?.getLastKnownLocation(provider)
                    } catch (_: SecurityException) {
                        null
                    }
                }
                .maxByOrNull { it.time }
                ?: return "Position GPS indisponible. Active la localisation puis réessaie : aucun SIRET n'est nécessaire."

            zoneId = "smart_auto_test_${UUID.randomUUID()}"
            address = "Position actuelle — détection automatique"
            val radius = gps.getInt("radius", 150).coerceIn(50, 1000)
            zones.put(
                JSONObject()
                    .put("id", zoneId)
                    .put("address", address)
                    .put("latitude", location.latitude)
                    .put("longitude", location.longitude)
                    .put("radius", radius)
                    .put("pointSource", "smart_auto_test")
                    .put("companySlot", companySlot)
                    .put("smartCandidate", true)
                    .put("testOnly", true)
            )
            gps.edit().putString("zones", zones.toString()).putBoolean("enabled", true).apply()
        }

        val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.FRANCE)
        val cal = Calendar.getInstance(Locale.FRANCE)
        val days = mutableListOf<String>()
        repeat(3) {
            days += formatter.format(cal.time)
            cal.add(Calendar.DAY_OF_YEAR, -1)
        }
        days.sort()

        context.getSharedPreferences(SMART_PREFS, Context.MODE_PRIVATE).edit()
            .putString("candidate_days_$zoneId", JSONArray(days).toString())
            .putString("pending_workplace_zone", zoneId)
            .putString("pending_workplace_address", address)
            .putInt("pending_workplace_company", companySlot)
            .putBoolean("proposal_dialog_visible", false)
            .remove("proposal_silenced_$zoneId")
            .putInt("proposal_count_$zoneId", 0)
            .apply()

        return "Simulation prête : 3 jours consécutifs de présence qualifiée ont été injectés à $address. Aucun SIRET requis."
    }

    private fun hasLocationPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }
}
