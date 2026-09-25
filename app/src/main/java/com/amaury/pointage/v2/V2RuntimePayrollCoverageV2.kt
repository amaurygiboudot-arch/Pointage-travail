package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.v2.engine.SegmentedPayrollSessionSourceV2
import com.amaury.pointage.v2.model.WorkSessionV2
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

data class PayrollCoverageAttestationV2(
    val sourceId: String,
    val employerId: String,
    val coveredStartEpochDay: Long,
    val coveredEndEpochDay: Long,
    val checkedAtMs: Long,
    val timeZoneId: String,
    val snapshotId: String
)

/**
 * Preuve locale distincte de la fiabilité du runtime.
 *
 * Une lecture saine ne devient jamais exhaustive automatiquement. L'exhaustivité doit avoir été
 * explicitement confirmée, puis reste valable uniquement tant que l'empreinte des sessions pouvant
 * toucher la plage confirmée est identique. Une correction de pointage/pause/employeur/déplacement
 * rend donc l'attestation obsolète sans effacer l'historique.
 */
object V2RuntimePayrollCoverageV2 {
    const val MISSING_ATTESTATION_WARNING =
        "Preuves B21 : aucune attestation d'exhaustivité n'est enregistrée pour cette période."
    const val STALE_ATTESTATION_WARNING =
        "Preuves B21 : l'attestation d'exhaustivité ne correspond plus au snapshot courant des pointages."
    const val STORE_WARNING =
        "Preuves B21 : le store local des attestations d'exhaustivité est illisible ; couverture bloquée."

    private const val PREFS = "horatrack_v2_payroll_coverage"
    private const val KEY_PAYLOAD = "payload"
    private const val SCHEMA_VERSION = 1

    fun confirm(
        context: Context,
        employerId: String,
        coveredStartEpochDay: Long,
        coveredEndEpochDay: Long,
        checkedAtMs: Long,
        timeZoneId: String
    ): Boolean {
        val employer = employerId.trim()
        val zone = runCatching { ZoneId.of(timeZoneId) }.getOrNull() ?: return false
        if (employer.isBlank() || coveredEndEpochDay < coveredStartEpochDay || checkedAtMs <= 0L) return false
        val closedAt = runCatching {
            LocalDate.ofEpochDay(coveredEndEpochDay).plusDays(1)
                .atStartOfDay(zone).toInstant().toEpochMilli()
        }.getOrNull() ?: return false
        if (closedAt > checkedAtMs) return false

        val read = V2RuntimeReader.allSessions(context, checkedAtMs)
        if (!read.reliable) return false
        val snapshot = snapshotId(
            read.sessions,
            coveredStartEpochDay,
            coveredEndEpochDay,
            timeZoneId
        ) ?: return false
        val current = readStored(context) ?: return false
        val replacement = PayrollCoverageAttestationV2(
            sourceId = "runtime-coverage:${UUID.randomUUID()}",
            employerId = employer,
            coveredStartEpochDay = coveredStartEpochDay,
            coveredEndEpochDay = coveredEndEpochDay,
            checkedAtMs = checkedAtMs,
            timeZoneId = timeZoneId,
            snapshotId = snapshot
        )
        val records = current.filterNot {
            it.employerId.trim() == employer &&
                it.coveredStartEpochDay == coveredStartEpochDay &&
                it.coveredEndEpochDay == coveredEndEpochDay &&
                it.timeZoneId == timeZoneId
        } + replacement
        return writeStored(context, records)
    }

    fun source(
        context: Context,
        employerId: String,
        coveredStartEpochDay: Long,
        coveredEndEpochDay: Long,
        timeZoneId: String,
        nowMs: Long = System.currentTimeMillis()
    ): SegmentedPayrollSessionSourceV2 {
        val read = V2RuntimeReader.allSessions(context, nowMs)
        val stored = readStored(context)
        val employer = employerId.trim()
        val attestation = stored?.filter {
            it.employerId.trim() == employer &&
                it.coveredStartEpochDay == coveredStartEpochDay &&
                it.coveredEndEpochDay == coveredEndEpochDay &&
                it.timeZoneId == timeZoneId
        }?.maxByOrNull { it.checkedAtMs }
        val extraWarnings = when {
            stored == null -> listOf(STORE_WARNING)
            attestation == null -> listOf(MISSING_ATTESTATION_WARNING)
            else -> emptyList()
        }
        return sourceFrom(
            read = read,
            attestation = attestation,
            employerId = employerId,
            coveredStartEpochDay = coveredStartEpochDay,
            coveredEndEpochDay = coveredEndEpochDay,
            timeZoneId = timeZoneId,
            nowMs = nowMs,
            extraWarnings = extraWarnings
        )
    }

    internal fun sourceFrom(
        read: V2RuntimeReader.SessionsRead,
        attestation: PayrollCoverageAttestationV2?,
        employerId: String,
        coveredStartEpochDay: Long,
        coveredEndEpochDay: Long,
        timeZoneId: String,
        nowMs: Long,
        extraWarnings: List<String> = emptyList()
    ): SegmentedPayrollSessionSourceV2 {
        val employer = employerId.trim()
        val fingerprint = snapshotId(
            read.sessions,
            coveredStartEpochDay,
            coveredEndEpochDay,
            timeZoneId
        )
        val attestationValid = read.reliable &&
            attestation != null &&
            employer.isNotBlank() &&
            attestation.employerId.trim() == employer &&
            attestation.coveredStartEpochDay == coveredStartEpochDay &&
            attestation.coveredEndEpochDay == coveredEndEpochDay &&
            attestation.timeZoneId == timeZoneId &&
            attestation.checkedAtMs in 1..nowMs &&
            fingerprint != null &&
            attestation.snapshotId == fingerprint

        val warnings = buildList {
            addAll(read.warnings)
            addAll(extraWarnings)
            if (attestation != null && !attestationValid) add(STALE_ATTESTATION_WARNING)
            if (attestation == null && extraWarnings.isEmpty()) add(MISSING_ATTESTATION_WARNING)
        }.distinct()

        return SegmentedPayrollSessionSourceV2(
            employerId = employer,
            sessions = read.sessions,
            sourceId = attestation?.sourceId?.takeIf { it.isNotBlank() } ?: "runtime-v2-unattested",
            reliable = read.reliable,
            exhaustive = attestationValid,
            coveredStartEpochDay = coveredStartEpochDay,
            coveredEndEpochDay = coveredEndEpochDay,
            checkedAtMs = attestation?.checkedAtMs ?: nowMs,
            timeZoneId = timeZoneId,
            warnings = warnings
        )
    }

    internal fun snapshotId(
        sessions: List<WorkSessionV2>,
        coveredStartEpochDay: Long,
        coveredEndEpochDay: Long,
        timeZoneId: String
    ): String? {
        if (coveredEndEpochDay < coveredStartEpochDay) return null
        val zone = runCatching { ZoneId.of(timeZoneId) }.getOrNull() ?: return null
        val from = runCatching {
            LocalDate.ofEpochDay(coveredStartEpochDay).atStartOfDay(zone).toInstant().toEpochMilli()
        }.getOrNull() ?: return null
        val to = runCatching {
            LocalDate.ofEpochDay(coveredEndEpochDay).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        }.getOrNull() ?: return null
        if (to <= from) return null

        val relevant = sessions.filter { touches(it, from, to) }
            .sortedWith(compareBy<WorkSessionV2>({ earliest(it) }, { it.id }))

        val canonical = StringBuilder()
        append(canonical, "coverage-v1")
        append(canonical, coveredStartEpochDay)
        append(canonical, coveredEndEpochDay)
        append(canonical, timeZoneId)
        append(canonical, relevant.size.toLong())
        relevant.forEach { session ->
            append(canonical, session.id)
            append(canonical, session.employerId)
            append(canonical, session.realArrivalMs)
            append(canonical, session.countedEntryMs)
            append(canonical, session.countedExitMs)
            append(canonical, session.realExitMs)
            append(canonical, session.status.name)
            append(canonical, session.placeId)
            append(canonical, session.placeLabel)
            append(canonical, session.legacyFixedUnpaidPauseMs)
            append(canonical, session.pauses.size.toLong())
            session.pauses.forEach { pause ->
                append(canonical, pause.startMs)
                append(canonical, pause.endMs)
                append(canonical, pause.paid)
                append(canonical, pause.source.name)
                append(canonical, pause.status.name)
            }
            append(canonical, session.travels.size.toLong())
            session.travels.forEach { travel ->
                append(canonical, travel.startMs)
                append(canonical, travel.endMs)
                append(canonical, travel.employerBeforeId)
                append(canonical, travel.employerAfterId)
                append(canonical, travel.distanceMeters?.let(java.lang.Double::doubleToLongBits))
                append(canonical, travel.classification.name)
            }
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "sha256:$digest"
    }

    private fun touches(session: WorkSessionV2, from: Long, to: Long): Boolean {
        val start = listOfNotNull(session.realArrivalMs, session.countedEntryMs).minOrNull() ?: return true
        val end = listOfNotNull(session.realExitMs, session.countedExitMs).maxOrNull()
        return if (end == null) start < to else start < to && end > from
    }

    private fun earliest(session: WorkSessionV2): Long =
        listOfNotNull(session.realArrivalMs, session.countedEntryMs).minOrNull() ?: Long.MIN_VALUE

    private fun append(out: StringBuilder, value: String?) {
        if (value == null) out.append("N;")
        else out.append("S").append(value.length).append(":").append(value).append(";")
    }
    private fun append(out: StringBuilder, value: Long?) {
        if (value == null) out.append("N;") else out.append("L").append(value).append(";")
    }
    private fun append(out: StringBuilder, value: Boolean?) {
        when (value) {
            null -> out.append("N;")
            true -> out.append("B1;")
            false -> out.append("B0;")
        }
    }

    private fun readStored(context: Context): List<PayrollCoverageAttestationV2>? {
        val raw = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PAYLOAD, null) ?: return emptyList()
        return try {
            val root = JSONObject(raw)
            if (root.optInt("schemaVersion", -1) != SCHEMA_VERSION) return null
            val array = root.optJSONArray("records") ?: return null
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: return null
                    val sourceId = item.optString("sourceId").trim()
                    val employerId = item.optString("employerId").trim()
                    val timeZoneId = item.optString("timeZoneId").trim()
                    val snapshotId = item.optString("snapshotId").trim()
                    if (sourceId.isBlank() || employerId.isBlank() || timeZoneId.isBlank() || snapshotId.isBlank()) return null
                    add(
                        PayrollCoverageAttestationV2(
                            sourceId = sourceId,
                            employerId = employerId,
                            coveredStartEpochDay = item.getLong("coveredStartEpochDay"),
                            coveredEndEpochDay = item.getLong("coveredEndEpochDay"),
                            checkedAtMs = item.getLong("checkedAtMs"),
                            timeZoneId = timeZoneId,
                            snapshotId = snapshotId
                        )
                    )
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun writeStored(
        context: Context,
        records: List<PayrollCoverageAttestationV2>
    ): Boolean {
        val array = JSONArray()
        records.sortedWith(compareBy({ it.employerId }, { it.coveredStartEpochDay }, { it.coveredEndEpochDay }))
            .forEach {
                array.put(
                    JSONObject()
                        .put("sourceId", it.sourceId)
                        .put("employerId", it.employerId)
                        .put("coveredStartEpochDay", it.coveredStartEpochDay)
                        .put("coveredEndEpochDay", it.coveredEndEpochDay)
                        .put("checkedAtMs", it.checkedAtMs)
                        .put("timeZoneId", it.timeZoneId)
                        .put("snapshotId", it.snapshotId)
                )
            }
        val root = JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("records", array)
        return context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_PAYLOAD, root.toString()).commit()
    }
}
