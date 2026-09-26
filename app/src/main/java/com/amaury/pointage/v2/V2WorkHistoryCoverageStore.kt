package com.amaury.pointage.v2

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Attestations explicites de complétude du journal de pointage.
 *
 * Une lecture fiable de l'historique ne crée jamais une attestation. Une période vide ne peut donc
 * devenir un zéro de paie que lorsqu'elle a été confirmée séparément pour l'employeur et le fuseau.
 */
object V2WorkHistoryCoverageStore {
    private const val PREFS = "horatrack_v2_work_history_coverage"
    private const val KEY_CONFIRMED = "confirmed_coverage"
    private const val KEY_BACKUP = "confirmed_coverage_last_known_good"

    const val STORAGE_WARNING =
        "Couverture des pointages V2 : registre local incohérent ; aucune période complète ne peut être déduite."
    const val COVERAGE_WARNING =
        "Couverture des pointages V2 : la période demandée n'est pas entièrement attestée."
    const val FUTURE_WARNING =
        "Couverture des pointages V2 : une attestation future ou prématurée a été refusée."

    data class Attestation(
        val id: String,
        val sourceId: String,
        val employerId: String,
        val startEpochDay: Long,
        val endEpochDay: Long,
        val confirmedAtMs: Long,
        val timeZoneId: String,
        val note: String? = null
    )

    data class ReadResult(
        val attestations: List<Attestation>,
        val reliable: Boolean,
        val repairedFromBackup: Boolean,
        val warnings: List<String>
    )

    data class CoverageResult(
        val employerId: String,
        val startEpochDay: Long,
        val endEpochDay: Long,
        val timeZoneId: String,
        val attestations: List<Attestation>,
        val fullyCovered: Boolean,
        val reliable: Boolean,
        val sourceId: String,
        val checkedAtMs: Long,
        val warnings: List<String>
    )

    fun readConfirmed(context: Context): ReadResult {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val primary = prefs.getString(KEY_CONFIRMED, null)
        val backup = prefs.getString(KEY_BACKUP, null)
        if (primary == null && backup == null) return ReadResult(emptyList(), true, false, emptyList())

        val decodedPrimary = primary?.let(::decode)
        if (decodedPrimary?.reliable == true) {
            if (backup != primary) writeVerified(context, primary)
            return decodedPrimary
        }
        val decodedBackup = backup?.let(::decode)
        if (decodedBackup?.reliable == true && writeVerified(context, backup)) {
            return decodedBackup.copy(
                repairedFromBackup = true,
                warnings = decodedBackup.warnings + "Couverture des pointages V2 : registre restauré depuis la dernière copie valide."
            )
        }
        return ReadResult(
            attestations = decodedPrimary?.attestations.orEmpty(),
            reliable = false,
            repairedFromBackup = false,
            warnings = listOf(STORAGE_WARNING)
        )
    }

    @Synchronized
    fun saveConfirmed(
        context: Context,
        attestation: Attestation,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        val normalized = normalize(attestation)
        if (!validAttestation(normalized, nowMs)) return false
        val stored = readConfirmed(context)
        if (!stored.reliable) return false
        val updated = stored.attestations.filterNot { it.id == normalized.id } + normalized
        return writeAll(context, updated)
    }

    fun coverage(
        context: Context,
        employerId: String,
        startEpochDay: Long,
        endEpochDay: Long,
        timeZoneId: String,
        nowMs: Long = System.currentTimeMillis()
    ): CoverageResult = coverageFrom(
        readConfirmed(context),
        employerId,
        startEpochDay,
        endEpochDay,
        timeZoneId,
        nowMs
    )

    internal fun coverageFrom(
        stored: ReadResult,
        employerId: String,
        startEpochDay: Long,
        endEpochDay: Long,
        timeZoneId: String,
        nowMs: Long
    ): CoverageResult {
        val employer = employerId.trim()
        val zoneId = timeZoneId.trim()
        val baseWarnings = stored.warnings.toMutableList()
        if (!stored.reliable || employer.isBlank() || zoneId.isBlank() ||
            endEpochDay < startEpochDay || nowMs <= 0L ||
            runCatching { ZoneId.of(zoneId) }.isFailure
        ) {
            return CoverageResult(
                employer, startEpochDay, endEpochDay, zoneId, emptyList(), false, false,
                "", 0L, (baseWarnings + STORAGE_WARNING).distinct()
            )
        }

        val matching = stored.attestations.filter {
            it.employerId == employer &&
                it.timeZoneId == zoneId &&
                it.endEpochDay >= startEpochDay &&
                it.startEpochDay <= endEpochDay
        }
        if (matching.any { it.confirmedAtMs > nowMs || !validAttestation(it, nowMs) }) {
            return CoverageResult(
                employer, startEpochDay, endEpochDay, zoneId, matching, false, false,
                "", 0L, (baseWarnings + FUTURE_WARNING).distinct()
            )
        }

        val sorted = matching.sortedWith(compareBy<Attestation> { it.startEpochDay }.thenByDescending { it.endEpochDay })
        var cursor = startEpochDay
        val used = mutableListOf<Attestation>()
        for (item in sorted) {
            if (item.endEpochDay < cursor) continue
            if (item.startEpochDay > cursor) break
            used += item
            if (item.endEpochDay >= endEpochDay) {
                cursor = endEpochDay
                break
            }
            if (item.endEpochDay == Long.MAX_VALUE) break
            cursor = item.endEpochDay + 1L
        }
        val fullyCovered = used.isNotEmpty() && cursor >= endEpochDay
        if (!fullyCovered) baseWarnings += COVERAGE_WARNING

        return CoverageResult(
            employerId = employer,
            startEpochDay = startEpochDay,
            endEpochDay = endEpochDay,
            timeZoneId = zoneId,
            attestations = used,
            fullyCovered = fullyCovered,
            reliable = true,
            sourceId = if (fullyCovered) {
                "coverage:" + used.map { it.id }.distinct().sorted().joinToString(",")
            } else "",
            checkedAtMs = used.maxOfOrNull { it.confirmedAtMs } ?: 0L,
            warnings = baseWarnings.distinct()
        )
    }

    /** Toute correction d'un fait de temps invalide les attestations qui recouvrent ce fait. */
    @Synchronized
    fun invalidateRange(context: Context, startMs: Long, endMsExclusive: Long): Boolean {
        if (startMs <= 0L || endMsExclusive <= startMs) return false
        val stored = readConfirmed(context)
        if (!stored.reliable) return false
        val kept = invalidateAttestations(stored.attestations, startMs, endMsExclusive)
        return kept == stored.attestations || writeAll(context, kept)
    }

    @Synchronized
    fun clearAll(context: Context): Boolean = writeAll(context, emptyList())

    internal fun invalidateAttestations(
        attestations: List<Attestation>,
        startMs: Long,
        endMsExclusive: Long
    ): List<Attestation> = attestations.filterNot { item ->
        val zone = runCatching { ZoneId.of(item.timeZoneId) }.getOrNull() ?: return@filterNot true
        val affectedStart = Instant.ofEpochMilli(startMs).atZone(zone).toLocalDate().toEpochDay()
        val affectedEnd = Instant.ofEpochMilli(endMsExclusive - 1L).atZone(zone).toLocalDate().toEpochDay()
        affectedStart <= item.endEpochDay && affectedEnd >= item.startEpochDay
    }

    internal fun decode(raw: String): ReadResult {
        val array = runCatching { JSONArray(raw) }.getOrNull()
            ?: return ReadResult(emptyList(), false, false, listOf(STORAGE_WARNING))
        val output = mutableListOf<Attestation>()
        var malformed = false
        for (index in 0 until array.length()) {
            val obj = array.optJSONObject(index)
            val item = obj?.let(::decodeAttestation)
            if (item == null) malformed = true else output += normalize(item)
        }
        if (output.map { it.id }.distinct().size != output.size) malformed = true
        if (output.any { !validShape(it) }) malformed = true
        return ReadResult(
            attestations = output,
            reliable = !malformed,
            repairedFromBackup = false,
            warnings = if (malformed) listOf(STORAGE_WARNING) else emptyList()
        )
    }

    internal fun encode(attestations: List<Attestation>): String {
        val sorted = attestations.map(::normalize).sortedWith(
            compareBy<Attestation> { it.employerId }
                .thenBy { it.timeZoneId }
                .thenBy { it.startEpochDay }
                .thenBy { it.endEpochDay }
                .thenBy { it.id }
        )
        return JSONArray().apply {
            sorted.forEach { item ->
                put(JSONObject()
                    .put("id", item.id)
                    .put("sourceId", item.sourceId)
                    .put("employerId", item.employerId)
                    .put("startEpochDay", item.startEpochDay)
                    .put("endEpochDay", item.endEpochDay)
                    .put("confirmedAtMs", item.confirmedAtMs)
                    .put("timeZoneId", item.timeZoneId)
                    .put("note", item.note ?: JSONObject.NULL))
            }
        }.toString()
    }

    private fun decodeAttestation(obj: JSONObject): Attestation? = runCatching {
        Attestation(
            id = requiredString(obj, "id"),
            sourceId = requiredString(obj, "sourceId"),
            employerId = requiredString(obj, "employerId"),
            startEpochDay = requiredLong(obj, "startEpochDay"),
            endEpochDay = requiredLong(obj, "endEpochDay"),
            confirmedAtMs = requiredLong(obj, "confirmedAtMs"),
            timeZoneId = requiredString(obj, "timeZoneId"),
            note = optionalString(obj, "note")
        )
    }.getOrNull()

    private fun validAttestation(item: Attestation, nowMs: Long): Boolean {
        if (!validShape(item) || item.confirmedAtMs > nowMs) return false
        val zone = runCatching { ZoneId.of(item.timeZoneId) }.getOrNull() ?: return false
        val end = runCatching { LocalDate.ofEpochDay(item.endEpochDay) }.getOrNull() ?: return false
        if (item.endEpochDay == Long.MAX_VALUE) return false
        val completeAt = runCatching {
            end.plusDays(1L).atStartOfDay(zone).toInstant().toEpochMilli()
        }.getOrNull() ?: return false
        return item.confirmedAtMs >= completeAt
    }

    private fun validShape(item: Attestation): Boolean =
        item.id.isNotBlank() &&
            item.sourceId.isNotBlank() &&
            item.employerId.isNotBlank() &&
            item.timeZoneId.isNotBlank() &&
            item.endEpochDay >= item.startEpochDay &&
            item.confirmedAtMs > 0L &&
            runCatching { ZoneId.of(item.timeZoneId) }.isSuccess

    private fun normalize(item: Attestation): Attestation = item.copy(
        id = item.id.trim(),
        sourceId = item.sourceId.trim(),
        employerId = item.employerId.trim(),
        timeZoneId = item.timeZoneId.trim(),
        note = item.note?.trim()?.takeIf { it.isNotEmpty() }
    )

    private fun writeAll(context: Context, attestations: List<Attestation>): Boolean {
        if (attestations.map { it.id }.distinct().size != attestations.size ||
            attestations.any { !validShape(it) }) return false
        val raw = encode(attestations)
        return writeVerified(context, raw)
    }

    private fun writeVerified(context: Context, raw: String): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ok = runCatching {
            prefs.edit().putString(KEY_CONFIRMED, raw).putString(KEY_BACKUP, raw).commit()
        }.getOrDefault(false)
        if (!ok) return false
        return prefs.getString(KEY_CONFIRMED, null) == raw &&
            prefs.getString(KEY_BACKUP, null) == raw &&
            decode(raw).reliable
    }

    private fun requiredString(obj: JSONObject, key: String): String {
        val value = obj.opt(key) as? String ?: error("$key invalide")
        return value.trim().takeIf { it.isNotEmpty() } ?: error("$key vide")
    }

    private fun optionalString(obj: JSONObject, key: String): String? {
        val value = obj.opt(key)
        if (value == null || value === JSONObject.NULL) return null
        return (value as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: error("$key invalide")
    }

    private fun requiredLong(obj: JSONObject, key: String): Long {
        val number = obj.opt(key) as? Number ?: error("$key invalide")
        val value = number.toDouble()
        if (!value.isFinite() || value % 1.0 != 0.0) error("$key entier invalide")
        return number.toLong()
    }
}
