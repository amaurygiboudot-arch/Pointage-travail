package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.v2.engine.PayrollCoverageAttestationPolicyV2
import com.amaury.pointage.v2.engine.PayrollCoverageAttestationV2
import com.amaury.pointage.v2.engine.SegmentedPayrollSessionSourceV2
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

object V2PayrollCoverageStore {
    private const val PREFS = "horatrack_v2_payroll_coverage"
    private const val KEY_ITEMS = "confirmed_ranges"
    private const val SCHEMA_VERSION = 1

    fun saveConfirmed(
        context: Context,
        employerId: String,
        coveredStartEpochDay: Long,
        coveredEndEpochDay: Long,
        checkedAtMs: Long,
        timeZoneId: String,
        nowMs: Long = System.currentTimeMillis()
    ): PayrollCoverageAttestationV2? {
        val employer = employerId.trim()
        if (employer.isEmpty() || checkedAtMs <= 0L || checkedAtMs > nowMs) return null
        if (!PayrollCoverageAttestationPolicyV2.coverageClosedBeforeCheck(
                coveredEndEpochDay, checkedAtMs, timeZoneId
            )) return null

        val read = V2RuntimeReader.allSessions(context, nowMs)
        if (!read.reliable) return null
        val fingerprint = PayrollCoverageAttestationPolicyV2.fingerprint(
            read.sessions, coveredStartEpochDay, coveredEndEpochDay, timeZoneId
        ) ?: return null
        val attestation = PayrollCoverageAttestationV2(
            employerId = employer,
            coveredStartEpochDay = coveredStartEpochDay,
            coveredEndEpochDay = coveredEndEpochDay,
            checkedAtMs = checkedAtMs,
            timeZoneId = timeZoneId.trim(),
            sourceId = "coverage:" + UUID.randomUUID().toString(),
            sessionFingerprint = fingerprint
        )
        val items = readItems(context).filterNot {
            it.employerId == employer &&
                it.coveredStartEpochDay == coveredStartEpochDay &&
                it.coveredEndEpochDay == coveredEndEpochDay &&
                it.timeZoneId == timeZoneId.trim()
        } + attestation
        return if (writeItems(context, items)) attestation else null
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
        val attestation = readItems(context)
            .filter {
                it.employerId == employerId.trim() &&
                    it.coveredStartEpochDay == coveredStartEpochDay &&
                    it.coveredEndEpochDay == coveredEndEpochDay &&
                    it.timeZoneId == timeZoneId.trim()
            }
            .maxByOrNull { it.checkedAtMs }
        val valid = read.reliable && attestation != null &&
            PayrollCoverageAttestationPolicyV2.isValid(
                attestation, read.sessions, employerId, coveredStartEpochDay,
                coveredEndEpochDay, timeZoneId, nowMs
            )
        val warnings = buildList {
            addAll(read.warnings)
            if (attestation == null) add(PayrollCoverageAttestationPolicyV2.MISSING_WARNING)
            else if (!valid) add(PayrollCoverageAttestationPolicyV2.STALE_WARNING)
        }.distinct()
        return SegmentedPayrollSessionSourceV2(
            employerId = employerId.trim(),
            sessions = read.sessions,
            sourceId = attestation?.sourceId.orEmpty(),
            reliable = read.reliable,
            exhaustive = valid,
            coveredStartEpochDay = coveredStartEpochDay,
            coveredEndEpochDay = coveredEndEpochDay,
            checkedAtMs = attestation?.checkedAtMs ?: nowMs,
            timeZoneId = timeZoneId.trim(),
            warnings = warnings
        )
    }

    private fun readItems(context: Context): List<PayrollCoverageAttestationV2> {
        val raw = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ITEMS, null) ?: return emptyList()
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyList()
        if (root.optInt("schemaVersion", -1) != SCHEMA_VERSION) return emptyList()
        val array = root.optJSONArray("items") ?: return emptyList()
        val result = mutableListOf<PayrollCoverageAttestationV2>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: return emptyList()
            val parsed = parse(item) ?: return emptyList()
            result += parsed
        }
        return result
    }

    private fun writeItems(context: Context, items: List<PayrollCoverageAttestationV2>): Boolean {
        val array = JSONArray()
        items.sortedWith(compareBy({ it.employerId }, { it.coveredStartEpochDay }, { it.coveredEndEpochDay }, { it.checkedAtMs }))
            .forEach { item ->
                array.put(JSONObject()
                    .put("employerId", item.employerId)
                    .put("coveredStartEpochDay", item.coveredStartEpochDay)
                    .put("coveredEndEpochDay", item.coveredEndEpochDay)
                    .put("checkedAtMs", item.checkedAtMs)
                    .put("timeZoneId", item.timeZoneId)
                    .put("sourceId", item.sourceId)
                    .put("sessionFingerprint", item.sessionFingerprint))
            }
        val root = JSONObject().put("schemaVersion", SCHEMA_VERSION).put("items", array)
        return context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_ITEMS, root.toString()).commit()
    }

    private fun parse(item: JSONObject): PayrollCoverageAttestationV2? {
        val employer = item.optString("employerId").trim()
        val zone = item.optString("timeZoneId").trim()
        val source = item.optString("sourceId").trim()
        val fingerprint = item.optString("sessionFingerprint").trim()
        val start = item.optLong("coveredStartEpochDay", Long.MIN_VALUE)
        val end = item.optLong("coveredEndEpochDay", Long.MIN_VALUE)
        val checked = item.optLong("checkedAtMs", 0L)
        if (employer.isEmpty() || zone.isEmpty() || source.isEmpty() || fingerprint.isEmpty() ||
            end < start || checked <= 0L) return null
        return PayrollCoverageAttestationV2(employer, start, end, checked, zone, source, fingerprint)
    }
}
