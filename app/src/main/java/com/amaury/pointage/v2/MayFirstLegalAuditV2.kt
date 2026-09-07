package com.amaury.pointage.v2

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks

/** Recherche ciblée et vérification officielle de l'article L3133-6 applicable à la date de paie. */
object MayFirstLegalAuditV2 {
    data class Summary(
        val referenceAtMs: Long,
        val candidates: Int,
        val verifiedArticles: Int,
        val structuredRules: Int,
        val saved: Boolean,
        val selectedArticleId: String? = null,
        val warnings: List<String> = emptyList()
    )

    fun audit(context: Context, atMs: Long): Task<Summary> {
        require(atMs > 0L) { "Date LEGI invalide" }
        val body = searchBody(atMs)
        return LegifranceFunctionClientV2.request("/search", body).continueWithTask { searchTask ->
            if (!searchTask.isSuccessful) {
                return@continueWithTask Tasks.forResult(
                    Summary(atMs, 0, 0, 0, false, warnings = listOf("LEGI 1er mai : recherche officielle impossible."))
                )
            }
            val candidates = OfficialLegalCodeSourceV2.parseCandidates(searchTask.result?.data)
            val preferred = candidates.filter { normalizeArticleNumber(it.articleNumber) == "L3133-6" }
            val toVerify = if (preferred.isNotEmpty()) preferred else candidates
            OfficialLegalCodeVerifierV2.verify(
                topic = OfficialLegalCodeSourceV2.Topic.PUBLIC_HOLIDAYS,
                candidates = toVerify,
                atMs = atMs,
                maxCandidates = 12
            ).continueWith { verifyTask ->
                if (!verifyTask.isSuccessful) {
                    return@continueWith Summary(
                        atMs, candidates.size, 0, 0, false,
                        warnings = listOf("LEGI 1er mai : consultation officielle impossible.")
                    )
                }
                val verified = verifyTask.result.verified
                val rules = verified.mapNotNull(OfficialMayFirstLegalRuleV2::parseVerified)
                    .distinctBy { it.articleId }
                val selected = rules.singleOrNull()
                val saved = selected?.let { MayFirstLegalRuleStoreV2.save(context, it) } == true
                Summary(
                    referenceAtMs = atMs,
                    candidates = candidates.size,
                    verifiedArticles = verified.size,
                    structuredRules = rules.size,
                    saved = saved,
                    selectedArticleId = if (saved) selected?.articleId else null,
                    warnings = buildList {
                        if (candidates.isEmpty()) add("LEGI 1er mai : aucun candidat officiel trouvé.")
                        if (verified.isNotEmpty() && rules.isEmpty()) {
                            add("LEGI 1er mai : article(s) vérifié(s), mais aucun ne confirme strictement la règle de L3133-6.")
                        }
                        if (rules.size > 1) add("LEGI 1er mai : plusieurs versions calculables sont applicables ; aucun choix automatique.")
                        if (selected != null && !saved) add("LEGI 1er mai : règle vérifiée mais stockage local impossible.")
                    }
                )
            }
        }
    }

    internal fun searchBody(atMs: Long): Map<String, Any> {
        require(atMs > 0L)
        return mapOf(
            "fond" to "CODE_DATE",
            "recherche" to mapOf(
                "champs" to listOf(
                    mapOf(
                        "typeChamp" to "ARTICLE",
                        "criteres" to listOf(
                            mapOf(
                                "typeRecherche" to "TOUS_LES_MOTS_DANS_UN_CHAMP",
                                "valeur" to "1er mai indemnité salaire",
                                "operateur" to "ET"
                            )
                        ),
                        "operateur" to "ET"
                    )
                ),
                "filtres" to listOf(
                    mapOf("facette" to "NOM_CODE", "valeurs" to listOf("Code du travail")),
                    mapOf("facette" to "DATE_VERSION", "singleDate" to atMs),
                    mapOf("facette" to "TEXT_LEGAL_STATUS", "valeur" to "VIGUEUR")
                ),
                "pageNumber" to 1,
                "pageSize" to 25,
                "operateur" to "ET",
                "sort" to "PERTINENCE",
                "typePagination" to "ARTICLE"
            )
        )
    }

    private fun normalizeArticleNumber(value: String?): String = value.orEmpty()
        .uppercase()
        .replace(Regex("\\s+"), "")
        .replace('–', '-')
        .replace('—', '-')
}
