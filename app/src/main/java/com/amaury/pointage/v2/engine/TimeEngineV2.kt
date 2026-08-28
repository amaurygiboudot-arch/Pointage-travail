package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.WorkSessionV2

/**
 * Contrat du moteur Temps V2.
 * Le moteur sera branché uniquement après validation des tests V2.
 */
interface TimeEngineV2 {
    fun calculate(session: WorkSessionV2, nowMs: Long): TimeResultV2
}

data class TimeResultV2(
    val presenceMs: Long,
    val paidWorkMs: Long,
    val unpaidPauseMs: Long,
    val paidPauseMs: Long,
    val warnings: List<String> = emptyList()
)
