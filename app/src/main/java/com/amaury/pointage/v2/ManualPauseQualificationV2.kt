package com.amaury.pointage.v2

/**
 * Qualification explicite d'une pause saisie manuellement.
 *
 * Le statut payé/non payé est un fait métier : il ne peut jamais être déduit du canal de saisie.
 */
data class ManualPauseDraftV2(
    val startMs: Long,
    val endMs: Long,
    val paid: Boolean?
)

data class QualifiedManualPauseV2(
    val startMs: Long,
    val endMs: Long,
    val paid: Boolean
)

object ManualPauseQualificationV2 {
    fun qualify(drafts: List<ManualPauseDraftV2>): List<QualifiedManualPauseV2>? {
        val qualified = drafts.map { draft ->
            val paid = draft.paid ?: return null
            if (draft.startMs <= 0L || draft.endMs <= draft.startMs) return null
            QualifiedManualPauseV2(
                startMs = draft.startMs,
                endMs = draft.endMs,
                paid = paid
            )
        }.sortedBy { it.startMs }

        for (index in 1 until qualified.size) {
            if (qualified[index].startMs < qualified[index - 1].endMs) return null
        }
        return qualified
    }
}
