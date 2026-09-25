package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.v2.engine.SegmentedPayrollSessionSourceV2
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

data class PayrollCoverageAttestationV2(
    val id: String,
    val employerId: String,
    val startEpochDay: Long,
    val endEpochDay: Long,
    val confirmedAtMs: Long,
    val timeZoneId: String
)

data class PayrollCoverageResolutionV2(
    val reliable: Boolean,
    val exhaustive: Boolean,
    val coveredStartEpochDay: Long,
    val coveredEndEpochDay: Long,
    val checkedAtMs: Long,
    val sourceId: String,
    val warnings: List<String>
)

/**
 * Preuve de couverture civile distincte de l'intégrité du stockage RuntimeV2.
 * Un historique lisible ne prouve jamais qu'un jour sans session vaut 0 h.
 */
object PayrollCoverageAttestationStoreV2 {
    const val PREFS = "horatrack_v2_payroll_coverage"
    private const val KEY_ATTESTATIONS = "attestations"
    private const val SCHEMA = 1
    private const val MIN_EPOCH_DAY = -25567L
    private const val MAX_EPOCH_DAY = 84370L

    const val CORRUPT_WARNING =
        "Couverture paie V2 illisible ou incohérente : aucune semaine vide ne peut être considérée comme confirmée."
    const val MISSING_WARNING =
        "Couverture paie V2 incomplète : la période doit être explicitement confirmée avant le calcul B21."

    data class ReadResult(
        val attestations: List<PayrollCoverageAttestationV2>,
        val reliable: Boolean,
        val warnings: List<String>
    )

    fun confirm(
        context: Context,
        employerId: String,
        startEpochDay: Long,
        endEpochDay: Long,
        timeZoneId: String,
        confirmedAtMs: Long = System.currentTimeMillis()
    ): Boolean {
        val employer = employerId.trim()
        val zone = runCatching { ZoneId.of(timeZoneId.trim()) }.getOrNull() ?: return false
        if (employer.isEmpty() || confirmedAtMs <= 0L ||
            startEpochDay !in MIN_EPOCH_DAY..MAX_EPOCH_DAY ||
            endEpochDay !in MIN_EPOCH_DAY..MAX_EPOCH_DAY ||
            endEpochDay < startEpochDay
        ) return false
        if (!intervalFinished(endEpochDay, zone, confirmedAtMs)) return false

        val current = read(context)
        if (!current.reliable) return false
        val next = current.attestations + PayrollCoverageAttestationV2(
            id = UUID.randomUUID().toString(),
            employerId = employer,
            startEpochDay = startEpochDay,
            endEpochDay = endEpochDay,
            confirmedAtMs = confirmedAtMs,
            timeZoneId = zone.id
        )
        return save(context, next)
    }

    fun read(context: Context): ReadResult {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_ATTESTATIONS)) return ReadResult(emptyList(), true, emptyList())
        val raw = runCatching { prefs.getString(KEY_ATTESTATIONS, null) }.getOrNull()
            ?: return corrupt()
        return decode(raw)
    }

    fun resolve(
        attestations: List<PayrollCoverageAttestationV2>,
        employerId: String,
        requestedStartEpochDay: Long,
        requestedEndEpochDay: Long,
        timeZoneId: String,
        nowMs: Long
    ): PayrollCoverageResolutionV2 {
        val employer = employerId.trim()
        val zone = runCatching { ZoneId.of(timeZoneId.trim()) }.getOrNull()
        if (employer.isEmpty() || zone == null || nowMs <= 0L ||
            requestedStartEpochDay !in MIN_EPOCH_DAY..MAX_EPOCH_DAY ||
            requestedEndEpochDay !in MIN_EPOCH_DAY..MAX_EPOCH_DAY ||
            requestedEndEpochDay < requestedStartEpochDay ||
            attestations.any { !isValid(it, nowMs) }
        ) {
            return PayrollCoverageResolutionV2(
                reliable = false,
                exhaustive = false,
                coveredStartEpochDay = requestedStartEpochDay,
                coveredEndEpochDay = requestedEndEpochDay,
                checkedAtMs = nowMs.coerceAtLeast(0L),
                sourceId = "",
                warnings = listOf(CORRUPT_WARNING)
            )
        }

        val matching = attestations
            .filter { it.employerId.trim() == employer && it.timeZoneId == zone.id }
            .sortedWith(compareBy<PayrollCoverageAttestationV2> { it.startEpochDay }
                .thenBy { it.endEpochDay }
                .thenBy { it.confirmedAtMs }
                .thenBy { it.id })

        var cursor = requestedStartEpochDay
        var checkedAt = 0L
        val used = mutableListOf<PayrollCoverageAttestationV2>()
        for (item in matching) {
            if (item.endEpochDay < cursor) continue
            if (item.startEpochDay > cursor) break
            if (item.startEpochDay <= cursor && item.endEpochDay >= cursor) {
                used += item
                checkedAt = maxOf(checkedAt, item.confirmedAtMs)
                cursor = item.endEpochDay + 1
                if (cursor > requestedEndEpochDay) break
            }
        }

        val exhaustive = cursor > requestedEndEpochDay
        return PayrollCoverageResolutionV2(
            reliable = true,
            exhaustive = exhaustive,
            coveredStartEpochDay = requestedStartEpochDay,
            coveredEndEpochDay = requestedEndEpochDay,
            checkedAtMs = if (exhaustive) checkedAt else nowMs,
            sourceId = if (exhaustive) {
                "coverage-v" + SCHEMA + ":" +
                    used.map { it.id }.distinct().sorted().joinToString(",")
            } else "",
            warnings = if (exhaustive) emptyList() else listOf(MISSING_WARNING)
        )
    }

    fun resolve(
        context: Context,
        employerId: String,
        requestedStartEpochDay: Long,
        requestedEndEpochDay: Long,
        timeZoneId: String,
        nowMs: Long = System.currentTimeMillis()
    ): PayrollCoverageResolutionV2 {
        val read = read(context)
        if (!read.reliable) {
            return PayrollCoverageResolutionV2(
                reliable = false,
                exhaustive = false,
                coveredStartEpochDay = requestedStartEpochDay,
                coveredEndEpochDay = requestedEndEpochDay,
                checkedAtMs = nowMs.coerceAtLeast(0L),
                sourceId = "",
                warnings = read.warnings
            )
        }
        return resolve(
            read.attestations,
            employerId,
            requestedStartEpochDay,
            requestedEndEpochDay,
            timeZoneId,
            nowMs
        )
    }

    fun sessionSource(
        context: Context,
        employerId: String,
        requestedStartEpochDay: Long,
        requestedEndEpochDay: Long,
        timeZoneId: String,
        nowMs: Long = System.currentTimeMillis()
    ): SegmentedPayrollSessionSourceV2 {
        val runtime = V2RuntimeReader.allSessions(context, nowMs)
        val coverage = resolve(
            context,
            employerId,
            requestedStartEpochDay,
            requestedEndEpochDay,
            timeZoneId,
            nowMs
        )
        val warnings = (runtime.warnings + coverage.warnings).distinct()
        val sourceId = if (coverage.sourceId.isBlank()) {
            "runtime-v2:" + nowMs
        } else {
            "runtime-v2:" + nowMs + ":" + coverage.sourceId
        }
        return SegmentedPayrollSessionSourceV2(
            employerId = employerId.trim(),
            sessions = runtime.sessions,
            sourceId = sourceId,
            reliable = runtime.reliable && coverage.reliable,
            exhaustive = coverage.exhaustive,
            coveredStartEpochDay = coverage.coveredStartEpochDay,
            coveredEndEpochDay = coverage.coveredEndEpochDay,
            checkedAtMs = coverage.checkedAtMs,
            timeZoneId = timeZoneId.trim(),
            warnings = warnings
        )
    }

    internal fun decode(raw: String): ReadResult {
        if (raw.isBlank()) return corrupt()
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return corrupt()
        if (root.optInt("schema", 0) != SCHEMA) return corrupt()
        val array = root.optJSONArray(KEY_ATTESTATIONS) ?: return corrupt()
        val result = mutableListOf<PayrollCoverageAttestationV2>()
        val ids = mutableSetOf<String>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: return corrupt()
            val id = item.optString("id").trim()
            val employer = item.optString("employerId").trim()
            val zone = item.optString("timeZoneId").trim()
            val start = strictLong(item.opt("startEpochDay")) ?: return corrupt()
            val end = strictLong(item.opt("endEpochDay")) ?: return corrupt()
            val confirmedAt = strictLong(item.opt("confirmedAtMs")) ?: return corrupt()
            if (id.isEmpty() || employer.isEmpty() || zone.isEmpty() || !ids.add(id)) return corrupt()
            val attestation = PayrollCoverageAttestationV2(
                id, employer, start, end, confirmedAt, zone
            )
            if (!isValidStructure(attestation)) return corrupt()
            result += attestation
        }
        return ReadResult(result, true, emptyList())
    }

    internal fun encode(attestations: List<PayrollCoverageAttestationV2>): String? {
        if (attestations.map { it.id }.distinct().size != attestations.size ||
            attestations.any { !isValidStructure(it) }
        ) return null
        val array = JSONArray()
        attestations.forEach {
            array.put(
                JSONObject()
                    .put("id", it.id)
                    .put("employerId", it.employerId.trim())
                    .put("startEpochDay", it.startEpochDay)
                    .put("endEpochDay", it.endEpochDay)
                    .put("confirmedAtMs", it.confirmedAtMs)
                    .put("timeZoneId", it.timeZoneId)
            )
        }
        return JSONObject()
            .put("schema", SCHEMA)
            .put(KEY_ATTESTATIONS, array)
            .toString()
    }

    private fun save(
        context: Context,
        attestations: List<PayrollCoverageAttestationV2>
    ): Boolean {
        val encoded = encode(attestations) ?: return false
        return context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ATTESTATIONS, encoded)
            .commit()
    }

    private fun isValid(
        item: PayrollCoverageAttestationV2,
        nowMs: Long
    ): Boolean {
        if (!isValidStructure(item) || item.confirmedAtMs > nowMs) return false
        val zone = runCatching { ZoneId.of(item.timeZoneId) }.getOrNull() ?: return false
        return intervalFinished(item.endEpochDay, zone, item.confirmedAtMs)
    }

    private fun isValidStructure(item: PayrollCoverageAttestationV2): Boolean =
        item.id.trim().isNotEmpty() &&
            item.employerId.trim().isNotEmpty() &&
            item.startEpochDay in MIN_EPOCH_DAY..MAX_EPOCH_DAY &&
            item.endEpochDay in MIN_EPOCH_DAY..MAX_EPOCH_DAY &&
            item.endEpochDay >= item.startEpochDay &&
            item.confirmedAtMs > 0L &&
            runCatching { ZoneId.of(item.timeZoneId) }.isSuccess

    private fun intervalFinished(
        endEpochDay: Long,
        zone: ZoneId,
        confirmedAtMs: Long
    ): Boolean = runCatching {
        LocalDate.ofEpochDay(endEpochDay)
            .plusDays(1)
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli() <= confirmedAtMs
    }.getOrDefault(false)

    private fun strictLong(value: Any?): Long? = when (value) {
        is Byte, is Short, is Int, is Long -> (value as Number).toLong()
        is Float, is Double -> {
            val number = (value as Number).toDouble()
            number.takeIf { it.isFinite() && it % 1.0 == 0.0 }?.toLong()
        }
        is String -> value.trim().toLongOrNull()
        else -> null
    }

    private fun corrupt() =
        ReadResult(emptyList(), false, listOf(CORRUPT_WARNING))
}
