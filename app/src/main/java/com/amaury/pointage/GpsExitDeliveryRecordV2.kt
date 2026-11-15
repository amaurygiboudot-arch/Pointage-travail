package com.amaury.pointage

import com.amaury.pointage.v2.engine.GpsEventV2
import com.amaury.pointage.v2.engine.GpsPointTypeV2
import com.amaury.pointage.v2.engine.GpsTransitionV2
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest

/** An observation awaiting delivery, never a work session or a payment decision. */
internal data class GpsExitDeliveryRecordV2(
    val event: GpsEventV2,
    val sessionId: String,
    val sessionArrivalMs: Long,
    val accountScope: String,
    val configurationFingerprint: String,
    /** Last observed EXIT used for arbitration; never substitutes the work departure time. */
    val lastObservedAtMs: Long = event.atMs,
    val episodeZoneIds: Set<String> = setOf(event.placeId)
) {
    init {
        require(event.transition == GpsTransitionV2.EXIT && event.atMs > 0L &&
            sessionArrivalMs > 0L && event.atMs >= sessionArrivalMs && lastObservedAtMs >= event.atMs)
        require(event.placeId in episodeZoneIds && episodeZoneIds.all { it.isNotBlank() })
        require(event.id.isNotBlank() && event.placeId.isNotBlank() && sessionId.isNotBlank() &&
            configurationFingerprint.isNotBlank())
        require(accountScope == "guest" || accountScope.startsWith("uid:") && accountScope.length > 4)
    }
    fun observationContext(): String = encodeObservationContext(sessionId, sessionArrivalMs,
        accountScope, configurationFingerprint)
    fun encode(): String = (listOf(
        "2", event.id, event.atMs.toString(), event.placeId, event.pointType.name,
        sessionId, sessionArrivalMs.toString(), accountScope, configurationFingerprint, lastObservedAtMs.toString()
    ) + episodeZoneIds.sorted()).joinToString("|") { URLEncoder.encode(it, "UTF-8") }

    fun receiptId(): String = MessageDigest.getInstance("SHA-256")
        .digest(encode().toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun matchesSession(id: String?, arrivalMs: Long?): Boolean =
        id == sessionId && arrivalMs == sessionArrivalMs && event.atMs >= sessionArrivalMs

    companion object {
        fun encodeObservationContext(sessionId: String, arrivalMs: Long, accountScope: String,
            fingerprint: String): String = listOf("1", sessionId, arrivalMs.toString(), accountScope, fingerprint)
            .joinToString("|") { URLEncoder.encode(it, "UTF-8") }

        /** Recognition for inert quarantine only; never constructs actionable evidence. */
        fun legacyOwner(raw: String): String? = runCatching {
            val parts = raw.split('|').map { URLDecoder.decode(it, "UTF-8") }
            require(parts.size == 9 && parts[0] == "1")
            require(listOf(1, 3, 5, 7, 8).all { parts[it].isNotBlank() })
            require(parts[2].toLong() >= parts[6].toLong() && parts[6].toLong() > 0)
            GpsPointTypeV2.valueOf(parts[4])
            require(parts[7] == "guest" || parts[7].startsWith("uid:") && parts[7].length > 4)
            require(parts.joinToString("|") { URLEncoder.encode(it, "UTF-8") } == raw)
            parts[7]
        }.getOrNull()

        fun decode(raw: String): GpsExitDeliveryRecordV2? = runCatching {
            val parts = raw.split('|').map { URLDecoder.decode(it, "UTF-8") }
            // Version 1 lacks the last EXIT and full episode: never invent those facts.
            require(parts.size >= 11 && parts[0] == "2")
            require(parts.drop(10).distinct().size == parts.size - 10)
            require(listOf(1, 3, 5, 7, 8).all { parts[it].isNotBlank() })
            val at = parts[2].toLong()
            val arrival = parts[6].toLong()
            require(arrival > 0 && at >= arrival)
            require(parts[7] == "guest" || parts[7].startsWith("uid:") && parts[7].length > 4)
            GpsExitDeliveryRecordV2(
                GpsEventV2(parts[1], at, parts[3], GpsPointTypeV2.valueOf(parts[4]), GpsTransitionV2.EXIT),
                parts[5], arrival, parts[7], parts[8], parts[9].toLong(), parts.drop(10).toSet()
            )
        }.getOrNull()
    }
}
