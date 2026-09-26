package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.v2.engine.SegmentedPayrollSessionSourceV2
import com.amaury.pointage.v2.model.PauseV2
import com.amaury.pointage.v2.model.TravelV2
import com.amaury.pointage.v2.model.WorkSessionV2
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.time.DateTimeException
import java.time.LocalDate
import java.time.ZoneId

/**
 * Registre séparé des attestations d'exhaustivité du journal de pointage.
 *
 * Une lecture techniquement fiable ne crée JAMAIS une attestation.
 * Seul un appel explicite à confirmCoverage(...) peut certifier une plage déjà close.
 * L'attestation est liée à l'empreinte exacte du journal canonique : toute modification des
 * sessions, pauses, déplacements ou employeurs la rend automatiquement caduque.
 */
object V2PayrollCoverageAttestationStoreV2 {
    private const val PREFS = "horatrack_v2_payroll_coverage"
    private const val KEY_ATTESTATIONS = "attestations"
    const val MISSING_WARNING =
        "Couverture B21 : aucune attestation exhaustive compatible avec l'historique courant."
    const val CORRUPT_WARNING =
        "Couverture B21 : registre d'attestations illisible ; exhaustivité non prouvée."

    data class Attestation(
        val employerId: String,
        val coveredStartEpochDay: Long,
        val coveredEndEpochDay: Long,
        val checkedAtMs: Long,
        val timeZoneId: String,
        val sourceId: String,
        val historyFingerprint: String
    )

    data class Resolution(
        val exhaustive: Boolean,
        val storeReliable: Boolean,
        val coveredStartEpochDay: Long,
        val coveredEndEpochDay: Long,
        val checkedAtMs: Long,
        val sourceId: String,
        val warnings: List<String>
    )

    fun confirmCoverage(
        context: Context,
        sessionsRead: V2RuntimeReader.SessionsRead,
        employerId: String,
        coveredStartEpochDay: Long,
        coveredEndEpochDay: Long,
        checkedAtMs: Long,
        timeZoneId: String,
        sourceId: String,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        if (!sessionsRead.reliable) return false
        val employer = employerId.trim()
        val source = sourceId.trim()
        val zone = runCatching { ZoneId.of(timeZoneId.trim()) }.getOrNull() ?: return false
        if (employer.isEmpty() || source.isEmpty() ||
            coveredEndEpochDay < coveredStartEpochDay ||
            checkedAtMs <= 0L || checkedAtMs > nowMs ||
            !coverageWindowClosed(coveredEndEpochDay, checkedAtMs, zone)
        ) return false

        val current = readStored(context)
        if (!current.first) return false
        val attestation = Attestation(
            employerId = employer,
            coveredStartEpochDay = coveredStartEpochDay,
            coveredEndEpochDay = coveredEndEpochDay,
            checkedAtMs = checkedAtMs,
            timeZoneId = zone.id,
            sourceId = source,
            historyFingerprint = fingerprint(sessionsRead.sessions)
        )
        val retained = upsertAttestation(current.second, attestation)
        return writeStored(context, retained)
    }

    fun resolve(
        context: Context,
        sessionsRead: V2RuntimeReader.SessionsRead,
        employerId: String,
        requestedStartEpochDay: Long,
        requestedEndEpochDay: Long,
        timeZoneId: String,
        nowMs: Long = System.currentTimeMillis()
    ): Resolution {
        if (!sessionsRead.reliable) {
            return missing(false, requestedStartEpochDay, requestedEndEpochDay, sessionsRead.warnings)
        }
        if (requestedEndEpochDay < requestedStartEpochDay) {
            return missing(false, requestedStartEpochDay, requestedEndEpochDay, listOf(CORRUPT_WARNING))
        }
        val zone = runCatching { ZoneId.of(timeZoneId.trim()) }.getOrNull()
            ?: return missing(false, requestedStartEpochDay, requestedEndEpochDay, listOf(CORRUPT_WARNING))
        val stored = readStored(context)
        if (!stored.first) {
            return missing(false, requestedStartEpochDay, requestedEndEpochDay, listOf(CORRUPT_WARNING))
        }
        val match = selectMatching(
            attestations = stored.second,
            historyFingerprint = fingerprint(sessionsRead.sessions),
            employerId = employerId,
            requestedStartEpochDay = requestedStartEpochDay,
            requestedEndEpochDay = requestedEndEpochDay,
            timeZoneId = zone.id,
            nowMs = nowMs
        ) ?: return missing(true, requestedStartEpochDay, requestedEndEpochDay, listOf(MISSING_WARNING))

        return Resolution(
            exhaustive = true,
            storeReliable = true,
            coveredStartEpochDay = match.coveredStartEpochDay,
            coveredEndEpochDay = match.coveredEndEpochDay,
            checkedAtMs = match.checkedAtMs,
            sourceId = match.sourceId,
            warnings = emptyList()
        )
    }

    internal fun upsertAttestation(
        attestations: List<Attestation>,
        replacement: Attestation
    ): List<Attestation> =
        (attestations.filterNot {
            it.employerId == replacement.employerId &&
                it.sourceId == replacement.sourceId &&
                it.timeZoneId == replacement.timeZoneId &&
                it.coveredStartEpochDay == replacement.coveredStartEpochDay &&
                it.coveredEndEpochDay == replacement.coveredEndEpochDay
        } + replacement).sortedBy { it.checkedAtMs }

    internal fun validStoredAttestation(attestation: Attestation): Boolean {
        val zone = runCatching { ZoneId.of(attestation.timeZoneId.trim()) }.getOrNull() ?: return false
        return attestation.employerId.trim().isNotEmpty() &&
            attestation.sourceId.trim().isNotEmpty() &&
            attestation.historyFingerprint.trim().isNotEmpty() &&
            attestation.coveredEndEpochDay >= attestation.coveredStartEpochDay &&
            attestation.checkedAtMs > 0L &&
            coverageWindowClosed(attestation.coveredEndEpochDay, attestation.checkedAtMs, zone)
    }

    internal fun selectMatching(
        attestations: List<Attestation>,
        historyFingerprint: String,
        employerId: String,
        requestedStartEpochDay: Long,
        requestedEndEpochDay: Long,
        timeZoneId: String,
        nowMs: Long
    ): Attestation? {
        val employer = employerId.trim()
        val zone = timeZoneId.trim()
        if (employer.isEmpty() || zone.isEmpty() || historyFingerprint.isEmpty() ||
            requestedEndEpochDay < requestedStartEpochDay || nowMs <= 0L
        ) return null
        return attestations.asSequence()
            .filter {
                it.employerId == employer &&
                    it.timeZoneId == zone &&
                    it.historyFingerprint == historyFingerprint &&
                    it.sourceId.isNotBlank() &&
                    it.coveredStartEpochDay <= requestedStartEpochDay &&
                    it.coveredEndEpochDay >= requestedEndEpochDay &&
                    it.checkedAtMs in 1..nowMs
            }
            .maxByOrNull { it.checkedAtMs }
    }

    internal fun fingerprint(sessions: List<WorkSessionV2>): String {
        val canonical = buildString {
            sessions.sortedWith(compareBy<WorkSessionV2> { it.id }.thenBy { it.realArrivalMs ?: Long.MIN_VALUE })
                .forEach { session ->
                    field("session")
                    field(session.id)
                    field(session.employerId)
                    field(session.realArrivalMs)
                    field(session.countedEntryMs)
                    field(session.countedExitMs)
                    field(session.realExitMs)
                    field(session.status.name)
                    field(session.placeId)
                    field(session.placeLabel)
                    field(session.legacyFixedUnpaidPauseMs)
                    session.pauses.sortedWith(
                        compareBy<PauseV2> { it.startMs }
                            .thenBy { it.endMs ?: Long.MIN_VALUE }
                            .thenBy { it.source.name }
                    ).forEach { pause ->
                        field("pause")
                        field(pause.startMs)
                        field(pause.endMs)
                        field(pause.paid?.toString())
                        field(pause.source.name)
                        field(pause.status.name)
                    }
                    session.travels.sortedWith(
                        compareBy<TravelV2> { it.startMs }
                            .thenBy { it.endMs ?: Long.MIN_VALUE }
                            .thenBy { it.employerBeforeId ?: "" }
                    ).forEach { travel ->
                        field("travel")
                        field(travel.startMs)
                        field(travel.endMs)
                        field(travel.employerBeforeId)
                        field(travel.employerAfterId)
                        field(travel.distanceMeters?.let(java.lang.Double::doubleToLongBits))
                        field(travel.classification.name)
                    }
                    field("end-session")
                }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun StringBuilder.field(value: Any?) {
        val raw = value?.toString() ?: "<null>"
        append(raw.length).append(':').append(raw).append('|')
    }

    private fun coverageWindowClosed(endEpochDay: Long, checkedAtMs: Long, zone: ZoneId): Boolean =
        try {
            val endExclusive = LocalDate.ofEpochDay(endEpochDay)
                .plusDays(1)
                .atStartOfDay(zone)
                .toInstant()
                .toEpochMilli()
            checkedAtMs >= endExclusive
        } catch (_: DateTimeException) {
            false
        } catch (_: ArithmeticException) {
            false
        }

    private fun readStored(context: Context): Pair<Boolean, List<Attestation>> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_ATTESTATIONS)) return true to emptyList()
        val raw = runCatching { prefs.getString(KEY_ATTESTATIONS, null) }.getOrNull()
            ?: return false to emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return false to emptyList()
        val out = mutableListOf<Attestation>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: return false to emptyList()
            val employer = item.optString("employerId").trim()
            val zone = item.optString("timeZoneId").trim()
            val source = item.optString("sourceId").trim()
            val fingerprint = item.optString("historyFingerprint").trim()
            val start = strictLong(item.opt("coveredStartEpochDay")) ?: return false to emptyList()
            val end = strictLong(item.opt("coveredEndEpochDay")) ?: return false to emptyList()
            val checkedAt = strictLong(item.opt("checkedAtMs")) ?: return false to emptyList()
            val attestation = Attestation(employer, start, end, checkedAt, zone, source, fingerprint)
            if (!validStoredAttestation(attestation)) return false to emptyList()
            out += attestation
        }
        val identities = out.map {
            listOf(
                it.employerId,
                it.timeZoneId,
                it.sourceId,
                it.coveredStartEpochDay.toString(),
                it.coveredEndEpochDay.toString()
            )
        }
        if (identities.toSet().size != identities.size) return false to emptyList()
        return true to out
    }

    private fun writeStored(context: Context, attestations: List<Attestation>): Boolean {
        val array = JSONArray()
        attestations.forEach { a ->
            array.put(
                JSONObject()
                    .put("employerId", a.employerId)
                    .put("coveredStartEpochDay", a.coveredStartEpochDay)
                    .put("coveredEndEpochDay", a.coveredEndEpochDay)
                    .put("checkedAtMs", a.checkedAtMs)
                    .put("timeZoneId", a.timeZoneId)
                    .put("sourceId", a.sourceId)
                    .put("historyFingerprint", a.historyFingerprint)
            )
        }
        val saved = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ATTESTATIONS, array.toString())
            .commit()
        if (!saved) return false
        val verified = readStored(context)
        return verified.first && verified.second == attestations
    }

    private fun strictLong(value: Any?): Long? = when (value) {
        is Byte, is Short, is Int, is Long -> (value as Number).toLong()
        is Float, is Double -> (value as Number).toDouble()
            .takeIf { it.isFinite() && it % 1.0 == 0.0 }
            ?.toLong()
        is String -> value.trim().toLongOrNull()
        else -> null
    }

    private fun missing(
        storeReliable: Boolean,
        start: Long,
        end: Long,
        warnings: List<String>
    ) = Resolution(
        exhaustive = false,
        storeReliable = storeReliable,
        coveredStartEpochDay = start,
        coveredEndEpochDay = end,
        checkedAtMs = 0L,
        sourceId = "runtime-unattested",
        warnings = warnings.distinct()
    )
}

/**
 * Adaptateur unique du journal RuntimeV2 vers le contrat d'entrée B21.
 * Il ne fabrique aucune attestation : sans preuve persistée compatible, exhaustive reste faux.
 */
object V2SegmentedPayrollSessionSourceFactoryV2 {
    fun create(
        context: Context,
        employerId: String,
        requestedStartEpochDay: Long,
        requestedEndEpochDay: Long,
        timeZoneId: String,
        nowMs: Long = System.currentTimeMillis()
    ): SegmentedPayrollSessionSourceV2 {
        val sessions = V2RuntimeReader.allSessions(context, nowMs)
        val coverage = V2PayrollCoverageAttestationStoreV2.resolve(
            context = context,
            sessionsRead = sessions,
            employerId = employerId,
            requestedStartEpochDay = requestedStartEpochDay,
            requestedEndEpochDay = requestedEndEpochDay,
            timeZoneId = timeZoneId,
            nowMs = nowMs
        )
        return SegmentedPayrollSessionSourceV2(
            employerId = employerId.trim(),
            sessions = sessions.sessions,
            sourceId = coverage.sourceId,
            reliable = sessions.reliable && coverage.storeReliable,
            exhaustive = coverage.exhaustive,
            coveredStartEpochDay = coverage.coveredStartEpochDay,
            coveredEndEpochDay = coverage.coveredEndEpochDay,
            checkedAtMs = coverage.checkedAtMs.takeIf { it > 0L } ?: nowMs,
            timeZoneId = timeZoneId,
            warnings = (sessions.warnings + coverage.warnings).distinct()
        )
    }
}
