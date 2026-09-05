package com.amaury.pointage.v2

import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Vérifie les candidats LEGI par consultation de l'article officiel.
 *
 * Cette couche ne déduit aucun taux ni aucune règle chiffrée du texte : elle confirme seulement
 * l'identifiant, l'état juridique compatible et la période d'application à la date de paie contrôlée.
 */
object OfficialLegalCodeVerifierV2 {
    data class VerifiedArticle(
        val topic: OfficialLegalCodeSourceV2.Topic,
        val articleId: String,
        val articleNumber: String?,
        val status: String,
        val excerpt: String,
        val effectiveFromMs: Long,
        val effectiveToMs: Long?,
        val referenceAtMs: Long,
        val checkedAtMs: Long
    )

    data class Result(
        val verified: List<VerifiedArticle>,
        val rejectedOrSkippedCount: Int
    )

    fun verify(
        topic: OfficialLegalCodeSourceV2.Topic,
        candidates: List<OfficialLegalCodeSourceV2.Candidate>,
        atMs: Long,
        maxCandidates: Int = 6
    ): Task<Result> {
        require(atMs > 0L) { "Date LEGI invalide" }
        val limited = candidates.distinctBy { it.articleId }.take(maxCandidates.coerceIn(1, 12))
        if (limited.isEmpty()) return Tasks.forResult(Result(emptyList(), candidates.size))

        val checks = limited.map { candidate ->
            LegifranceFunctionClientV2.request("/consult/getArticle", mapOf("id" to candidate.articleId))
                .continueWith { task ->
                    if (!task.isSuccessful) return@continueWith null
                    val article = OfficialLegalCodeSourceV2.parseArticle(task.result?.data)
                        ?: return@continueWith null
                    validate(topic, candidate, article, atMs, System.currentTimeMillis())
                }
        }

        return Tasks.whenAllComplete(checks).continueWith {
            val verified = checks.mapNotNull { check ->
                if (check.isSuccessful) check.result else null
            }.distinctBy { it.articleId }
            Result(
                verified = verified,
                rejectedOrSkippedCount = candidates.distinctBy { it.articleId }.size - verified.size
            )
        }
    }

    internal fun validate(
        topic: OfficialLegalCodeSourceV2.Topic,
        candidate: OfficialLegalCodeSourceV2.Candidate,
        article: OfficialLegalCodeSourceV2.Article,
        atMs: Long,
        checkedAtMs: Long
    ): VerifiedArticle? {
        if (article.articleId != candidate.articleId) return null
        val normalizedStatus = article.status?.trim()?.uppercase().orEmpty()
        if (normalizedStatus !in setOf("VIGUEUR", "VIGUEUR_DIFF")) return null
        val from = parseDateMs(article.effectiveFrom) ?: return null
        val to = parseDateMs(article.effectiveTo)
        if (atMs < from || (to != null && atMs > to)) return null
        val excerpt = article.content.replace(Regex("\\s+"), " ").trim().take(1_200)
        if (excerpt.isBlank()) return null
        return VerifiedArticle(
            topic = topic,
            articleId = article.articleId,
            articleNumber = article.articleNumber ?: candidate.articleNumber,
            status = normalizedStatus,
            excerpt = excerpt,
            effectiveFromMs = from,
            effectiveToMs = to,
            referenceAtMs = atMs,
            checkedAtMs = checkedAtMs
        )
    }

    private fun parseDateMs(raw: String?): Long? {
        val value = raw?.trim().orEmpty()
        if (value.isBlank()) return null
        value.toLongOrNull()?.takeIf { it > 0L }?.let { return it }
        return runCatching {
            LocalDate.parse(value.take(10))
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli()
        }.getOrNull()?.takeIf { it > 0L }
    }
}
