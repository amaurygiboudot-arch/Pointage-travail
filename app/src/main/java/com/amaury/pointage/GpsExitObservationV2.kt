package com.amaury.pointage

import com.amaury.pointage.v2.engine.GpsTransitionV2
import java.net.URLDecoder
import java.net.URLEncoder

/** Observed departures survive deferred arbitration; a later parking exit cannot redate work. */
internal object GpsExitObservationV2 {
    data class Observation(val atMs: Long, val continuingZoneIds: Set<String>)

    fun inContext(previous: Map<String, Observation>, storedContext: String?, currentContext: String?):
        Map<String, Observation> = if (currentContext != null && currentContext == storedContext) previous else emptyMap()

    fun advance(
        previous: Map<String, Observation>,
        activeZoneIds: Set<String>,
        triggeredZoneIds: List<String>,
        transition: GpsTransitionV2,
        observedAtMs: Long,
        equivalentWorkZones: (String, String) -> Boolean
    ): Map<String, Observation> {
        val observations = previous.toMutableMap()
        val triggered = triggeredZoneIds.toSet()
        when (transition) {
            GpsTransitionV2.ENTER -> {
                // A new presence in B must not extend an earlier departure from A through B.
                val invalidated = (triggered - activeZoneIds).toMutableSet()
                // An ID now denotes another presence cycle. Earlier linked evidence is no
                // longer sufficient; do not attach it to the new cycle's eventual exit.
                do {
                    val parents = observations.filter { (_, value) ->
                        value.continuingZoneIds.any { it in invalidated }
                    }.keys - invalidated
                    invalidated.addAll(parents)
                } while (parents.isNotEmpty())
                invalidated.forEach(observations::remove)
            }
            GpsTransitionV2.EXIT -> {
                val exited = triggered.intersect(activeZoneIds)
                val remaining = activeZoneIds - exited
                if (observedAtMs > 0L) exited.forEach { id ->
                    observations[id] = Observation(
                        observedAtMs, remaining.filterTo(mutableSetOf()) { equivalentWorkZones(id, it) }
                    )
                }
            }
        }
        return observations
    }

    /** Unknown observations remain unknown, including old persisted states from earlier versions. */
    fun resolveExitAtMs(
        selectedZoneId: String,
        exitedZoneIds: Set<String>,
        observations: Map<String, Observation>,
        resolutionAtMs: Long,
        equivalentWorkZones: (String, String) -> Boolean
    ): Long? {
        val visiting = mutableSetOf<String>()
        val resolved = mutableMapOf<String, Long>()
        fun latest(id: String, notBefore: Long): Long? {
            if (id !in exitedZoneIds) return null
            val observed = observations[id] ?: return null
            if (observed.atMs <= 0L || observed.atMs < notBefore || observed.atMs > resolutionAtMs) return null
            resolved[id]?.let { return it }
            if (!visiting.add(id)) return null
            var result = observed.atMs
            for (continuing in observed.continuingZoneIds) {
                if (!equivalentWorkZones(id, continuing)) continue
                result = maxOf(result, latest(continuing, observed.atMs) ?: return null)
            }
            visiting.remove(id)
            resolved[id] = result
            return result
        }
        return latest(selectedZoneId, 0L)
    }

    // A StringSet is committed with presence IDs in the same preferences transaction.
    // URL encoding preserves arbitrary canonical IDs without delimiter ambiguity.
    fun encode(observations: Map<String, Observation>): Set<String> = observations.map { (id, value) ->
        (listOf(value.atMs.toString(), escape(id)) + value.continuingZoneIds.sorted().map(::escape))
            .joinToString("|")
    }.toSet()

    fun decode(raw: Set<String>?): Map<String, Observation> = runCatching {
        val result = mutableMapOf<String, Observation>()
        for (record in raw.orEmpty()) {
            val parts = record.split('|')
            require(parts.size >= 2)
            val at = parts[0].toLongOrNull()?.takeIf { it > 0L } ?: error("Invalid observation")
            val id = unescape(parts[1]).takeIf(String::isNotBlank) ?: error("Invalid zone")
            val continuing = parts.drop(2).map(::unescape)
            require(continuing.none(String::isBlank) && id !in continuing && id !in result)
            result[id] = Observation(at, continuing.toSet())
        }
        result.toMap()
    }.getOrDefault(emptyMap())

    private fun escape(value: String): String = URLEncoder.encode(value, "UTF-8")
    private fun unescape(value: String): String = URLDecoder.decode(value, "UTF-8")
}
