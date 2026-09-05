package com.amaury.pointage.v2

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks

/** Chaîne LEGI complète : recherche datée -> consultation -> validation -> stockage d'audit. */
object LegalPayrollAuditV2 {
    data class TopicResult(
        val topic: OfficialLegalCodeSourceV2.Topic,
        val candidates: Int,
        val verified: Int,
        val rejectedOrSkipped: Int,
        val saved: Boolean,
        val warnings: List<String> = emptyList()
    )

    data class Summary(
        val referenceAtMs: Long,
        val results: List<TopicResult>
    ) {
        val verifiedArticles: Int get() = results.sumOf { it.verified }
        val completedTopics: Int get() = results.count { it.saved && it.verified > 0 }
        val warnings: List<String> get() = results.flatMap { it.warnings }
    }

    fun auditTopic(
        context: Context,
        topic: OfficialLegalCodeSourceV2.Topic,
        atMs: Long,
        pageSize: Int = 10
    ): Task<TopicResult> {
        require(atMs > 0L) { "Date LEGI invalide" }
        val app = context.applicationContext
        val body = OfficialLegalCodeSourceV2.searchBody(topic, atMs, pageSize)
        return LegifranceFunctionClientV2.request("/search", body)
            .continueWithTask { searchTask ->
                if (!searchTask.isSuccessful) {
                    return@continueWithTask Tasks.forResult(
                        TopicResult(
                            topic = topic,
                            candidates = 0,
                            verified = 0,
                            rejectedOrSkipped = 0,
                            saved = false,
                            warnings = listOf("LEGI ${topic.label} : ${searchTask.exception?.message ?: "recherche officielle impossible"}")
                        )
                    )
                }
                val candidates = OfficialLegalCodeSourceV2.parseCandidates(searchTask.result?.data)
                OfficialLegalCodeVerifierV2.verify(topic, candidates, atMs)
                    .continueWith { verifyTask ->
                        if (!verifyTask.isSuccessful) {
                            return@continueWith TopicResult(
                                topic = topic,
                                candidates = candidates.size,
                                verified = 0,
                                rejectedOrSkipped = candidates.size,
                                saved = false,
                                warnings = listOf("LEGI ${topic.label} : consultation des articles impossible.")
                            )
                        }
                        val verified = verifyTask.result.verified
                        val saved = LegalPayrollSourceStoreV2.replaceTopicSnapshot(app, topic, atMs, verified)
                        val warnings = buildList {
                            if (candidates.isEmpty()) add("LEGI ${topic.label} : aucun article candidat trouvé pour cette date.")
                            else if (verified.isEmpty()) add("LEGI ${topic.label} : aucun candidat n'a passé les contrôles ID / VIGUEUR / période.")
                            if (!saved) add("LEGI ${topic.label} : résultat vérifié mais stockage local impossible.")
                        }
                        TopicResult(
                            topic = topic,
                            candidates = candidates.size,
                            verified = verified.size,
                            rejectedOrSkipped = verifyTask.result.rejectedOrSkippedCount,
                            saved = saved,
                            warnings = warnings
                        )
                    }
            }
    }

    /**
     * Contrôle les thèmes l'un après l'autre afin d'éviter une rafale de requêtes PISTE.
     * Un thème en erreur n'empêche pas les suivants d'être audités.
     */
    fun auditAll(context: Context, atMs: Long): Task<Summary> {
        require(atMs > 0L) { "Date LEGI invalide" }
        return auditSequential(context.applicationContext, atMs, OfficialLegalCodeSourceV2.Topic.entries, 0, emptyList())
            .continueWith { Summary(atMs, it.result ?: emptyList()) }
    }

    private fun auditSequential(
        context: Context,
        atMs: Long,
        topics: List<OfficialLegalCodeSourceV2.Topic>,
        index: Int,
        accumulated: List<TopicResult>
    ): Task<List<TopicResult>> {
        if (index >= topics.size) return Tasks.forResult(accumulated)
        return auditTopic(context, topics[index], atMs)
            .continueWithTask { task ->
                val result = if (task.isSuccessful) {
                    task.result
                } else {
                    TopicResult(
                        topic = topics[index],
                        candidates = 0,
                        verified = 0,
                        rejectedOrSkipped = 0,
                        saved = false,
                        warnings = listOf("LEGI ${topics[index].label} : contrôle interrompu.")
                    )
                }
                auditSequential(context, atMs, topics, index + 1, accumulated + result)
            }
    }
}
