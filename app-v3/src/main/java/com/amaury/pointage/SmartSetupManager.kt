package com.amaury.pointage

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.location.Geocoder
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.abs

/**
 * Configuration intelligente activée par défaut à la première installation.
 *
 * Une adresse trouvée grâce au SIRET n'est jamais considérée immédiatement comme
 * un lieu de travail. Elle devient d'abord une zone candidate silencieuse.
 * HoraTrack ne propose cette adresse comme lieu de travail qu'après une présence
 * d'au moins 7 heures pendant 3 jours calendaires consécutifs sur la même zone.
 * L'utilisateur doit ensuite confirmer explicitement la proposition.
 */
object SmartSetupManager : SharedPreferences.OnSharedPreferenceChangeListener {
    private const val PREFS = "smart_setup"
    private const val KEY_INITIALIZED = "initialized"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_WORKPLACE = "learn_workplace"
    private const val KEY_PAUSES = "learn_pauses"
    private const val MIN_WORKPLACE_DWELL_MS = 7L * 60L * 60L * 1000L
    private const val REQUIRED_CONSECUTIVE_DAYS = 3

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
                    if (siret.length == 14 && address.isNotBlank()) ensureCandidateZone(context, slot, address)
                }
            } finally {
                busyCompany = false
            }
        }.start()
    }

    private fun ensureCandidateZone(context: Context, companySlot: Int, address: String) {
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
        val id = "smart_candidate_${companySlot}_${UUID.randomUUID()}"
        zones.put(
            JSONObject()
                .put("id", id)
                .put("address", address)
                .put("latitude", geocoded.latitude)
                .put("longitude", geocoded.longitude)
                .put("radius", radius)
                .put("pointSource", "smart_siret_candidate")
                .put("companySlot", companySlot)
                .put("smartCandidate", true)
        )

        gps.edit()
            .putString("zones", zones.toString())
            .putBoolean("enabled", true)
            .putBoolean("smart_setup_candidate_created", true)
            .apply()

        if (GeofenceManager.hasRequiredPermissions(context)) registerStoredZones(context)
    }

    fun isCandidateZone(context: Context, zoneId: String): Boolean {
        val zone = findZone(context, zoneId) ?: return false
        return zone.optBoolean("smartCandidate", false)
    }

    fun onCandidateEnter(context: Context, zoneId: String) {
        if (!isCandidateZone(context, zoneId)) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = "candidate_enter_$zoneId"
        if (prefs.getLong(key, 0L) <= 0L) prefs.edit().putLong(key, System.currentTimeMillis()).apply()
    }

    fun onCandidateExit(context: Context, zoneId: String) {
        val zone = findZone(context, zoneId) ?: return
        if (!zone.optBoolean("smartCandidate", false)) return

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val enterKey = "candidate_enter_$zoneId"
        val enteredAt = prefs.getLong(enterKey, 0L)
        prefs.edit().remove(enterKey).apply()
        if (enteredAt <= 0L) return

        val exitedAt = System.currentTimeMillis()
        if (exitedAt - enteredAt < MIN_WORKPLACE_DWELL_MS) return

        val dayKey = dayKey(enteredAt)
        val daysKey = "candidate_days_$zoneId"
        val days = runCatching { JSONArray(prefs.getString(daysKey, "[]") ?: "[]") }.getOrElse { JSONArray() }
        val uniqueDays = mutableSetOf<String>()
        for (i in 0 until days.length()) days.optString(i).takeIf { it.isNotBlank() }?.let(uniqueDays::add)
        uniqueDays += dayKey

        val sorted = uniqueDays.sorted()
        prefs.edit().putString(daysKey, JSONArray(sorted).toString()).apply()

        if (hasThreeConsecutiveDays(sorted)) {
            val pendingZone = prefs.getString("pending_workplace_zone", "").orEmpty()
            if (pendingZone.isBlank()) {
                prefs.edit()
                    .putString("pending_workplace_zone", zoneId)
                    .putString("pending_workplace_address", zone.optString("address"))
                    .putInt("pending_workplace_company", zone.optInt("companySlot", 1))
                    .apply()
            }
        }
    }

    fun showPendingWorkplaceProposal(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val zoneId = prefs.getString("pending_workplace_zone", "").orEmpty()
        if (zoneId.isBlank()) return
        if (prefs.getBoolean("proposal_dialog_visible", false)) return

        val zone = findZone(activity, zoneId)
        if (zone == null || !zone.optBoolean("smartCandidate", false)) {
            clearPendingProposal(prefs)
            return
        }

        val address = zone.optString("address").ifBlank { prefs.getString("pending_workplace_address", "").orEmpty() }
        val company = zone.optInt("companySlot", prefs.getInt("pending_workplace_company", 1)).coerceIn(1, 2)
        prefs.edit().putBoolean("proposal_dialog_visible", true).apply()

        AlertDialog.Builder(activity)
            .setTitle("Lieu de travail détecté ?")
            .setMessage(
                "Tu as passé au moins 7 heures à cette adresse pendant 3 jours consécutifs :\n\n$address\n\n" +
                    "Est-ce bien un lieu de travail pour l'Entreprise $company ? HoraTrack ne l'activera jamais sans ta confirmation."
            )
            .setPositiveButton("OUI, C'EST MON TRAVAIL") { _, _ ->
                confirmCandidate(activity, zoneId)
                clearPendingProposal(prefs)
            }
            .setNegativeButton("NON") { _, _ ->
                rejectCandidate(activity, zoneId)
                clearPendingProposal(prefs)
            }
            .setOnCancelListener {
                prefs.edit().putBoolean("proposal_dialog_visible", false).apply()
            }
            .show()
    }

    private fun confirmCandidate(context: Context, zoneId: String) {
        val gps = context.getSharedPreferences("gps_settings", Context.MODE_PRIVATE)
        val zones = runCatching { JSONArray(gps.getString("zones", "[]") ?: "[]") }.getOrElse { JSONArray() }
        var confirmedAddress = ""
        var companySlot = 1
        for (i in 0 until zones.length()) {
            val zone = zones.optJSONObject(i) ?: continue
            if (zone.optString("id") != zoneId) continue
            zone.put("smartCandidate", false)
            zone.put("pointSource", "smart_siret_confirmed")
            confirmedAddress = zone.optString("address")
            companySlot = zone.optInt("companySlot", 1).coerceIn(1, 2)
            break
        }

        if (confirmedAddress.isNotBlank()) {
            val addresses = gps.getString("address", "").orEmpty().lines()
                .map { it.trim() }.filter { it.isNotBlank() }.toMutableList()
            if (addresses.none { it.equals(confirmedAddress, ignoreCase = true) }) addresses += confirmedAddress
            val companyMap = runCatching { JSONObject(gps.getString("address_company_slots", "{}") ?: "{}") }.getOrElse { JSONObject() }
            companyMap.put(confirmedAddress, companySlot)
            gps.edit()
                .putString("zones", zones.toString())
                .putString("address", addresses.distinctBy { it.lowercase(Locale.FRANCE) }.take(10).joinToString("\n"))
                .putString("address_company_slots", companyMap.toString())
                .putBoolean("enabled", true)
                .apply()
        } else {
            gps.edit().putString("zones", zones.toString()).apply()
        }
        registerStoredZones(context)
    }

    private fun rejectCandidate(context: Context, zoneId: String) {
        val gps = context.getSharedPreferences("gps_settings", Context.MODE_PRIVATE)
        val old = runCatching { JSONArray(gps.getString("zones", "[]") ?: "[]") }.getOrElse { JSONArray() }
        val kept = JSONArray()
        for (i in 0 until old.length()) {
            val zone = old.optJSONObject(i) ?: continue
            if (zone.optString("id") != zoneId) kept.put(zone)
        }
        gps.edit().putString("zones", kept.toString()).apply()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("candidate_rejected_$zoneId", true)
            .remove("candidate_days_$zoneId")
            .remove("candidate_enter_$zoneId")
            .apply()
        registerStoredZones(context)
    }

    private fun clearPendingProposal(prefs: SharedPreferences) {
        prefs.edit()
            .remove("pending_workplace_zone")
            .remove("pending_workplace_address")
            .remove("pending_workplace_company")
            .putBoolean("proposal_dialog_visible", false)
            .apply()
    }

    private fun dayKey(timestamp: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.FRANCE).format(Date(timestamp))

    private fun hasThreeConsecutiveDays(days: List<String>): Boolean {
        if (days.size < REQUIRED_CONSECUTIVE_DAYS) return false
        val format = SimpleDateFormat("yyyy-MM-dd", Locale.FRANCE).apply { isLenient = false }
        val times = days.mapNotNull { runCatching { format.parse(it)?.time }.getOrNull() }.sorted()
        var streak = 1
        for (i in 1 until times.size) {
            val previous = startOfDay(times[i - 1])
            val current = startOfDay(times[i])
            val diffDays = ((current - previous) / (24L * 60L * 60L * 1000L)).toInt()
            streak = if (diffDays == 1) streak + 1 else if (diffDays == 0) streak else 1
            if (streak >= REQUIRED_CONSECUTIVE_DAYS) return true
        }
        return false
    }

    private fun startOfDay(timestamp: Long): Long = Calendar.getInstance().run {
        timeInMillis = timestamp
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        timeInMillis
    }

    private fun findZone(context: Context, zoneId: String): JSONObject? {
        val gps = context.getSharedPreferences("gps_settings", Context.MODE_PRIVATE)
        val zones = runCatching { JSONArray(gps.getString("zones", "[]") ?: "[]") }.getOrElse { JSONArray() }
        for (i in 0 until zones.length()) {
            val zone = zones.optJSONObject(i) ?: continue
            if (zone.optString("id") == zoneId) return zone
        }
        return null
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
        if (zones.isNotEmpty()) GeofenceManager.registerAll(context, zones) else GeofenceManager.remove(context)
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
                val c = Calendar.getInstance(Locale.FRANCE).apply { timeInMillis = start }
                val minute = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
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
