package com.amaury.pointage

import android.content.Context
import android.content.SharedPreferences
import android.location.Geocoder
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID
import kotlin.math.abs

/**
 * Configuration intelligente activée par défaut à la première installation.
 *
 * 1) Dès qu'une entreprise est enregistrée par SIRET, son adresse publique devient
 *    automatiquement une première zone GPS candidate (rayon 150 m).
 * 2) Après plusieurs journées, les pauses supplémentaires qui reviennent aux mêmes
 *    horaires sont apprises et peuvent devenir automatiquement les pauses de base.
 *
 * Aucune permission Android n'est contournée : le geofencing n'est réellement
 * enregistré que lorsque la localisation fine + arrière-plan ont été accordées.
 */
object SmartSetupManager : SharedPreferences.OnSharedPreferenceChangeListener {
    private const val PREFS = "smart_setup"
    private const val KEY_INITIALIZED = "initialized"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_WORKPLACE = "learn_workplace"
    private const val KEY_PAUSES = "learn_pauses"

    @Volatile private var appContext: Context? = null
    @Volatile private var listening = false
    @Volatile private var busyCompany = false
    @Volatile private var busyPause = false

    fun init(context: Context) {
        val app = context.applicationContext
        appContext = app
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_INITIALIZED, false)) {
            prefs.edit()
                .putBoolean(KEY_INITIALIZED, true)
                .putBoolean(KEY_ENABLED, true)
                .putBoolean(KEY_WORKPLACE, true)
                .putBoolean(KEY_PAUSES, true)
                .apply()
        }
        if (!listening) {
            app.getSharedPreferences("salary_settings", Context.MODE_PRIVATE)
                .registerOnSharedPreferenceChangeListener(this)
            app.getSharedPreferences("pointage", Context.MODE_PRIVATE)
                .registerOnSharedPreferenceChangeListener(this)
            listening = true
        }
        syncKnownCompaniesAsync(app)
        learnPausesAsync(app)
    }

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        val context = appContext ?: return
        if (!enabled(context)) return
        when {
            key == "company_address" || key == "company_siret" ||
                key == "company2_address" || key == "company2_siret" -> syncKnownCompaniesAsync(context)
            key == "data" -> learnPausesAsync(context)
        }
    }

    private fun syncKnownCompaniesAsync(context: Context) {
        if (!enabled(context) || !context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_WORKPLACE, true)) return
        if (busyCompany) return
        busyCompany = true
        Thread {
            try {
                val salary = context.getSharedPreferences("salary_settings", Context.MODE_PRIVATE)
                for (slot in 1..2) {
                    val prefix = if (slot == 2) "company2_" else "company_"
                    val siret = salary.getString(prefix + "siret", "").orEmpty()
                    val address = salary.getString(prefix + "address", "").orEmpty().trim()
                    if (siret.length == 14 && address.isNotBlank()) ensureSmartZone(context, slot, address)
                }
            } finally {
                busyCompany = false
            }
        }.start()
    }

    private fun ensureSmartZone(context: Context, companySlot: Int, address: String) {
        val gps = context.getSharedPreferences("gps_settings", Context.MODE_PRIVATE)
        val zones = runCatching { JSONArray(gps.getString("zones", "[]") ?: "[]") }.getOrElse { JSONArray() }

        for (i in 0 until zones.length()) {
            val z = zones.optJSONObject(i) ?: continue
            if (z.optString("address").trim().equals(address, ignoreCase = true)) return
        }

        val geocoded = runCatching {
            Geocoder(context, Locale.FRANCE).getFromLocationName(address, 1)?.firstOrNull()
        }.getOrNull() ?: return

        val radius = gps.getInt("radius", 150).coerceIn(50, 1000)
        val id = "smart_${companySlot}_${UUID.randomUUID()}"
        zones.put(
            JSONObject()
                .put("id", id)
                .put("address", address)
                .put("latitude", geocoded.latitude)
                .put("longitude", geocoded.longitude)
                .put("radius", radius)
                .put("pointSource", "smart_siret")
                .put("companySlot", companySlot)
        )

        val addresses = gps.getString("address", "").orEmpty().lines()
            .map { it.trim() }.filter { it.isNotBlank() }.toMutableList()
        if (addresses.none { it.equals(address, ignoreCase = true) }) addresses += address

        val companyMap = runCatching {
            JSONObject(gps.getString("address_company_slots", "{}") ?: "{}")
        }.getOrElse { JSONObject() }
        companyMap.put(address, companySlot)

        gps.edit()
            .putString("zones", zones.toString())
            .putString("address", addresses.distinctBy { it.lowercase(Locale.FRANCE) }.take(10).joinToString("\n"))
            .putString("address_company_slots", companyMap.toString())
            .putBoolean("enabled", true)
            .putBoolean("smart_setup_zone_created", true)
            .apply()

        if (GeofenceManager.hasRequiredPermissions(context)) registerStoredZones(context)
    }

    private fun registerStoredZones(context: Context) {
        val gps = context.getSharedPreferences("gps_settings", Context.MODE_PRIVATE)
        val raw = runCatching { JSONArray(gps.getString("zones", "[]") ?: "[]") }.getOrElse { JSONArray() }
        val zones = mutableListOf<WorkZone>()
        for (i in 0 until raw.length()) {
            val z = raw.optJSONObject(i) ?: continue
            val id = z.optString("id")
            val lat = z.optDouble("latitude", Double.NaN)
            val lon = z.optDouble("longitude", Double.NaN)
            val radius = z.optDouble("radius", 150.0).toFloat().coerceIn(50f, 1000f)
            if (id.isNotBlank() && lat.isFinite() && lon.isFinite()) zones += WorkZone(id, lat, lon, radius)
        }
        if (zones.isNotEmpty()) GeofenceManager.registerAll(context, zones)
    }

    private fun learnPausesAsync(context: Context) {
        if (!enabled(context) || !context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_PAUSES, true)) return
        if (busyPause) return
        busyPause = true
        Thread {
            try {
                for (company in 1..2) learnCompanyPauses(context, company)
            } finally {
                busyPause = false
            }
        }.start()
    }

    private data class PauseSample(val startMinute: Int, val duration: Int)

    private fun learnCompanyPauses(context: Context, companySlot: Int) {
        val missing = (1..2).filter { CompanyBasePauseSettings.pause(context, companySlot, it) == null }
        if (missing.isEmpty()) return

        val data = PointageStore.load(context)
        val samples = mutableListOf<PauseSample>()
        var sessionsSeen = 0
        for (i in data.length() - 1 downTo 0) {
            if (sessionsSeen >= 14) break
            val item = data.optJSONObject(i) ?: continue
            if (item.isNull("exit") || item.optInt("companySlot", 1) != companySlot) continue
            val entry = item.optLong("entry", -1L)
            if (entry <= 0L) continue
            sessionsSeen++
            val pauses = item.optJSONArray("pauses") ?: continue
            for (j in 0 until pauses.length()) {
                val p = pauses.optJSONObject(j) ?: continue
                if (p.optBoolean("automatic", false)) continue
                val start = p.optLong("start", -1L)
                val end = p.optLong("end", -1L)
                if (start <= 0L || end <= start) continue
                val c = java.util.Calendar.getInstance(Locale.FRANCE).apply { timeInMillis = start }
                val minute = c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE)
                val duration = ((end - start) / 60_000L).toInt().coerceIn(1, 180)
                samples += PauseSample(minute, duration)
            }
        }
        if (samples.size < 3) return

        val candidates = samples.map { anchor ->
            val group = samples.filter {
                circularMinuteDistance(it.startMinute, anchor.startMinute) <= 20 && abs(it.duration - anchor.duration) <= 10
            }
            if (group.size < 3) null else {
                val start = circularAverage(group.map { it.startMinute })
                val duration = group.map { it.duration }.sorted()[group.size / 2].coerceIn(1, 180)
                Triple(group.size, start, duration)
            }
        }.filterNotNull()
            .distinctBy { Pair(it.second / 10, it.third / 5) }
            .sortedByDescending { it.first }

        val chosen = mutableListOf<Triple<Int, Int, Int>>()
        for (candidate in candidates) {
            if (chosen.isEmpty() || chosen.all { circularMinuteDistance(it.second, candidate.second) > 45 }) {
                chosen += candidate
            }
            if (chosen.size == missing.size) break
        }

        missing.zip(chosen).forEach { (pauseIndex, candidate) ->
            val start = candidate.second.coerceIn(0, 1439)
            val end = (start + candidate.third) % 1440
            CompanyBasePauseSettings.savePause(context, companySlot, pauseIndex, start, end)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean("company${companySlot}_pause${pauseIndex}_learned", true)
                .apply()
        }
        if (chosen.isNotEmpty()) CompanyPauseAlarmManager.scheduleAll(context)
    }

    private fun circularMinuteDistance(a: Int, b: Int): Int {
        val d = abs(a - b)
        return minOf(d, 1440 - d)
    }

    private fun circularAverage(values: List<Int>): Int {
        if (values.isEmpty()) return 0
        return values.sorted()[values.size / 2]
    }
}
