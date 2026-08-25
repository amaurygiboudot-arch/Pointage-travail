package com.amaury.pointage

/**
 * Human recognition between colleagues.
 *
 * Privacy rule: public evaluation documents must never contain the reviewer's
 * name, email address or Google display name. The backend may use a private
 * one-way reviewer key to enforce one active evaluation per colleague.
 */
object ColleagueRecognitionModel {
    const val MIN_REVIEWS_FOR_PUBLIC_RESULT = 5
    const val SCORE_MIN = 1
    const val SCORE_MAX = 5

    data class Criterion(
        val id: String,
        val label: String,
        val required: Boolean
    )

    val criteria = listOf(
        Criterion("respect", "Respect des autres", true),
        Criterion("helpfulness", "Entraide", true),
        Criterion("reliability", "Fiabilité", true),
        Criterion("communication", "Communication", true),
        Criterion("team_spirit", "Esprit d’équipe", true),
        Criterion("listening", "Écoute", false),
        Criterion("kindness", "Bienveillance", false),
        Criterion("conflict_attitude", "Attitude en cas de conflit", false),
        Criterion("fairness", "Équité avec les collègues", false),
        Criterion("general_attitude", "Comportement humain général", false)
    )

    data class WorkplaceIdentity(
        val id: String,
        val displayName: String,
        val source: Source,
        val confirmed: Boolean
    ) {
        enum class Source { AUTO_DETECTED, MANUAL }
    }

    data class ColleagueProfile(
        val id: String,
        val workplaceId: String,
        val nickname: String
    )

    data class Evaluation(
        val workplaceId: String,
        val colleagueId: String,
        val reviewerKey: String,
        val scores: Map<String, Int>,
        val updatedAtMillis: Long
    ) {
        fun isValid(): Boolean {
            if (workplaceId.isBlank() || colleagueId.isBlank() || reviewerKey.isBlank()) return false
            val required = criteria.filter { it.required }.map { it.id }
            if (!required.all(scores::containsKey)) return false
            return scores.all { (id, score) ->
                criteria.any { it.id == id } && score in SCORE_MIN..SCORE_MAX
            }
        }
    }

    data class PublicScore(
        val colleagueId: String,
        val workplaceId: String,
        val reviewCount: Int,
        val averages: Map<String, Double>
    ) {
        val visible: Boolean get() = reviewCount >= MIN_REVIEWS_FOR_PUBLIC_RESULT
        val overall: Double?
            get() = if (!visible || averages.isEmpty()) null else averages.values.average()
    }
}
