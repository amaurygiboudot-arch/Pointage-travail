package com.amaury.pointage.v2

import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Vérifie les candidats LEGI par consultation de l'article officiel.
 *
 * Cette couche ne déduit aucun taux ni aucune règle chiffrée du texte : elle confirme seulement
 * l'identifiant officiel (version ou CID), l'état juridique compatible et la période d'application
 * à la date de paie contrôlée.
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
        val rejectedOrSkippedCount: Int,
        val rejectionReasons: List<String> = emptyList()
    )

    private data class CandidateCheck(
        val verified: VerifiedArticle?,
        val reason: String?
    )

    fun verify(
        topic: OfficialLegalCodeSourceV2.Topic,
        candidates: List<OfficialLegalCodeSourceV2.Candidate>,
        atMs: Long,
        maxCandidates: Int = 6
    ): Task<Result> {
        require(atMs > 0L) { "Date LEGI invalide" }
        val distinct = candidates.distinctBy { it.articleId }
        val limited = distinct.take(maxCandidates.coerceIn(1, 12))
        if (limited.isEmpty()) return Tasks.forResult(Result(emptyList(), distinct.size))

        val checks = limited.map { candidate ->
            LegifranceFunctionClientV2.request("/consult/getArticle", mapOf("id" to candidate.articleId))
                .continueWith { task ->
                    if (!task.isSuccessful) {
                        return@continueWith CandidateCheck(null, "consultation article impossible")
                    }
                    val article = OfficialLegalCodeSourceV2.parseArticle(task.result?.data)
                        ?: return@continueWith CandidateCheck(null, "réponse article illisible")
                    val failure = validationFailureReason(candidate, article, atMs)
                    if (failure != null) {
                        CandidateCheck(null, failure)
                    } else {
                        CandidateCheck(
                            validate(topic, candidate, article, atMs, System.currentTimeMillis()),
                            null
                        )
                    }
                }
        }

        return Tasks.whenAllComplete(checks).continueWith {
            val outcomes = checks.mapNotNull { check ->
                if (check.isSuccessful) check.result else null
            }
            val verified = outcomes.mapNotNull { it.verified }.distinctBy { it.articleId }
            Result(
                verified = verified,
                rejectedOrSkippedCount = distinct.size - verified.size,
                rejectionReasons = outcomes.mapNotNull { it.reason }.distinct().take(4)
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
        if (validationFailureReason(candidate, article, atMs) != null) return null
        val normalizedStatus = article.status?.trim()?.uppercase().orEmpty()
        val from = parseDateMs(article.effectiveFrom) ?: return null
        val to = parseDateMs(article.effectiveTo)
        val excerpt = article.content.replace(Regex("\\s+"), " ").trim().take(1_200)
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

    private fun validationFailureReason(
        candidate: OfficialLegalCodeSourceV2.Candidate,
        article: OfficialLegalCodeSourceV2.Article,
        atMs: Long
    ): String? {
        val candidateMatchesVersion = article.articleId == candidate.articleId
        val candidateMatchesCid = article.articleCid == candidate.articleId
        if (!candidateMatchesVersion && !candidateMatchesCid) return "identifiant LEGI différent"

        val normalizedStatus = article.status?.trim()?.uppercase().orEmpty()
        if (normalizedStatus !in setOf("VIGUEUR", "VIGUEUR_DIFF")) {
            return if (normalizedStatus.isBlank()) "état juridique absent" else "état juridique $normalizedStatus"
        }

        val from = parseDateMs(article.effectiveFrom) ?: return "date de début absente ou invalide"
        val to = parseDateMs(article.effectiveTo)
        if (atMs < from || (to != null && atMs > to)) return "article hors période"

        val excerpt = article.content.replace(Regex("\\s+"), " ").trim()
        if (excerpt.isBlank()) return "contenu article vide"
        return null
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
