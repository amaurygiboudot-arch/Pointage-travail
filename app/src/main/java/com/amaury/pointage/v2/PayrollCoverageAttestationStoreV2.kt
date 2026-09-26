package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.v2.engine.SegmentedPayrollSessionSourceV2
import com.amaury.pointage.v2.model.WorkSessionV2
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneId

data class PayrollCoverageAttestationV2(
    val employerId: String,
    val sourceId: String,
    val coveredStartEpochDay: Long,
    val coveredEndEpochDay: Long,
    val checkedAtMs: Long,
    val timeZoneId: String,
    val historyFingerprint: String
)

data class PayrollCoverageAttestationReadV2(
    val attestation: PayrollCoverageAttestationV2?,
    val reliable: Boolean,
    val warnings: List<String>
)

object PayrollCoverageAttestationStoreV2 {
    const val PREFS = "horatrack_v2_payroll_coverage"
    private const val KEY_ATTESTATIONS = "attestations_v1"

    const val MISSING_WARNING =
        "Couverture des pointages : aucune attestation exhaustive confirmée ne couvre cette période."
    const val STALE_WARNING =
        "Couverture des pointages : l'historique a changé depuis l'attestation ; une nouvelle confirmation est requise."
    const val INVALID_WARNING =
        "Couverture des pointages : attestation enregistrée illisible ou incohérente."

    fun confirm(
        context: Context,
        employerId: String,
        coveredStartEpochDay: Long,
        coveredEndEpochDay: Long,
        sourceId: String,
        timeZoneId: String,
        checkedAtMs: Long = System.currentTimeMillis()
    ): Boolean {
        val employer = employerId.trim()
        val source = sourceId.trim()
        val zone = runCatching { ZoneId.of(timeZoneId) }.getOrNull() ?: return false
        if (employer.isBlank() || source.isBlank() || checkedAtMs <= 0L ||
            coveredEndEpochDay < coveredStartEpochDay
        ) return false
        val coverageEndExclusive = runCatching {
            LocalDate.ofEpochDay(coveredEndEpochDay).plusDays(1).atStartOfDay(zone)
                .toInstant().toEpochMilli()
        }.getOrNull() ?: return false
        if (coverageEndExclusive > checkedAtMs) return false

        val runtime = V2RuntimeReader.allSessions(context, checkedAtMs)
        if (!runtime.reliable) return false
        val attestation = PayrollCoverageAttestationV2(
            employerId = employer,
            sourceId = source,
            coveredStartEpochDay = coveredStartEpochDay,
            coveredEndEpochDay = coveredEndEpochDay,
            checkedAtMs = checkedAtMs,
            timeZoneId = zone.id,
            historyFingerprint = fingerprint(runtime.sessions)
        )
        val existing = readAll(context).filterNot { it.employerId == employer }
        return writeAll(context, existing + attestation)
    }

    fun read(
        context: Context,
        employerId: String,
        runtime: V2RuntimeReader.SessionsRead,
        requiredStartEpochDay: Long,
        requiredEndEpochDay: Long,
        nowMs: Long = System.currentTimeMillis()
    ): PayrollCoverageAttestationReadV2 {
        if (!runtime.reliable) {
            return PayrollCoverageAttestationReadV2(
                null, false, runtime.warnings.ifEmpty { listOf(V2RuntimeReader.UNRELIABLE_MESSAGE) }
            )
        }
        val employer = employerId.trim()
        if (employer.isBlank() || requiredEndEpochDay < requiredStartEpochDay || nowMs <= 0L) {
            return PayrollCoverageAttestationReadV2(null, false, listOf(INVALID_WARNING))
        }
        val all = runCatching { readAll(context) }.getOrElse {
            return PayrollCoverageAttestationReadV2(null, false, listOf(INVALID_WARNING))
        }
        val attestation = all.singleOrNull { it.employerId == employer }
            ?: return PayrollCoverageAttestationReadV2(null, false, listOf(MISSING_WARNING))
        if (!valid(attestation, nowMs) ||
            requiredStartEpochDay < attestation.coveredStartEpochDay ||
            requiredEndEpochDay > attestation.coveredEndEpochDay
        ) {
            return PayrollCoverageAttestationReadV2(null, false, listOf(MISSING_WARNING))
        }
        if (attestation.historyFingerprint != fingerprint(runtime.sessions)) {
            return PayrollCoverageAttestationReadV2(null, false, listOf(STALE_WARNING))
        }
        return PayrollCoverageAttestationReadV2(attestation, true, emptyList())
    }

    fun clear(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().clear().commit()

    internal fun fingerprint(sessions: List<WorkSessionV2>): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            val sorted = sessions.sortedWith(compareBy<WorkSessionV2> { it.id }.thenBy { it.realArrivalMs ?: Long.MIN_VALUE })
            out.writeInt(sorted.size)
            sorted.forEach { session ->
                writeString(out, session.id)
                writeString(out, session.employerId)
                writeNullableLong(out, session.realArrivalMs)
                writeNullableLong(out, session.countedEntryMs)
                writeNullableLong(out, session.countedExitMs)
                writeNullableLong(out, session.realExitMs)
                writeString(out, session.status.name)
                writeString(out, session.placeId)
                writeString(out, session.placeLabel)
                out.writeLong(session.legacyFixedUnpaidPauseMs)

                val pauses = session.pauses.sortedWith(compareBy({ it.startMs }, { it.endMs ?: Long.MAX_VALUE }, { it.source.name }))
                out.writeInt(pauses.size)
                pauses.forEach { pause ->
                    out.writeLong(pause.startMs)
                    writeNullableLong(out, pause.endMs)
                    writeNullableBoolean(out, pause.paid)
                    writeString(out, pause.source.name)
                    writeString(out, pause.status.name)
                }

                val travels = session.travels.sortedWith(compareBy({ it.startMs }, { it.endMs ?: Long.MAX_VALUE }, { it.classification.name }))
                out.writeInt(travels.size)
                travels.forEach { travel ->
                    out.writeLong(travel.startMs)
                    writeNullableLong(out, travel.endMs)
                    writeString(out, travel.employerBeforeId)
                    writeString(out, travel.employerAfterId)
                    if (travel.distanceMeters == null) out.writeBoolean(false) else {
                        out.writeBoolean(true)
                        out.writeLong(java.lang.Double.doubleToLongBits(travel.distanceMeters))
                    }
                    writeString(out, travel.classification.name)
                }
            }
        }
        return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    internal fun decode(raw: String): List<PayrollCoverageAttestationV2> {
        val array = JSONArray(raw)
        val result = mutableListOf<PayrollCoverageAttestationV2>()
        for (index in 0 until array.length()) {
            val item = array.getJSONObject(index)
            val attestation = PayrollCoverageAttestationV2(
                employerId = item.getString("employerId").trim(),
                sourceId = item.getString("sourceId").trim(),
                coveredStartEpochDay = item.getLong("coveredStartEpochDay"),
                coveredEndEpochDay = item.getLong("coveredEndEpochDay"),
                checkedAtMs = item.getLong("checkedAtMs"),
                timeZoneId = item.getString("timeZoneId").trim(),
                historyFingerprint = item.getString("historyFingerprint").trim()
            )
            require(valid(attestation, Long.MAX_VALUE))
            result += attestation
        }
        require(result.map { it.employerId }.distinct().size == result.size)
        return result
    }

    private fun readAll(context: Context): List<PayrollCoverageAttestationV2> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_ATTESTATIONS, null) ?: return emptyList()
        return decode(raw)
    }

    private fun writeAll(context: Context, attestations: List<PayrollCoverageAttestationV2>): Boolean {
        if (attestations.map { it.employerId }.distinct().size != attestations.size ||
            attestations.any { !valid(it, Long.MAX_VALUE) }
        ) return false
        val array = JSONArray()
        attestations.sortedBy { it.employerId }.forEach { item ->
            array.put(
                JSONObject()
                    .put("employerId", item.employerId)
                    .put("sourceId", item.sourceId)
                    .put("coveredStartEpochDay", item.coveredStartEpochDay)
                    .put("coveredEndEpochDay", item.coveredEndEpochDay)
                    .put("checkedAtMs", item.checkedAtMs)
                    .put("timeZoneId", item.timeZoneId)
                    .put("historyFingerprint", item.historyFingerprint)
            )
        }
        return context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_ATTESTATIONS, array.toString()).commit()
    }

    private fun valid(attestation: PayrollCoverageAttestationV2, nowMs: Long): Boolean {
        if (attestation.employerId.isBlank() || attestation.sourceId.isBlank() ||
            attestation.coveredEndEpochDay < attestation.coveredStartEpochDay ||
            attestation.checkedAtMs <= 0L || attestation.checkedAtMs > nowMs ||
            !attestation.historyFingerprint.matches(Regex("[0-9a-f]{64}"))
        ) return false
        val zone = runCatching { ZoneId.of(attestation.timeZoneId) }.getOrNull() ?: return false
        val endExclusive = runCatching {
            LocalDate.ofEpochDay(attestation.coveredEndEpochDay).plusDays(1).atStartOfDay(zone)
                .toInstant().toEpochMilli()
        }.getOrNull() ?: return false
        return endExclusive <= attestation.checkedAtMs
    }

    private fun writeString(out: DataOutputStream, value: String?) {
        if (value == null) {
            out.writeInt(-1)
        } else {
            val bytes = value.toByteArray(Charsets.UTF_8)
            out.writeInt(bytes.size)
            out.write(bytes)
        }
    }

    private fun writeNullableLong(out: DataOutputStream, value: Long?) {
        out.writeBoolean(value != null)
        if (value != null) out.writeLong(value)
    }

    private fun writeNullableBoolean(out: DataOutputStream, value: Boolean?) {
        out.writeByte(when (value) { null -> -1; false -> 0; true -> 1 })
    }
}

object SegmentedPayrollSessionSourceFactoryV2 {
    fun fromStores(
        context: Context,
        employerId: String,
        requiredStartEpochDay: Long,
        requiredEndEpochDay: Long,
        requestedTimeZoneId: String,
        nowMs: Long = System.currentTimeMillis()
    ): SegmentedPayrollSessionSourceV2 {
        val runtime = V2RuntimeReader.allSessions(context, nowMs)
        val coverage = PayrollCoverageAttestationStoreV2.read(
            context = context,
            employerId = employerId,
            runtime = runtime,
            requiredStartEpochDay = requiredStartEpochDay,
            requiredEndEpochDay = requiredEndEpochDay,
            nowMs = nowMs
        )
        val attestation = coverage.attestation
        return SegmentedPayrollSessionSourceV2(
            employerId = employerId.trim(),
            sessions = runtime.sessions,
            sourceId = attestation?.sourceId.orEmpty(),
            reliable = runtime.reliable && coverage.reliable,
            exhaustive = coverage.reliable,
            coveredStartEpochDay = attestation?.coveredStartEpochDay ?: 0L,
            coveredEndEpochDay = attestation?.coveredEndEpochDay ?: -1L,
            checkedAtMs = attestation?.checkedAtMs ?: nowMs,
            timeZoneId = attestation?.timeZoneId ?: requestedTimeZoneId,
            warnings = (runtime.warnings + coverage.warnings).distinct()
        )
    }
}
