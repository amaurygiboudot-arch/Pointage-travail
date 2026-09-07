package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.v2.engine.ConventionNightRuleSnapshotV2
import com.amaury.pointage.v2.engine.NightPremiumRuleV2
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.firebase.functions.HttpsCallableResult
import java.time.LocalDate
import java.util.Locale

/**
 * Audit KALI ciblé du travail de nuit.
 *
 * Une règle n'est enregistrée que si HoraTrack a une recherche paginée complète, aucun KALISCTA
 * non résolu, tous les KALITEXT développés, tous les KALIARTI consultés et exactement un candidat
 * simple applicable à la date (une plage + un taux, sans condition détectée).
 */
object KaliNightPayrollAuditV2 {
    private const val PAGE_SIZE = 25

    data class CandidatePreview(
        val articleId: String,
        val effectiveFrom: LocalDate,
        val effectiveTo: LocalDate?,
        val startMinute: Int,
        val endMinute: Int,
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
        val candidates: List<OfficialKaliNightSourceV2.Candidate>,
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
        val diagnostics: List<OfficialKaliNightRuleParserV2.ArticleDiagnostic>,
        val warnings: List<String>
    )

    fun audit(context: Context, idcc: String, referenceDate: LocalDate): Task<Summary> {
        val normalizedIdcc = normalizeIdcc(idcc)
            ?: return Tasks.forResult(
                Summary(
                    idcc = idcc,
                    referenceDate = referenceDate,
                    pagesRead = 0,
                    candidates = 0,
                    articlesConsulted = 0,
                    structuredCandidates = 0,
                    warnings = listOf("KALI nuit : IDCC invalide.")
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
                                "KALI nuit : ${searchTask.exception?.message ?: "recherche officielle impossible"}"
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
                                    "KALI nuit : développement des KALITEXT interrompu ; " +
                                        "les articles directs restent analysés."
                                )
                            )
                        }

                        val expandedArticles = expansion.articleIds.map { articleId ->
                            OfficialKaliNightSourceV2.Candidate(
                                id = articleId,
                                title = "Article découvert via KALITEXT",
                                snippet = null
                            )
                        }
                        val articleCandidates = (directArticles + expandedArticles)
                            .distinctBy { it.id }

                        val preWarnings = buildList {
                            addAll(search.warnings)
                            addAll(expansion.warnings)
                            if (textCandidates.isNotEmpty()) {
                                add(
                                    "KALI nuit : ${expansion.textsConsulted}/${textCandidates.size} texte(s) KALITEXT " +
                                        "consulté(s) ; ${expandedArticles.map { it.id }.distinct().size} article(s) " +
                                        "supplémentaire(s) découvert(s)."
                                )
                            }
                            if (sectionCandidates.isNotEmpty()) {
                                add(
                                    "KALI nuit : ${sectionCandidates.size} section(s) KALISCTA restent des pistes ; " +
                                        "aucune route de consultation de section n'est supposée par HoraTrack."
                                )
                            }
                            add(
                                "KALI nuit : ${articleCandidates.size} article(s) KALIARTI unique(s) à consulter."
                            )
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
                                        warnings = preWarnings +
                                            "KALI nuit : consultation des articles impossible."
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
        val structured = consult.diagnostics
            .mapNotNull { it.candidate }
            .filter { it.calculationReady }
        val previews = structured
            .sortedWith(
                compareByDescending<OfficialKaliNightRuleParserV2.StructuredCandidate> {
                    it.article.effectiveFrom
                }.thenBy { it.article.articleId }
            )
            .take(12)
            .map { candidate ->
                CandidatePreview(
                    articleId = candidate.article.articleId,
                    effectiveFrom = candidate.article.effectiveFrom,
                    effectiveTo = candidate.article.effectiveTo,
                    startMinute = candidate.window.startMinute,
                    endMinute = candidate.window.endMinute,
                    percentage = candidate.percentage
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
            val snapshot = ConventionNightRuleSnapshotV2(
                idcc = idcc,
                versionId = "KALI-NIGHT-${selected.article.articleId}",
                sourceId = sourceId,
                effectiveFromEpochDay = selected.article.effectiveFrom.toEpochDay(),
                effectiveToEpochDay = selected.article.effectiveTo?.toEpochDay(),
                rule = NightPremiumRuleV2(
                    startMinute = selected.window.startMinute,
                    endMinute = selected.window.endMinute,
                    multiplier = selected.multiplier
                ),
                checkedAtMs = System.currentTimeMillis(),
                note = "Règle de nuit extraite d'un article KALI consulté, daté et applicable."
            )
            saved = runCatching {
                V2ConventionNightRuleStore.saveConfirmed(context, snapshot)
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
                add(
                    "KALI nuit : la pagination n'a pas pu être certifiée complète ; " +
                        "aucune règle n'est enregistrée automatiquement."
                )
            }
            if (unresolvedSections > 0) {
                add(
                    "KALI nuit : $unresolvedSections section(s) KALISCTA ne sont pas développées ; " +
                        "l'enregistrement automatique est bloqué pour éviter de manquer une règle concurrente."
                )
            }
            if (!allTextsExpanded) {
                add(
                    "KALI nuit : tous les KALITEXT n'ont pas pu être développés ; " +
                        "l'enregistrement automatique est bloqué."
                )
            }
            if (!consult.allArticlesConsulted) {
                add(
                    "KALI nuit : tous les KALIARTI n'ont pas pu être consultés ; " +
                        "l'enregistrement automatique est bloqué."
                )
            }

            when {
                structured.isEmpty() -> add(
                    "KALI nuit : aucune règle simple, datée et non conditionnelle n'a pu être structurée automatiquement."
                )
                structured.size > 1 -> add(
                    "KALI nuit : ${structured.size} règles simples applicables ont été trouvées ; " +
                        "HoraTrack refuse d'en choisir une automatiquement sans arbitrage supplémentaire."
                )
                saved -> add(
                    "KALI nuit : règle unique vérifiée et enregistrée. Les minutes de nuit seront calculées " +
                        "uniquement dans la plage officielle ${formatMinute(selected!!.window.startMinute)}-" +
                        "${formatMinute(selected.window.endMinute)}."
                )
                coverageComplete && saveError != null -> add(
                    "KALI nuit : règle unique vérifiée mais stockage impossible : $saveError."
                )
                else -> add(
                    "KALI nuit : un candidat calculable est présent mais la couverture de l'audit n'est pas " +
                        "assez complète pour l'enregistrer automatiquement."
                )
            }

            add(
                "KALI nuit : une recherche ciblée vide ou non structurée ne prouve jamais l'absence officielle " +
                    "d'une règle de travail de nuit."
            )
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
        accumulated: List<OfficialKaliNightSourceV2.Candidate> = emptyList(),
        expectedTotal: Int? = null
    ): Task<SearchBatch> {
        val body = OfficialKaliNightSourceV2.searchBody(idcc, pageNumber, PAGE_SIZE)
        return requestWithRetry("/search", body)
            .continueWithTask { task ->
                if (!task.isSuccessful) {
                    if (pageNumber == 1) {
                        return@continueWithTask Tasks.forException(
                            task.exception ?: IllegalStateException("KALI nuit : première page inaccessible")
                        )
                    }
                    return@continueWithTask Tasks.forResult(
                        SearchBatch(
                            candidates = accumulated.distinctBy { it.id },
                            pagesRead = pageNumber - 1,
                            resultCountComplete = false,
                            warnings = listOf(
                                "KALI nuit : pagination interrompue après ${pageNumber - 1} page(s), " +
                                    "y compris après une seconde tentative réseau."
                            )
                        )
                    )
                }

                val page = OfficialKaliNightSourceV2.parsePage(task.result.data, pageNumber, PAGE_SIZE)
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
                                            "KALI nuit : total officiel absent ; pagination poursuivie jusqu'à " +
                                                "la première page vide."
                                        } else {
                                            "KALI nuit : le nombre total de résultats n'est pas fourni par la réponse officielle."
                                        }
                                    )
                                }
                                if (noProgress) {
                                    add(
                                        "KALI nuit : une page a répété uniquement des identifiants déjà vus ; " +
                                            "pagination arrêtée pour éviter une boucle."
                                    )
                                }
                            }
                        )
                    )
                }
            }
    }

    private fun expandKaliTexts(
        candidates: List<OfficialKaliNightSourceV2.Candidate>,
        index: Int = 0,
        articleIds: List<String> = emptyList(),
        textsConsulted: Int = 0,
        allTextsExpanded: Boolean = true,
        warnings: List<String> = emptyList()
    ): Task<TextExpansionBatch> {
        if (index >= candidates.size) {
            return Tasks.forResult(
                TextExpansionBatch(
                    articleIds.distinct(),
                    textsConsulted,
                    allTextsExpanded,
                    warnings.distinct()
                )
            )
        }
        val candidate = candidates[index]
        return requestWithRetry("/consult/kaliText", mapOf("id" to candidate.id))
            .continueWithTask { task ->
                if (!task.isSuccessful) {
                    return@continueWithTask expandKaliTexts(
                        candidates,
                        index + 1,
                        articleIds,
                        textsConsulted + 1,
                        false,
                        warnings + "KALI nuit : ${candidate.id} n'a pas pu être développé après deux tentatives."
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
        candidates: List<OfficialKaliNightSourceV2.Candidate>,
        referenceDate: LocalDate,
        index: Int = 0,
        consulted: Int = 0,
        allArticlesConsulted: Boolean = true,
        diagnostics: List<OfficialKaliNightRuleParserV2.ArticleDiagnostic> = emptyList(),
        warnings: List<String> = emptyList()
    ): Task<ConsultBatch> {
        if (index >= candidates.size) {
            return Tasks.forResult(
                ConsultBatch(
                    consulted,
                    allArticlesConsulted,
                    diagnostics,
                    warnings.distinct()
                )
            )
        }
        val candidate = candidates[index]
        return requestWithRetry("/consult/kaliArticle", mapOf("id" to candidate.id))
            .continueWithTask { task ->
                if (!task.isSuccessful) {
                    return@continueWithTask consultArticles(
                        candidates,
                        referenceDate,
                        index + 1,
                        consulted + 1,
                        false,
                        diagnostics,
                        warnings + "KALI nuit : ${candidate.id} n'a pas pu être consulté après deux tentatives."
                    )
                }
                val article = OfficialKaliNightRuleParserV2.parseApplicableArticle(
                    task.result.data,
                    candidate.id,
                    referenceDate
                )
                val diagnostic = article?.let(OfficialKaliNightRuleParserV2::analyzeArticle)
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

    private fun requestWithRetry(
        path: String,
        body: Map<String, Any?>
    ): Task<HttpsCallableResult> =
        LegifranceFunctionClientV2.request(path, body)
            .continueWithTask { first ->
                if (first.isSuccessful) Tasks.forResult(first.result)
                else LegifranceFunctionClientV2.request(path, body)
            }

    internal fun diagnosticWarnings(
        diagnostics: List<OfficialKaliNightRuleParserV2.ArticleDiagnostic>
    ): List<String> = buildList {
        val multipleRates = diagnostics.filter {
            it.kind == OfficialKaliNightRuleParserV2.DiagnosticKind.MULTIPLE_RATES
        }
        if (multipleRates.isNotEmpty()) {
            add(
                "KALI nuit : ${multipleRates.size} article(s) applicable(s) contiennent plusieurs taux ; " +
                    "aucun taux unique n'est choisi automatiquement. Exemples : " +
                    multipleRates.take(6).joinToString(", ") { it.article.articleId } + "."
            )
        }

        val conditional = diagnostics.filter {
            it.kind == OfficialKaliNightRuleParserV2.DiagnosticKind.CONDITIONAL_RULE
        }
        if (conditional.isNotEmpty()) {
            add(
                "KALI nuit : ${conditional.size} article(s) applicable(s) comportent des conditions, catégories " +
                    "ou plusieurs plages ; ils restent à vérifier. Exemples : " +
                    conditional.take(6).joinToString(", ") { it.article.articleId } + "."
            )
        }

        val withoutWindow = diagnostics.filter {
            it.kind == OfficialKaliNightRuleParserV2.DiagnosticKind.RATE_WITHOUT_WINDOW
        }
        if (withoutWindow.isNotEmpty()) {
            add(
                "KALI nuit : ${withoutWindow.size} article(s) donnent un taux sans plage horaire explicite ; " +
                    "HoraTrack refuse d'utiliser la plage de poste de l'application comme substitut conventionnel."
            )
        }

        val withoutRate = diagnostics.filter {
            it.kind == OfficialKaliNightRuleParserV2.DiagnosticKind.NIGHT_WITHOUT_RATE
        }
        if (withoutRate.isNotEmpty()) {
            add(
                "KALI nuit : ${withoutRate.size} article(s) parlent du travail de nuit sans fournir un taux " +
                    "numérique unique exploitable."
            )
        }

        val structured = diagnostics.filter {
            it.kind == OfficialKaliNightRuleParserV2.DiagnosticKind.STRUCTURED_CANDIDATE
        }
        if (structured.isNotEmpty()) {
            val samples = structured.take(6).joinToString(" ; ") { diagnostic ->
                val candidate = diagnostic.candidate!!
                "${candidate.article.articleId} (${formatMinute(candidate.window.startMinute)}-" +
                    "${formatMinute(candidate.window.endMinute)} ; +${formatPercent(candidate.percentage)} %)"
            }
            add("KALI nuit : candidat(s) structuré(s) détecté(s) : $samples.")
        }
    }

    private fun formatMinute(minute: Int): String =
        "%02d:%02d".format(Locale.ROOT, minute / 60, minute % 60)

    private fun formatPercent(value: Double): String =
        if (value % 1.0 == 0.0) value.toInt().toString() else value.toString().replace('.', ',')

    private fun normalizeIdcc(value: String): String? {
        val digits = value.filter(Char::isDigit)
        val number = digits.takeIf { it.length in 1..4 }?.toIntOrNull() ?: return null
        if (number <= 0 || number == 9999) return null
        return number.toString().padStart(4, '0')
    }
}
