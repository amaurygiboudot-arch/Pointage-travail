package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.v2.engine.ConventionPublicHolidayPremiumSnapshotV2
import com.amaury.pointage.v2.engine.PublicHolidayPremiumRuleV2
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.firebase.functions.HttpsCallableResult
import java.time.LocalDate

/**
 * Audit KALI des majorations simples et uniformes de jours fériés.
 *
 * Une règle n'est enregistrée que si la recherche est complète, que tous les KALITEXT/KALIARTI
 * accessibles ont été examinés, qu'aucune section KALISCTA ne reste non résolue et qu'un seul
 * candidat non conditionnel est applicable à la date contrôlée.
 */
object KaliPublicHolidayPremiumAuditV2 {
    private const val PAGE_SIZE = 25
    private const val LABEL = "jours fériés"

    data class CandidatePreview(
        val articleId: String,
        val effectiveFrom: LocalDate,
        val effectiveTo: LocalDate?,
        val percentage: Double
    )

    data class Summary(
        val idcc: String,
        val referenceDate: LocalDate,
        val pagesRead: Int,
        val candidates: Int,
        val articlesConsulted: Int,
        val structuredCandidates: Int,
        val saved: Boolean = false,
        val selectedSourceId: String? = null,
        val previews: List<CandidatePreview> = emptyList(),
        val warnings: List<String> = emptyList()
    )

    private data class SearchBatch(
        val candidates: List<OfficialKaliPublicHolidayPremiumSourceV2.Candidate>,
        val pagesRead: Int,
        val resultCountComplete: Boolean,
        val warnings: List<String>
    )

    private data class TextExpansionBatch(
        val articleIds: List<String>,
        val textsConsulted: Int,
        val allTextsExpanded: Boolean,
        val warnings: List<String>
    )

    private data class ConsultBatch(
        val consulted: Int,
        val allArticlesConsulted: Boolean,
        val diagnostics: List<OfficialKaliPublicHolidayPremiumRuleParserV2.ArticleDiagnostic>,
        val warnings: List<String>
    )

    fun audit(
        context: Context,
        idcc: String,
        referenceDate: LocalDate
    ): Task<Summary> {
        val normalizedIdcc = normalizeIdcc(idcc)
            ?: return Tasks.forResult(
                Summary(
                    idcc = idcc,
                    referenceDate = referenceDate,
                    pagesRead = 0,
                    candidates = 0,
                    articlesConsulted = 0,
                    structuredCandidates = 0,
                    warnings = listOf("KALI $LABEL : IDCC invalide.")
                )
            )

        return fetchPages(normalizedIdcc)
            .continueWithTask { searchTask ->
                if (!searchTask.isSuccessful) {
                    return@continueWithTask Tasks.forResult(
                        Summary(
                            idcc = normalizedIdcc,
                            referenceDate = referenceDate,
                            pagesRead = 0,
                            candidates = 0,
                            articlesConsulted = 0,
                            structuredCandidates = 0,
                            warnings = listOf(
                                "KALI $LABEL : ${searchTask.exception?.message ?: "recherche officielle impossible"}"
                            )
                        )
                    )
                }

                val search = searchTask.result
                val directArticles = search.candidates
                    .filter { it.id.startsWith("KALIARTI") }
                    .distinctBy { it.id }
                val textCandidates = search.candidates
                    .filter { it.id.startsWith("KALITEXT") }
                    .distinctBy { it.id }
                val sectionCandidates = search.candidates
                    .filter { it.id.startsWith("KALISCTA") }
                    .distinctBy { it.id }

                expandKaliTexts(textCandidates)
                    .continueWithTask { expansionTask ->
                        val expansion = if (expansionTask.isSuccessful) {
                            expansionTask.result
                        } else {
                            TextExpansionBatch(
                                articleIds = emptyList(),
                                textsConsulted = 0,
                                allTextsExpanded = false,
                                warnings = listOf(
                                    "KALI $LABEL : développement des KALITEXT interrompu ; les articles directs restent analysés."
                                )
                            )
                        }

                        val expandedArticles = expansion.articleIds.map { articleId ->
                            OfficialKaliPublicHolidayPremiumSourceV2.Candidate(
                                id = articleId,
                                title = "Article découvert via KALITEXT",
                                snippet = null
                            )
                        }
                        val articleCandidates = (directArticles + expandedArticles).distinctBy { it.id }

                        val preWarnings = buildList {
                            addAll(search.warnings)
                            addAll(expansion.warnings)
                            if (textCandidates.isNotEmpty()) {
                                add(
                                    "KALI $LABEL : ${expansion.textsConsulted}/${textCandidates.size} texte(s) KALITEXT consulté(s) ; " +
                                        "${expandedArticles.map { it.id }.distinct().size} article(s) supplémentaire(s) découvert(s)."
                                )
                            }
                            if (sectionCandidates.isNotEmpty()) {
                                add(
                                    "KALI $LABEL : ${sectionCandidates.size} section(s) KALISCTA restent des pistes ; " +
                                        "aucune route de consultation de section n'est supposée par HoraTrack."
                                )
                            }
                            add("KALI $LABEL : ${articleCandidates.size} article(s) KALIARTI unique(s) à consulter.")
                        }

                        consultArticles(articleCandidates, referenceDate)
                            .continueWith { consultTask ->
                                if (!consultTask.isSuccessful) {
                                    return@continueWith Summary(
                                        idcc = normalizedIdcc,
                                        referenceDate = referenceDate,
                                        pagesRead = search.pagesRead,
                                        candidates = search.candidates.size,
                                        articlesConsulted = 0,
                                        structuredCandidates = 0,
                                        warnings = preWarnings + "KALI $LABEL : consultation des articles impossible."
                                    )
                                }

                                finalizeAudit(
                                    context = context,
                                    idcc = normalizedIdcc,
                                    referenceDate = referenceDate,
                                    search = search,
                                    unresolvedSections = sectionCandidates.size,
                                    allTextsExpanded = expansion.allTextsExpanded,
                                    consult = consultTask.result.copy(
                                        warnings = preWarnings + consultTask.result.warnings
                                    )
                                )
                            }
                    }
            }
    }

    private fun finalizeAudit(
        context: Context,
        idcc: String,
        referenceDate: LocalDate,
        search: SearchBatch,
        unresolvedSections: Int,
        allTextsExpanded: Boolean,
        consult: ConsultBatch
    ): Summary {
        val structured = consult.diagnostics.mapNotNull { it.candidate }.filter { it.calculationReady }
        val previews = structured
            .sortedWith(
                compareByDescending<OfficialKaliPublicHolidayPremiumRuleParserV2.StructuredCandidate> {
                    it.article.effectiveFrom
                }.thenBy { it.article.articleId }
            )
            .take(12)
            .map {
                CandidatePreview(
                    articleId = it.article.articleId,
                    effectiveFrom = it.article.effectiveFrom,
                    effectiveTo = it.article.effectiveTo,
                    percentage = it.percentage
                )
            }

        val coverageComplete = search.resultCountComplete &&
            unresolvedSections == 0 &&
            allTextsExpanded &&
            consult.allArticlesConsulted
        val selected = structured.singleOrNull()
        var saved = false
        var selectedSourceId: String? = null
        var saveError: String? = null

        if (selected != null && coverageComplete) {
            val sourceId = "legifrance:KALI:${selected.article.articleId}"
            val snapshot = ConventionPublicHolidayPremiumSnapshotV2(
                idcc = idcc,
                versionId = "KALI-PUBLIC-HOLIDAY-${selected.article.articleId}",
                sourceId = sourceId,
                effectiveFromEpochDay = selected.article.effectiveFrom.toEpochDay(),
                effectiveToEpochDay = selected.article.effectiveTo?.toEpochDay(),
                rule = PublicHolidayPremiumRuleV2(selected.multiplier),
                checkedAtMs = System.currentTimeMillis(),
                note = "Majoration uniforme de jours fériés extraite d'un article KALI consulté, daté et applicable."
            )
            saved = runCatching {
                V2ConventionPublicHolidayPremiumStore.saveConfirmed(context, snapshot)
                true
            }.getOrElse { error ->
                saveError = error.message ?: "stockage impossible"
                false
            }
            if (saved) selectedSourceId = sourceId
        }

        val warnings = buildList {
            addAll(consult.warnings)
            addAll(diagnosticWarnings(consult.diagnostics))
            if (!search.resultCountComplete) {
                add("KALI $LABEL : pagination non certifiée complète ; aucun enregistrement automatique.")
            }
            if (unresolvedSections > 0) {
                add("KALI $LABEL : $unresolvedSections section(s) KALISCTA non développée(s) ; enregistrement bloqué.")
            }
            if (!allTextsExpanded) {
                add("KALI $LABEL : tous les KALITEXT n'ont pas pu être développés ; enregistrement bloqué.")
            }
            if (!consult.allArticlesConsulted) {
                add("KALI $LABEL : tous les KALIARTI n'ont pas pu être consultés ; enregistrement bloqué.")
            }
            when {
                structured.isEmpty() -> add(
                    "KALI $LABEL : aucune majoration uniforme, datée et non conditionnelle n'a pu être structurée."
                )
                structured.size > 1 -> add(
                    "KALI $LABEL : ${structured.size} règles simples applicables ont été trouvées ; HoraTrack refuse d'en choisir une automatiquement."
                )
                saved -> add(
                    "KALI $LABEL : règle unique vérifiée et enregistrée (+${formatPercent(selected!!.percentage)} %)."
                )
                coverageComplete && saveError != null -> add(
                    "KALI $LABEL : règle unique vérifiée mais stockage impossible : $saveError."
                )
                else -> add(
                    "KALI $LABEL : candidat calculable trouvé mais couverture insuffisante pour l'enregistrer automatiquement."
                )
            }
            add("KALI $LABEL : une recherche vide ou non structurée ne prouve jamais l'absence officielle d'une règle.")
        }.distinct()

        return Summary(
            idcc = idcc,
            referenceDate = referenceDate,
            pagesRead = search.pagesRead,
            candidates = search.candidates.size,
            articlesConsulted = consult.consulted,
            structuredCandidates = structured.size,
            saved = saved,
            selectedSourceId = selectedSourceId,
            previews = previews,
            warnings = warnings
        )
    }

    private fun fetchPages(
        idcc: String,
        pageNumber: Int = 1,
        accumulated: List<OfficialKaliPublicHolidayPremiumSourceV2.Candidate> = emptyList(),
        expectedTotal: Int? = null
    ): Task<SearchBatch> {
        val body = OfficialKaliPublicHolidayPremiumSourceV2.searchBody(idcc, pageNumber, PAGE_SIZE)
        return requestWithRetry("/search", body).continueWithTask { task ->
            if (!task.isSuccessful) {
                if (pageNumber == 1) {
                    return@continueWithTask Tasks.forException(
                        task.exception ?: IllegalStateException("KALI $LABEL : première page inaccessible")
                    )
                }
                return@continueWithTask Tasks.forResult(
                    SearchBatch(
                        candidates = accumulated.distinctBy { it.id },
                        pagesRead = pageNumber - 1,
                        resultCountComplete = false,
                        warnings = listOf("KALI $LABEL : pagination interrompue après ${pageNumber - 1} page(s).")
                    )
                )
            }

            val page = OfficialKaliPublicHolidayPremiumSourceV2.parsePage(task.result.data, pageNumber, PAGE_SIZE)
            val total = expectedTotal ?: page.totalResults
            val before = accumulated.distinctBy { it.id }
            val combined = (before + page.candidates).distinctBy { it.id }
            val newIds = combined.size - before.size
            val completeByCount = total != null && combined.size >= total
            val emptyPage = page.candidates.isEmpty()
            val noProgress = page.candidates.isNotEmpty() && newIds == 0
            val continuePaging = !completeByCount && !emptyPage && !noProgress

            if (continuePaging) {
                fetchPages(idcc, pageNumber + 1, combined, total)
            } else {
                Tasks.forResult(
                    SearchBatch(
                        candidates = combined,
                        pagesRead = pageNumber,
                        resultCountComplete = completeByCount || (total == null && emptyPage),
                        warnings = buildList {
                            if (total == null) {
                                add(
                                    if (emptyPage) {
                                        "KALI $LABEL : total officiel absent ; pagination poursuivie jusqu'à la première page vide."
                                    } else {
                                        "KALI $LABEL : nombre total de résultats absent de la réponse officielle."
                                    }
                                )
                            }
                            if (noProgress) add("KALI $LABEL : page répétée ; pagination arrêtée pour éviter une boucle.")
                        }
                    )
                )
            }
        }
    }

    private fun expandKaliTexts(
        candidates: List<OfficialKaliPublicHolidayPremiumSourceV2.Candidate>,
        index: Int = 0,
        articleIds: List<String> = emptyList(),
        textsConsulted: Int = 0,
        allTextsExpanded: Boolean = true,
        warnings: List<String> = emptyList()
    ): Task<TextExpansionBatch> {
        if (index >= candidates.size) {
            return Tasks.forResult(TextExpansionBatch(articleIds.distinct(), textsConsulted, allTextsExpanded, warnings.distinct()))
        }
        val candidate = candidates[index]
        return requestWithRetry("/consult/kaliText", mapOf("id" to candidate.id)).continueWithTask { task ->
            if (!task.isSuccessful) {
                return@continueWithTask expandKaliTexts(
                    candidates,
                    index + 1,
                    articleIds,
                    textsConsulted + 1,
                    false,
                    warnings + "KALI $LABEL : ${candidate.id} n'a pas pu être développé après deux tentatives."
                )
            }
            val expansion = OfficialKaliTextExpansionV2.parse(task.result.data)
            expandKaliTexts(
                candidates,
                index + 1,
                (articleIds + expansion.articleIds).distinct(),
                textsConsulted + 1,
                allTextsExpanded,
                warnings
            )
        }
    }

    private fun consultArticles(
        candidates: List<OfficialKaliPublicHolidayPremiumSourceV2.Candidate>,
        referenceDate: LocalDate,
        index: Int = 0,
        consulted: Int = 0,
        allArticlesConsulted: Boolean = true,
        diagnostics: List<OfficialKaliPublicHolidayPremiumRuleParserV2.ArticleDiagnostic> = emptyList(),
        warnings: List<String> = emptyList()
    ): Task<ConsultBatch> {
        if (index >= candidates.size) {
            return Tasks.forResult(ConsultBatch(consulted, allArticlesConsulted, diagnostics, warnings.distinct()))
        }
        val candidate = candidates[index]
        return requestWithRetry("/consult/kaliArticle", mapOf("id" to candidate.id)).continueWithTask { task ->
            if (!task.isSuccessful) {
                return@continueWithTask consultArticles(
                    candidates,
                    referenceDate,
                    index + 1,
                    consulted + 1,
                    false,
                    diagnostics,
                    warnings + "KALI $LABEL : ${candidate.id} n'a pas pu être consulté après deux tentatives."
                )
            }
            val article = OfficialKaliPublicHolidayPremiumRuleParserV2.parseApplicableArticle(
                task.result.data,
                candidate.id,
                referenceDate
            )
            val diagnostic = article?.let(OfficialKaliPublicHolidayPremiumRuleParserV2::analyzeArticle)
            consultArticles(
                candidates,
                referenceDate,
                index + 1,
                consulted + 1,
                allArticlesConsulted,
                if (diagnostic == null) diagnostics else diagnostics + diagnostic,
                warnings
            )
        }
    }

    private fun requestWithRetry(path: String, body: Map<String, Any?>): Task<HttpsCallableResult> =
        LegifranceFunctionClientV2.request(path, body).continueWithTask { first ->
            if (first.isSuccessful) Tasks.forResult(first.result)
            else LegifranceFunctionClientV2.request(path, body)
        }

    internal fun diagnosticWarnings(
        diagnostics: List<OfficialKaliPublicHolidayPremiumRuleParserV2.ArticleDiagnostic>
    ): List<String> = buildList {
        val multiple = diagnostics.filter {
            it.kind == OfficialKaliPublicHolidayPremiumRuleParserV2.DiagnosticKind.MULTIPLE_RATES
        }
        if (multiple.isNotEmpty()) {
            add("KALI $LABEL : ${multiple.size} article(s) contiennent plusieurs taux ; aucun n'est choisi automatiquement.")
        }
        val conditional = diagnostics.filter {
            it.kind == OfficialKaliPublicHolidayPremiumRuleParserV2.DiagnosticKind.CONDITIONAL_RULE
        }
        if (conditional.isNotEmpty()) {
            add("KALI $LABEL : ${conditional.size} article(s) comportent des exceptions, jours nommés, conditions ou règles de cumul ; ils restent à vérifier.")
        }
        val withoutRate = diagnostics.filter {
            it.kind == OfficialKaliPublicHolidayPremiumRuleParserV2.DiagnosticKind.HOLIDAY_WITHOUT_RATE
        }
        if (withoutRate.isNotEmpty()) {
            add("KALI $LABEL : ${withoutRate.size} article(s) parlent des jours fériés sans taux numérique unique exploitable.")
        }
        val wrongContext = diagnostics.filter {
            it.kind == OfficialKaliPublicHolidayPremiumRuleParserV2.DiagnosticKind.RATE_WITHOUT_SIMPLE_PREMIUM_CONTEXT
        }
        if (wrongContext.isNotEmpty()) {
            add("KALI $LABEL : ${wrongContext.size} article(s) contiennent un pourcentage sans majoration simple confirmée ; aucun taux n'est appliqué.")
        }
        val structured = diagnostics.mapNotNull { it.candidate }
        if (structured.isNotEmpty()) {
            add(
                "KALI $LABEL : candidat(s) uniforme(s) détecté(s) : " +
                    structured.take(6).joinToString(" ; ") {
                        "${it.article.articleId} (+${formatPercent(it.percentage)} %)"
                    } + "."
            )
        }
    }

    private fun formatPercent(value: Double): String =
        if (value % 1.0 == 0.0) value.toInt().toString() else value.toString().replace('.', ',')

    private fun normalizeIdcc(value: String): String? {
        val digits = value.filter(Char::isDigit)
        val number = digits.takeIf { it.length in 1..4 }?.toIntOrNull() ?: return null
        if (number <= 0 || number == 9999) return null
        return number.toString().padStart(4, '0')
    }
}
