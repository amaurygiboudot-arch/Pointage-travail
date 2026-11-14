package com.amaury.pointage.v2.engine

/** GPS presence is evidence, never a qualification of paid work. Times are absolute instants. */
internal object DeparturePolicyV2 {
    const val RETURN_WINDOW_MS = 120_000L
    data class Facts(
        val observedAtMs: Long, val arrivalMs: Long, val nowMs: Long,
        val contextMatches: Boolean, val sessionOpen: Boolean,
        val hasActiveZone: Boolean, val unresolved: Boolean,
        val lastObservedAtMs: Long = observedAtMs
    )
    /** Validated physical departure. It neither ends a workday nor qualifies paid time. */
    fun observed(f: Facts): Boolean = f.contextMatches && f.sessionOpen &&
        !f.hasActiveZone && !f.unresolved && f.arrivalMs > 0 &&
        f.observedAtMs > f.arrivalMs && f.nowMs >= f.observedAtMs &&
        f.lastObservedAtMs >= f.observedAtMs && f.nowMs >= f.lastObservedAtMs

    fun eligible(f: Facts): Boolean = observed(f) &&
        f.nowMs - f.lastObservedAtMs >= RETURN_WINDOW_MS

    fun automatic(f: Facts, enabled: Boolean, expectedEndMs: Long?): Boolean =
        enabled && eligible(f) && expectedEndMs != null && expectedEndMs > f.arrivalMs &&
            f.observedAtMs >= expectedEndMs
}
