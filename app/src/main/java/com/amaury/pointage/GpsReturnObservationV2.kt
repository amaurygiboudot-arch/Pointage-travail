package com.amaury.pointage

import com.amaury.pointage.v2.engine.GpsPointTypeV2
import com.amaury.pointage.v2.engine.GpsTransitionV2
import java.net.URLDecoder
import java.net.URLEncoder

/** Last observed ENTER per zone, bound before the deferred entry-resolution timer starts. */
internal object GpsReturnObservationV2 {
    const val KEY = "worksite_return_observations"
    const val QUALIFICATION_KEY = "worksite_return_qualification"
    data class Qualification(val zoneId: String, val availableAtMs: Long) {
        fun encode(): String = "$availableAtMs|${URLEncoder.encode(zoneId, "UTF-8")}"
        companion object {
            fun decode(raw: String?): Qualification? = runCatching {
                val fields = checkNotNull(raw).split('|')
                require(fields.size == 2)
                val at = fields[0].toLong()
                val zone = URLDecoder.decode(fields[1], "UTF-8")
                require(at > 0L && zone.isNotBlank())
                Qualification(zone, at)
            }.getOrNull()
        }
    }
    data class Observation(val zoneId: String, val atMs: Long, val context: String) {
        fun encode(): String = listOf(zoneId, atMs.toString(), context)
            .joinToString("|") { URLEncoder.encode(it, "UTF-8") }
    }

    fun decode(raw: Set<String>): Map<String, Observation> = runCatching {
        val result = mutableMapOf<String, Observation>()
        for (record in raw) {
            val fields = record.split('|').map { URLDecoder.decode(it, "UTF-8") }
            require(fields.size == 3 && fields[0].isNotBlank() && fields[2].isNotBlank())
            val at = fields[1].toLong()
            require(at > 0L && fields[0] !in result)
            result[fields[0]] = Observation(fields[0], at, fields[2])
        }
        result.toMap()
    }.getOrDefault(emptyMap())

    fun observe(
        previous: Map<String, Observation>,
        knownZoneIds: Set<String>,
        enteredZoneIds: Set<String>,
        atMs: Long,
        context: String?
    ): Map<String, Observation> {
        val kept = previous.filter { (id, observation) ->
            id in knownZoneIds && (context == null || observation.context == context)
        }.toMutableMap()
        if (context != null && atMs > 0L) enteredZoneIds.filter { it in knownZoneIds }.forEach { id ->
            val previousAt = kept[id]?.atMs ?: 0L
            if (atMs >= previousAt) kept[id] = Observation(id, atMs, context)
        }
        return kept
    }

    fun provesReturn(observations: Map<String, Observation>, delivery: GpsExitDeliveryRecordV2,
        currentContext: String?, nowMs: Long,
        equivalentReturn: (String, String, Long, Long) -> Boolean = { _, _, _, _ -> false }): Boolean {
        if (delivery.event.pointType != GpsPointTypeV2.POSTE || delivery.event.transition != GpsTransitionV2.EXIT) return false
        if (currentContext == null || currentContext != delivery.observationContext()) return false
        return observations.values.any { returned ->
            // Equal wall-clock milliseconds cannot prove callback order after a restart.
            returned.context == currentContext && returned.atMs > delivery.event.atMs && returned.atMs <= nowMs &&
                (returned.zoneId == delivery.event.placeId || equivalentReturn(
                    delivery.event.placeId, returned.zoneId, delivery.event.atMs, returned.atMs
                ))
        }
    }
}
