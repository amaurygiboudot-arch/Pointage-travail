package com.amaury.pointage

import android.content.Context
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Outil privé de diagnostic : simule 3 jours consécutifs de présence >= 7 h. */
object SmartWorkplaceTestHarness {
    private const val SMART_PREFS = "smart_setup"
    private const val GPS_PREFS = "gps_settings"

    fun simulateThreeQualifiedDays(context: Context): String {
        val gps = context.getSharedPreferences(GPS_PREFS, Context.MODE_PRIVATE)
        val zones = runCatching { JSONArray(gps.getString("zones", "[]") ?: "[]") }.getOrElse { JSONArray() }

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

        if (zoneId.isBlank()) {
            return "Aucune zone candidate disponible. Renseigne d'abord un SIRET et laisse HoraTrack créer la zone GPS candidate."
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

        return "Simulation prête pour ${address.ifBlank { "la zone candidate" }} : 3 jours consécutifs de présence qualifiée ont été injectés."
    }
}
