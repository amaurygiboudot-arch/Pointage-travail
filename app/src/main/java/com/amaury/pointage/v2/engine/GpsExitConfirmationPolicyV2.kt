package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.SessionStatusV2
import com.amaury.pointage.v2.model.WorkSessionV2

/** Une correction explicite ne peut précéder un fait de la session ni dépasser la détection. */
object GpsExitConfirmationPolicyV2 {
    fun canAutomaticallyClose(session: WorkSessionV2, detectedMs: Long, expectedEndMs: Long?): Boolean {
        val entry = session.realArrivalMs ?: return false
        return expectedEndMs != null && expectedEndMs > entry && detectedMs >= expectedEndMs &&
            canConfirm(session, session.id, detectedMs, detectedMs) &&
            session.pauses.all { it.paid != null && it.status == com.amaury.pointage.v2.model.DecisionStatusV2.CONFIRMED } &&
            session.travels.all { it.endMs != null && it.classification != com.amaury.pointage.v2.model.TravelClassificationV2.TO_CONFIRM }
    }

    fun canConfirm(session: WorkSessionV2, sessionId: String, detectedMs: Long, exitMs: Long): Boolean {
        val entry = session.realArrivalMs ?: return false
        return session.id == sessionId && session.status == SessionStatusV2.OPEN &&
            session.realExitMs == null && entry > 0L && exitMs > entry && exitMs <= detectedMs &&
            session.pauses.all { exitMs >= (it.endMs ?: it.startMs) } &&
            session.travels.all { exitMs >= (it.endMs ?: it.startMs) }
    }
}
