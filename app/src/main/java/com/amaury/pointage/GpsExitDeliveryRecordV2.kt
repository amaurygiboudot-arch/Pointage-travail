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
    val configurationFingerprint: String
) {
    init {
        require(event.transition == GpsTransitionV2.EXIT && event.atMs > 0L &&
            sessionArrivalMs > 0L && event.atMs >= sessionArrivalMs)
        require(event.id.isNotBlank() && event.placeId.isNotBlank() && sessionId.isNotBlank() &&
            configurationFingerprint.isNotBlank())
        require(accountScope == "guest" || accountScope.startsWith("uid:") && accountScope.length > 4)
    }
    fun observationContext(): String = encodeObservationContext(sessionId, sessionArrivalMs,
        accountScope, configurationFingerprint)
    fun encode(): String = listOf(
        "1", event.id, event.atMs.toString(), event.placeId, event.pointType.name,
        sessionId, sessionArrivalMs.toString(), accountScope, configurationFingerprint
    ).joinToString("|") { URLEncoder.encode(it, "UTF-8") }

    fun receiptId(): String = MessageDigest.getInstance("SHA-256")
        .digest(encode().toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun matchesSession(id: String?, arrivalMs: Long?): Boolean =
        id == sessionId && arrivalMs == sessionArrivalMs && event.atMs >= sessionArrivalMs

    companion object {
        fun encodeObservationContext(sessionId: String, arrivalMs: Long, accountScope: String,
            fingerprint: String): String = listOf("1", sessionId, arrivalMs.toString(), accountScope, fingerprint)
            .joinToString("|") { URLEncoder.encode(it, "UTF-8") }

        fun decode(raw: String): GpsExitDeliveryRecordV2? = runCatching {
            val parts = raw.split('|').map { URLDecoder.decode(it, "UTF-8") }
            require(parts.size == 9 && parts[0] == "1")
            require(listOf(1, 3, 5, 7, 8).all { parts[it].isNotBlank() })
            val at = parts[2].toLong()
            val arrival = parts[6].toLong()
            require(arrival > 0 && at >= arrival)
            require(parts[7] == "guest" || parts[7].startsWith("uid:") && parts[7].length > 4)
            GpsExitDeliveryRecordV2(
                GpsEventV2(parts[1], at, parts[3], GpsPointTypeV2.valueOf(parts[4]), GpsTransitionV2.EXIT),
                parts[5], arrival, parts[7], parts[8]
            )
        }.getOrNull()
    }
}
