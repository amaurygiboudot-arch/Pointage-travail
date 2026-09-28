package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.v2.engine.SegmentedPayrollSessionSourceV2
import com.amaury.pointage.v2.engine.WorkSessionRangeV2
import com.amaury.pointage.v2.model.WorkSessionV2
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.time.DateTimeException
import java.time.LocalDate
import java.time.ZoneId

data class SegmentedPayrollCoverageAttestationV2(
    val id: String,
    val employerId: String,
    val coveredStartEpochDay: Long,
    val coveredEndEpochDay: Long,
    val confirmedAtMs: Long,
    val timeZoneId: String,
    val factFingerprint: String
)

data class SegmentedPayrollCoverageReadV2(
    val attestations: List<SegmentedPayrollCoverageAttestationV2>,
    val reliable: Boolean,
    val warnings: List<String>
)

/**
 * Attestations explicites d'exhaustivité du journal V2.
 *
 * Une attestation est liée à l'empreinte des faits qui touchent sa propre plage :
 * modifier cette plage l'invalide ; ajouter un pointage hors plage ne l'invalide pas.
 */
object SegmentedPayrollCoverageStoreV2 {
    const val PREFS = "horatrack_v2_payroll_coverage"
    private const val KEY_DATA = "attestations"
    private const val SCHEMA_VERSION = 1
    const val STORE_WARNING =
        "Couverture paie V2 illisible ou incohérente : aucune période n'est considérée exhaustive."
    const val MISSING_WARNING =
        "Couverture paie V2 non attestée pour toute la période demandée."
    const val STALE_WARNING =
        "Couverture paie V2 invalidée par une modification des pointages de la période."

    fun read(context: Context): SegmentedPayrollCoverageReadV2 {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_DATA)) return SegmentedPayrollCoverageReadV2(emptyList(), true, emptyList())
        val raw = runCatching { prefs.getString(KEY_DATA, null) }.getOrNull()
            ?: return corrupt()
        return decode(raw)
    }

    /**
     * À appeler uniquement après confirmation explicite de l'exhaustivité par une source autorisée.
     * Une simple lecture réussie du runtime n'appelle jamais cette méthode automatiquement.
     */
    fun confirm(
        context: Context,
        employerId: String,
        coveredStartEpochDay: Long,
        coveredEndEpochDay: Long,
        confirmedAtMs: Long = System.currentTimeMillis(),
        timeZoneId: String,
        id: String
    ): Boolean {
        val employer = employerId.trim()
        if (employer.isEmpty() || id.isBlank() || confirmedAtMs <= 0L) return false
        val bounds = rangeBounds(coveredStartEpochDay, coveredEndEpochDay, timeZoneId) ?: return false
        if (bounds.second > confirmedAtMs) return false

        val runtime = V2RuntimeReader.allSessions(context, confirmedAtMs)
        if (!runtime.reliable) return false
        val fingerprint = fingerprint(
            runtime.sessions,
            coveredStartEpochDay,
            coveredEndEpochDay,
            timeZoneId,
            confirmedAtMs
        ) ?: return false

        val current = read(context)
        if (!current.reliable) return false
        val item = SegmentedPayrollCoverageAttestationV2(
            id = id.trim(),
            employerId = employer,
            coveredStartEpochDay = coveredStartEpochDay,
            coveredEndEpochDay = coveredEndEpochDay,
            confirmedAtMs = confirmedAtMs,
            timeZoneId = timeZoneId,
            factFingerprint = fingerprint
        )
        val updated = current.attestations
            .filterNot { it.id == item.id }
            .plus(item)
            .sortedWith(compareBy<SegmentedPayrollCoverageAttestationV2>(
                { it.coveredStartEpochDay }, { it.coveredEndEpochDay }, { it.id }
            ))
        return context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_DATA, encode(updated)).commit()
    }

    fun source(
        context: Context,
        employerId: String,
        requiredStartEpochDay: Long,
        requiredEndEpochDay: Long,
        timeZoneId: String,
        nowMs: Long = System.currentTimeMillis()
    ): SegmentedPayrollSessionSourceV2 {
        val runtime = V2RuntimeReader.allSessions(context, nowMs)
        return resolveSource(
            sessions = runtime.sessions,
            runtimeReliable = runtime.reliable,
            runtimeWarnings = runtime.warnings,
            coverage = read(context),
            employerId = employerId,
            requiredStartEpochDay = requiredStartEpochDay,
            requiredEndEpochDay = requiredEndEpochDay,
            timeZoneId = timeZoneId,
            nowMs = nowMs
        )
    }

    internal fun resolveSource(
        sessions: List<WorkSessionV2>,
        runtimeReliable: Boolean,
        runtimeWarnings: List<String>,
        coverage: SegmentedPayrollCoverageReadV2,
        employerId: String,
        requiredStartEpochDay: Long,
        requiredEndEpochDay: Long,
        timeZoneId: String,
        nowMs: Long
    ): SegmentedPayrollSessionSourceV2 {
        val warnings = (runtimeWarnings + coverage.warnings).toMutableList()
        val employer = employerId.trim()
        val validRange = rangeBounds(requiredStartEpochDay, requiredEndEpochDay, timeZoneId) != null
        if (!runtimeReliable || !coverage.reliable || employer.isEmpty() || !validRange || nowMs <= 0L) {
            return SegmentedPayrollSessionSourceV2(
                employer, sessions, "coverage-unavailable", false, false,
                requiredStartEpochDay, requiredEndEpochDay, nowMs, timeZoneId, warnings.distinct()
            )
        }

        var staleFound = false
        val valid = coverage.attestations.filter { att ->
            if (att.employerId.trim() != employer || att.timeZoneId != timeZoneId ||
                att.confirmedAtMs <= 0L || att.confirmedAtMs > nowMs
            ) return@filter false
            val bounds = rangeBounds(att.coveredStartEpochDay, att.coveredEndEpochDay, att.timeZoneId)
                ?: return@filter false
            if (bounds.second > att.confirmedAtMs) return@filter false
            val current = fingerprint(
                sessions,
                att.coveredStartEpochDay,
                att.coveredEndEpochDay,
                att.timeZoneId,
                nowMs
            )
            if (current == null || current != att.factFingerprint) {
                staleFound = true
                false
            } else true
        }.sortedWith(compareBy({ it.coveredStartEpochDay }, { it.coveredEndEpochDay }, { it.id }))

        val selected = cover(requiredStartEpochDay, requiredEndEpochDay, valid)
        val exhaustive = selected != null
        if (!exhaustive) warnings += if (staleFound) STALE_WARNING else MISSING_WARNING
        val used = selected.orEmpty()
        val sourceId = if (used.isEmpty()) "coverage-unavailable" else {
            "coverage-" + sha256(
                used.sortedBy { it.id }.joinToString("|") {
                    "${it.id}:${it.factFingerprint}:${it.confirmedAtMs}"
                }
            ).take(24)
        }
        val start = used.minOfOrNull { it.coveredStartEpochDay } ?: requiredStartEpochDay
        val end = used.maxOfOrNull { it.coveredEndEpochDay } ?: requiredEndEpochDay
        val checkedAt = used.maxOfOrNull { it.confirmedAtMs } ?: nowMs

        return SegmentedPayrollSessionSourceV2(
            employerId = employer,
            sessions = sessions,
            sourceId = sourceId,
            reliable = true,
            exhaustive = exhaustive,
            coveredStartEpochDay = start,
            coveredEndEpochDay = end,
            checkedAtMs = checkedAt,
            timeZoneId = timeZoneId,
            warnings = warnings.distinct()
        )
    }

    internal fun fingerprint(
        sessions: List<WorkSessionV2>,
        startEpochDay: Long,
        endEpochDay: Long,
        timeZoneId: String,
        openEndMs: Long
    ): String? {
        val bounds = rangeBounds(startEpochDay, endEpochDay, timeZoneId) ?: return null
        if (openEndMs <= 0L) return null
        val relevant = sessions.filter {
            WorkSessionRangeV2.potentiallyTouches(it, bounds.first, bounds.second, openEndMs)
        }
        if (relevant.any { !WorkSessionRangeV2.isClosedAndComplete(it) }) return null
        val canonical = relevant.sortedBy { it.id }.joinToString("\n") { session ->
            buildString {
                append(field(session.id)); append('|')
                append(field(session.employerId)); append('|')
                append(session.realArrivalMs); append('|')
                append(session.countedEntryMs); append('|')
                append(session.countedExitMs); append('|')
                append(session.realExitMs); append('|')
                append(session.status.name); append('|')
                append(field(session.placeId)); append('|')
                append(session.legacyFixedUnpaidPauseMs); append('|')
                append(session.pauses.joinToString(";") { pause ->
                    "${pause.startMs},${pause.endMs},${pause.paid},${pause.source.name},${pause.status.name}"
                }); append('|')
                append(session.travels.joinToString(";") { travel ->
                    "${travel.startMs},${travel.endMs},${field(travel.employerBeforeId)}," +
                        "${field(travel.employerAfterId)},${travel.distanceMeters},${travel.classification.name}"
                })
            }
        }
        return sha256(canonical)
    }

    internal fun decode(raw: String): SegmentedPayrollCoverageReadV2 {
        if (raw.isBlank()) return corrupt()
        return runCatching {
            val root = JSONObject(raw)
            require(root.optInt("schemaVersion", 0) == SCHEMA_VERSION)
            val array = root.getJSONArray("items")
            val ids = mutableSetOf<String>()
            val items = buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    val parsed = SegmentedPayrollCoverageAttestationV2(
                        id = item.getString("id").trim(),
                        employerId = item.getString("employerId").trim(),
                        coveredStartEpochDay = item.getLong("coveredStartEpochDay"),
                        coveredEndEpochDay = item.getLong("coveredEndEpochDay"),
                        confirmedAtMs = item.getLong("confirmedAtMs"),
                        timeZoneId = item.getString("timeZoneId"),
                        factFingerprint = item.getString("factFingerprint").trim()
                    )
                    require(parsed.id.isNotEmpty() && ids.add(parsed.id))
                    require(parsed.employerId.isNotEmpty() && parsed.factFingerprint.matches(Regex("[0-9a-f]{64}")))
                    require(parsed.confirmedAtMs > 0L)
                    require(rangeBounds(
                        parsed.coveredStartEpochDay,
                        parsed.coveredEndEpochDay,
                        parsed.timeZoneId
                    ) != null)
                    add(parsed)
                }
            }
            SegmentedPayrollCoverageReadV2(items, true, emptyList())
        }.getOrElse { corrupt() }
    }

    internal fun encode(items: List<SegmentedPayrollCoverageAttestationV2>): String {
        val array = JSONArray()
        items.forEach { item ->
            array.put(JSONObject()
                .put("id", item.id)
                .put("employerId", item.employerId)
                .put("coveredStartEpochDay", item.coveredStartEpochDay)
                .put("coveredEndEpochDay", item.coveredEndEpochDay)
                .put("confirmedAtMs", item.confirmedAtMs)
                .put("timeZoneId", item.timeZoneId)
                .put("factFingerprint", item.factFingerprint))
        }
        return JSONObject().put("schemaVersion", SCHEMA_VERSION).put("items", array).toString()
    }

    private fun cover(
        requiredStart: Long,
        requiredEnd: Long,
        candidates: List<SegmentedPayrollCoverageAttestationV2>
    ): List<SegmentedPayrollCoverageAttestationV2>? {
        if (requiredEnd < requiredStart) return null
        var cursor = requiredStart
        val selected = mutableListOf<SegmentedPayrollCoverageAttestationV2>()
        while (cursor <= requiredEnd) {
            val next = candidates
                .filter { it.coveredStartEpochDay <= cursor && it.coveredEndEpochDay >= cursor }
                .maxWithOrNull(compareBy<SegmentedPayrollCoverageAttestationV2>(
                    { it.coveredEndEpochDay }, { it.confirmedAtMs }
                )) ?: return null
            selected += next
            if (next.coveredEndEpochDay >= requiredEnd) return selected.distinctBy { it.id }
            if (next.coveredEndEpochDay == Long.MAX_VALUE) return null
            cursor = next.coveredEndEpochDay + 1
        }
        return selected.distinctBy { it.id }
    }

    private fun rangeBounds(startEpochDay: Long, endEpochDay: Long, timeZoneId: String): Pair<Long, Long>? =
        try {
            if (endEpochDay < startEpochDay) return null
            val zone = ZoneId.of(timeZoneId)
            val start = LocalDate.ofEpochDay(startEpochDay)
            val endExclusive = LocalDate.ofEpochDay(Math.addExact(endEpochDay, 1L))
            if (start.year !in 1900..2200 || endExclusive.minusDays(1).year !in 1900..2200) return null
            val from = start.atStartOfDay(zone).toInstant().toEpochMilli()
            val to = endExclusive.atStartOfDay(zone).toInstant().toEpochMilli()
            if (to <= from) null else from to to
        } catch (_: DateTimeException) {
            null
        } catch (_: ArithmeticException) {
            null
        }

    private fun field(value: String?): String =
        value?.replace("\\", "\\\\")?.replace("|", "\\|")?.replace(";", "\\;") ?: "<null>"

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun corrupt() =
        SegmentedPayrollCoverageReadV2(emptyList(), false, listOf(STORE_WARNING))
}
