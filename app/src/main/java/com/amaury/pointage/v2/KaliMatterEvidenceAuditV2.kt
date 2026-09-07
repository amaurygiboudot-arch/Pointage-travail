package com.amaury.pointage.v2

import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.firebase.functions.HttpsCallableResult
import java.time.LocalDate

/**
 * Collecte exhaustive des pistes retournées par plusieurs recherches KALI ciblées.
 *
 * Cette classe ne déduit jamais une règle de paie ni une absence de droit. Elle
 * garantit seulement que toutes les pages des recherches demandées ont été lues,
 * que les KALITEXT ont été développés, que les KALIARTI ont été consultés et
 * expose les articles officiels applicables à la date contrôlée.
 */
object KaliMatterEvidenceAuditV2 {
    private const val PAGE_SIZE = 25

    data class Evidence(
        val idcc: String,
        val referenceDate: LocalDate,
        val expressions: List<String>,
        val pagesRead: Int,
        val candidates: Int,
        val textsConsulted: Int,
        val unresolvedSections: Int,
        val articlesConsulted: Int,
        val searchCoverageComplete: Boolean,
        val allTextsExpanded: Boolean,
        val allArticlesConsulted: Boolean,
        val articles: List<OfficialKaliOvertimeRuleParserV2.VerifiedArticle>,
        val warnings: List<String>
    ) {
        val technicalCoverageComplete: Boolean
            get() = searchCoverageComplete && allTextsExpanded && allArticlesConsulted && unresolvedSections == 0
    }

    private data class SearchBatch(
        val candidates: List<OfficialKaliMatterSourceV2.Candidate>,
        val pagesRead: Int,
        val complete: Boolean,
        val warnings: List<String>
    )

    private data class ExpressionBatch(
        val candidates: List<OfficialKaliMatterSourceV2.Candidate> = emptyList(),
        val pagesRead: Int = 0,
        val complete: Boolean = true,
        val warnings: List<String> = emptyList()
    )

    private data class ExpansionBatch(
        val articleIds: List<String>,
        val textsConsulted: Int,
        val complete: Boolean,
        val warnings: List<String>
    )

    private data class ConsultBatch(
        val consulted: Int,
        val complete: Boolean,
        val articles: List<OfficialKaliOvertimeRuleParserV2.VerifiedArticle>,
        val warnings: List<String>
    )

    fun audit(idcc: String, referenceDate: LocalDate, expressions: List<String>): Task<Evidence> {
        val normalized = normalizeIdcc(idcc)
            ?: return Tasks.forResult(
                Evidence(idcc, referenceDate, emptyList(), 0, 0, 0, 0, 0, false, false, false, emptyList(), listOf("KALI : IDCC invalide."))
            )
        val queries = expressions.map { it.trim().replace(Regex("\\s+"), " ") }.filter { it.isNotBlank() }.distinct()
        if (queries.isEmpty()) {
            return Tasks.forResult(
                Evidence(normalized, referenceDate, emptyList(), 0, 0, 0, 0, 0, false, false, false, emptyList(), listOf("KALI : aucune expression de recherche fournie."))
            )
        }

        return fetchExpressions(normalized, queries)
            .continueWithTask { searchTask ->
                if (!searchTask.isSuccessful) {
                    return@continueWithTask Tasks.forResult(
                        Evidence(
                            normalized, referenceDate, queries, 0, 0, 0, 0, 0,
                            false, false, false, emptyList(),
                            listOf("KALI : collecte des recherches officielles impossible.")
                        )
                    )
                }
                val search = searchTask.result
                val direct = search.candidates.filter { it.id.startsWith("KALIARTI") }.distinctBy { it.id }
                val texts = search.candidates.filter { it.id.startsWith("KALITEXT") }.distinctBy { it.id }
                val sections = search.candidates.filter { it.id.startsWith("KALISCTA") }.distinctBy { it.id }

                expandTexts(texts).continueWithTask { expansionTask ->
                    val expansion = if (expansionTask.isSuccessful) expansionTask.result else ExpansionBatch(
                        emptyList(), 0, false, listOf("KALI : développement des textes interrompu.")
                    )
                    val articleIds = (direct.map { it.id } + expansion.articleIds).distinct()
                    consultArticles(articleIds, referenceDate).continueWith { consultTask ->
                        val consult = if (consultTask.isSuccessful) consultTask.result else ConsultBatch(
                            0, false, emptyList(), listOf("KALI : consultation des articles interrompue.")
                        )
                        Evidence(
                            idcc = normalized,
                            referenceDate = referenceDate,
                            expressions = queries,
                            pagesRead = search.pagesRead,
                            candidates = search.candidates.size,
                            textsConsulted = expansion.textsConsulted,
                            unresolvedSections = sections.size,
                            articlesConsulted = consult.consulted,
                            searchCoverageComplete = search.complete,
                            allTextsExpanded = expansion.complete,
                            allArticlesConsulted = consult.complete,
                            articles = consult.articles.distinctBy { it.articleId },
                            warnings = buildList {
                                addAll(search.warnings)
                                addAll(expansion.warnings)
                                addAll(consult.warnings)
                                if (sections.isNotEmpty()) add("KALI : ${sections.size} section(s) KALISCTA restent non résolues ; aucune règle ne peut être auto-enregistrée à partir de cette collecte.")
                                if (articleIds.isEmpty()) add("KALI : aucune piste KALIARTI n'a été obtenue par les recherches ciblées ; cela ne prouve jamais l'absence officielle de droit.")
                            }.distinct()
                        )
                    }
                }
            }
    }

    private fun fetchExpressions(
        idcc: String,
        expressions: List<String>,
        index: Int = 0,
        accumulated: ExpressionBatch = ExpressionBatch()
    ): Task<ExpressionBatch> {
        if (index >= expressions.size) return Tasks.forResult(accumulated.copy(candidates = accumulated.candidates.distinctBy { it.id }))
        val expression = expressions[index]
        return fetchPages(idcc, expression).continueWithTask { task ->
            if (!task.isSuccessful) {
                return@continueWithTask fetchExpressions(
                    idcc, expressions, index + 1,
                    accumulated.copy(
                        complete = false,
                        warnings = accumulated.warnings + "KALI : recherche « $expression » impossible."
                    )
                )
            }
            val batch = task.result
            fetchExpressions(
                idcc, expressions, index + 1,
                ExpressionBatch(
                    candidates = (accumulated.candidates + batch.candidates).distinctBy { it.id },
                    pagesRead = accumulated.pagesRead + batch.pagesRead,
                    complete = accumulated.complete && batch.complete,
                    warnings = (accumulated.warnings + batch.warnings).distinct()
                )
            )
        }
    }

    private fun fetchPages(
        idcc: String,
        expression: String,
        pageNumber: Int = 1,
        accumulated: List<OfficialKaliMatterSourceV2.Candidate> = emptyList(),
        expectedTotal: Int? = null
    ): Task<SearchBatch> {
        val body = OfficialKaliMatterSourceV2.searchBody(idcc, expression, pageNumber, PAGE_SIZE)
        return requestWithRetry("/search", body).continueWithTask { task ->
            if (!task.isSuccessful) {
                if (pageNumber == 1) return@continueWithTask Tasks.forException(task.exception ?: IllegalStateException("Première page KALI inaccessible"))
                return@continueWithTask Tasks.forResult(
                    SearchBatch(accumulated.distinctBy { it.id }, pageNumber - 1, false,
                        listOf("KALI : pagination « $expression » interrompue après ${pageNumber - 1} page(s)."))
                )
            }
            val page = OfficialKaliMatterSourceV2.parsePage(task.result.data, pageNumber, PAGE_SIZE)
            val total = expectedTotal ?: page.totalResults
            val before = accumulated.distinctBy { it.id }
            val combined = (before + page.candidates).distinctBy { it.id }
            val newIds = combined.size - before.size
            val completeByCount = total != null && combined.size >= total
            val empty = page.candidates.isEmpty()
            val noProgress = page.candidates.isNotEmpty() && newIds == 0
            if (!completeByCount && !empty && !noProgress) {
                fetchPages(idcc, expression, pageNumber + 1, combined, total)
            } else {
                Tasks.forResult(
                    SearchBatch(
                        combined,
                        pageNumber,
                        completeByCount || (total == null && empty),
                        buildList {
                            if (total == null && !empty) add("KALI : total officiel absent pour « $expression » ; couverture non certifiée.")
                            if (noProgress) add("KALI : pagination « $expression » sans nouvel identifiant ; couverture non certifiée.")
                        }
                    )
                )
            }
        }
    }

    private fun expandTexts(
        candidates: List<OfficialKaliMatterSourceV2.Candidate>,
        index: Int = 0,
        articleIds: List<String> = emptyList(),
        consulted: Int = 0,
        complete: Boolean = true,
        warnings: List<String> = emptyList()
    ): Task<ExpansionBatch> {
        if (index >= candidates.size) return Tasks.forResult(ExpansionBatch(articleIds.distinct(), consulted, complete, warnings.distinct()))
        val candidate = candidates[index]
        return requestWithRetry("/consult/kaliText", mapOf("id" to candidate.id)).continueWithTask { task ->
            if (!task.isSuccessful) {
                expandTexts(candidates, index + 1, articleIds, consulted + 1, false,
                    warnings + "KALI : ${candidate.id} n'a pas pu être développé après deux tentatives.")
            } else {
                val expansion = OfficialKaliTextExpansionV2.parse(task.result.data)
                expandTexts(candidates, index + 1, (articleIds + expansion.articleIds).distinct(), consulted + 1, complete, warnings)
            }
        }
    }

    private fun consultArticles(
        articleIds: List<String>,
        referenceDate: LocalDate,
        index: Int = 0,
        consulted: Int = 0,
        complete: Boolean = true,
        articles: List<OfficialKaliOvertimeRuleParserV2.VerifiedArticle> = emptyList(),
        warnings: List<String> = emptyList()
    ): Task<ConsultBatch> {
        if (index >= articleIds.size) return Tasks.forResult(ConsultBatch(consulted, complete, articles.distinctBy { it.articleId }, warnings.distinct()))
        val id = articleIds[index]
        return requestWithRetry("/consult/kaliArticle", mapOf("id" to id)).continueWithTask { task ->
            if (!task.isSuccessful) {
                consultArticles(articleIds, referenceDate, index + 1, consulted + 1, false, articles,
                    warnings + "KALI : $id n'a pas pu être consulté après deux tentatives.")
            } else {
                val article = OfficialKaliOvertimeRuleParserV2.parseApplicableArticle(task.result.data, id, referenceDate)
                consultArticles(
                    articleIds, referenceDate, index + 1, consulted + 1, complete,
                    if (article == null) articles else articles + article,
                    warnings
                )
            }
        }
    }

    private fun requestWithRetry(path: String, body: Map<String, Any?>, attempt: Int = 0): Task<HttpsCallableResult> =
        LegifranceFunctionClientV2.request(path, body).continueWithTask { task ->
            if (task.isSuccessful || attempt >= 1) task
            else requestWithRetry(path, body, attempt + 1)
        }

    private fun normalizeIdcc(value: String): String? {
        val digits = value.filter(Char::isDigit)
        val number = digits.takeIf { it.length in 1..4 }?.toIntOrNull() ?: return null
        if (number <= 0 || number == 9999) return null
        return number.toString().padStart(4, '0')
    }
}
