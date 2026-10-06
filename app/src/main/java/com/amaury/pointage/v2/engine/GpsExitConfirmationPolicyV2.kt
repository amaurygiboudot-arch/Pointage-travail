package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.SessionStatusV2
import com.amaury.pointage.v2.model.WorkSessionV2

/** Une correction explicite ne peut précéder un fait de la session ni dépasser la détection. */
object GpsExitConfirmationPolicyV2 {
    fun canConfirm(session: WorkSessionV2, sessionId: String, detectedMs: Long, exitMs: Long): Boolean {
        val entry = session.realArrivalMs ?: return false
        return session.id == sessionId && session.status == SessionStatusV2.OPEN &&
            session.realExitMs == null && entry > 0L && exitMs > entry && exitMs <= detectedMs &&
            session.pauses.all { exitMs >= (it.endMs ?: it.startMs) } &&
            session.travels.all { exitMs >= (it.endMs ?: it.startMs) }
    }
}
