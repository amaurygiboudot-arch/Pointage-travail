package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.SalaryCompanyStore
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs

/**
 * Horaires explicites V2, propriétaires de l’entreprise.
 *
 * Les anciennes clés globales de shift_profiles ne sont reprises automatiquement que lorsqu’une
 * seule entreprise V2 confirmée existe. Avec plusieurs entreprises, aucune ancienne valeur globale
 * n’est appliquée silencieusement à une société.
 */
object V2ScheduleStore {
    private const val PREFS = "shift_profiles"
    private const val LEGACY_KEY_MODE = "selected_shift"
    private const val MIGRATION_PREFIX = "v2_schedule_legacy_migrated:"
    val SHIFT_IDS = listOf("morning", "day", "afternoon", "night")

    data class Schedule(val id: String, val startMinute: Int?, val endMinute: Int?)
    data class Match(val schedule: Schedule, val expectedStartMs: Long?, val expectedEndMs: Long, val scoreMs: Long)

    internal fun companyPreferenceKey(companyId: String, suffix: String): String =
        "company:${companyId.trim()}:$suffix"

    internal fun canMigrateLegacySchedule(
        companiesReliable: Boolean,
        confirmedCompanyIds: List<String>,
        companyId: String
    ): Boolean {
        val id = companyId.trim()
        return companiesReliable && id.isNotBlank() && confirmedCompanyIds.singleOrNull() == id
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun ensureLegacyMigratedForSoleCompany(context: Context, companyId: String) {
        val id = companyId.trim()
        if (id.isBlank()) return
        val p = prefs(context)
        val marker = MIGRATION_PREFIX + id
        if (p.getBoolean(marker, false)) return

        val stored = SalaryCompanyStore.readConfirmed(context)
        if (!canMigrateLegacySchedule(stored.reliable, stored.companies.map { it.id }, id)) return

        val editor = p.edit()
        if (!p.contains(companyPreferenceKey(id, LEGACY_KEY_MODE))) {
            val legacyMode = p.getString(LEGACY_KEY_MODE, "auto").orEmpty()
            if (legacyMode == "auto" || legacyMode in SHIFT_IDS) {
                editor.putString(companyPreferenceKey(id, LEGACY_KEY_MODE), legacyMode)
            }
        }

        SHIFT_IDS.forEach { shiftId ->
            listOf("expected_start_$shiftId", "expected_end_$shiftId").forEach { legacyKey ->
                val companyKey = companyPreferenceKey(id, legacyKey)
                if (!p.contains(companyKey)) {
                    val legacyValue = p.getString(legacyKey, "").orEmpty()
                    if (legacyValue.isNotBlank() && parseMinute(legacyValue) != null) {
                        editor.putString(companyKey, legacyValue)
                    }
                }
            }
        }
        editor.putBoolean(marker, true).commit()
    }

    fun schedule(context: Context, companyId: String, id: String): Schedule {
        require(id in SHIFT_IDS)
        val company = companyId.trim()
        if (company.isBlank()) return Schedule(id, null, null)
        ensureLegacyMigratedForSoleCompany(context, company)
        val p = prefs(context)
        return Schedule(
            id = id,
            startMinute = parseMinute(p.getString(companyPreferenceKey(company, "expected_start_$id"), "").orEmpty()),
            endMinute = parseMinute(p.getString(companyPreferenceKey(company, "expected_end_$id"), "").orEmpty())
        )
    }

    fun save(context: Context, companyId: String, id: String, start: String?, end: String?): Boolean {
        require(id in SHIFT_IDS)
        val company = companyId.trim()
        if (company.isBlank()) return false
        val stored = SalaryCompanyStore.readConfirmed(context)
        if (!stored.reliable || stored.companies.none { it.id == company }) return false

        val startMin = start?.takeIf { it.isNotBlank() }?.let(::parseMinute)
        val endMin = end?.takeIf { it.isNotBlank() }?.let(::parseMinute)
        if (!start.isNullOrBlank() && startMin == null) return false
        if (!end.isNullOrBlank() && endMin == null) return false

        val editor = prefs(context).edit()
        val startKey = companyPreferenceKey(company, "expected_start_$id")
        val endKey = companyPreferenceKey(company, "expected_end_$id")
        if (start.isNullOrBlank()) editor.remove(startKey)
        else editor.putString(startKey, formatMinute(startMin!!))
        if (end.isNullOrBlank()) editor.remove(endKey)
        else editor.putString(endKey, formatMinute(endMin!!))
        editor.putBoolean(MIGRATION_PREFIX + company, true)
        return editor.commit()
    }

    fun selectedMode(context: Context, companyId: String): String {
        val company = companyId.trim()
        if (company.isBlank()) return "auto"
        ensureLegacyMigratedForSoleCompany(context, company)
        val mode = prefs(context).getString(
            companyPreferenceKey(company, LEGACY_KEY_MODE),
            "auto"
        ).orEmpty()
        return if (mode == "auto" || mode in SHIFT_IDS) mode else "auto"
    }

    fun setSelectedMode(context: Context, companyId: String, mode: String): Boolean {
        val company = companyId.trim()
        if (company.isBlank() || (mode != "auto" && mode !in SHIFT_IDS)) return false
        val stored = SalaryCompanyStore.readConfirmed(context)
        if (!stored.reliable || stored.companies.none { it.id == company }) return false
        return prefs(context).edit()
            .putString(companyPreferenceKey(company, LEGACY_KEY_MODE), mode)
            .putBoolean(MIGRATION_PREFIX + company, true)
            .commit()
    }

    /** Fin prévue utilisable dès l’entrée uniquement si le profil est choisi explicitement. */
    fun expectedEndForEntry(context: Context, companyId: String, entryMs: Long): Long? {
        val mode = selectedMode(context, companyId)
        if (mode !in SHIFT_IDS) return null
        return expectedEnd(schedule(context, companyId, mode), entryMs)
    }

    /**
     * En automatique, compare l’entrée ET la sortie réelles aux profils explicitement configurés.
     * Un profil sans fin prévue n’est jamais utilisé pour la règle de sortie +20 min.
     */
    fun bestMatch(context: Context, companyId: String, entryMs: Long, exitMs: Long): Match? {
        if (exitMs <= entryMs) return null
        val mode = selectedMode(context, companyId)
        val candidates = if (mode in SHIFT_IDS) {
            listOf(schedule(context, companyId, mode))
        } else {
            SHIFT_IDS.map { schedule(context, companyId, it) }
        }
        return candidates.mapNotNull { s ->
            val end = expectedEnd(s, entryMs) ?: return@mapNotNull null
            val start = s.startMinute?.let { occurrenceNearEntry(entryMs, it) }
            val entryScore = start?.let { abs(entryMs - it) } ?: 0L
            val exitScore = abs(exitMs - end)
            Match(s, start, end, entryScore + exitScore)
        }.minByOrNull { it.scoreMs }
    }

    fun expectedEnd(context: Context, companyId: String, entryMs: Long, exitMs: Long): Long? =
        expectedEndForEntry(context, companyId, entryMs)
            ?: bestMatch(context, companyId, entryMs, exitMs)?.expectedEndMs

    private fun expectedEnd(schedule: Schedule, entryMs: Long): Long? {
        val endMinute = schedule.endMinute ?: return null
        val end = occurrenceNearEntry(entryMs, endMinute)
        return if (end <= entryMs) end + dayLengthAround(end) else end
    }

    private fun occurrenceNearEntry(entryMs: Long, minuteOfDay: Int): Long =
        Calendar.getInstance(Locale.FRANCE).apply {
            timeInMillis = entryMs
            set(Calendar.HOUR_OF_DAY, minuteOfDay / 60)
            set(Calendar.MINUTE, minuteOfDay % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun dayLengthAround(ms: Long): Long {
        val a = Calendar.getInstance(Locale.FRANCE).apply { timeInMillis = ms }
        val b = (a.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
        return b.timeInMillis - a.timeInMillis
    }

    fun parseMinute(value: String): Int? {
        val m = Regex("^\\s*(\\d{1,2})[:hH](\\d{2})\\s*$").matchEntire(value) ?: return null
        val h = m.groupValues[1].toIntOrNull() ?: return null
        val min = m.groupValues[2].toIntOrNull() ?: return null
        return if (h in 0..23 && min in 0..59) h * 60 + min else null
    }

    fun formatMinute(minute: Int): String =
        String.format(Locale.FRANCE, "%02d:%02d", minute / 60, minute % 60)
}
