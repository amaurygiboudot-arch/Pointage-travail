package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.WorkSessionV2

/** Analyse factuelle du repos entre deux journées, sans inventer de seuil légal. */
object RestEngineV2 {
    data class DailyRest(
        val previousSessionId: String,
        val nextSessionId: String,
        val previousCountedEndMs: Long,
        val nextCountedStartMs: Long,
        val restMs: Long
    )

    fun dailyRests(sessions: List<WorkSessionV2>): List<DailyRest> {
        val closed = sessions.mapNotNull { s ->
            val start = s.countedEntryMs ?: s.realArrivalMs ?: return@mapNotNull null
            val end = s.countedExitMs ?: s.realExitMs ?: return@mapNotNull null
            if (end <= start) return@mapNotNull null
            Triple(s, start, end)
        }.sortedBy { it.second }

        return buildList {
            for (i in 0 until closed.lastIndex) {
                val previous = closed[i]
                val next = closed[i + 1]
                if (next.second <= previous.third) continue
                add(
                    DailyRest(
                        previousSessionId = previous.first.id,
                        nextSessionId = next.first.id,
                        previousCountedEndMs = previous.third,
                        nextCountedStartMs = next.second,
                        restMs = next.second - previous.third
                    )
                )
            }
        }
    }
}
