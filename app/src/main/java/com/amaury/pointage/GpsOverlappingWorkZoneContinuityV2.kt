package com.amaury.pointage

import com.amaury.pointage.core.location.GeoPoint
import com.amaury.pointage.core.location.WorkplaceGeometryEngine

/**
 * Two independently configured work zones may describe the very same worksite.
 * A return from A to overlapping B may cancel an UNCONFIRMED departure only
 * with positive evidence: same verified employer, same place, intersecting
 * circular boundaries and a brief interval. Never use a global selected job.
 *
 * This does not create or close work sessions or infer paid minutes.
 */
internal object GpsOverlappingWorkZoneContinuityV2 {
    const val MAX_CONTINUITY_GAP_MS = 120_000L

    fun isProvenSameWorksite(
        exited: StoredGpsZone?,
        entered: StoredGpsZone?,
        exitAtMs: Long,
        entryAtMs: Long
    ): Boolean {
        if (exited == null || entered == null || exited.id == entered.id ||
            exitAtMs <= 0L || entryAtMs < exitAtMs ||
            entryAtMs - exitAtMs > MAX_CONTINUITY_GAP_MS) return false

        val oldEmployer = exited.companyId?.trim()?.takeIf { it.isNotBlank() } ?: return false
        if (entered.companyId?.trim() != oldEmployer ||
            exited.companySlot != null || entered.companySlot != null ||
            exited.roleForContextV2() != GpsZoneRoleV2.WORK ||
            entered.roleForContextV2() != GpsZoneRoleV2.WORK ||
            exited.placeScope() != entered.placeScope() ||
            exited.address.isNullOrBlank() || entered.address.isNullOrBlank()) return false

        return runCatching {
            WorkplaceGeometryEngine.distanceMeters(
                GeoPoint(exited.latitude, exited.longitude),
                GeoPoint(entered.latitude, entered.longitude)
            ) <= exited.radius.toDouble() + entered.radius.toDouble()
        }.getOrDefault(false)
    }
}
