package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.v2.engine.SegmentedPayrollSessionSourceV2
import com.amaury.pointage.v2.model.WorkSessionV2
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneId

/**
 * Attestation explicite qu'un intervalle civil a été vérifié contre un snapshot canonique
 * du journal de pointage. Une lecture fiable ne crée jamais cette preuve à elle seule.
 *
 * L'empreinte porte sur TOUS les faits de session utiles à la paie. Toute modification
 * ultérieure d'un pointage, d'une pause ou d'un déplacement invalide donc automatiquement
 * l'attestation lors de la prochaine résolution.
 */
data class V2PayrollCoverageAttestationV2(
    val employerId: String,
    val coveredStartEpochDay: Long,
    val coveredEndEpochDay: Long,
    val checkedAtMs: Long,
    val timeZoneId: String,
    val sourceId: String,
    val historyFingerprint: String
)

data class V2PayrollCoverageResolutionV2(
    val attestation: V2PayrollCoverageAttestationV2?,
    val reliable: Boolean,
    val warnings: List<String>
)

object V2PayrollCoverageFingerprintV2 {
    fun compute(sessions: List<WorkSessionV2>): String {
        val canonical = sessions
            .sortedWith(compareBy<WorkSessionV2>({ it.id }, { it.realArrivalMs ?: Long.MIN_VALUE }))
            .joinToString(prefix = "[", postfix = "]", separator = ",") { session(it) }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun session(value: WorkSessionV2): String = buildString {
        token(value.id)
        token(value.employerId)
        token(value.realArrivalMs)
        token(value.countedEntryMs)
        token(value.countedExitMs)
        token(value.realExitMs)
        token(value.status.name)
        token(value.placeId)
        token(value.placeLabel)
        token(value.legacyFixedUnpaidPauseMs)
        append("<pauses:")
        value.pauses
            .sortedWith(compareBy({ it.startMs }, { it.endMs ?: Long.MIN_VALUE }, { it.source.name }))
            .forEach {
                token(it.startMs); token(it.endMs); token(it.paid); token(it.source.name); token(it.status.name)
            }
        append("><travels:")
        value.travels
            .sortedWith(compareBy({ it.startMs }, { it.endMs ?: Long.MIN_VALUE }, { it.employerBeforeId ?: "" }))
            .forEach {
                token(it.startMs); token(it.endMs); token(it.employerBeforeId); token(it.employerAfterId)
                token(it.distanceMeters?.toBits()); token(it.classification.name)
            }
        append('>')
    }

    private fun StringBuilder.token(value: Any?) {
        if (value == null) {
            append("-1:")
        } else {
            val text = value.toString()
            append(text.length).append(':').append(text)
        }
        append('|')
    }
}

object V2PayrollCoverageResolverV2 {
    const val MISSING_WARNING =
        "Preuves B21 : aucune attestation exhaustive valide ne couvre toute la période demandée."
    const val CHANGED_WARNING =
        "Preuves B21 : l'historique de pointage a changé depuis l'attestation de couverture."
    const val SOURCE_WARNING =
        "Preuves B21 : historique V2 non fiable ; aucune attestation de couverture n'est utilisable."

    fun resolve(
        attestations: List<V2PayrollCoverageAttestationV2>,
        sessions: List<WorkSessionV2>,
        sourceReliable: Boolean,
        sourceWarnings: List<String>,
        employerId: String,
        requiredStartEpochDay: Long,
        requiredEndEpochDay: Long,
        nowMs: Long
    ): V2PayrollCoverageResolutionV2 {
        val warnings = sourceWarnings.toMutableList()
        if (!sourceReliable) {
            warnings += SOURCE_WARNING
            return V2PayrollCoverageResolutionV2(null, false, warnings.distinct())
        }
        val employer = employerId.trim()
        if (employer.isEmpty() || requiredEndEpochDay < requiredStartEpochDay || nowMs <= 0L) {
            warnings += MISSING_WARNING
            return V2PayrollCoverageResolutionV2(null, false, warnings.distinct())
        }
        val fingerprint = V2PayrollCoverageFingerprintV2.compute(sessions)
        val structurallyMatching = attestations.filter { attestation ->
            attestation.employerId.trim() == employer &&
                attestation.coveredStartEpochDay <= requiredStartEpochDay &&
                attestation.coveredEndEpochDay >= requiredEndEpochDay &&
                attestation.checkedAtMs in 1..nowMs &&
                attestation.sourceId.isNotBlank() &&
                runCatching { ZoneId.of(attestation.timeZoneId) }.isSuccess
        }
        val valid = structurallyMatching
            .filter { it.historyFingerprint.equals(fingerprint, ignoreCase = true) }
            .maxWithOrNull(compareBy<V2PayrollCoverageAttestationV2>({ it.checkedAtMs }, {
                -(it.coveredEndEpochDay - it.coveredStartEpochDay)
            }))
        if (valid != null) return V2PayrollCoverageResolutionV2(valid, true, warnings.distinct())
        warnings += if (structurallyMatching.isNotEmpty()) CHANGED_WARNING else MISSING_WARNING
        return V2PayrollCoverageResolutionV2(null, false, warnings.distinct())
    }
}

/**
 * Journal local des attestations. L'API recordExplicitConfirmation ne doit être appelée
 * qu'après une action explicite d'un flux métier capable d'affirmer la couverture.
 * Elle ne déduit jamais l'exhaustivité à partir du simple contenu du store.
 */
object V2PayrollCoverageAttestationStoreV2 {
    private const val PREFS = "horatrack_v2_payroll_coverage"
    private const val KEY = "attestations"
    private const val MAX_ATTESTATIONS = 64

    fun recordExplicitConfirmation(
        context: Context,
        employerId: String,
        coveredStartEpochDay: Long,
        coveredEndEpochDay: Long,
        checkedAtMs: Long,
        timeZoneId: String,
        sourceId: String,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        val employer = employerId.trim()
        val source = sourceId.trim()
        val zone = runCatching { ZoneId.of(timeZoneId) }.getOrNull() ?: return false
        if (employer.isEmpty() || source.isEmpty() || checkedAtMs !in 1..nowMs ||
            coveredEndEpochDay < coveredStartEpochDay
        ) return false
        val endExclusive = runCatching {
            LocalDate.ofEpochDay(coveredEndEpochDay).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        }.getOrNull() ?: return false
        if (endExclusive > checkedAtMs) return false

        val read = V2RuntimeReader.allSessions(context, nowMs)
        if (!read.reliable) return false
        val attestation = V2PayrollCoverageAttestationV2(
            employerId = employer,
            coveredStartEpochDay = coveredStartEpochDay,
            coveredEndEpochDay = coveredEndEpochDay,
            checkedAtMs = checkedAtMs,
            timeZoneId = zone.id,
            sourceId = source,
            historyFingerprint = V2PayrollCoverageFingerprintV2.compute(read.sessions)
        )
        val current = readAll(context).filterNot { it.sourceId == source }.toMutableList()
        current += attestation
        val kept = current.sortedByDescending { it.checkedAtMs }.take(MAX_ATTESTATIONS)
        return context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, encode(kept).toString()).commit()
    }

    fun readAll(context: Context): List<V2PayrollCoverageAttestationV2> {
        val raw = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                decode(item)?.let(::add)
            }
        }
    }

    private fun encode(values: List<V2PayrollCoverageAttestationV2>) = JSONArray().apply {
        values.forEach {
            put(JSONObject()
                .put("employerId", it.employerId)
                .put("coveredStartEpochDay", it.coveredStartEpochDay)
                .put("coveredEndEpochDay", it.coveredEndEpochDay)
                .put("checkedAtMs", it.checkedAtMs)
                .put("timeZoneId", it.timeZoneId)
                .put("sourceId", it.sourceId)
                .put("historyFingerprint", it.historyFingerprint))
        }
    }

    private fun decode(item: JSONObject): V2PayrollCoverageAttestationV2? {
        val employer = item.optString("employerId").trim().takeIf(String::isNotEmpty) ?: return null
        val source = item.optString("sourceId").trim().takeIf(String::isNotEmpty) ?: return null
        val zone = item.optString("timeZoneId").trim().takeIf {
            runCatching { ZoneId.of(it) }.isSuccess
        } ?: return null
        val start = item.optLong("coveredStartEpochDay", Long.MIN_VALUE)
        val end = item.optLong("coveredEndEpochDay", Long.MIN_VALUE)
        val checked = item.optLong("checkedAtMs", 0L)
        val fingerprint = item.optString("historyFingerprint").lowercase()
        if (end < start || checked <= 0L || fingerprint.length != 64 ||
            fingerprint.any { it !in '0'..'9' && it !in 'a'..'f' }
        ) return null
        return V2PayrollCoverageAttestationV2(employer, start, end, checked, zone, source, fingerprint)
    }
}

/** Adaptateur store V2 -> source B21. Sans attestation valide, exhaustive reste toujours faux. */
object V2PayrollSessionSourceFactoryV2 {
    fun fromRuntime(
        context: Context,
        employerId: String,
        requiredStartEpochDay: Long,
        requiredEndEpochDay: Long,
        nowMs: Long = System.currentTimeMillis()
    ): SegmentedPayrollSessionSourceV2 {
        val read = V2RuntimeReader.allSessions(context, nowMs)
        val resolution = V2PayrollCoverageResolverV2.resolve(
            attestations = V2PayrollCoverageAttestationStoreV2.readAll(context),
            sessions = read.sessions,
            sourceReliable = read.reliable,
            sourceWarnings = read.warnings,
            employerId = employerId,
            requiredStartEpochDay = requiredStartEpochDay,
            requiredEndEpochDay = requiredEndEpochDay,
            nowMs = nowMs
        )
        val attestation = resolution.attestation
        return SegmentedPayrollSessionSourceV2(
            employerId = employerId.trim(),
            sessions = read.sessions,
            sourceId = attestation?.sourceId ?: "runtime-unattested",
            reliable = read.reliable,
            exhaustive = resolution.reliable && attestation != null,
            coveredStartEpochDay = attestation?.coveredStartEpochDay ?: requiredStartEpochDay,
            coveredEndEpochDay = attestation?.coveredEndEpochDay ?: requiredEndEpochDay,
            checkedAtMs = attestation?.checkedAtMs ?: nowMs,
            timeZoneId = attestation?.timeZoneId ?: ZoneId.systemDefault().id,
            warnings = resolution.warnings
        )
    }
}
