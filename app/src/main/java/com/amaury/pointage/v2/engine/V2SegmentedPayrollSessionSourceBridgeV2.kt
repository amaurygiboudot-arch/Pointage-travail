package com.amaury.pointage.v2.engine

import android.content.Context
import com.amaury.pointage.v2.V2RuntimeReader
import com.amaury.pointage.v2.V2WorkHistoryCoverageStore

/** Adaptateur canonique journal V2 + attestation explicite -> source B21. */
object V2SegmentedPayrollSessionSourceBridgeV2 {
    fun source(
        context: Context,
        employerId: String,
        startEpochDay: Long,
        endEpochDay: Long,
        timeZoneId: String,
        nowMs: Long = System.currentTimeMillis()
    ): SegmentedPayrollSessionSourceV2 {
        val work = V2RuntimeReader.allSessions(context, nowMs)
        val coverage = V2WorkHistoryCoverageStore.coverage(
            context = context,
            employerId = employerId,
            startEpochDay = startEpochDay,
            endEpochDay = endEpochDay,
            timeZoneId = timeZoneId,
            nowMs = nowMs
        )
        return SegmentedPayrollSessionSourceV2(
            employerId = employerId.trim(),
            sessions = work.sessions,
            sourceId = coverage.sourceId,
            reliable = work.reliable && coverage.reliable,
            exhaustive = coverage.fullyCovered,
            coveredStartEpochDay = startEpochDay,
            coveredEndEpochDay = endEpochDay,
            checkedAtMs = coverage.checkedAtMs,
            timeZoneId = timeZoneId.trim(),
            warnings = (work.warnings + coverage.warnings).distinct()
        )
    }
}
